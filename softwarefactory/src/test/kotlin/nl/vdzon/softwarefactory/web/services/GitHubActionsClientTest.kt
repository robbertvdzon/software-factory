package nl.vdzon.softwarefactory.web.services

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
}
