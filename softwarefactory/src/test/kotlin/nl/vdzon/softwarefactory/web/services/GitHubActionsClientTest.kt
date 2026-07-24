package nl.vdzon.softwarefactory.dashboard.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Dekt de pure parsing-logica van [GitHubActionsClient] (groeperen per workflow, laatste run
 * kiezen, duur berekenen) zonder een echte HTTP-call te doen — geen precedent in deze repo voor
 * het mocken van `java.net.http.HttpClient`, dus alleen het testbare/pure deel wordt gedekt.
 */
class GitHubActionsClientTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `houdt per workflow alleen de meest recente run over`() {
        val body = objectMapper.readTree(
            """
            {
              "workflow_runs": [
                {
                  "name": "Build",
                  "status": "completed",
                  "conclusion": "success",
                  "head_branch": "main",
                  "event": "push",
                  "run_started_at": "2026-07-08T10:00:00Z",
                  "updated_at": "2026-07-08T10:05:00Z",
                  "html_url": "https://github.com/robbert/sf/actions/runs/1"
                },
                {
                  "name": "Build",
                  "status": "completed",
                  "conclusion": "failure",
                  "head_branch": "main",
                  "head_sha": "deadbeefcafebabe",
                  "event": "push",
                  "run_started_at": "2026-07-09T10:00:00Z",
                  "updated_at": "2026-07-09T10:02:30Z",
                  "html_url": "https://github.com/robbert/sf/actions/runs/2"
                },
                {
                  "name": "Validate PR",
                  "status": "in_progress",
                  "conclusion": null,
                  "head_branch": "ai/SF-1",
                  "event": "pull_request",
                  "run_started_at": "2026-07-09T11:00:00Z",
                  "updated_at": "2026-07-09T11:00:10Z",
                  "html_url": "https://github.com/robbert/sf/actions/runs/3"
                }
              ]
            }
            """.trimIndent(),
        )

        val runs = GitHubActionsClient.parseLatestRunsPerWorkflow(body, "robbert/sf", "SF")

        assertEquals(2, runs.size)
        val build = runs.first { it.workflowName == "Build" }
        assertEquals("failure", build.conclusion)
        assertEquals("robbert/sf", build.repository)
        assertEquals("SF", build.projectKey)
        assertEquals("main", build.branch)
        assertEquals(150L, build.durationSeconds)
        assertEquals("https://github.com/robbert/sf/actions/runs/2", build.htmlUrl)
        assertEquals("deadbeefcafebabe", build.headSha)
        assertEquals("2026-07-09T10:00:00Z", build.runStartedAt)

        val validate = runs.first { it.workflowName == "Validate PR" }
        assertEquals(null, validate.conclusion)
        assertEquals("in_progress", validate.status)
    }

    @Test
    fun `negeert runs zonder workflow-naam`() {
        val body = objectMapper.readTree("""{"workflow_runs":[{"name":"","status":"completed"}]}""")

        val runs = GitHubActionsClient.parseLatestRunsPerWorkflow(body, "robbert/sf", "SF")

        assertEquals(0, runs.size)
    }

    @Test
    fun `ontbrekende workflow_runs levert een lege lijst`() {
        val body = objectMapper.readTree("""{}""")

        val runs = GitHubActionsClient.parseLatestRunsPerWorkflow(body, "robbert/sf", "SF")

        assertEquals(0, runs.size)
    }

    @Test
    fun `ongeldige timestamps geven geen duration ipv een crash`() {
        val body = objectMapper.readTree(
            """
            {
              "workflow_runs": [
                {"name":"Build","status":"completed","conclusion":"success","head_branch":"main","event":"push","run_started_at":"niet-een-datum","updated_at":"2026-07-09T10:02:30Z","html_url":"x"}
              ]
            }
            """.trimIndent(),
        )

        val runs = GitHubActionsClient.parseLatestRunsPerWorkflow(body, "robbert/sf", "SF")

        assertNull(runs.single().durationSeconds)
    }

    @Test
    fun `parseWorkflowNames houdt alleen actieve workflows over`() {
        val body = objectMapper.readTree(
            """
            {
              "workflows": [
                {"name":"Repository verification","state":"active"},
                {"name":"Oude workflow","state":"disabled_manually"},
                {"name":"","state":"active"}
              ]
            }
            """.trimIndent(),
        )

        val names = GitHubActionsClient.parseWorkflowNames(body)

        assertEquals(listOf("Repository verification"), names)
    }

    @Test
    fun `parseWorkflowNames zonder workflows-veld levert een lege lijst`() {
        assertEquals(0, GitHubActionsClient.parseWorkflowNames(objectMapper.readTree("""{}""")).size)
    }

    @Test
    fun `parseRunsForCommit levert elke run op, niet gecollapst tot een per workflow`() {
        val body = objectMapper.readTree(
            """
            {
              "workflow_runs": [
                {"name":"Build backend","status":"completed","conclusion":"success","head_branch":"main","event":"push","head_sha":"deadbeef","html_url":"https://x/1"},
                {"name":"Build backend","status":"completed","conclusion":"failure","head_branch":"main","event":"push","head_sha":"deadbeef","html_url":"https://x/2"}
              ]
            }
            """.trimIndent(),
        )

        val runs = GitHubActionsClient.parseRunsForCommit(body, "robbert/sf", "SF")

        assertEquals(2, runs.size)
    }

    @Test
    fun `parseOpenPullRequests leest number, head-ref en head-sha uit een top-level array`() {
        val body = objectMapper.readTree(
            """
            [
              {"number":182,"title":"Fix login bug","html_url":"https://github.com/robbert/sf/pull/182","updated_at":"2026-07-24T09:00:00Z","head":{"ref":"fix/login-bug","sha":"e4f5a6b"}}
            ]
            """.trimIndent(),
        )

        val prs = GitHubActionsClient.parseOpenPullRequests(body)

        val pr = prs.single()
        assertEquals(182, pr.number)
        assertEquals("fix/login-bug", pr.headRef)
        assertEquals("e4f5a6b", pr.headSha)
        assertEquals("Fix login bug", pr.title)
    }

    @Test
    fun `parseOpenPullRequests negeert entries zonder head-sha`() {
        val body = objectMapper.readTree("""[{"number":1,"head":{"ref":"x","sha":""}}]""")

        assertEquals(0, GitHubActionsClient.parseOpenPullRequests(body).size)
    }

    @Test
    fun `parseLatestCommit leest sha, boodschap en datum van het eerste element`() {
        val body = objectMapper.readTree(
            """
            [
              {"sha":"deadbeefcafebabe","commit":{"message":"Update UI","author":{"date":"2026-07-24T10:34:00Z"}}}
            ]
            """.trimIndent(),
        )

        val commit = GitHubActionsClient.parseLatestCommit(body)

        assertEquals("deadbeefcafebabe", commit?.sha)
        assertEquals("Update UI", commit?.message)
        assertEquals("2026-07-24T10:34:00Z", commit?.date)
    }

    @Test
    fun `parseLatestCommit levert null op voor een lege array`() {
        assertNull(GitHubActionsClient.parseLatestCommit(objectMapper.readTree("""[]""")))
    }
}
