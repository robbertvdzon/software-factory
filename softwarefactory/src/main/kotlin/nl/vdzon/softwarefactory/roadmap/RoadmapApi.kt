package nl.vdzon.softwarefactory.roadmap

import nl.vdzon.softwarefactory.roadmap.models.CreateRoadmapEpicCommand
import nl.vdzon.softwarefactory.roadmap.models.RoadmapEpicView
import nl.vdzon.softwarefactory.roadmap.models.RoadmapPageData
import nl.vdzon.softwarefactory.roadmap.models.UpdateRoadmapEpicCommand

interface RoadmapApi {
    fun roadmap(): RoadmapPageData
    fun createEpic(command: CreateRoadmapEpicCommand): RoadmapEpicView
    fun updateEpic(id: Long, command: UpdateRoadmapEpicCommand): RoadmapEpicView
    fun updateProcessRank(id: Long, processRank: Int): RoadmapEpicView
}
