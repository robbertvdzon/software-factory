package nl.vdzon.softwarefactory.maintenance

/**
 * Publieke poort op de GitHub-release/package-opruiming (SF-1929): exact dezelfde ronde die de cron
 * draait, ook aanroepbaar vanaf de "Nu draaien"-knop — géén tweede implementatie.
 *
 * Root-package-poort volgens het precedent `runtime.AgentLogApi` / `pipeline.DeployTargetStatusApi`:
 * de aanroeper (`runtime`, en via die weg het dashboard) mag
 * [nl.vdzon.softwarefactory.maintenance.services.MaintenanceCleanupScheduler] zelf niet kennen.
 */
fun interface MaintenanceCleanupApi {
    /**
     * Eén volledige ronde over álle projecten met een release-cleanup-configuratie, met [trigger]
     * (`scheduled`/`manual`) op de logregels. De aanroeper houdt de [CleanupRunGuard] al vast — deze
     * methode doet zelf géén dubbel-draaien-bescherming.
     */
    fun runCleanupRoundLocked(trigger: String)
}
