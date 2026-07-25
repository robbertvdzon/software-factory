package nl.vdzon.softwarefactory.maintenance.services

import nl.vdzon.softwarefactory.config.ReleasePrefixRule
import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseRetentionPlannerTest {

    @Test
    fun `bewaart de nieuwste keep releases per prefix`() {
        val releases = listOf(
            ReleaseInfo(1, "apk-a", "2026-01-01T00:00:00Z"),
            ReleaseInfo(2, "apk-b", "2026-01-03T00:00:00Z"),
            ReleaseInfo(3, "apk-c", "2026-01-02T00:00:00Z"),
        )

        val toDelete = ReleaseRetentionPlanner.releasesToDelete(releases, listOf(ReleasePrefixRule("apk-", 2)))

        assertEquals(listOf(ReleaseInfo(1, "apk-a", "2026-01-01T00:00:00Z")), toDelete)
    }

    @Test
    fun `prefixen mengen elkaar niet`() {
        val releases = listOf(
            ReleaseInfo(1, "apk-a", "2026-01-01T00:00:00Z"),
            ReleaseInfo(2, "reader-apk-a", "2026-01-01T00:00:00Z"),
        )

        val toDelete = ReleaseRetentionPlanner.releasesToDelete(
            releases,
            listOf(ReleasePrefixRule("apk-", 5), ReleasePrefixRule("reader-apk-", 5)),
        )

        assertEquals(emptyList(), toDelete)
    }

    @Test
    fun `minder releases dan keep levert niets te verwijderen op`() {
        val releases = listOf(ReleaseInfo(1, "apk-a", "2026-01-01T00:00:00Z"))

        val toDelete = ReleaseRetentionPlanner.releasesToDelete(releases, listOf(ReleasePrefixRule("apk-", 3)))

        assertEquals(emptyList(), toDelete)
    }

    @Test
    fun `release zonder matchende prefix blijft altijd staan`() {
        val releases = listOf(ReleaseInfo(1, "wind-latest", "2026-01-01T00:00:00Z"))

        val toDelete = ReleaseRetentionPlanner.releasesToDelete(releases, listOf(ReleasePrefixRule("apk-", 0)))

        assertEquals(emptyList(), toDelete)
    }

    @Test
    fun `geen regels levert nooit iets te verwijderen op`() {
        val releases = listOf(ReleaseInfo(1, "apk-a", "2026-01-01T00:00:00Z"))

        assertEquals(emptyList(), ReleaseRetentionPlanner.releasesToDelete(releases, emptyList()))
    }
}
