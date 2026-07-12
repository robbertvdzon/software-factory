# Publieke module-API-inventaris

Versie 1, gecontroleerd door `ModulithArchitectureTest` en `ModuleApiConventionTest`.

| Module | Publieke API | Bewuste named interfaces |
| --- | --- | --- |
| bridge | `BridgeClient`, `BridgeRequestHandler` | — |
| config | `ConfigApi` | — |
| core | domeinpoorten en domeintypes in de module-root | — |
| knowledge | `KnowledgeApi` | — |
| merge | `PullRequestMergeService` | — |
| orchestrator | `OrchestratorApi` | — |
| runtime | `RuntimeApi`, `SubtaskMaterializationApi` | — |
| tracker | `TrackerApi` | — |
| web | `WebApi` | `models`; tijdelijke `services` tot ARC-01 |

De volledige tijdelijke rootinventaris staat machineleesbaar in de individuele allowlist bij de
architectuurtest. Nieuwe publieke modellen, types of fouten vragen een expliciete named interface,
een API-signature/cross-modulegebruiker en een testwijziging.
