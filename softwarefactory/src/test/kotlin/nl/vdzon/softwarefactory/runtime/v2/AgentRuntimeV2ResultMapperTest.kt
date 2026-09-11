package nl.vdzon.softwarefactory.runtime.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.core.AgentRole
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentRuntimeV2ResultMapperTest {
    private val mapper = AgentRuntimeV2ResultMapper()
    private val json = jacksonObjectMapper()

    @Test
    fun `blijvend rode developerverificatie wordt een begrensde domeinloopback zonder succesclaim`() {
        val jobId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val result = mapper.completed(
            storyKey = "SF-42",
            role = AgentRole.DEVELOPER,
            runtimeJobId = jobId.toString(),
            job = job(jobId, RuntimeJobStatus.FAILED),
            result = RuntimeJobResultView(
                jobId = jobId,
                result = json.readTree(
                    """{"phase":"developed","outcome":"developed","summaryText":"Implementatie klaar"}""",
                ),
                verificationResult = RuntimeVerificationResult(
                    status = RuntimeVerificationStatus.FAILED,
                    configVersion = 1,
                    agentRounds = 4,
                    commands = listOf(
                        RuntimeVerificationCommandResult(
                            id = "backend",
                            argv = listOf("mvn", "verify"),
                            status = RuntimeVerificationCommandStatus.FAILED,
                            exitCode = 1,
                            durationMillis = 50,
                            outputTail = "1 test failed",
                        ),
                    ),
                ),
                usageSummary = RuntimeUsageSummary(1, "MEASURED"),
                completedAt = OffsetDateTime.parse("2026-09-11T07:00:00Z"),
            ),
        )

        assertEquals("development-rejected", result.phase)
        assertEquals("development-rejected", result.outcome)
        assertEquals(0, result.exitCode)
        assertTrue(result.summaryText.orEmpty().contains("backend: FAILED"))
        assertTrue(result.summaryText.orEmpty().contains("agentRounds=4"))
    }

    private fun job(id: UUID, status: RuntimeJobStatus) = RuntimeJobView(
        id = id,
        tenantId = "software-factory",
        idempotencyKey = "sf-42",
        jobKind = RuntimeJobKind.REPOSITORY_WORK,
        taskType = RuntimeTaskType.REPOSITORY_AGENT,
        execution = RuntimeExecution("openai", "gpt-5.6-sol", RuntimeExecutionMode.SUBSCRIPTION),
        status = status,
        phase = "COMPLETED",
        attemptCount = 1,
        maxAttempts = 3,
        createdAt = OffsetDateTime.parse("2026-09-11T07:00:00Z"),
        updatedAt = OffsetDateTime.parse("2026-09-11T07:00:00Z"),
    )
}

