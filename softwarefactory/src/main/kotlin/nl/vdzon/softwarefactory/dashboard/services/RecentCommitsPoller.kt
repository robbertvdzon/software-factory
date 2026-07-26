package nl.vdzon.softwarefactory.dashboard.services

import nl.vdzon.softwarefactory.config.ProjectDashboardSettings
import nl.vdzon.softwarefactory.dashboard.models.ProjectRecentCommits
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Houdt elke minuut bij welke projecten er zijn (projects.yaml) en wat hun laatste 4 commits op de
 * default branch zijn, puur in-memory — bewust GEEN lazy TTL-cache zoals de rest van dit bestand
 * (`NightlyJobsReader`, `DashboardQueryService`'s `*Cache`-velden): de Builds-tab moet bij het
 * allereerste openen al weten welk project/branch waarschijnlijk interessant is (de meest recente
 * commit over alle projecten heen), zonder daarvoor eerst een live GitHub-call af te wachten.
 * Zelfde per-project-`runCatching`-foutafhandeling als [nl.vdzon.softwarefactory.maintenance.services.MaintenanceCleanupScheduler]:
 * één mislukt project breekt de rest van de tick niet af.
 */
@Component
class RecentCommitsPoller(
    private val projectRepoResolver: ProjectDashboardSettings,
    private val gitHubActionsClient: GitHubActionsClient,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var snapshot: Map<String, ProjectRecentCommits> = emptyMap()

    @Scheduled(fixedDelayString = "\${sf.dashboard.recent-commits-poll-ms:60000}")
    fun refresh() {
        val updated = mutableMapOf<String, ProjectRecentCommits>()
        for (name in projectRepoResolver.projectNames()) {
            runCatching {
                val slug = GitHubSlug.fromUrl(projectRepoResolver.repoFor(name)) ?: return@runCatching
                val branch = gitHubActionsClient.defaultBranch(slug) ?: "main"
                val commits = gitHubActionsClient.commitsOn(slug, branch, page = 1, perPage = 4)
                if (commits.isNotEmpty()) updated[name] = ProjectRecentCommits(name, branch, commits)
            }.onFailure { logger.warn("RecentCommitsPoller: ophalen commits mislukt voor '{}'.", name, it) }
        }
        snapshot = updated
    }

    fun all(): Map<String, ProjectRecentCommits> = snapshot

    fun mostRecent(): ProjectRecentCommits? = mostRecentOf(snapshot)

    internal companion object {
        /** Project met de meest recente eerste-commit-datum over alle projecten heen. Puur/testbaar zonder HTTP. */
        internal fun mostRecentOf(snapshot: Map<String, ProjectRecentCommits>): ProjectRecentCommits? =
            snapshot.values.maxByOrNull { it.commits.firstOrNull()?.date ?: "" }
    }
}
