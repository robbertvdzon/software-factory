package nl.vdzon.softwarefactory.maintenance.services

import kotlin.test.Test
import kotlin.test.assertEquals

class PackageVersionRetentionPlannerTest {

    @Test
    fun `bewaart de nieuwste keep versions`() {
        val versions = listOf(
            PackageVersionInfo(1, "2026-01-01T00:00:00Z", emptyList()),
            PackageVersionInfo(2, "2026-01-03T00:00:00Z", emptyList()),
            PackageVersionInfo(3, "2026-01-02T00:00:00Z", emptyList()),
        )

        val toDelete = PackageVersionRetentionPlanner.versionsToDelete(versions, keep = 2, protectedTags = emptySet(), alwaysKeepTags = emptySet())

        assertEquals(listOf(PackageVersionInfo(1, "2026-01-01T00:00:00Z", emptyList())), toDelete)
    }

    @Test
    fun `bewaart een version met een protected-sha-tag ongeacht leeftijd`() {
        val versions = listOf(
            PackageVersionInfo(1, "2026-01-01T00:00:00Z", listOf("sha-aa68624")),
            PackageVersionInfo(2, "2026-01-05T00:00:00Z", emptyList()),
            PackageVersionInfo(3, "2026-01-04T00:00:00Z", emptyList()),
        )

        val toDelete = PackageVersionRetentionPlanner.versionsToDelete(
            versions,
            keep = 2,
            protectedTags = setOf("sha-aa68624"),
            alwaysKeepTags = emptySet(),
        )

        assertEquals(emptyList(), toDelete)
    }

    @Test
    fun `bewaart een version met de main-tag ongeacht leeftijd`() {
        val versions = listOf(
            PackageVersionInfo(1, "2026-01-01T00:00:00Z", listOf("main")),
            PackageVersionInfo(2, "2026-01-05T00:00:00Z", emptyList()),
        )

        val toDelete = PackageVersionRetentionPlanner.versionsToDelete(
            versions,
            keep = 1,
            protectedTags = emptySet(),
            alwaysKeepTags = setOf("main"),
        )

        assertEquals(emptyList(), toDelete)
    }

    @Test
    fun `verwijdert een oude version buiten alle bescherming`() {
        val versions = listOf(
            PackageVersionInfo(1, "2026-01-01T00:00:00Z", listOf("sha-oud")),
            PackageVersionInfo(2, "2026-01-05T00:00:00Z", emptyList()),
        )

        val toDelete = PackageVersionRetentionPlanner.versionsToDelete(
            versions,
            keep = 1,
            protectedTags = setOf("sha-anders"),
            alwaysKeepTags = setOf("main"),
        )

        assertEquals(listOf(PackageVersionInfo(1, "2026-01-01T00:00:00Z", listOf("sha-oud"))), toDelete)
    }

    @Test
    fun `lege protected-set en alwaysKeepTags werkt nog steeds op keep-N alleen`() {
        val versions = listOf(
            PackageVersionInfo(1, "2026-01-01T00:00:00Z", emptyList()),
            PackageVersionInfo(2, "2026-01-02T00:00:00Z", emptyList()),
        )

        val toDelete = PackageVersionRetentionPlanner.versionsToDelete(versions, keep = 1, protectedTags = emptySet(), alwaysKeepTags = emptySet())

        assertEquals(listOf(PackageVersionInfo(1, "2026-01-01T00:00:00Z", emptyList())), toDelete)
    }
}
