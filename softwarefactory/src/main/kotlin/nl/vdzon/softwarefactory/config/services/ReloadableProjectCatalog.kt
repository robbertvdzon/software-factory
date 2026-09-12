package nl.vdzon.softwarefactory.config.services

import nl.vdzon.softwarefactory.config.ApkPackageMapping
import nl.vdzon.softwarefactory.config.DeployConfig
import nl.vdzon.softwarefactory.config.DeployTarget
import nl.vdzon.softwarefactory.config.LiveComponentConfig
import nl.vdzon.softwarefactory.config.ProjectCatalog
import nl.vdzon.softwarefactory.config.ProjectConfiguration
import nl.vdzon.softwarefactory.config.ReleaseCleanupConfig
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicReference

/**
 * Herlaadbare [ProjectCatalog]: houdt de actieve [ProjectConfiguration] in een referentie die bij
 * [save] wordt vervangen, zodat alle poorten meteen de nieuwe catalogus zien zonder herstart.
 *
 * Bron bij opstart, in deze volgorde: het document in de database; anders een eenmalige import van
 * [importFile] (het vroegere `projects.yaml`, zodat een bestaande installatie zonder handmatige
 * stap overgaat); anders een lege catalogus. Een [seed] (tests die zelf een configuratie leveren)
 * gaat vóór alles.
 */
class ReloadableProjectCatalog(
    private val repository: ProjectCatalogRepository,
    importFile: Path? = null,
    seed: ProjectConfiguration? = null,
) : ProjectCatalog {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val active = AtomicReference<ProjectConfiguration>()
    private val document = AtomicReference<ProjectCatalogDocument?>()

    init {
        val stored = runCatching { repository.read() }
            .onFailure { logger.error("Projectcatalogus kon niet uit de database worden gelezen: {}", it.message) }
            .getOrNull()
        when {
            seed != null -> active.set(seed)
            stored != null -> {
                document.set(stored)
                active.set(parseOrEmpty(stored.yaml, "database"))
            }
            importFile != null && Files.exists(importFile) -> {
                val yaml = Files.readString(importFile)
                val imported = parseOrEmpty(yaml, importFile.toString())
                active.set(imported)
                runCatching { repository.write(yaml, "import:$importFile") }
                    .onSuccess { document.set(it); logger.info("Projectcatalogus eenmalig geïmporteerd uit {} ({} projecten).", importFile, imported.projectNames().size) }
                    .onFailure { logger.error("Projectcatalogus uit {} kon niet worden opgeslagen: {}", importFile, it.message) }
            }
            else -> {
                logger.warn("Geen projectcatalogus in de database en geen bestand om te importeren; geen enkele story krijgt een repo tot de catalogus via het dashboard is opgeslagen.")
                active.set(ProjectConfiguration(emptyMap()))
            }
        }
    }

    private fun parseOrEmpty(yaml: String, source: String): ProjectConfiguration =
        try {
            ProjectConfiguration.parseYamlText(yaml).also {
                logger.info("Projectcatalogus geladen uit {}: {} project(en) {}.", source, it.projectNames().size, it.projectNames())
            }
        } catch (ex: Exception) {
            logger.error("Projectcatalogus uit {} is ongeldig ({}); lege catalogus actief.", source, ex.message)
            ProjectConfiguration(emptyMap())
        }

    fun current(): ProjectConfiguration = active.get()

    override fun yaml(): String? = document.get()?.yaml
    override fun updatedAt(): OffsetDateTime? = document.get()?.updatedAt
    override fun updatedBy(): String? = document.get()?.updatedBy

    override fun save(yaml: String, updatedBy: String): ProjectConfiguration {
        require(yaml.isNotBlank()) { "De projectcatalogus mag niet leeg zijn." }
        val parsed = ProjectConfiguration.parseYamlText(yaml)
        parsed.requireCompleteMergePolicies()
        val written = repository.write(yaml, updatedBy)
        document.set(written)
        active.set(parsed)
        logger.info("Projectcatalogus opgeslagen door {}: {} project(en) {}.", updatedBy, parsed.projectNames().size, parsed.projectNames())
        return parsed
    }

    override fun repoFor(projectName: String?): String? = current().repoFor(projectName)
    override fun resolve(repoOrName: String?): String? = current().resolve(repoOrName)
    override fun projectNames(): List<String> = current().projectNames()
    override fun projectNameFor(repoOrName: String?): String? = current().projectNameFor(repoOrName)
    override fun runtimeAliasFor(repoOrName: String?): String? = current().runtimeAliasFor(repoOrName)
    override fun projectNameForChatId(chatId: String?): String? = current().projectNameForChatId(chatId)
    override fun telegramChatIdFor(projectName: String?): String? = current().telegramChatIdFor(projectName)
    override fun telegramChatIds(): Set<String> = current().telegramChatIds()
    override fun privateFilesFor(projectName: String?): List<String> = current().privateFilesFor(projectName)
    override fun baseProjectName(): String? = current().baseProjectName()
    override fun requiredChecksFor(projectName: String?): Set<String> = current().requiredChecksFor(projectName)
    override fun requiredChecksForRepo(targetRepo: String): Set<String> = current().requiredChecksForRepo(targetRepo)
    override fun requireCompleteMergePolicies() = current().requireCompleteMergePolicies()
    override fun deployConfigFor(projectName: String?): DeployConfig = current().deployConfigFor(projectName)
    override fun liveComponentsFor(projectName: String?): List<LiveComponentConfig> = current().liveComponentsFor(projectName)
    override fun deployTargetsFor(projectName: String?): List<DeployTarget> = current().deployTargetsFor(projectName)
    override fun apkPackagesFor(projectName: String?): List<ApkPackageMapping> = current().apkPackagesFor(projectName)
    override fun releaseCleanupFor(projectName: String?): ReleaseCleanupConfig? = current().releaseCleanupFor(projectName)
}
