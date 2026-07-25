package nl.vdzon.softwarefactory.maintenance.services

import nl.vdzon.softwarefactory.config.ProjectConfiguration
import nl.vdzon.softwarefactory.config.ReleaseCleanupConfig
import nl.vdzon.softwarefactory.telegram.TelegramMessageGateway
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Nachtelijke, niet-AI-gedreven opruiming van oude GitHub Releases + ghcr.io-package-versions voor
 * elk project met een `releaseCleanup:`-blok in projects.yaml (SF: build-apk/build-images maken bij
 * elke push een nieuwe release/image-tag aan die nooit vanzelf verdween). Bewust GEEN onderdeel van
 * [nl.vdzon.softwarefactory.nightly.services.NightlyScheduler]: dat is een DB-backed reconciliatie-
 * machine rond AI-agent-story's (git-checkout, `gateway.startStory`) — verkeerd gereedschap voor
 * deze stateless mechanische taak, die elke tick opnieuw uit de GitHub-API te herberekenen is.
 */
@Component
class MaintenanceCleanupScheduler(
    private val projects: ProjectConfiguration,
    private val releaseClient: GitHubReleaseCleanupClient,
    private val packageClient: GitHubPackageCleanupClient,
    private val protectedShaSource: GitHubProtectedShaSource,
    private val telegram: TelegramMessageGateway,
    @Value("\${sf.maintenance.dry-run:false}") private val dryRun: Boolean,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${sf.maintenance.cleanup-cron:0 30 2 * * *}", zone = "UTC")
    fun tick() {
        for (projectName in projects.projectNames()) {
            val config = projects.releaseCleanupFor(projectName) ?: continue
            runCatching { cleanupProject(projectName, config) }
                .onFailure { logger.warn("Maintenance-cleanup voor project '{}' faalde.", projectName, it) }
        }
    }

    private fun cleanupProject(projectName: String, config: ReleaseCleanupConfig) {
        val slug = githubSlugFrom(projects.repoFor(projectName))
        if (slug == null) {
            logger.warn("Maintenance-cleanup: geen GitHub-slug voor project '{}', overgeslagen.", projectName)
            return
        }
        val (releasesDeleted, releasesKept) = cleanupReleases(slug, config)
        val (packagesDeleted, packagesKept) = cleanupPackages(slug, config)
        logger.info(
            "Maintenance-cleanup '{}': releases verwijderd={} bewaard={}, packages verwijderd={} bewaard={}{}",
            projectName, releasesDeleted, releasesKept, packagesDeleted, packagesKept,
            if (dryRun) " (dry-run)" else "",
        )
        if (!dryRun && (releasesDeleted > 0 || packagesDeleted > 0) && telegram.enabled) {
            runCatching {
                telegram.sendMessage(
                    text = "🧹 Maintenance-cleanup '$projectName': $releasesDeleted release(s) en " +
                        "$packagesDeleted package-version(s) opgeruimd.",
                    chatId = projects.telegramChatIdFor(projectName),
                )
            }.onFailure { logger.warn("Maintenance-cleanup-Telegram-melding voor '{}' faalde.", projectName, it) }
        }
    }

    /** @return (aantal verwijderd, aantal bewaard). */
    private fun cleanupReleases(slug: String, config: ReleaseCleanupConfig): Pair<Int, Int> {
        if (config.releases.isEmpty()) return 0 to 0
        val releases = releaseClient.listReleases(slug)
        val toDelete = ReleaseRetentionPlanner.releasesToDelete(releases, config.releases)
        if (!dryRun) {
            toDelete.forEach { release ->
                runCatching { releaseClient.deleteRelease(slug, release.id, release.tagName) }
                    .onFailure { logger.warn("Release-delete '{}' ({}) faalde voor {}.", release.tagName, release.id, slug, it) }
            }
        }
        return toDelete.size to (releases.size - toDelete.size)
    }

    /** @return (aantal verwijderd, aantal bewaard), opgeteld over alle geconfigureerde packages. */
    private fun cleanupPackages(slug: String, config: ReleaseCleanupConfig): Pair<Int, Int> {
        if (config.packages.isEmpty()) return 0 to 0
        val owner = slug.substringBefore("/")
        val protectedTags = protectedShaSource.protectedTags(slug, config.protectedManifestPaths)
        var deleted = 0
        var kept = 0
        for (rule in config.packages) {
            val versions = packageClient.listVersions(owner, rule.name)
            val toDelete = PackageVersionRetentionPlanner.versionsToDelete(versions, rule.keep, protectedTags, config.alwaysKeepTags)
            if (!dryRun) {
                toDelete.forEach { version ->
                    runCatching { packageClient.deleteVersion(owner, rule.name, version.id) }
                        .onFailure { logger.warn("Package-version-delete {} ({}) faalde voor {}/{}.", version.id, version.tags, owner, rule.name, it) }
                }
            }
            deleted += toDelete.size
            kept += versions.size - toDelete.size
        }
        return deleted to kept
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
}
