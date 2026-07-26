package nl.vdzon.softwarefactory.audit.services

import nl.vdzon.softwarefactory.audit.models.AuditOutcome
import nl.vdzon.softwarefactory.audit.repositories.AuditJobStatus
import nl.vdzon.softwarefactory.audit.repositories.AuditRunJobRecord
import nl.vdzon.softwarefactory.audit.repositories.AuditRunKind
import nl.vdzon.softwarefactory.audit.repositories.AuditRunRecord
import nl.vdzon.softwarefactory.audit.repositories.AuditRunStatus
import nl.vdzon.softwarefactory.audit.repositories.AuditSettings
import nl.vdzon.softwarefactory.audit.types.AuditOutcomeStatus

/**
 * Eén concrete actie die de [AuditScheduler]-executor op de DB/gateway moet uitvoeren. Zelfde
 * plan/uitvoer-scheiding als [nl.vdzon.softwarefactory.nightly.services.NightlyPlanner] (puur en
 * deterministisch testbaar zonder DB/Docker).
 */
sealed interface AuditAction {
    /** Maak de run voor vandaag aan; het kiezen + seeden van de audits gebeurt in de executor
     * (heeft `audit_report`-historie nodig — geen puur-plan-data). */
    data object CreateRun : AuditAction

    /** Dispatch de auditor-agent voor deze job en zet 'm op running. */
    data class StartJob(val jobId: Long) : AuditAction

    /** Markeer een lopende job terminaal (done/failed), gekoppeld aan het gepersisteerde rapport. */
    data class MarkJobTerminal(val jobId: Long, val status: String, val reportId: Long?, val error: String?) : AuditAction

    /** Zet de run op ended (alle jobs terminaal). */
    data object EndRun : AuditAction
}

/** Alle invoer die de planner nodig heeft; tijd-/DB-vragen zijn al beantwoord door de executor. */
data class AuditPlannerInput(
    val settings: AuditSettings,
    /** De actieve run (status != ended), of null als er geen run loopt. */
    val run: AuditRunRecord?,
    val jobs: List<AuditRunJobRecord>,
    /** Per lopende job (status running) de zojuist gepolde uitkomst. */
    val outcomes: Map<Long, AuditOutcome>,
    /** Heeft de huidige tijd de (naar UTC omgerekende) start-tijd van vandaag bereikt? */
    val startReached: Boolean,
    /** Bestaat er vandaag al een scheduled run? Zo ja, geen tweede automatische run aanmaken. */
    val scheduledRunExistsToday: Boolean = false,
)

/**
 * Restart-veilige beslis-kern van de audit-scheduler. Zelfde opzet als `NightlyPlanner`, maar
 * zonder digest-stap (rapporten staan al meteen in het dashboard) — de run eindigt zodra alle
 * (hoogstens 1-per-project) jobs terminaal zijn.
 */
object AuditPlanner {

    fun plan(input: AuditPlannerInput): List<AuditAction> {
        val actions = mutableListOf<AuditAction>()
        val run = input.run

        // 1. Run-creatie: alleen als er geen run loopt, de scheduler aan staat, de start-tijd
        //    bereikt is én er vandaag nog geen scheduled run was. Het kiezen van "welke audit per
        //    project" (oudste-eerst, op basis van audit_report-historie) gebeurt in de executor —
        //    verder deze tick niets: de jobs verschijnen volgende tick.
        if (run == null) {
            if (input.settings.enabled && input.startReached && !input.scheduledRunExistsToday) {
                actions += AuditAction.CreateRun
            }
            return actions
        }

        if (run.status == AuditRunStatus.ENDED) return actions

        // 2. Reconcile per project: onafhankelijke queues, maar er zit toch al maar 1 job per
        //    project in (zie executor) — deze structuur blijft consistent met NightlyPlanner en
        //    kan zonder wijziging meerdere jobs per project aan mocht dat ooit gewenst zijn.
        for ((_, projectJobs) in input.jobs.groupBy { it.project }) {
            val sorted = projectJobs.sortedBy { it.id }
            val running = sorted.firstOrNull { it.status == AuditJobStatus.RUNNING }
            if (running != null) {
                val outcome = input.outcomes[running.id]
                when (outcome?.status) {
                    AuditOutcomeStatus.DONE -> {
                        actions += AuditAction.MarkJobTerminal(running.id, AuditJobStatus.DONE, outcome.report?.reportId, null)
                        startNextPending(sorted, actions)
                    }
                    AuditOutcomeStatus.FAILED -> {
                        actions += AuditAction.MarkJobTerminal(running.id, AuditJobStatus.FAILED, null, outcome.error)
                        startNextPending(sorted, actions)
                    }
                    else -> {
                        // Run loopt nog (of uitkomst onbekend): wachten tot de volgende tick.
                    }
                }
            } else {
                startNextPending(sorted, actions)
            }
        }

        // 3. Run ended zodra alle jobs terminaal zijn (of geen jobs — misconfiguratie/geen enabled audits).
        val allTerminal = input.jobs.all { AuditJobStatus.isTerminal(it.status) }
        if (allTerminal) actions += AuditAction.EndRun

        return actions
    }

    private fun startNextPending(sortedProjectJobs: List<AuditRunJobRecord>, actions: MutableList<AuditAction>) {
        val next = sortedProjectJobs.firstOrNull { it.status == AuditJobStatus.PENDING } ?: return
        actions += AuditAction.StartJob(next.id)
    }
}
