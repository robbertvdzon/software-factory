package nl.vdzon.softwarefactory.telegram

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Antwoord van één assistent-beurt. */
data class AssistantReply(
    val text: String,
    val isError: Boolean,
    /** De (eventueel nieuwe) claude session-id, om de volgende beurt mee te resumen. */
    val sessionId: String?,
    val costUsd: Double,
)

/**
 * Draait de `claude` CLI op de host voor de Telegram-assistent (Fase B). Eén aanroep = één beurt;
 * multi-turn loopt via `--session-id` (eerste beurt) en `--resume <id>` (vervolg), zodat claude de
 * eerdere conversatie zelf meeneemt.
 *
 * Auth gaat via `CLAUDE_CODE_OAUTH_TOKEN` (uit `SF_AI_OAUTH_TOKEN`) — net als de Docker-agents; een
 * geneste `claude` erft de host-login niet vanzelf. Zonder token is de assistent uitgeschakeld.
 *
 * Veiligheid (B1): álle tools staan uit (`--disallowed-tools`), dus puur conversationeel. Read-only
 * tools en acties (YouTrack/DB/story aanmaken) komen in een latere fase, met expliciete bevestiging.
 */
@Component
class ClaudeAssistantClient(
    private val secrets: FactorySecrets,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** De assistent kan alleen draaien met een OAuth-token voor de host-`claude`. */
    val enabled: Boolean get() = !secrets.aiOauthToken.isNullOrBlank()

    /**
     * Stelt één vraag aan claude. [sessionId] null => nieuwe sessie (nieuwe id wordt teruggegeven);
     * anders wordt die sessie hervat. Geeft het tekst-antwoord + de session-id voor de volgende beurt.
     */
    fun ask(systemPrompt: String, userMessage: String, sessionId: String?): AssistantReply {
        val sid = sessionId ?: UUID.randomUUID().toString()
        val command = buildList {
            add(claudeBin())
            add("-p"); add(userMessage)
            add("--output-format"); add("stream-json")
            add("--verbose")
            add("--model"); add(MODEL)
            if (sessionId == null) {
                add("--session-id"); add(sid)
            } else {
                add("--resume"); add(sid)
            }
            add("--append-system-prompt"); add(systemPrompt)
            // Variadic flag als laatste, zodat het de andere argumenten niet opslokt.
            add("--disallowed-tools"); addAll(DISALLOWED_TOOLS)
        }
        return runCatching { runClaude(command) }
            .getOrElse {
                logger.warn("Assistent-aanroep faalde.", it)
                AssistantReply("⚠️ De assistent kon niet antwoorden (interne fout).", isError = true, sessionId = sid, costUsd = 0.0)
            }
    }

    private fun runClaude(command: List<String>): AssistantReply {
        val process = ProcessBuilder(command)
            .directory(workdir().toFile())
            .redirectErrorStream(true)
            .also { builder ->
                secrets.aiOauthToken?.takeIf { it.isNotBlank() }?.let { builder.environment()["CLAUDE_CODE_OAUTH_TOKEN"] = it }
                builder.environment()["NPM_CONFIG_UPDATE_NOTIFIER"] = "false"
            }
            .start()
        process.outputStream.close()

        // Stdout in een aparte thread draineren (anders blokkeert een volle buffer); met timeout.
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<AssistantReply> {
            var text = ""
            var isError = false
            var sessionId: String? = null
            var cost = 0.0
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val node = runCatching { objectMapper.readTree(line) }.getOrNull() ?: return@forEach
                    if (node.path("type").asText() == "result") {
                        text = node.path("result").asText("").trim()
                        isError = node.path("is_error").asBoolean(false)
                        sessionId = node.path("session_id").asText(null)?.takeIf { it.isNotBlank() }
                        cost = node.path("total_cost_usd").asDouble(0.0)
                    }
                }
            }
            process.waitFor()
            AssistantReply(text.ifBlank { "(leeg antwoord)" }, isError, sessionId, cost)
        }
        return try {
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (timeout: Exception) {
            process.destroyForcibly()
            logger.warn("Assistent-aanroep duurde langer dan {}s en is afgebroken.", TIMEOUT_SECONDS)
            AssistantReply("⚠️ De assistent deed er te lang over en is afgebroken.", isError = true, sessionId = null, costUsd = 0.0)
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Lokaliseert de `claude` CLI. Vanuit een GUI/IntelliJ-gestart proces ontbreekt vaak Homebrew in
     * PATH, dus naast `SF_CLAUDE_BIN` en kale `claude` proberen we de bekende installatiepaden.
     */
    private fun claudeBin(): String {
        System.getenv("SF_CLAUDE_BIN")?.takeIf { it.isNotBlank() }?.let { return it }
        val candidates = listOf(
            "/opt/homebrew/bin/claude",
            "/usr/local/bin/claude",
            System.getProperty("user.home")?.let { "$it/.claude/local/claude" },
        )
        return candidates.filterNotNull().firstOrNull { Files.isExecutable(Path.of(it)) } ?: CLAUDE_BIN
    }

    /** Stabiele, secrets-vrije werkmap; claude bewaart z'n sessies per cwd, dus die moet constant zijn. */
    private fun workdir(): Path {
        val dir = Path.of("work", "assistant")
        runCatching { Files.createDirectories(dir) }
        return dir
    }

    private companion object {
        private const val CLAUDE_BIN = "claude"
        private const val MODEL = "claude-sonnet-4-6"
        private const val TIMEOUT_SECONDS = 180L
        // B1: geen enkel tool — puur tekst. (Latere fase: read-only tools + acties met bevestiging.)
        private val DISALLOWED_TOOLS = listOf(
            "Bash", "Edit", "Write", "NotebookEdit", "Read", "Grep", "Glob",
            "WebFetch", "WebSearch", "Task",
        )
    }
}
