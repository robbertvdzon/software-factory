# SF-985 — Mechanische naamcleanup en verwijdering aantoonbaar dode code

## Inventarisatie

| Kandidaat | Referenties en classificatie | Uitvoering / bewijs |
|---|---|---|
| `OrchestratorApi.pollOnce(projectKey)` | Parameter had geen enkele lezer; alle productiecalls gebruikten de default | Parameter en drie testoverrides mechanisch verwijderd; orchestratorgerichte tests |
| `TrackerCapabilities.findAiIssues(projectKey, …)` | Implementatie filtert uitsluitend op getypeerde `FactorySecrets.trackerProjects`; argument was ongebruikt | Parameter en alle overrides mechanisch verwijderd; tracker- en orchestratorgerichte tests |
| `core/contracts/YouTrackModels.kt` | Bevat uitsluitend generieke trackerentiteiten | Hernoemd naar `TrackerEntities.kt`; het bestaande brede workflowbestand is verduidelijkt als `WorkflowModels.kt` |
| `CostMonitorService.kt`, `CreditsPauseService.kt` | Geen import-only resten: beide bevatten actieve `@Service`-implementaties met productiegebruik | Al inhoudelijk opgelost; bestanden bewust behouden |
| dashboard-backend-POM | Omschreef de muterende bridge onjuist als read-only | Omschrijving mechanisch gewijzigd naar thin dashboard bridge API |
| packagepluraliteit | `pipeline/service` is de cohesieve verwerkingslaag van die module; overige meervoudpackages zijn adaptercollecties | Geen aantoonbaar gelijk ownership en dus geen mechanische rename; bewust ongewijzigd om geen architectuurkeuze in cleanup te verstoppen |
| `ProjectRepoResolver` / `ProjectCatalog` | Geen productie-, test- of actuele-documentatiereferenties | Al opgelost door ARC-07; geen facade geïntroduceerd |

## Verificatie

- `git diff --find-renames --stat`
- gerichte orchestrator-, tracker-, pipeline- en Modulith-tests
- `./quality/run.sh`
- `tools/generate-module-dependencies --check`
- afsluitend `tools/verify-repository`
