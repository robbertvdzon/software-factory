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

    @Test
    fun `result artifacts blijven als immutable objectrefs beschikbaar voor completion`() {
        val jobId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val artifact = RuntimeOutputObject(
            objectId = UUID.fromString("44444444-4444-4444-4444-444444444444"),
            name = "screenshots",
            filename = "screenshots",
            mimeType = "application/zip",
            sizeBytes = 123,
            sha256 = "a".repeat(64),
            state = "READY",
            createdAt = OffsetDateTime.parse("2026-09-11T07:00:00Z"),
            readyAt = OffsetDateTime.parse("2026-09-11T07:00:00Z"),
            downloadUrl = "/v2/jobs/$jobId/objects/44444444-4444-4444-4444-444444444444/content",
        )

        val completion = mapper.completed(
            storyKey = "SF-42",
            role = AgentRole.TESTER,
            runtimeJobId = jobId.toString(),
            job = job(jobId, RuntimeJobStatus.SUCCEEDED),
            result = RuntimeJobResultView(
                jobId = jobId,
                result = json.readTree("""{"phase":"tested","outcome":"tested"}"""),
                artifacts = listOf(artifact),
                usageSummary = RuntimeUsageSummary(1, "MEASURED"),
                completedAt = OffsetDateTime.parse("2026-09-11T07:00:00Z"),
            ),
        )

        assertEquals(listOf(artifact), completion.runtimeArtifacts)
    }

    @Test
    fun `structured rollen behouden vragen subtaken en samenvattingen`() {
        val jobId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val runtimeJob = job(jobId, RuntimeJobStatus.SUCCEEDED)
        val refiner = mapper.completed(
            "SF-42",
            AgentRole.REFINER,
            jobId.toString(),
            runtimeJob,
            result(jobId, """{"phase":"refined-with-questions","outcome":"waiting","summaryText":"Nog niet helder.","questions":["Wat is leidend?"]}"""),
        )
        val planner = mapper.completed(
            "SF-42",
            AgentRole.PLANNER,
            jobId.toString(),
            runtimeJob,
            result(jobId, """{"phase":"planned","outcome":"planned","summaryText":"Plan klaar.","subtasks":[{"type":"development","title":"Bouw het"}]}"""),
        )
        val summarizer = mapper.completed(
            "SF-42",
            AgentRole.SUMMARIZER,
            jobId.toString(),
            runtimeJob,
            result(jobId, """{"phase":"summarized","outcome":"summarized","summaryText":"Klaar.","descriptionSummary":"Uitgebreid.","shortDescriptionSummary":"Kort."}"""),
        )

        assertTrue(refiner.summaryText.orEmpty().contains("Wat is leidend?"))
        assertEquals("development", planner.subtasks.single().type)
        assertEquals("Uitgebreid.", summarizer.descriptionSummary)
        assertEquals("Kort.", summarizer.shortDescriptionSummary)
    }

    private fun result(jobId: UUID, payload: String) = RuntimeJobResultView(
        jobId = jobId,
        result = json.readTree(payload),
        usageSummary = RuntimeUsageSummary(1, "MEASURED"),
        completedAt = OffsetDateTime.parse("2026-09-11T07:00:00Z"),
    )

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
