package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.runtime.models.CleanupRunNowOutcome

/**
 * Publieke poort waarmee het dashboard een opruimronde nú kan laten draaien (SF-1929) — de vier
 * factory-brede opruimers én, via [nl.vdzon.softwarefactory.maintenance.MaintenanceCleanupApi], de
 * GitHub-cleanup.
 *
 * Root-package-poort volgens het precedent [AgentLogApi] /
 * [nl.vdzon.softwarefactory.pipeline.DeployTargetStatusApi]: de dashboard-module mag de pollers in
 * `runtime :: services`/`runtime :: workspaces` zelf niet kennen.
 */
interface CleanupRunNowApi {
    /**
     * Start één ronde van [kind] (een `CleanupKinds`-waarde), of van álle vrije soorten bij
     * `CleanupKinds.ALL_KINDS`. Niet-blokkerend: de ronde loopt op een achtergrond-executor, zodat
     * de 30s-timeout van de bridge een lange GitHub-ronde nooit afkapt.
     */
    fun runNow(kind: String): CleanupRunNowOutcome
}
