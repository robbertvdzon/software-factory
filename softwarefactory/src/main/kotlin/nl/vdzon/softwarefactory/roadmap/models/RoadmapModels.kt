package nl.vdzon.softwarefactory.roadmap.models

import java.time.OffsetDateTime
import nl.vdzon.softwarefactory.roadmap.types.EpicStatus

data class RoadmapEpicRecord(
    val id: Long,
    val title: String,
    val description: String?,
    val status: EpicStatus,
    val customerRank: Int,
    val processRank: Int,
    val dependencyIds: Set<Long>,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

data class RoadmapEpicView(
    val id: Long,
    val title: String,
    val description: String?,
    val status: String,
    val customerRank: Int,
    val processRank: Int,
    val roadmapRank: Int,
    val dependencyIds: Set<Long>,
    val blockedByIds: Set<Long>,
    val blocksIds: Set<Long>,
    val rankExplanation: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

data class RoadmapPageData(
    val epics: List<RoadmapEpicView>,
    val customerWeightPercentage: Int = 75,
    val processWeightPercentage: Int = 25,
)

data class CreateRoadmapEpicCommand(
    val title: String,
    val description: String?,
)

data class UpdateRoadmapEpicCommand(
    val title: String,
    val description: String?,
    val status: String,
    val customerRank: Int,
    val dependencyIds: Set<Long>,
)
