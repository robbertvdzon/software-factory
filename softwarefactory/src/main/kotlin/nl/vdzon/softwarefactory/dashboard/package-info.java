@org.springframework.modulith.ApplicationModule(allowedDependencies = {
    "config", "core", "core :: contracts", "git", "nightly", "nightly :: models",
    "nightly :: repositories", "nightly :: services", "nightly :: types", "orchestrator",
    "preview", "runtime", "runtime :: models", "telegram", "telegram :: models", "tracker",
    "tracker :: errors"
})
/** Public application ports for dashboard adapters. */
package nl.vdzon.softwarefactory.dashboard;
