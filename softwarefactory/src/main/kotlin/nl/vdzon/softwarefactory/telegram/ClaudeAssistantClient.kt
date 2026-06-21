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
 * Draait de `claude` CLI voor de Telegram-assistent (Fase B), maar **in een Docker-container**
 * (`assistant:local`, afgeleid van het agent-image). Voordelen t.o.v. host: isolatie (claude kan
 * niets buiten de gemounte mappen) en consistente tooling (gh/oc/psql/python/playwright zitten al
 * in het image), los van wat er toevallig op de host staat.
 *
 * Eén aanroep = één beurt. Multi-turn loopt via `--session-id` (eerste beurt) / `--resume <id>`
 * (vervolg). Omdat de container per beurt wegwerp is (`--rm`), mounten we een **per-chat**
 * `~/.claude`-map zodat claude z'n sessie-transcriptie terugvindt; de cwd in de container is constant
 * (`/work`), zodat de sessie-sleutel klopt.
 *
 * Auth via `CLAUDE_CODE_OAUTH_TOKEN` (uit `SF_AI_OAUTH_TOKEN`). Het interne factory-endpoint is vanuit
 * de container bereikbaar op `host.docker.internal`. Het `sf-youtrack`-script wordt read-only in
 * `/usr/local/bin` gemount.
 */
@Component
class ClaudeAssistantClient(
    private val secrets: FactorySecrets,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** De assistent draait alleen met een OAuth-token voor claude. */
    val enabled: Boolean get() = !secrets.aiOauthToken.isNullOrBlank()

    /**
     * Stelt één vraag aan claude in de container voor [chatId]. [sessionId] null => nieuwe sessie
     * (nieuwe id wordt teruggegeven); anders wordt die hervat.
     */
    fun ask(
        chatId: String,
        systemPrompt: String,
        userMessage: String,
        sessionId: String?,
        extraMounts: List<String> = emptyList(),
    ): AssistantReply {
        val first = attempt(chatId, systemPrompt, userMessage, sessionId, extraMounts)
        // Zelfherstel: een mislukte `--resume` (bv. de sessie bestaat niet meer in de mount) → opnieuw
        // met een verse sessie. Context gaat dan verloren, maar de gebruiker krijgt wél antwoord.
        if (sessionId != null && first.isError) {
            logger.info("Resume van sessie {} faalde; start een nieuwe sessie.", sessionId)
            return attempt(chatId, systemPrompt, userMessage, null, extraMounts)
        }
        return first
    }

    private fun attempt(
        chatId: String,
        systemPrompt: String,
        userMessage: String,
        sessionId: String?,
        extraMounts: List<String>,
    ): AssistantReply {
        val sid = sessionId ?: UUID.randomUUID().toString()
        val safeChat = chatId.replace(Regex("[^A-Za-z0-9]"), "_")
        val chatDir = stateDir(safeChat)
        // Verse /out per beurt: alleen afbeeldingen die claude nú maakt, sturen we terug.
        runCatching { chatDir.resolve("work").resolve("out").toFile().listFiles()?.forEach { it.delete() } }
        val containerName = "sf-assistant-$safeChat-${UUID.randomUUID().toString().take(8)}"
        val command = dockerCommand(chatDir, containerName, systemPrompt, userMessage, sid, isResume = sessionId != null, extraMounts)
        return runCatching { runDocker(command, containerName) }
            .getOrElse {
                logger.warn("Assistent-aanroep faalde.", it)
                AssistantReply("⚠️ De assistent kon niet antwoorden (interne fout).", isError = true, sessionId = sid, costUsd = 0.0)
            }
    }

    private fun dockerCommand(
        chatDir: Path,
        containerName: String,
        systemPrompt: String,
        userMessage: String,
        sid: String,
        isResume: Boolean,
        extraMounts: List<String>,
    ): List<String> {
        val toolsScript = Path.of("tools", "sf-youtrack").toAbsolutePath().toString()
        return buildList {
            add("docker"); add("run"); add("--rm")
            add("--name"); add(containerName)
            // Auth voor claude + YouTrack (sf-youtrack praat direct met YouTrack, met de factory-token).
            secrets.aiOauthToken?.takeIf { it.isNotBlank() }?.let { add("-e"); add("CLAUDE_CODE_OAUTH_TOKEN=$it") }
            add("-e"); add("SF_YOUTRACK_BASE_URL=${secrets.youTrackBaseUrl}")
            add("-e"); add("SF_YOUTRACK_PUBLIC_URL=${secrets.youTrackPublicUrl}")
            add("-e"); add("SF_YOUTRACK_TOKEN=${secrets.youTrackToken}")
            add("-e"); add("HOME=/home/runner")
            add("-e"); add("NPM_CONFIG_UPDATE_NOTIFIER=false")
            // Sessie-opslag (per chat) + werkmap; cwd constant op /work.
            add("-v"); add("${chatDir.resolve("claude")}:/home/runner/.claude")
            add("-v"); add("${chatDir.resolve("work")}:/work")
            add("-w"); add("/work")
            // sf-youtrack read-only in PATH (geen volledige tools-map mounten).
            add("-v"); add("$toolsScript:/usr/local/bin/sf-youtrack:ro")
            // Workspace-lagen (factory + project: /work/<naam>/repo + /private), read-only.
            extraMounts.forEach { add("-v"); add(it) }
            add(IMAGE)
            // Het commando dat in de container draait:
            add("claude")
            add("-p"); add(userMessage)
            add("--output-format"); add("stream-json")
            add("--verbose")
            add("--model"); add(MODEL)
            if (isResume) {
                add("--resume"); add(sid)
            } else {
                add("--session-id"); add(sid)
            }
            add("--append-system-prompt"); add(systemPrompt)
            add("--permission-mode"); add("bypassPermissions")
            add("--disallowed-tools"); addAll(DISALLOWED_TOOLS)
        }
    }

    private fun runDocker(command: List<String>, containerName: String): AssistantReply {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.close()

        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<AssistantReply> {
            var text = ""
            var isError = false
            var sessionId: String? = null
            var cost = 0.0
            var sawResult = false
            val tail = ArrayDeque<String>() // laatste regels voor diagnose bij een lege/mislukte run
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        tail.addLast(line.take(500))
                        if (tail.size > 30) tail.removeFirst()
                    }
                    val node = runCatching { objectMapper.readTree(line) }.getOrNull() ?: return@forEach
                    if (node.path("type").asText() == "result") {
                        sawResult = true
                        text = node.path("result").asText("").trim()
                        isError = node.path("is_error").asBoolean(false)
                        sessionId = node.path("session_id").asText(null)?.takeIf { it.isNotBlank() }
                        cost = node.path("total_cost_usd").asDouble(0.0)
                    }
                }
            }
            val exit = process.waitFor()
            if (!sawResult) {
                // Geen result-regel = de container/claude is gefaald (image, mount, docker, auth, …).
                logger.warn(
                    "Assistent-container gaf geen resultaat (exit={}, image={}). Laatste output:\n{}",
                    exit, IMAGE, tail.joinToString("\n"),
                )
                return@submit AssistantReply(
                    "⚠️ De assistent-container leverde geen antwoord (exit $exit). Check de factory-log.",
                    isError = true, sessionId = null, costUsd = 0.0,
                )
            }
            logger.info("Assistent-container klaar (exit={}, kosten ~${'$'}{}).", exit, cost)
            AssistantReply(text.ifBlank { "(leeg antwoord)" }, isError, sessionId, cost)
        }
        return try {
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (timeout: Exception) {
            logger.warn("Assistent-aanroep duurde langer dan {}s; container {} wordt gestopt.", TIMEOUT_SECONDS, containerName)
            runCatching { ProcessBuilder("docker", "kill", containerName).start().waitFor(10, TimeUnit.SECONDS) }
            process.destroyForcibly()
            AssistantReply("⚠️ De assistent deed er te lang over en is afgebroken.", isError = true, sessionId = null, costUsd = 0.0)
        } finally {
            executor.shutdownNow()
        }
    }

    /** Per-chat staat-map (`~/.claude`-sessies + werkmap met in/out), aangemaakt door de host-gebruiker. */
    private fun stateDir(safeChat: String): Path {
        val dir = Path.of("work", "assistant", safeChat).toAbsolutePath()
        runCatching {
            Files.createDirectories(dir.resolve("claude"))
            Files.createDirectories(dir.resolve("work").resolve("in"))
            Files.createDirectories(dir.resolve("work").resolve("out"))
        }
        return dir
    }

    /** Map (host) die in de container `/work/in` is — hier zet de factory binnenkomende foto's neer. */
    fun inputDir(chatId: String): Path {
        val dir = Path.of("work", "assistant", chatId.replace(Regex("[^A-Za-z0-9]"), "_"), "work", "in").toAbsolutePath()
        runCatching { Files.createDirectories(dir) }
        return dir
    }

    /** Afbeeldingen die claude deze beurt naar `/work/out` schreef (om naar Telegram te sturen). */
    fun outputImages(chatId: String): List<Path> {
        val dir = Path.of("work", "assistant", chatId.replace(Regex("[^A-Za-z0-9]"), "_"), "work", "out").toAbsolutePath()
        if (!Files.isDirectory(dir)) return emptyList()
        return runCatching {
            Files.list(dir).use { stream -> stream.toList() }
                .filter { Files.isRegularFile(it) && it.fileName.toString().substringAfterLast('.', "").lowercase() in IMAGE_EXTS }
        }.getOrDefault(emptyList())
    }

    private companion object {
        private val IMAGE = System.getenv("SF_ASSISTANT_IMAGE")?.takeIf { it.isNotBlank() } ?: "assistant:local"
        private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp")
        private const val MODEL = "claude-sonnet-4-6"
        private const val TIMEOUT_SECONDS = 300L
        // Bash blijft toegestaan (sf-youtrack + tools in het image); file-edit/web/subagents uit.
        // In Docker is de blast radius beperkt tot de gemounte mappen + de credentials die we injecteren.
        private val DISALLOWED_TOOLS = listOf(
            "Edit", "Write", "NotebookEdit", "WebFetch", "WebSearch", "Task",
        )
    }
}
