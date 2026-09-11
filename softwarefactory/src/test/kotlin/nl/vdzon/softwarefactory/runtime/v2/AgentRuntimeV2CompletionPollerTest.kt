package nl.vdzon.softwarefactory.runtime.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.AgentRunRecord
import nl.vdzon.softwarefactory.core.contracts.AgentRunRepository
import nl.vdzon.softwarefactory.core.contracts.StoryRunRecord
import nl.vdzon.softwarefactory.core.contracts.StoryRunRepository
import nl.vdzon.softwarefactory.runtime.RuntimeApi
import nl.vdzon.softwarefactory.runtime.models.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.repositories.AgentEventRepository
import nl.vdzon.softwarefactory.runtime.types.CompletionOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.time.OffsetDateTime
import java.util.UUID

class AgentRuntimeV2CompletionPollerTest {
    private val jobId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val now = OffsetDateTime.parse("2026-09-11T07:00:00Z")

    @Test
    fun `cancelled job wordt zonder result request als zichtbare fout afgerond`() {
        val fixture = fixture(job(RuntimeJobStatus.CANCELLED, "USER_CANCELLED", "Geannuleerd"))

        fixture.poller.poll()

        verify(fixture.client, never()).getResult(jobId)
        assertEquals("error", fixture.runtime.requests.single().outcome)
        assertTrue(fixture.runtime.requests.single().summaryText.orEmpty().contains("USER_CANCELLED: Geannuleerd"))
    }

    @Test
    fun `failed job zonder result wordt als runtimefout afgerond`() {
        val fixture = fixture(job(RuntimeJobStatus.FAILED, "AGENT_FAILED", "Agent stopte"))
        doThrow(
            AgentRuntimeV2Exception("get result", 409, """{"code":"RESULT_NOT_READY"}"""),
        ).`when`(fixture.client).getResult(jobId)

        fixture.poller.poll()

        assertEquals("error", fixture.runtime.requests.single().outcome)
        assertTrue(fixture.runtime.requests.single().summaryText.orEmpty().contains("AGENT_FAILED: Agent stopte"))
    }

    @Test
    fun `failed verification job bewaart en publiceert zijn getypeerde result`() {
        val fixture = fixture(job(RuntimeJobStatus.FAILED, "VERIFICATION_FAILED", "Checks blijven rood"))
        val result = RuntimeJobResultView(
            jobId = jobId,
            result = jacksonObjectMapper().readTree(
                """{"phase":"developed","outcome":"developed","summaryText":"Werk uitgevoerd"}""",
            ),
            verificationResult = RuntimeVerificationResult(
                status = RuntimeVerificationStatus.FAILED,
                agentRounds = 4,
            ),
            usageSummary = RuntimeUsageSummary(1, "MEASURED"),
            completedAt = now,
        )
        doReturn(result).`when`(fixture.client).getResult(jobId)

        fixture.poller.poll()

        assertEquals("development-rejected", fixture.runtime.requests.single().outcome)
        verify(fixture.agentRuns).storeRuntimeJobResult(
            jobId.toString(),
            null,
            null,
            null,
            jacksonObjectMapper().writeValueAsString(result.verificationResult),
        )
    }

    @Test
    fun `transient resultfout op succeeded job wacht op volgende poll`() {
        val fixture = fixture(job(RuntimeJobStatus.SUCCEEDED))
        doThrow(IllegalStateException("tijdelijke netwerkfout")).`when`(fixture.client).getResult(jobId)

        fixture.poller.poll()

        assertTrue(fixture.runtime.requests.isEmpty())
    }

    @Test
    fun `resultaat van subtaak wordt op subtaak en niet op parent-story afgerond`() {
        val fixture = fixture(job(RuntimeJobStatus.CANCELLED, "USER_CANCELLED", "Geannuleerd"), "SF-43")

        fixture.poller.poll()

        assertEquals("SF-43", fixture.runtime.requests.single().storyKey)
    }

    private fun fixture(job: RuntimeJobView, subtaskKey: String? = null): Fixture {
        val agentRuns = mock(AgentRunRepository::class.java)
        val storyRuns = mock(StoryRunRepository::class.java)
        val client = mock(AgentRuntimeV2HttpClient::class.java)
        val runtime = CapturingRuntime()
        doReturn(listOf(run(subtaskKey))).`when`(agentRuns).activeRuns()
        doReturn(StoryRunRecord(7, "SF-42", "https://github.com/example/repo"))
            .`when`(storyRuns).get(7)
        doReturn(RuntimeJobEventPage(active = false)).`when`(client).events(jobId)
        doReturn(job).`when`(client).getJob(jobId)
        val poller = AgentRuntimeV2CompletionPoller(
            agentRuns,
            storyRuns,
            client,
            runtime,
            mock(AgentEventRepository::class.java),
            jacksonObjectMapper(),
        )
        return Fixture(poller, agentRuns, client, runtime)
    }

    private fun run(subtaskKey: String? = null) = AgentRunRecord(
        id = 1,
        storyRunId = 7,
        role = AgentRole.DEVELOPER,
        containerName = jobId.toString(),
        startedAt = now,
        endedAt = null,
        outcome = null,
        summaryText = null,
        subtaskKey = subtaskKey,
    )

    private fun job(status: RuntimeJobStatus, errorCode: String? = null, errorMessage: String? = null) =
        RuntimeJobView(
            id = jobId,
            tenantId = "software-factory",
            idempotencyKey = "sf-42-developer-1",
            jobKind = RuntimeJobKind.REPOSITORY_WORK,
            taskType = RuntimeTaskType.REPOSITORY_AGENT,
            execution = RuntimeExecution("openai", "gpt-5.6-sol", RuntimeExecutionMode.SUBSCRIPTION),
            status = status,
            phase = "COMPLETED",
            attemptCount = 1,
            maxAttempts = 3,
            errorCode = errorCode,
            errorMessage = errorMessage,
            createdAt = now,
            updatedAt = now,
            completedAt = now,
        )

    private data class Fixture(
        val poller: AgentRuntimeV2CompletionPoller,
        val agentRuns: AgentRunRepository,
        val client: AgentRuntimeV2HttpClient,
        val runtime: CapturingRuntime,
    )

    private class CapturingRuntime : RuntimeApi {
        val requests = mutableListOf<AgentRunCompleteRequest>()

        override fun complete(request: AgentRunCompleteRequest): CompletionOutcome {
            requests += request
            return CompletionOutcome.NoActiveRun
        }
    }
}
