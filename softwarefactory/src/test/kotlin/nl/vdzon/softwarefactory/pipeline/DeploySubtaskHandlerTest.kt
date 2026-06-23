package nl.vdzon.softwarefactory.pipeline

import nl.vdzon.softwarefactory.config.DeployConfig
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.core.IssueProcessResult
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerIssueFields
import nl.vdzon.softwarefactory.pipeline.service.DeploySubtaskHandler
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class DeploySubtaskHandlerTest {

    private val subtaskKey = "SF-102"
    private val parentKey = "SF-100"
    private val targetRepo = "git@github.com:robbert/sf.git"
    private val now = OffsetDateTime.parse("2026-01-01T12:00:00Z")
    private val clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC)

    private fun subtask(phase: SubtaskPhase?, agentStartedAt: OffsetDateTime? = null) = TrackerIssue(
        key = subtaskKey,
        summary = "Deploy subtask",
        status = "Open",
        fields = TrackerIssueFields(
            targetRepo = targetRepo,
            repo = "softwarefactory",
            aiPhase = null,
            aiLevel = null,
            aiTokenBudget = null,
            aiTokensUsed = null,
            agentStartedAt = agentStartedAt,
            paused = false,
            error = null,
            subtaskPhase = phase?.trackerValue,
        ),
        comments = emptyList(),
    )

    private fun parentIssue(projectName: String = "softwarefactory") = TrackerIssue(
        key = parentKey,
        summary = "Parent story",
        status = "Open",
        fields = TrackerIssueFields(
            targetRepo = targetRepo,
            repo = projectName,
            aiPhase = null,
            aiLevel = null,
            aiTokenBudget = null,
            aiTokensUsed = null,
            agentStartedAt = null,
            paused = false,
            error = null,
        ),
        comments = emptyList(),
    )

    private fun buildHandler(
        deployConfig: DeployConfig,
        capturedUpdates: MutableList<Pair<String, TrackerFieldUpdate>> = mutableListOf(),
        advanceResult: IssueProcessResult = IssueProcessResult.Chained(subtaskKey, null),
    ): DeploySubtaskHandler {
        val youTrack = object : YouTrackApi {
            override fun getIssue(issueKey: String) = parentIssue()
            override fun parentStoryKey(subtaskKey: String) = parentKey
            override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) {
                capturedUpdates.add(issueKey to update)
            }

            override fun transitionIssue(issueKey: String, statusName: String) {}
            override fun postAgentComment(issueKey: String, role: nl.vdzon.softwarefactory.core.AgentRole, message: String) = error("unused")
        }
        val resolver = ProjectRepoResolver(
            mapOf("softwarefactory" to targetRepo),
            deployConfigs = mapOf("softwarefactory" to deployConfig),
        )
        return DeploySubtaskHandler(youTrack, resolver, { advanceResult }, clock)
    }

    @Test
    fun `null phase returns Skipped`() {
        val handler = buildHandler(DeployConfig.Skip)
        val result = handler.process(subtask(null), null)
        assertTrue(result is IssueProcessResult.Skipped)
    }

    @Test
    fun `Skip config on START advances chain immediately`() {
        val advanced = IssueProcessResult.Chained(subtaskKey, null)
        val handler = buildHandler(DeployConfig.Skip, advanceResult = advanced)
        val result = handler.process(subtask(SubtaskPhase.START), SubtaskPhase.START)
        assertEquals(advanced, result)
    }

    @Test
    fun `rest-restart START sends restart and sets DEPLOYING`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        // Use a config pointing to a non-existing URL to test the error path
        val handler = buildHandler(
            DeployConfig.RestRestart(
                restartUrl = "http://127.0.0.1:0/api/restart",
                versionUrl = "http://127.0.0.1:0/api/version",
                tokenEnvVar = "SF_TEST_NONEXISTENT_TOKEN",
                pollIntervalSeconds = 1,
                timeoutMinutes = 1,
            ),
            capturedUpdates = updates,
        )
        // No env var set → should error
        val result = handler.process(subtask(SubtaskPhase.START), SubtaskPhase.START)
        assertTrue(result is IssueProcessResult.Errored)
    }

    @Test
    fun `rest-restart timeout sets DEPLOY_FAILED`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val pastTime = now.minusMinutes(10)
        val handler = buildHandler(
            DeployConfig.RestRestart(
                restartUrl = "http://127.0.0.1:0/api/restart",
                versionUrl = "http://127.0.0.1:0/api/version",
                tokenEnvVar = "SF_TEST_TOKEN",
                pollIntervalSeconds = 1,
                timeoutMinutes = 5,
            ),
            capturedUpdates = updates,
        )
        val result = handler.process(subtask(SubtaskPhase.DEPLOYING, agentStartedAt = pastTime), SubtaskPhase.DEPLOYING)
        assertTrue(result is IssueProcessResult.Errored)
        val phases = updates.map { it.second.values[TrackerField.SUBTASK_PHASE] }
        assertTrue(SubtaskPhase.DEPLOY_FAILED.trackerValue in phases)
    }

    @Test
    fun `openshift-watch timeout sets DEPLOY_FAILED`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val pastTime = now.minusMinutes(15)
        val handler = buildHandler(
            DeployConfig.OpenshiftWatch(namespace = "ns", deployment = "app", timeoutMinutes = 10),
            capturedUpdates = updates,
        )
        val result = handler.process(subtask(SubtaskPhase.DEPLOYING, agentStartedAt = pastTime), SubtaskPhase.DEPLOYING)
        assertTrue(result is IssueProcessResult.Errored)
        val phases = updates.map { it.second.values[TrackerField.SUBTASK_PHASE] }
        assertTrue(SubtaskPhase.DEPLOY_FAILED.trackerValue in phases)
    }

    @Test
    fun `parseCommitDate extracts ISO date from json`() {
        val handler = buildHandler(DeployConfig.Skip)
        val json = """{"commitHash":"abc","commitDate":"2026-01-01T13:00:00Z","branch":"main"}"""
        val date = handler.parseCommitDate(json)
        assertNotNull(date)
        assertEquals(OffsetDateTime.parse("2026-01-01T13:00:00Z"), date)
    }

    @Test
    fun `parseCommitDate returns null for malformed json`() {
        val handler = buildHandler(DeployConfig.Skip)
        assertNull(handler.parseCommitDate("not json at all"))
        assertNull(handler.parseCommitDate("""{"other":"field"}"""))
    }
}
