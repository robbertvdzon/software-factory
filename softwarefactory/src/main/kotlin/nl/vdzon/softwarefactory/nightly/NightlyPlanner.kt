package nl.vdzon.softwarefactory.nightly

/**
 * Eén concrete actie die de [NightlyScheduler]-executor op de DB/gateway moet uitvoeren. Het plannen
 * (de beslissingen) is bewust gescheiden van het uitvoeren zodat de hele scheduler-logica puur en
 * deterministisch getest kan worden zonder DB of gateway.
 */
sealed interface NightlyAction {
    /** Maak de run voor vandaag aan en vul de queue met de enabled jobs. */
    data object CreateRun : NightlyAction

    /** Start de (volgende pending) job: maak+start de story en zet 'm op running. */
    data class StartJob(val jobId: Long) : NightlyAction

    /** Markeer een lopende job terminaal (done/failed). */
    data class MarkJobTerminal(val jobId: Long, val status: String, val error: String?) : NightlyAction

    /** Bouw en verstuur de digest en zet summary_sent_at. */
    data object SendDigest : NightlyAction

    /** Zet de run op ended (alle jobs terminaal én digest verstuurd). */
    data object EndRun : NightlyAction
}

/** Alle invoer die de planner nodig heeft; tijd-/DB-vragen zijn al beantwoord door de executor. */
data class NightlyPlannerInput(
    val settings: NightlySettings,
    /** De actieve run (status != ended), of de run van vandaag, of null als er nog geen run is. */
    val run: NightlyRunRecord?,
    val jobs: List<NightlyRunJobRecord>,
    /** Per lopende job (status running) de zojuist gepolde uitkomst. */
    val outcomes: Map<Long, NightlyStoryOutcome>,
    /** Heeft de huidige tijd de (naar UTC omgerekende) start-tijd van vandaag bereikt? */
    val startReached: Boolean,
    /** Heeft de huidige tijd de summary-tijd van de run-datum bereikt? */
    val summaryReached: Boolean,
)

/**
 * Restart-veilige beslis-kern van de nachtelijke scheduler. Volledig op DB-state: krijgt de huidige
 * run + jobs + gepolde story-uitkomsten en geeft de uit te voeren acties terug. Bevat geen tijd-,
 * DB- of netwerk-afhankelijkheden, zodat idempotentie, sequentieel/parallel en restart-pickup puur
 * te testen zijn.
 */
object NightlyPlanner {

    fun plan(input: NightlyPlannerInput): List<NightlyAction> {
        val actions = mutableListOf<NightlyAction>()
        val run = input.run

        // 1. Run-creatie: enabled + start-tijd bereikt + nog geen run → maak er één aan (idempotent
        //    op run_date in de DB-laag). Verder deze tick niets: de jobs verschijnen volgende tick.
        if (run == null) {
            if (input.settings.enabled && input.startReached) actions += NightlyAction.CreateRun
            return actions
        }

        // Een al beëindigde run (incl. digest verstuurd) is klaar; niets meer te doen.
        if (run.status == NightlyRunStatus.ENDED) return actions

        // 2. Reconcile per project: queues zijn onafhankelijk (projecten draaien parallel), maar binnen
        //    een project strikt sequentieel — pas een volgende job starten als de vorige terminaal is.
        for ((_, projectJobs) in input.jobs.groupBy { it.project }) {
            val sorted = projectJobs.sortedBy { it.id }
            val running = sorted.firstOrNull { it.status == NightlyJobStatus.RUNNING }
            if (running != null) {
                when (input.outcomes[running.id]?.status) {
                    NightlyOutcomeStatus.DONE -> {
                        actions += NightlyAction.MarkJobTerminal(running.id, NightlyJobStatus.DONE, null)
                        startNextPending(sorted, actions)
                    }
                    NightlyOutcomeStatus.FAILED -> {
                        val error = input.outcomes[running.id]?.error
                        actions += NightlyAction.MarkJobTerminal(running.id, NightlyJobStatus.FAILED, error)
                        // Eén fout blokkeert de nacht niet: ga door met de volgende job van dit project.
                        startNextPending(sorted, actions)
                    }
                    else -> {
                        // Story loopt nog (of uitkomst onbekend): wachten tot de volgende tick.
                    }
                }
            } else {
                // Geen lopende job in dit project: start de eerstvolgende pending (indien aanwezig).
                startNextPending(sorted, actions)
            }
        }

        // 3. Digest: na de summary-tijd exact één keer (summary_sent_at borgt de idempotentie).
        val sendingDigest = run.summarySentAt == null && input.summaryReached
        if (sendingDigest) actions += NightlyAction.SendDigest

        // 4. Run ended: alle jobs terminaal (of geen jobs) én de digest is/wordt verstuurd.
        val allTerminal = input.jobs.all { NightlyJobStatus.isTerminal(it.status) }
        val summaryHandled = run.summarySentAt != null || sendingDigest
        if (allTerminal && summaryHandled) actions += NightlyAction.EndRun

        return actions
    }

    /** Voegt een [NightlyAction.StartJob] toe voor de eerste pending job van dit (gesorteerde) project. */
    private fun startNextPending(sortedProjectJobs: List<NightlyRunJobRecord>, actions: MutableList<NightlyAction>) {
        val next = sortedProjectJobs.firstOrNull { it.status == NightlyJobStatus.PENDING } ?: return
        actions += NightlyAction.StartJob(next.id)
    }
}
