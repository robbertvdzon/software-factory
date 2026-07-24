@org.springframework.modulith.ApplicationModule(allowedDependencies = {
    "config", "core", "core :: contracts", "git", "nightly", "nightly :: models",
    "nightly :: repositories", "nightly :: services", "nightly :: types", "orchestrator",
    // "pipeline" (zonder named interface): alleen de root-package-poort DeployTargetStatusApi
    // (Story 4 — story-detail per-onderdeel build-status), niet pipeline.service zelf.
    // "pipeline :: models": het bijbehorende MatchedDeployTarget-datamodel.
    "pipeline", "pipeline :: models",
    "preview", "runtime", "runtime :: models", "telegram", "telegram :: models", "tracker",
    "tracker :: errors"
})
/** Public application ports for dashboard adapters. */
package nl.vdzon.softwarefactory.dashboard;
