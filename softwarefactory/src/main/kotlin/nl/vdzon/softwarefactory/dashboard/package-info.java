@org.springframework.modulith.ApplicationModule(allowedDependencies = {
    "audit", "audit :: models", "audit :: repositories", "audit :: services", "audit :: types",
    "config", "config :: time", "contract", "core", "core :: contracts", "git", "knowledge",
    "knowledge :: models",
    // maintenance :: repositories: de cleanup-run-historie achter het Maintenance-scherm (SF-1913);
    // de bridge-module mag maintenance zelf niet kennen en leest 'm via DashboardQueries.
    "maintenance", "maintenance :: repositories",
    "orchestrator",
    // pipeline (zonder named interface): alleen de root-package-poort DeployTargetStatusApi
    // (Story 4 — story-detail per-onderdeel build-status), niet pipeline.service zelf.
    // pipeline :: models: het bijbehorende MatchedDeployTarget-datamodel.
    "pipeline", "pipeline :: models",
    "preview", "runtime", "runtime :: models", "support", "telegram", "telegram :: models", "tracker",
    "tracker :: errors"
})
/** Public application ports for dashboard adapters. */
package nl.vdzon.softwarefactory.dashboard;
