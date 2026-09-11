package nl.vdzon.softwarefactory.telegram.clients

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.AgentInputAttachment
import nl.vdzon.softwarefactory.runtime.v2.AgentRoleExecutionConfigService
import nl.vdzon.softwarefactory.runtime.v2.AgentRuntimeInputUploadService
import nl.vdzon.softwarefactory.runtime.v2.AgentRuntimeV2HttpClient
import nl.vdzon.softwarefactory.runtime.v2.AgentRuntimeV2Settings
import nl.vdzon.softwarefactory.runtime.v2.RuntimeCreateJobRequest
import nl.vdzon.softwarefactory.runtime.v2.RuntimeJobInput
import nl.vdzon.softwarefactory.runtime.v2.RuntimeJobKind
import nl.vdzon.softwarefactory.runtime.v2.RuntimeJobResultView
import nl.vdzon.softwarefactory.runtime.v2.RuntimeJobStatus
import nl.vdzon.softwarefactory.runtime.v2.RuntimeOutputContract
import nl.vdzon.softwarefactory.runtime.v2.RuntimeTaskType
import nl.vdzon.softwarefactory.telegram.InteractiveAssistantClient
import nl.vdzon.softwarefactory.telegram.models.AssistantInputFile
import nl.vdzon.softwarefactory.telegram.models.AssistantReply
import nl.vdzon.softwarefactory.telegram.models.AssistantTip
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Telegram-assistent via Agent Runtime v2. Iedere beurt is een stateless structured-generationjob;
 * begrensde gesprekshistorie wordt expliciet in de volgende instructie opgenomen. Er worden geen
 * lokale container, repositorycheckout, providercredential, toolmount of projectsecret gebruikt.
 */
@Component
open class RuntimeAssistantClient(
    private val runtime: AgentRuntimeV2HttpClient,
    private val uploads: AgentRuntimeInputUploadService,
    private val executions: AgentRoleExecutionConfigService,
    private val settings: AgentRuntimeV2Settings,
    private val objectMapper: ObjectMapper,
    configApi: ConfigApi = ConfigApi.default(),
) : InteractiveAssistantClient {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val running = ConcurrentHashMap<String, UUID>()
    private val histories = ConcurrentHashMap<String, ArrayDeque<Turn>>()
    private val timeoutSeconds = configApi.resolvedValues()["SF_ASSISTANT_TIMEOUT_SECONDS"]
        ?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_SECONDS

    override val enabled: Boolean
        get() = !settings.token.isNullOrBlank()

    override fun ask(
        chatId: String,
        projectKey: String?,
        sessionId: String,
        isResume: Boolean,
        systemPrompt: String,
        userMessage: String,
        inputFile: AssistantInputFile?,
        timeoutSecondsOverride: Long?,
    ): AssistantReply {
        if (!enabled) return errorReply("⚠️ De assistent staat uit: Agent Runtime is niet geconfigureerd.", sessionId)
        if (!isResume) histories.remove(sessionId)
        val idempotencyKey = "sf-assistant-${sessionId.take(36)}-${UUID.randomUUID()}".take(160)
        return runCatching {
            val execution = executions.resolve(AgentRole.ASSISTANT, projectKey).execution
            val objects = inputFile?.let { file ->
                uploads.upload(
                    idempotencyKey,
                    listOf(
                        AgentInputAttachment(
                            logicalName = "telegram-input",
                            originalFilename = file.filename,
                            uploadFilename = file.filename,
                            mimeType = file.mimeType,
                            bytes = file.bytes,
                            role = "ATTACHMENT",
                        ),
                    ),
                )
            }.orEmpty()
            val created = runtime.createJob(
                RuntimeCreateJobRequest(
                    idempotencyKey = idempotencyKey,
                    jobKind = RuntimeJobKind.APPLICATION_WORK,
                    taskType = RuntimeTaskType.STRUCTURED_GENERATION,
                    execution = execution,
                    input = RuntimeJobInput(instruction(systemPrompt, sessionId, userMessage, inputFile != null), objects),
                    output = RuntimeOutputContract(resultSchema = resultSchema()),
                    executionTimeoutSeconds = (timeoutSecondsOverride ?: timeoutSeconds).toInt().coerceIn(600, 86_400),
                ),
            )
            running[sessionId] = created.id
            val result = awaitResult(created.id, timeoutSecondsOverride ?: timeoutSeconds)
            val reply = mapResult(result, sessionId)
            if (!reply.isError) remember(sessionId, userMessage, reply.text)
            reply
        }.getOrElse { exception ->
            logger.warn("Agent Runtime-assistentaanroep faalde voor chat {}.", chatId, exception)
            errorReply("⚠️ De assistent kon niet antwoorden (interne Runtime-fout).", sessionId)
        }.also { running.remove(sessionId) }
    }

    override fun stop(sessionId: String): Boolean {
        val jobId = running[sessionId] ?: return false
        return runCatching { runtime.cancel(jobId); true }
            .onFailure { logger.warn("Kon Runtime-assistentjob {} niet annuleren.", jobId, it) }
            .getOrDefault(false)
    }

    private fun awaitResult(jobId: UUID, requestedTimeoutSeconds: Long): RuntimeJobResultView {
        val deadline = Instant.now().plusSeconds(requestedTimeoutSeconds.coerceAtLeast(1))
        while (Instant.now().isBefore(deadline)) {
            val job = runtime.getJob(jobId)
            if (job.terminal) {
                require(job.status == RuntimeJobStatus.SUCCEEDED) {
                    listOfNotNull(job.errorCode, job.errorMessage).joinToString(": ")
                        .ifBlank { "Agent Runtime job eindigde als ${job.status}." }
                }
                return runtime.getResult(jobId)
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        runCatching { runtime.cancel(jobId) }
        error("Agent Runtime-assistentjob $jobId overschreed de timeout.")
    }

    private fun instruction(systemPrompt: String, sessionId: String, userMessage: String, hasInput: Boolean): String =
        buildString {
            appendLine(systemPrompt.trim())
            appendLine()
            appendLine("## Gesprek")
            histories[sessionId]?.forEach { turn ->
                appendLine("Gebruiker: ${turn.user}")
                appendLine("Assistent: ${turn.assistant}")
            }
            appendLine("Gebruiker: $userMessage")
            if (hasInput) appendLine("Bijlage: lees `/job/input/objects/telegram-input/content`.")
            appendLine()
            appendLine("Geef uitsluitend het JSON-resultaat volgens het opgegeven schema.")
        }.takeLast(MAX_INSTRUCTION_CHARS)

    private fun mapResult(result: RuntimeJobResultView, sessionId: String): AssistantReply {
        val text = result.result.path("text").asText("").trim()
        require(text.isNotBlank()) { "Agent Runtime-assistent leverde geen antwoordtekst." }
        val tips = result.result.path("tips").mapNotNull { node ->
            val category = node.nonBlank("category") ?: return@mapNotNull null
            val key = node.nonBlank("key") ?: return@mapNotNull null
            val content = node.nonBlank("content") ?: return@mapNotNull null
            AssistantTip(category, key, content)
        }
        val cost = result.usageSummary.costs
            .filter { it.currency == "USD" }
            .fold(BigDecimal.ZERO) { total, item -> total + item.amount }
            .toDouble()
        return AssistantReply(text, isError = false, sessionId = sessionId, costUsd = cost, tips = tips)
    }

    private fun remember(sessionId: String, user: String, assistant: String) {
        val history = histories.computeIfAbsent(sessionId) { ArrayDeque() }
        synchronized(history) {
            history.addLast(Turn(user.take(MAX_TURN_CHARS), assistant.take(MAX_TURN_CHARS)))
            while (history.size > MAX_TURNS) history.removeFirst()
        }
    }

    private fun resultSchema(): JsonNode = objectMapper.readTree(RESULT_SCHEMA)

    private fun JsonNode.nonBlank(field: String): String? = path(field).asText("").trim().takeIf(String::isNotBlank)

    private fun errorReply(text: String, sessionId: String) =
        AssistantReply(text, isError = true, sessionId = sessionId, costUsd = 0.0)

    private data class Turn(val user: String, val assistant: String)

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 3_600L
        const val POLL_INTERVAL_MILLIS = 500L
        const val MAX_TURNS = 12
        const val MAX_TURN_CHARS = 8_000
        const val MAX_INSTRUCTION_CHARS = 60_000
        val RESULT_SCHEMA = """
            {
              "type":"object",
              "additionalProperties":false,
              "required":["text","tips"],
              "properties":{
                "text":{"type":"string","minLength":1},
                "tips":{"type":"array","items":{"type":"object","additionalProperties":false,
                  "required":["category","key","content"],
                  "properties":{"category":{"type":"string"},"key":{"type":"string"},"content":{"type":"string"}}}}
              }
            }
        """.trimIndent()
    }
}
