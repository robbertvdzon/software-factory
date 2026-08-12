package nl.vdzon.softwarefactory.roadmap.services

import nl.vdzon.softwarefactory.roadmap.models.RoadmapEpicRecord
import java.util.PriorityQueue

/**
 * Topologische roadmap-sortering. Dependencies zijn hard; tussen de uitvoerbare
 * kandidaten telt de klantvolgorde voor 75% en de procesvolgorde voor 25%.
 */
object RoadmapRanker {
    const val CUSTOMER_WEIGHT_PERCENTAGE = 75

    fun rank(epics: List<RoadmapEpicRecord>): List<RoadmapEpicRecord> {
        val byId = epics.associateBy { it.id }
        require(epics.all { epic -> epic.dependencyIds.all(byId::containsKey) }) {
            "Een epic verwijst naar een onbekende dependency."
        }

        val dependents = epics.associate { it.id to mutableListOf<RoadmapEpicRecord>() }
        val remainingDependencies = epics.associate { it.id to it.dependencyIds.size }.toMutableMap()
        epics.forEach { epic ->
            epic.dependencyIds.forEach { dependencyId -> dependents.getValue(dependencyId) += epic }
        }

        val ready = PriorityQueue(
            compareBy<RoadmapEpicRecord>({ weightedRank(it) }, { it.customerRank }, { it.processRank }, { it.id }),
        )
        ready += epics.filter { remainingDependencies.getValue(it.id) == 0 }
        val result = mutableListOf<RoadmapEpicRecord>()
        while (ready.isNotEmpty()) {
            val next = ready.remove()
            result += next
            dependents.getValue(next.id).forEach { dependent ->
                val remaining = remainingDependencies.getValue(dependent.id) - 1
                remainingDependencies[dependent.id] = remaining
                if (remaining == 0) ready += dependent
            }
        }
        require(result.size == epics.size) { "Circulaire epic-afhankelijkheid: de roadmap moet acyclisch blijven." }
        return result
    }

    private fun weightedRank(epic: RoadmapEpicRecord): Int = epic.customerRank * 3 + epic.processRank
}
