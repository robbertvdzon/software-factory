package nl.vdzon.softwarefactory.roadmap.services

import nl.vdzon.softwarefactory.roadmap.models.RoadmapEpicRecord
import nl.vdzon.softwarefactory.roadmap.types.EpicStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime

class RoadmapRankerTest {
    @Test
    fun `klant-rank telt drie keer zo zwaar als process-rank`() {
        val customerFavourite = epic(id = 1, customerRank = 1, processRank = 4)
        val processFavourite = epic(id = 2, customerRank = 2, processRank = 1)

        val ranked = RoadmapRanker.rank(listOf(processFavourite, customerFavourite))

        assertEquals(listOf(1L, 2L), ranked.map { it.id })
    }

    @Test
    fun `dependency gaat voor een hogere klantprioriteit`() {
        val prerequisite = epic(id = 1, customerRank = 5, processRank = 5)
        val favourite = epic(id = 2, customerRank = 1, processRank = 1, dependencies = setOf(1))

        val ranked = RoadmapRanker.rank(listOf(favourite, prerequisite))

        assertEquals(listOf(1L, 2L), ranked.map { it.id })
    }

    @Test
    fun `circulaire dependencies worden geweigerd`() {
        val first = epic(id = 1, customerRank = 1, processRank = 1, dependencies = setOf(2))
        val second = epic(id = 2, customerRank = 2, processRank = 2, dependencies = setOf(1))

        val error = assertThrows<IllegalArgumentException> { RoadmapRanker.rank(listOf(first, second)) }

        assertEquals("Circulaire epic-afhankelijkheid: de roadmap moet acyclisch blijven.", error.message)
    }

    private fun epic(
        id: Long,
        customerRank: Int,
        processRank: Int,
        dependencies: Set<Long> = emptySet(),
    ) = RoadmapEpicRecord(
        id = id,
        title = "Epic $id",
        description = null,
        status = EpicStatus.PLANNED,
        customerRank = customerRank,
        processRank = processRank,
        dependencyIds = dependencies,
        createdAt = OffsetDateTime.parse("2026-08-12T10:00:00Z"),
        updatedAt = OffsetDateTime.parse("2026-08-12T10:00:00Z"),
    )
}
