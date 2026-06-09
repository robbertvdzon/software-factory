package nl.vdzon.softwarefactory.orchestrator

/**
 * Spring-event: de factory-state is mogelijk veranderd (bv. een agent is klaar en heeft de
 * story/subtask bijgewerkt). De [schedulers.OrchestratorPoller] luistert hierop en wordt direct
 * wakker gemaakt, zodat de keten niet hoeft te wachten op het volgende poll-interval.
 */
data class FactoryStateChangedEvent(
    /** Korte herkomst-omschrijving, puur voor logging/diagnostiek. */
    val origin: String,
)
