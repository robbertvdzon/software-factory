@org.springframework.modulith.ApplicationModule(allowedDependencies = {
    // runtime (root, alleen de poort SubtaskMaterializationApi): de hotfix-tak materialiseert
    // zijn eigen subtaak-keten (SF-1959). Cyclusvrij: runtime kent pipeline niet.
    "config", "core", "core :: contracts", "github", "merge", "preview", "runtime", "support", "tracker"
})
package nl.vdzon.softwarefactory.pipeline;
