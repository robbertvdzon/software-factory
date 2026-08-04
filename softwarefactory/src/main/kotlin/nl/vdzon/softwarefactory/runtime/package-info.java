@org.springframework.modulith.ApplicationModule(allowedDependencies = {
    "config", "contract", "core", "core :: contracts", "docs", "git", "github", "knowledge",
    "knowledge :: models",
    // maintenance (root): de gedeelde dubbel-draaien-bewaking CleanupRunGuard en de poort
    // MaintenanceCleanupApi op de GitHub-cleanup (SF-1929) — géén interne subpackage.
    "maintenance", "maintenance :: repositories", "maintenance :: types", "support", "tracker", "verification"
})
package nl.vdzon.softwarefactory.runtime;
