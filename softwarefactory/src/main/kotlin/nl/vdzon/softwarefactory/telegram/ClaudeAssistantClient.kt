package nl.vdzon.softwarefactory.telegram

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Eén geleerde tip die de assistent in z'n antwoord teruggaf (zelfde mechanisme als de werk-agents). */
data class AssistantTip(val category: String, val key: String, val content: String)

/** Antwoord van één assistent-beurt. */
data class AssistantReply(
    val text: String,
    val isError: Boolean,
    /** De (eventueel nieuwe) claude session-id, om de volgende beurt mee te resumen. */
    val sessionId: String?,
    val costUsd: Double,
    /** True als de gebruiker deze beurt via /stop heeft afgebroken (antwoord niet tonen). */
    val stopped: Boolean = false,
    /** Tips uit het `agent_tips_update`-JSON in het antwoord; de factory slaat ze op. */
    val tips: List<AssistantTip> = emptyList(),
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

    /** Lopende containers per thread-sessie, zodat /stop ze gericht kan killen. */
    private val running = ConcurrentHashMap<String, RunningAssistant>()

    /** Harde backstop: na zoveel seconden wordt de container alsnog gekild. Config via SF_ASSISTANT_TIMEOUT_SECONDS. */
    private val timeoutSeconds: Long =
        System.getenv("SF_ASSISTANT_TIMEOUT_SECONDS")?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_SECONDS

    /** Handle op een lopende beurt: de containernaam (voor `docker kill`) en het proces. */
    private class RunningAssistant(val containerName: String, val process: Process) {
        val stopped = AtomicBoolean(false)
    }

    /** De assistent draait alleen met een OAuth-token voor claude. */
    val enabled: Boolean get() = !secrets.aiOauthToken.isNullOrBlank()

    /** Breekt de lopende assistent-beurt van [sessionId] af (kilt de container). True als er iets liep. */
    fun stop(sessionId: String): Boolean {
        val handle = running[sessionId] ?: return false
        handle.stopped.set(true)
        logger.info("Assistent-thread {} wordt gestopt (container {}).", sessionId.take(8), handle.containerName)
        runCatching { ProcessBuilder("docker", "kill", handle.containerName).start().waitFor(10, TimeUnit.SECONDS) }
        handle.process.destroyForcibly()
        return true
    }

    /**
     * Stelt één vraag aan claude in de container voor thread [sessionId] in [chatId]. [isResume] false
     * = nieuwe thread (de meegegeven sessie-id wordt aangemaakt); true = bestaande thread hervatten.
     * Elke thread heeft een eigen geïsoleerde map (sessie + /work + in/out), zodat parallelle threads
     * elkaar niet in de weg zitten. De teruggegeven [AssistantReply.sessionId] is de werkelijk gebruikte
     * sessie (kan na zelfherstel afwijken).
     */
    fun ask(
        chatId: String,
        sessionId: String,
        isResume: Boolean,
        systemPrompt: String,
        userMessage: String,
        extraMounts: List<String> = emptyList(),
        extraEnv: Map<String, String> = emptyMap(),
        timeoutSecondsOverride: Long? = null,
    ): AssistantReply {
        val first = attempt(chatId, sessionId, isResume, systemPrompt, userMessage, extraMounts, extraEnv, timeoutSecondsOverride)
        // Zelfherstel: een mislukte `--resume` (sessie bestaat niet meer) → opnieuw met een verse sessie.
        // Maar NIET als de gebruiker zelf gestopt heeft — dan willen we 'm juist niet opnieuw starten.
        if (isResume && first.isError && !first.stopped) {
            logger.info("Resume van sessie {} faalde; start een nieuwe sessie.", sessionId)
            return attempt(chatId, UUID.randomUUID().toString(), false, systemPrompt, userMessage, extraMounts, extraEnv, timeoutSecondsOverride)
        }
        return first
    }

    private fun attempt(
        chatId: String,
        sessionId: String,
        isResume: Boolean,
        systemPrompt: String,
        userMessage: String,
        extraMounts: List<String>,
        extraEnv: Map<String, String>,
        timeoutSecondsOverride: Long?,
    ): AssistantReply {
        val threadDir = threadDir(chatId, sessionId)
        // Verse /out per beurt: alleen afbeeldingen die claude nú maakt, sturen we terug.
        runCatching { threadDir.resolve("work").resolve("out").toFile().listFiles()?.forEach { it.delete() } }
        val containerName = "sf-assistant-${sessionId.take(12)}-${UUID.randomUUID().toString().take(6)}"
        val command = dockerCommand(threadDir, containerName, systemPrompt, userMessage, sessionId, isResume, extraMounts, extraEnv)
        return runCatching { runDocker(command, containerName, sessionId, timeoutSecondsOverride ?: timeoutSeconds) }
            .getOrElse {
                logger.warn("Assistent-aanroep faalde.", it)
                AssistantReply("⚠️ De assistent kon niet antwoorden (interne fout).", isError = true, sessionId = sessionId, costUsd = 0.0)
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
        extraEnv: Map<String, String>,
    ): List<String> {
        // Tools resolven vanaf de repo-root — niet cwd-relatief. Bij `mvn -pl softwarefactory spring-boot:run`
        // is de cwd de module-map, en dan zou `tools/...` naar het niet-bestaande softwarefactory/tools wijzen;
        // Docker maakt van zo'n ontbrekend bind-pad een LEGE map → sf-youtrack/sf-browser kapot.
        val toolsDir = repoRoot().resolve("tools")
        val youtrackScript = toolsDir.resolve("sf-youtrack").toString()
        val browserScript = toolsDir.resolve("sf-browser").toString()
        return buildList {
            add("docker"); add("run"); add("--rm")
            add("--name"); add(containerName)
            // Auth voor claude + YouTrack (sf-youtrack praat direct met YouTrack, met de factory-token).
            secrets.aiOauthToken?.takeIf { it.isNotBlank() }?.let { add("-e"); add("CLAUDE_CODE_OAUTH_TOKEN=$it") }
            add("-e"); add("SF_YOUTRACK_BASE_URL=${secrets.youTrackBaseUrl}")
            add("-e"); add("SF_YOUTRACK_PUBLIC_URL=${secrets.youTrackPublicUrl}")
            add("-e"); add("SF_YOUTRACK_TOKEN=${secrets.youTrackToken}")
            // Cluster-toegang: dezelfde (cert-based) kubeconfig als de tester/refiner-agents, read-only +
            // KUBECONFIG gezet. Zo kan de assistent zelf `oc`/`kubectl` draaien (describe/logs/get) i.p.v.
            // jou te vragen de output te plakken. Cert-based, dus geen verlopende token.
            secrets.kubeconfig?.takeIf { it.isNotBlank() }?.let {
                add("-v"); add("${localPath(it)}:/home/runner/.kube/config:ro")
                add("-e"); add("KUBECONFIG=/home/runner/.kube/config")
            }
            add("-e"); add("HOME=/home/runner")
            add("-e"); add("NPM_CONFIG_UPDATE_NOTIFIER=false")
            // Extra env (bv. GH_TOKEN voor de nightly-digest, zodat `gh` de commits/PR's kan lezen).
            extraEnv.forEach { (key, value) -> if (value.isNotBlank()) { add("-e"); add("$key=$value") } }
            // Sessie-opslag (per chat) + werkmap; cwd constant op /work.
            add("-v"); add("${chatDir.resolve("claude")}:/home/runner/.claude")
            add("-v"); add("${chatDir.resolve("work")}:/work")
            add("-w"); add("/work")
            // Tools read-only in PATH (losse bestanden, geen volledige tools-map mounten).
            add("-v"); add("$youtrackScript:/usr/local/bin/sf-youtrack:ro")
            add("-v"); add("$browserScript:/usr/local/bin/sf-browser:ro")
            // Tips slaat de assistent NIET zelf op: hij geeft ze terug als agent_tips_update-JSON in z'n
            // antwoord, en de factory parset + bewaart dat (zelfde mechanisme als de werk-agents).
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

    private fun runDocker(command: List<String>, containerName: String, sessionId: String, timeoutSeconds: Long): AssistantReply {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.close()
        val handle = RunningAssistant(containerName, process)
        running[sessionId] = handle

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
            val tips = extractTips(text)
            val cleanText = stripTipsJson(text).ifBlank { "(leeg antwoord)" }
            AssistantReply(cleanText, isError, sessionId, cost, tips = tips)
        }
        return try {
            val reply = future.get(timeoutSeconds, TimeUnit.SECONDS)
            // Door de gebruiker gestopt vlak voor het einde? Toon dan niet alsnog een (half) antwoord.
            if (handle.stopped.get()) STOPPED_REPLY else reply
        } catch (ignored: Exception) {
            if (handle.stopped.get()) {
                // Door /stop gekild — het antwoord onderdrukken; de /stop-handler meldt het al.
                STOPPED_REPLY
            } else {
                logger.warn("Assistent-aanroep duurde langer dan {}s; container {} wordt gestopt.", timeoutSeconds, containerName)
                runCatching { ProcessBuilder("docker", "kill", containerName).start().waitFor(10, TimeUnit.SECONDS) }
                process.destroyForcibly()
                AssistantReply("⚠️ De assistent deed er te lang over en is afgebroken.", isError = true, sessionId = null, costUsd = 0.0)
            }
        } finally {
            running.remove(sessionId, handle)
            executor.shutdownNow()
        }
    }

    /** Haalt de tips uit het laatste `agent_tips_update`-JSON-object in het antwoord (zelfde vorm als de werk-agents). */
    private fun extractTips(text: String): List<AssistantTip> =
        jsonObjectCandidates(text).asReversed().firstNotNullOfOrNull { candidate ->
            val root = runCatching { objectMapper.readTree(candidate) }.getOrNull() ?: return@firstNotNullOfOrNull null
            val updates = root.path("agent_tips_update").takeIf { it.isArray } ?: return@firstNotNullOfOrNull null
            updates.mapNotNull { u ->
                val category = u.path("category").asText("").trim()
                val key = u.path("key").asText("").trim()
                val content = u.path("content").asText("").trim()
                if (category.isBlank() || key.isBlank() || content.isBlank()) null
                else AssistantTip(category, key, content)
            }
        }.orEmpty()

    /** Verwijdert elk JSON-object met een `agent_tips_update`-veld uit de tekst, zodat de gebruiker het niet ziet. */
    private fun stripTipsJson(text: String): String {
        var result = text
        jsonObjectCandidates(text).filter { it.contains("agent_tips_update") }.forEach { result = result.replace(it, "") }
        return result.trim()
    }

    /** Vindt top-level `{...}`-objecten in vrije tekst (let op string-literals/escapes), om als JSON te proberen. */
    private fun jsonObjectCandidates(text: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inStr = false
        var esc = false
        for (i in text.indices) {
            val c = text[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) { out.add(text.substring(start, i + 1)); start = -1 }
                }
            }
        }
        return out
    }

    /**
     * Repo-root, ook als de app via `mvn -pl softwarefactory spring-boot:run` start (dan is de cwd de
     * module-map `softwarefactory/`). Nodig om de tools betrouwbaar te kunnen mounten, ongeacht de cwd.
     */
    private fun repoRoot(): Path {
        val cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val parent = cwd.parent
        return if (cwd.fileName?.toString() == "softwarefactory" && parent != null && Files.exists(parent.resolve("agentworker"))) {
            parent
        } else {
            cwd
        }
    }

    /** Per-thread staat-map (`~/.claude`-sessie + werkmap met in/out), aangemaakt door de host-gebruiker. */
    private fun threadDir(chatId: String, sessionId: String): Path {
        val dir = Path.of("work", "assistant", chatId.sanitized(), sessionId.sanitized()).toAbsolutePath()
        runCatching {
            Files.createDirectories(dir.resolve("claude"))
            Files.createDirectories(dir.resolve("work").resolve("in"))
            Files.createDirectories(dir.resolve("work").resolve("out"))
        }
        return dir
    }

    /** Map (host) die in de container `/work/in` is — hier zet de factory binnenkomende foto's neer. */
    fun inputDir(chatId: String, sessionId: String): Path =
        threadDir(chatId, sessionId).resolve("work").resolve("in")

    /** Afbeeldingen die claude deze beurt naar `/work/out` schreef (om naar Telegram te sturen). */
    fun outputImages(chatId: String, sessionId: String): List<Path> {
        val dir = threadDir(chatId, sessionId).resolve("work").resolve("out")
        if (!Files.isDirectory(dir)) return emptyList()
        return runCatching {
            Files.list(dir).use { stream -> stream.toList() }
                .filter { Files.isRegularFile(it) && it.fileName.toString().substringAfterLast('.', "").lowercase() in IMAGE_EXTS }
        }.getOrDefault(emptyList())
    }

    private fun String.sanitized(): String = replace(Regex("[^A-Za-z0-9]"), "_")

    /** Expandeert een eventueel `~`-pad (zoals SF_KUBECONFIG) naar een absoluut pad voor de docker-mount. */
    private fun localPath(value: String): String {
        val trimmed = value.trim()
        val expanded = when {
            trimmed == "~" -> System.getProperty("user.home")
            trimmed.startsWith("~/") -> System.getProperty("user.home") + trimmed.removePrefix("~")
            else -> trimmed
        }
        return Path.of(expanded).toAbsolutePath().normalize().toString()
    }

    private companion object {
        private val IMAGE = System.getenv("SF_ASSISTANT_IMAGE")?.takeIf { it.isNotBlank() } ?: "assistant:local"
        private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp")
        private const val MODEL = "claude-opus-4-8"
        /** Backstop-timeout als SF_ASSISTANT_TIMEOUT_SECONDS niet gezet is (60 min). */
        private const val DEFAULT_TIMEOUT_SECONDS = 3600L
        /** Sentinel-antwoord voor een door de gebruiker gestopte beurt; wordt niet naar Telegram gestuurd. */
        private val STOPPED_REPLY = AssistantReply("", isError = true, sessionId = null, costUsd = 0.0, stopped = true)
        // Bash blijft toegestaan (sf-youtrack + tools in het image); file-edit/web/subagents uit.
        // In Docker is de blast radius beperkt tot de gemounte mappen + de credentials die we injecteren.
        private val DISALLOWED_TOOLS = listOf(
            "Edit", "Write", "NotebookEdit", "WebFetch", "WebSearch", "Task",
        )
    }
}
