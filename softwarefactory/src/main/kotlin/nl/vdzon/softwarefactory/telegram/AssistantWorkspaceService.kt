package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.git.services.ProcessRunner
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Bouwt per gesprek de "lagen" die de assistent-container krijgt: altijd de basislaag (de Software
 * Factory) plus het project van het kanaal. Elke laag levert een **read-only** repo-checkout en
 * (optioneel) een mapje met de geconfigureerde `private:`-bestanden, gemount onder
 * `/work/<naam>/repo` resp. `/work/<naam>/private`.
 *
 * Repo's worden factory-beheerd uitgecheckt onder `work/assistant-checkouts/<naam>` (clone-als-nieuw,
 * anders `git fetch`+reset bij een nieuw gesprek) — nooit jouw eigen werkmap. Private-bestanden worden
 * runtime gekopieerd naar een per-chat map (en bij elke voorbereiding vers opgebouwd).
 */
@Service
class AssistantWorkspaceService(
    private val git: GitApi,
    private val processRunner: ProcessRunner,
    private val secrets: FactorySecrets,
    private val resolver: ProjectRepoResolver,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Eén laag in de workspace, met de container-paden (null = niet beschikbaar). */
    data class Layer(val name: String, val repoPath: String?, val privatePath: String?, val isBase: Boolean)

    /** [mounts] = docker `-v`-specs (host:container:ro); [layers] = beschrijving voor de prompt. */
    data class Layout(val mounts: List<String>, val layers: List<Layer>)

    /**
     * Zet de lagen klaar voor [chatId]. [refresh] true (nieuw gesprek) => repo's bijwerken naar remote;
     * binnen een lopend gesprek laten we de checkout met rust (snel + stabiel).
     */
    fun prepare(chatId: String, refresh: Boolean): Layout {
        val safeChat = chatId.sanitized()
        val base = resolver.baseProjectName()
        val project = resolver.projectNameForChatId(chatId)
        val names = listOfNotNull(base, project).distinctBy { it.lowercase() }

        val mounts = mutableListOf<String>()
        val layers = mutableListOf<Layer>()
        for (name in names) {
            val safeName = name.sanitized()
            val containerBase = "/work/$safeName"

            val repoPath = resolver.repoFor(name)?.let { url ->
                runCatching { ensureCheckout(safeName, url, refresh) }
                    .onFailure { logger.warn("Checkout van project '{}' faalde (laag overgeslagen).", name, it) }
                    .getOrNull()
            }?.also { mounts += "$it:$containerBase/repo:ro" }

            val privateDir = copyPrivateFiles(safeChat, safeName, resolver.privateFilesFor(name))
                ?.also { mounts += "$it:$containerBase/private:ro" }

            if (repoPath != null || privateDir != null) {
                layers += Layer(
                    name = name,
                    repoPath = repoPath?.let { "$containerBase/repo" },
                    privatePath = privateDir?.let { "$containerBase/private" },
                    isBase = name.equals(base, ignoreCase = true),
                )
            }
        }
        return Layout(mounts, layers)
    }

    private fun ensureCheckout(safeName: String, url: String, refresh: Boolean): Path {
        val repoRoot = Path.of("work", "assistant-checkouts", safeName, "repo").toAbsolutePath()
        if (!Files.exists(repoRoot.resolve(".git"))) {
            logger.info("Assistent: repo '{}' klonen ({}).", safeName, url)
            git.clone(url, repoRoot, secrets.githubToken)
        } else if (refresh) {
            runCatching { git.checkoutBase(repoRoot, defaultBranch(repoRoot), secrets.githubToken) }
                .onFailure { logger.warn("Bijwerken van checkout '{}' faalde (bestaande gebruiken).", safeName, it) }
        }
        return repoRoot
    }

    /** De default-branch van de remote (origin/HEAD), met `main` als vangnet. */
    private fun defaultBranch(repoRoot: Path): String {
        val result = runCatching {
            processRunner.run(listOf("git", "rev-parse", "--abbrev-ref", "origin/HEAD"), cwd = repoRoot, timeoutSeconds = 20)
        }.getOrNull()
        return result?.stdout?.trim()?.substringAfter("origin/")?.takeIf { it.isNotEmpty() } ?: "main"
    }

    /** Kopieert de private-bestanden naar een verse per-chat map; geeft die map terug of null als er niets is. */
    private fun copyPrivateFiles(safeChat: String, safeName: String, files: List<String>): Path? {
        if (files.isEmpty()) return null
        val dir = Path.of("work", "assistant", safeChat, "private", safeName).toAbsolutePath()
        runCatching {
            if (Files.exists(dir)) dir.toFile().deleteRecursively()
            Files.createDirectories(dir)
        }
        var copied = 0
        for (entry in files) {
            val src = Path.of(entry).let { if (it.isAbsolute) it else Path.of("").toAbsolutePath().resolve(it) }
            if (Files.isRegularFile(src)) {
                runCatching { Files.copy(src, dir.resolve(src.fileName.toString()), StandardCopyOption.REPLACE_EXISTING) }
                    .onSuccess { copied++ }
                    .onFailure { logger.warn("Kon private-bestand '{}' niet kopiëren.", src, it) }
            } else {
                logger.warn("Private-bestand niet gevonden voor '{}': {}", safeName, src)
            }
        }
        return if (copied > 0) dir else null
    }

    private fun String.sanitized(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")
}
