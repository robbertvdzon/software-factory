package nl.vdzon.softwarefactory.dashboard.services

import nl.vdzon.softwarefactory.dashboard.models.CommitInfo
import nl.vdzon.softwarefactory.dashboard.models.ProjectRecentCommits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Dekt de pure `mostRecentOf`-selectie van [RecentCommitsPoller] (welk project over alle
 * projecten heen de meest recente commit heeft) zonder scheduler/HTTP — zelfde recept als
 * [GitHubActionsClientTest]/`DashboardQueryServiceTest`'s pure companion-functies.
 */
class RecentCommitsPollerTest {

    private fun project(name: String, commitDate: String?) = ProjectRecentCommits(
        project = name,
        branch = "main",
        commits = listOfNotNull(commitDate?.let { CommitInfo(sha = "abc", message = "x", date = it) }),
    )

    @Test
    fun `kiest het project met de meest recente eerste-commit-datum`() {
        val snapshot = mapOf(
            "oud-project" to project("oud-project", "2026-07-20T10:00:00Z"),
            "nieuw-project" to project("nieuw-project", "2026-07-25T09:00:00Z"),
        )

        val top = RecentCommitsPoller.mostRecentOf(snapshot)

        assertEquals("nieuw-project", top?.project)
    }

    @Test
    fun `lege snapshot levert null op`() {
        assertNull(RecentCommitsPoller.mostRecentOf(emptyMap()))
    }

    @Test
    fun `project zonder commits telt mee als leeg (lege string), niet als crash`() {
        val snapshot = mapOf(
            "zonder-commits" to project("zonder-commits", null),
            "met-commit" to project("met-commit", "2026-07-25T09:00:00Z"),
        )

        val top = RecentCommitsPoller.mostRecentOf(snapshot)

        assertEquals("met-commit", top?.project)
    }
}
