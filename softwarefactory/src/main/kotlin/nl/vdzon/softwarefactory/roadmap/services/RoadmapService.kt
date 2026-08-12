package nl.vdzon.softwarefactory.roadmap.services

import nl.vdzon.softwarefactory.core.contracts.FactoryStateChangedEvent
import nl.vdzon.softwarefactory.roadmap.RoadmapApi
import nl.vdzon.softwarefactory.roadmap.models.CreateRoadmapEpicCommand
import nl.vdzon.softwarefactory.roadmap.models.RoadmapEpicRecord
import nl.vdzon.softwarefactory.roadmap.models.RoadmapEpicView
import nl.vdzon.softwarefactory.roadmap.models.RoadmapPageData
import nl.vdzon.softwarefactory.roadmap.models.UpdateRoadmapEpicCommand
import nl.vdzon.softwarefactory.roadmap.repositories.RoadmapRepository
import nl.vdzon.softwarefactory.roadmap.types.EpicStatus
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RoadmapService(
    private val repository: RoadmapRepository,
    private val eventPublisher: ApplicationEventPublisher,
) : RoadmapApi {
    override fun roadmap(): RoadmapPageData = page(repository.findAll())

    @Transactional
    override fun createEpic(command: CreateRoadmapEpicCommand): RoadmapEpicView {
        validateText(command.title, command.description)
        repository.lockAll()
        val current = repository.findAll()
        val rank = current.size + 1
        val id = repository.create(command.title.trim(), command.description.clean(), rank, rank)
        changed("roadmap-epic-create:$id")
        return page(repository.findAll()).epics.first { it.id == id }
    }

    @Transactional
    override fun updateEpic(id: Long, command: UpdateRoadmapEpicCommand): RoadmapEpicView {
        validateText(command.title, command.description)
        require(id !in command.dependencyIds) { "Een epic kan niet van zichzelf afhankelijk zijn." }
        repository.lockAll()
        val current = repository.findAll()
        val existing = current.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Epic $id bestaat niet.")
        val knownIds = current.mapTo(mutableSetOf()) { it.id }
        require(command.dependencyIds.all(knownIds::contains)) { "Een of meer dependencies bestaan niet." }
        repository.update(id, command.title.trim(), command.description.clean(), EpicStatus.fromWire(command.status))
        repository.replaceDependencies(id, command.dependencyIds)
        repository.reorderCustomer(id, existing.customerRank, command.customerRank, current.size)
        val updated = repository.findAll()
        RoadmapRanker.rank(updated)
        changed("roadmap-epic-update:$id")
        return page(updated).epics.first { it.id == id }
    }

    @Transactional
    override fun updateProcessRank(id: Long, processRank: Int): RoadmapEpicView {
        require(processRank > 0) { "Process-rank moet minimaal 1 zijn." }
        repository.lockAll()
        val current = repository.findAll()
        val existing = current.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Epic $id bestaat niet.")
        repository.reorderProcess(id, existing.processRank, processRank, current.size)
        changed("roadmap-process-rank:$id")
        return page(repository.findAll()).epics.first { it.id == id }
    }

    private fun page(records: List<RoadmapEpicRecord>): RoadmapPageData {
        val ranked = RoadmapRanker.rank(records)
        val titles = records.associate { it.id to it.title }
        val blocks = records.flatMap { epic -> epic.dependencyIds.map { it to epic.id } }
            .groupBy({ it.first }, { it.second })
        return RoadmapPageData(
            ranked.mapIndexed { index, epic ->
                val blockedBy = epic.dependencyIds.filterTo(linkedSetOf()) { dependencyId ->
                    records.first { it.id == dependencyId }.status != EpicStatus.DONE
                }
                RoadmapEpicView(
                    id = epic.id,
                    title = epic.title,
                    description = epic.description,
                    status = epic.status.wireValue,
                    customerRank = epic.customerRank,
                    processRank = epic.processRank,
                    roadmapRank = index + 1,
                    dependencyIds = epic.dependencyIds,
                    blockedByIds = blockedBy,
                    blocksIds = blocks[epic.id].orEmpty().toSet(),
                    rankExplanation = explanation(epic, index + 1, blockedBy, titles),
                    createdAt = epic.createdAt,
                    updatedAt = epic.updatedAt,
                )
            },
        )
    }

    private fun explanation(epic: RoadmapEpicRecord, roadmapRank: Int, blockedBy: Set<Long>, titles: Map<Long, String>): String? = when {
        blockedBy.isNotEmpty() -> "Wacht op ${blockedBy.joinToString { titles[it].orEmpty() }}; dependencies gaan voor prioriteit."
        roadmapRank != epic.customerRank && epic.dependencyIds.isNotEmpty() ->
            "Volgt op ${epic.dependencyIds.joinToString { titles[it].orEmpty() }}; de dependency bepaalt de volgorde."
        roadmapRank != epic.customerRank -> "De definitieve positie combineert 75% klant-rank en 25% process-rank."
        else -> null
    }

    private fun validateText(title: String, description: String?) {
        require(title.isNotBlank()) { "Titel is verplicht." }
        require(title.trim().length <= 80) { "Titel mag maximaal 80 tekens bevatten." }
        require((description?.length ?: 0) <= 10_000) { "Omschrijving mag maximaal 10.000 tekens bevatten." }
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    private fun changed(origin: String) {
        eventPublisher.publishEvent(FactoryStateChangedEvent(origin))
    }
}
