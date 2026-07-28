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
    /** Maak de run voor vandaag aan (leeg — het seeden per project gebeurt via [SeedProject]). */
    data object CreateRun : AuditAction

    /** Kies + seed de audits voor dit ene project (heeft `audit_report`-historie + per-project
     * instellingen nodig — geen puur-plan-data, gebeurt dus in de executor). */
    data class SeedProject(val project: String) : AuditAction

    /** Dispatch de auditor-agent voor deze job en zet 'm op running. */
    data class StartJob(val jobId: Long) : AuditAction

    /** Markeer een lopende job terminaal (done/failed), gekoppeld aan het gepersisteerde rapport. */
    data class MarkJobTerminal(val jobId: Long, val status: String, val reportId: Long?, val error: String?) : AuditAction

    /** Zet de run op ended (alle jobs terminaal en geen project meer te seeden). */
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
    /** Bestaat er vandaag al een scheduled run? Zo ja, geen tweede automatische run aanmaken. */
    val scheduledRunExistsToday: Boolean = false,
    /**
     * Projecten die deze run nog moeten seeden (audit_count > 0, nog geen job in deze run), met of
     * hun (per-project, anders globale) starttijd al bereikt is. Leeg voor een MANUAL-run (die blijft
     * beperkt tot de ene expliciet aangevraagde audit) en vóórdat er een run is, is dit precies de
     * volledige kandidatenlijst.
     */
    val pendingProjects: Map<String, Boolean> = emptyMap(),
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

        // 1. Run-creatie: alleen als er geen run loopt, de scheduler aan staat, er vandaag nog geen
        //    scheduled run was, én minstens één project z'n starttijd al bereikt heeft. De run zelf
        //    is een lege container; het seeden per project gebeurt via SeedProject (stap 2) zodra
        //    dát project aan de beurt is — kan dus per project op een ander moment vallen.
        if (run == null) {
            if (input.settings.enabled && !input.scheduledRunExistsToday && input.pendingProjects.values.any { it }) {
                actions += AuditAction.CreateRun
            }
            return actions
        }

        if (run.status == AuditRunStatus.ENDED) return actions

        // 2. Seed elk project waarvan de starttijd bereikt is en dat nog geen job in deze run heeft
        //    (input.pendingProjects bevat per constructie alleen nog-niet-geseede projecten).
        for ((project, reached) in input.pendingProjects) {
            if (reached) actions += AuditAction.SeedProject(project)
        }

        // 3. Reconcile per project: onafhankelijke queues; sequentieel binnen een project (precies 1
        //    running tegelijk) — al geldig zowel voor 1 als voor N jobs per project.
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
                    // De auditor stelde een vraag: geen rapport, maar wél terminaal. Zou deze job
                    // blijven "wachten", dan sluit de run nooit (stap 4) en wordt er ook nooit een
                    // nieuwe aangemaakt (stap 1 eist "geen run actief") — één openstaande vraag zou
                    // dus alle audits van alle projecten stilleggen. Het antwoord plant later een
                    // nieuwe job in; de vraag zelf staat in audit_question.
                    AuditOutcomeStatus.ASKED -> {
                        actions += AuditAction.MarkJobTerminal(running.id, AuditJobStatus.ASKED, null, null)
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

        // 4. Run ended zodra alle jobs terminaal zijn EN geen project meer wacht om geseed te worden
        //    (anders zou de run eindigen vóórdat een project met een latere starttijd aan bod komt).
        val allTerminal = input.jobs.all { AuditJobStatus.isTerminal(it.status) }
        if (input.pendingProjects.isEmpty() && allTerminal) actions += AuditAction.EndRun

        return actions
    }

    private fun startNextPending(sortedProjectJobs: List<AuditRunJobRecord>, actions: MutableList<AuditAction>) {
        val next = sortedProjectJobs.firstOrNull { it.status == AuditJobStatus.PENDING } ?: return
        actions += AuditAction.StartJob(next.id)
    }
}
