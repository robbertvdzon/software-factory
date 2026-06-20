package nl.vdzon.softwarefactory.config

import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * Mapt een logische projectnaam (waarde van het `Project`-veld op een story) naar een git-repo.
 *
 * De koppeling staat in een YAML-config-bestand naast de andere config (`projects.yaml`):
 *
 * ```yaml
 * projects:
 *   - name: personal-feed
 *     repo: git@github.com:robbert/personal-feed.git
 *   - name: softwarefactory
 *     repo: https://github.com/robbert/softwarefactory.git
 * ```
 *
 * Matching op naam is hoofdletter-ongevoelig en negeert omringende spaties. Eén YouTrack-project
 * kan zo stories voor verschillende repo's bevatten; subtaken erven de repo van hun parent-story.
 *
 * Het bestand wordt één keer (bij opstart) ingelezen. Ontbreekt het of is een entry ongeldig, dan
 * wordt dat gelogd en blijft de betreffende naam simpelweg onopgelost (→ story zonder repo).
 */
class ProjectRepoResolver(
    repos: Map<String, String>,
    telegramChatIds: Map<String, String> = emptyMap(),
) {
    private val byName = LinkedHashMap<String, String>()
    private val originalNames = mutableListOf<String>()
    private val chatIdByName = LinkedHashMap<String, String>()

    init {
        repos.forEach { (name, repo) ->
            val key = name.trim().lowercase()
            val value = repo.trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                if (byName.put(key, value) == null) {
                    originalNames.add(name.trim())
                }
            }
        }
        telegramChatIds.forEach { (name, chatId) ->
            val key = name.trim().lowercase()
            val value = chatId.trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                chatIdByName[key] = value
            }
        }
    }

    /** Het Telegram-kanaal (chat-id) voor [projectName], of null als de naam leeg/onbekend/zonder kanaal is. */
    fun telegramChatIdFor(projectName: String?): String? {
        val key = projectName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return chatIdByName[key]
    }

    /** Alle geconfigureerde Telegram-kanalen (voor de inkomende chat-id-allowlist). */
    fun telegramChatIds(): Set<String> = chatIdByName.values.toSet()

    /** De geconfigureerde repo voor [projectName], of null als de naam leeg/onbekend is. */
    fun repoFor(projectName: String?): String? {
        val key = projectName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return byName[key]
    }

    /**
     * Bepaalt de repo voor een `Repo`-veldwaarde: staat de tekst als projectnaam in de config,
     * dan de bijbehorende repo; zo niet, dan wordt de tekst zélf als repo-URL gebruikt. Leeg → null.
     */
    fun resolve(repoOrName: String?): String? {
        val value = repoOrName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return byName[value.lowercase()] ?: value
    }

    /** Alle geconfigureerde projectnamen (genormaliseerd, lowercased), voor logging/diagnose. */
    fun configuredNames(): Set<String> = byName.keys

    /** De projectnamen in hun originele schrijfwijze — bv. als enum-keuzes in YouTrack. */
    fun projectNames(): List<String> = originalNames.toList()

    companion object {
        private val logger = LoggerFactory.getLogger(ProjectRepoResolver::class.java)

        /**
         * Leest [path] (YAML) in. Ontbreekt het bestand, dan een lege resolver (alles → geen repo).
         * Een onleesbaar of foutief bestand levert eveneens een lege resolver op (gelogd), zodat een
         * typefout in de config de hele factory niet platlegt.
         */
        fun fromYaml(path: Path): ProjectRepoResolver {
            if (!Files.exists(path)) {
                logger.warn("Project-config '{}' niet gevonden; geen enkele story krijgt een repo tot dit bestaat.", path)
                return ProjectRepoResolver(emptyMap())
            }
            return try {
                val parsed = Files.newBufferedReader(path).use { reader -> parse(Yaml().load(reader)) }
                logger.info(
                    "Project-config '{}' geladen: {} project(en) {}, {} met Telegram-kanaal.",
                    path, parsed.repos.size, parsed.repos.keys, parsed.telegramChatIds.size,
                )
                ProjectRepoResolver(parsed.repos, parsed.telegramChatIds)
            } catch (ex: Exception) {
                logger.error("Project-config '{}' kon niet worden gelezen: {}", path, ex.message, ex)
                ProjectRepoResolver(emptyMap())
            }
        }

        private data class ParsedProjects(
            val repos: Map<String, String>,
            val telegramChatIds: Map<String, String>,
        )

        private fun parse(root: Any?): ParsedProjects {
            val projects = (root as? Map<*, *>)?.get("projects") as? List<*>
                ?: throw IllegalArgumentException("verwacht een top-level 'projects:'-lijst")
            val repos = LinkedHashMap<String, String>()
            val chatIds = LinkedHashMap<String, String>()
            projects.forEachIndexed { index, entry ->
                val map = entry as? Map<*, *>
                    ?: throw IllegalArgumentException("project #${index + 1} is geen naam/repo-object")
                val name = (map["name"] as? String)?.trim().orEmpty()
                val repo = (map["repo"] as? String)?.trim().orEmpty()
                if (name.isEmpty() || repo.isEmpty()) {
                    logger.warn("Project-config: entry #{} mist 'name' of 'repo'; overgeslagen.", index + 1)
                    return@forEachIndexed
                }
                // Bewaar de originele schrijfwijze als sleutel; de resolver dedupt case-insensitive.
                if (repos.put(name, repo) != null) {
                    logger.warn("Project-config: dubbele projectnaam '{}'; laatste waarde wint.", name)
                }
                // telegramChatId is optioneel; YAML kan het als getal of string leveren.
                (map["telegramChatId"])?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { chatIds[name] = it }
            }
            return ParsedProjects(repos, chatIds)
        }
    }
}
