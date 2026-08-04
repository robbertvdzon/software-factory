package nl.vdzon.softwarefactory.maintenance.services

import nl.vdzon.softwarefactory.config.ProjectConfiguration
import nl.vdzon.softwarefactory.config.ReleaseCleanupConfig
import nl.vdzon.softwarefactory.maintenance.repositories.MaintenanceCleanupRunRepository
import nl.vdzon.softwarefactory.maintenance.repositories.NewMaintenanceCleanupRun
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Nachtelijke, niet-AI-gedreven opruiming van oude GitHub Releases + ghcr.io-package-versions voor
 * elk project met een `releaseCleanup:`-blok in projects.yaml (SF: build-apk/build-images maken bij
 * elke push een nieuwe release/image-tag aan die nooit vanzelf verdween). Bewust GEEN onderdeel van
 * [nl.vdzon.softwarefactory.nightly.services.NightlyScheduler]: dat is een DB-backed reconciliatie-
 * machine rond AI-agent-story's (git-checkout, `gateway.startStory`) — verkeerd gereedschap voor
 * deze stateless mechanische taak, die elke tick opnieuw uit de GitHub-API te herberekenen is.
 *
 * Elke projectronde legt één rij vast in [MaintenanceCleanupRunRepository] — óók bij 0
 * verwijderingen, bij een dry-run en bij een mislukte ronde. Dat is bewust: zonder rij is "er viel
 * niets op te ruimen" niet te onderscheiden van "de opruimer heeft niet gedraaid". Er gaat sinds
 * SF-1913 géén Telegram-melding meer uit; het dashboard leest deze historie.
 */
@Component
class MaintenanceCleanupScheduler(
    private val projects: ProjectConfiguration,
    private val releaseClient: GitHubReleaseCleanupClient,
    private val packageClient: GitHubPackageCleanupClient,
    private val protectedShaSource: GitHubProtectedShaSource,
    private val runRepository: MaintenanceCleanupRunRepository,
    private val settings: MaintenanceCleanupSettings,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val dryRun get() = settings.dryRun

    @Scheduled(cron = "\${sf.maintenance.cleanup-cron:0 30 2 * * *}", zone = "UTC")
    fun tick() {
        for (projectName in projects.projectNames()) {
            val config = projects.releaseCleanupFor(projectName) ?: continue
            val startedAt = OffsetDateTime.now()
            runCatching { cleanupProject(projectName, config) }
                .onSuccess { outcome -> outcome?.let { record(projectName, startedAt, it, error = null) } }
                .onFailure { failure ->
                    logger.warn("Maintenance-cleanup voor project '{}' faalde.", projectName, failure)
                    record(projectName, startedAt, CleanupOutcome.EMPTY, error = describe(failure))
                }
        }
        purgeOldRuns()
    }

    /** @return de uitkomst, of null als het project geen GitHub-slug heeft (overgeslagen, géén rij). */
    private fun cleanupProject(projectName: String, config: ReleaseCleanupConfig): CleanupOutcome? {
        val slug = githubSlugFrom(projects.repoFor(projectName))
        if (slug == null) {
            logger.warn("Maintenance-cleanup: geen GitHub-slug voor project '{}', overgeslagen.", projectName)
            return null
        }
        val releases = cleanupReleases(slug, config)
        val packages = cleanupPackages(slug, config)
        logger.info(
            "Maintenance-cleanup '{}': releases verwijderd={} bewaard={}, packages verwijderd={} bewaard={}{}",
            projectName, releases.deleted.size, releases.kept, packages.deleted.size, packages.kept,
            if (dryRun) " (dry-run)" else "",
        )
        return CleanupOutcome(
            releasesKept = releases.kept,
            packagesKept = packages.kept,
            deletedReleaseTags = releases.deleted,
            deletedPackageVersions = packages.deleted,
        )
    }

    /** Fail-soft: kan de historie niet worden weggeschreven, dan blijft de opruiming zelf gewoon staan. */
    private fun record(projectName: String, startedAt: OffsetDateTime, outcome: CleanupOutcome, error: String?) {
        runCatching {
            runRepository.add(
                NewMaintenanceCleanupRun(
                    project = projectName,
                    startedAt = startedAt,
                    finishedAt = OffsetDateTime.now(),
                    releasesDeleted = outcome.deletedReleaseTags.size,
                    releasesKept = outcome.releasesKept,
                    packagesDeleted = outcome.deletedPackageVersions.size,
                    packagesKept = outcome.packagesKept,
                    dryRun = dryRun,
                    error = error,
                    deletedReleaseTags = outcome.deletedReleaseTags,
                    deletedPackageVersions = outcome.deletedPackageVersions,
                ),
            )
        }.onFailure { logger.warn("Vastleggen van de maintenance-cleanup-run voor '{}' faalde.", projectName, it) }
    }

    /** Retentie op de eigen historie; zelfde fail-soft-stijl als de rest van de tick. */
    private fun purgeOldRuns() {
        runCatching {
            val deleted = runRepository.deleteOlderThan(OffsetDateTime.now().minusDays(settings.retentionDays))
            if (deleted > 0) {
                logger.info("Maintenance-cleanup-retentie: {} run(s) ouder dan {} dagen verwijderd.", deleted, settings.retentionDays)
            }
        }.onFailure { logger.warn("Maintenance-cleanup-retentie faalde.", it) }
    }

    private fun cleanupReleases(slug: String, config: ReleaseCleanupConfig): CleanupStep {
        if (config.releases.isEmpty()) return CleanupStep()
        val releases = releaseClient.listReleases(slug)
        val toDelete = ReleaseRetentionPlanner.releasesToDelete(releases, config.releases)
        val deleted = if (dryRun) {
            toDelete.map { it.tagName }
        } else {
            toDelete.mapNotNull { release ->
                runCatching { releaseClient.deleteRelease(slug, release.id, release.tagName) }
                    .onFailure { logger.warn("Release-delete '{}' ({}) faalde voor {}.", release.tagName, release.id, slug, it) }
                    .map { release.tagName }
                    .getOrNull()
            }
        }
        return CleanupStep(deleted, releases.size - toDelete.size)
    }

    private fun cleanupPackages(slug: String, config: ReleaseCleanupConfig): CleanupStep {
        if (config.packages.isEmpty()) return CleanupStep()
        val owner = slug.substringBefore("/")
        val protectedTags = protectedShaSource.protectedTags(slug, config.protectedManifestPaths)
        val deleted = mutableListOf<String>()
        var kept = 0
        for (rule in config.packages) {
            val versions = packageClient.listVersions(owner, rule.name)
            val toDelete = PackageVersionRetentionPlanner.versionsToDelete(versions, rule.keep, protectedTags, config.alwaysKeepTags)
            if (dryRun) {
                toDelete.forEach { deleted += describeVersion(rule.name, it) }
            } else {
                toDelete.forEach { version ->
                    runCatching { packageClient.deleteVersion(owner, rule.name, version.id) }
                        .onSuccess { deleted += describeVersion(rule.name, version) }
                        .onFailure { logger.warn("Package-version-delete {} ({}) faalde voor {}/{}.", version.id, version.tags, owner, rule.name, it) }
                }
            }
            kept += versions.size - toDelete.size
        }
        return CleanupStep(deleted, kept)
    }

    /**
     * Herleidt "owner/repo" uit een GitHub-repo-URL. Bewuste duplicatie van
     * [nl.vdzon.softwarefactory.dashboard.services.GitHubSlug.fromUrl]: dat zit in `dashboard.services`,
     * niet cross-module importeerbaar (zie ModulithArchitectureTest) — te klein om daarvoor
     * `dashboard.services` een publieke NamedInterface te geven.
     */
    private fun githubSlugFrom(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim().trim('<', '>').removeSuffix(".git").trimEnd('/')
        Regex("""^https://github\.com/([^/]+/[^/]+)$""").find(trimmed)?.let { return it.groupValues[1] }
        Regex("""^git@github\.com:([^/]+/.+)$""").find(trimmed)?.let { return it.groupValues[1].removeSuffix(".git") }
        return null
    }

    /** Wat één helper (releases of packages) opleverde: de leesbare namen van wat weg is, plus wat bleef staan. */
    private data class CleanupStep(val deleted: List<String> = emptyList(), val kept: Int = 0)

    /** Wat er in één projectronde is opgeruimd; bij een gefaalde ronde blijft dit [EMPTY]. */
    private data class CleanupOutcome(
        val releasesKept: Int,
        val packagesKept: Int,
        val deletedReleaseTags: List<String>,
        val deletedPackageVersions: List<String>,
    ) {
        companion object {
            val EMPTY = CleanupOutcome(0, 0, emptyList(), emptyList())
        }
    }

    private companion object {
        /** Zonder tags is de id het enige onderscheidende kenmerk van een ghcr-version. */
        fun describeVersion(packageName: String, version: PackageVersionInfo): String =
            if (version.tags.isEmpty()) "$packageName #${version.id}"
            else "$packageName #${version.id} (${version.tags.joinToString(", ")})"

        fun describe(failure: Throwable): String =
            failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
    }
}
