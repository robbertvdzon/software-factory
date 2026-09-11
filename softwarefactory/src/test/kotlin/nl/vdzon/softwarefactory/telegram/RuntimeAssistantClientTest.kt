package nl.vdzon.softwarefactory.telegram

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.runtime.v2.AgentRoleExecutionConfig
import nl.vdzon.softwarefactory.runtime.v2.AgentRoleExecutionConfigService
import nl.vdzon.softwarefactory.runtime.v2.AgentRuntimeInputUploadService
import nl.vdzon.softwarefactory.runtime.v2.AgentRuntimeV2HttpClient
import nl.vdzon.softwarefactory.runtime.v2.AgentRuntimeV2Settings
import nl.vdzon.softwarefactory.runtime.v2.RuntimeCreateJobRequest
import nl.vdzon.softwarefactory.runtime.v2.RuntimeExecution
import nl.vdzon.softwarefactory.runtime.v2.RuntimeExecutionMode
import nl.vdzon.softwarefactory.runtime.v2.RuntimeJobKind
import nl.vdzon.softwarefactory.runtime.v2.RuntimeJobResultView
import nl.vdzon.softwarefactory.runtime.v2.RuntimeJobStatus
import nl.vdzon.softwarefactory.runtime.v2.RuntimeJobView
import nl.vdzon.softwarefactory.runtime.v2.RuntimeTaskType
import nl.vdzon.softwarefactory.runtime.v2.RuntimeUsageCost
import nl.vdzon.softwarefactory.runtime.v2.RuntimeUsageSummary
import nl.vdzon.softwarefactory.telegram.clients.RuntimeAssistantClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.math.BigDecimal
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

class RuntimeAssistantClientTest {
    private val jobId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val execution = RuntimeExecution("openai", "gpt-5.6-sol", RuntimeExecutionMode.SUBSCRIPTION)

    @Test
    fun `assistent maakt een structured Runtime-job en leest tekst tips en kosten`() {
        val runtime = mock(AgentRuntimeV2HttpClient::class.java)
        val executions = mock(AgentRoleExecutionConfigService::class.java)
        var capturedRequest: RuntimeCreateJobRequest? = null
        doReturn(AgentRoleExecutionConfig(role = AgentRole.ASSISTANT, projectKey = "project", execution = execution, updatedBy = "test"))
            .`when`(executions).resolve(AgentRole.ASSISTANT, "project")
        doAnswer { invocation ->
            capturedRequest = invocation.getArgument(0)
            job(RuntimeJobStatus.QUEUED)
        }.`when`(runtime).createJob(anyValue())
        doReturn(job(RuntimeJobStatus.SUCCEEDED)).`when`(runtime).getJob(jobId)
        doReturn(
            RuntimeJobResultView(
                jobId = jobId,
                result = jacksonObjectMapper().readTree(
                    """{"text":"Helder antwoord","tips":[{"category":"ux","key":"login","content":"Toon opnieuw inloggen"}]}""",
                ),
                usageSummary = RuntimeUsageSummary(
                    attemptCount = 1,
                    usageQuality = "MEASURED",
                    costs = listOf(RuntimeUsageCost("model", "FINAL", "USD", BigDecimal("0.12"))),
                ),
                completedAt = OffsetDateTime.now(),
            ),
        ).`when`(runtime).getResult(jobId)

        val client = client(runtime, executions, "token")
        val reply = client.ask(
            chatId = "chat",
            projectKey = "project",
            sessionId = "session",
            isResume = false,
            systemPrompt = "Je bent behulpzaam.",
            userMessage = "Help me.",
        )

        assertFalse(reply.isError)
        assertEquals("Helder antwoord", reply.text)
        assertEquals(0.12, reply.costUsd)
        assertEquals("login", reply.tips.single().key)
        val request = requireNotNull(capturedRequest)
        assertEquals(RuntimeJobKind.APPLICATION_WORK, request.jobKind)
        assertEquals(RuntimeTaskType.STRUCTURED_GENERATION, request.taskType)
        assertEquals(execution, request.execution)
        assertTrue(request.environmentKeys.isEmpty())
        assertTrue(request.input.instruction.contains("Help me."))
    }

    @Test
    fun `assistent staat uit zonder Runtime-token`() {
        val runtime = mock(AgentRuntimeV2HttpClient::class.java)
        val executions = mock(AgentRoleExecutionConfigService::class.java)
        val client = client(runtime, executions, null)

        val reply = client.ask("chat", null, "session", false, "systeem", "vraag")

        assertTrue(reply.isError)
        assertTrue(reply.text.contains("Agent Runtime"))
        verify(runtime, never()).createJob(anyValue())
    }

    private fun client(
        runtime: AgentRuntimeV2HttpClient,
        executions: AgentRoleExecutionConfigService,
        token: String?,
    ) = RuntimeAssistantClient(
        runtime = runtime,
        uploads = mock(AgentRuntimeInputUploadService::class.java),
        executions = executions,
        settings = AgentRuntimeV2Settings("https://runtime.example", token, Duration.ofSeconds(1), 3),
        objectMapper = jacksonObjectMapper(),
        configApi = object : ConfigApi {
            override fun resolvedValues() = mapOf("SF_ASSISTANT_TIMEOUT_SECONDS" to "5")
        },
    )

    private fun job(status: RuntimeJobStatus) = RuntimeJobView(
        id = jobId,
        tenantId = "software-factory",
        idempotencyKey = "assistant-test",
        jobKind = RuntimeJobKind.APPLICATION_WORK,
        taskType = RuntimeTaskType.STRUCTURED_GENERATION,
        execution = execution,
        status = status,
        phase = status.name.lowercase(),
        attemptCount = 1,
        maxAttempts = 1,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now(),
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyValue(): T {
        org.mockito.ArgumentMatchers.any<T>()
        return null as T
    }
}
