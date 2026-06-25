# SF-244 / SF-245 — Merge altijd automatisch; verwijder merge.mode/AWAITING_HUMAN-mergepoort

## Story in eigen woorden

De MERGE-subtaak moet niet langer een keuze hebben tussen handmatig en automatisch mergen.
Hij merget voortaan altijd automatisch zodra hij aan de beurt is (fase START), ná de
handmatige goedkeuring-poort (`manual-approve`) en de voorgaande subtaken. De aparte,
configureerbare handmatige merge-poort vervalt volledig, inclusief de `merge.mode`-config in
`projects.yaml` en de bijbehorende `MergeConfig`-code. Een merge-conflict of GitHub-fout leidt
tot het bestaande Errored-gedrag (keten stopt), niet meer tot een `AWAITING_HUMAN`-status.

Alleen de software-factory-repo wordt geraakt; geen DB-migratie; deploy-gedrag ongewijzigd.

## Checklist

- [x]: `MergeSubtaskHandler` voert bij START altijd `performAutomaticMerge` uit; geen tak meer die `AWAITING_HUMAN` zet (AC1)
- [x]: `merge:`-blok / `merge.mode` verwijderd uit `projects.yaml.example` voor beide projecten + uit `ProjectRepoResolver.parse()` (AC2)
- [x]: `MergeConfig` sealed class + parsing + default + `mergeConfigFor` + constructor-param + init-loop + `ParsedProjects.mergeConfigs` verwijderd; geen dode verwijzingen (AC3)
- [x]: Merge-conflict / GitHub-fout → Errored-gedrag (Error op subtaak, keten stopt), geen awaiting-human (AC4)
- [x]: Na succesvolle merge gaat de keten door naar DEPLOY (MERGING → MERGE_APPROVED → advanceChain) (AC5)
- [x]: Tests aangepast/opgeschoond; test toegevoegd die bevestigt dat START altijd automatisch mergt en nooit `AWAITING_HUMAN` zet (AC6)
- [x]: Specs in `docs/factory/` bijgewerkt (technical-spec.md, functional-spec.md)
- [x]: Scope-tests groen gedraaid

## Wat is er precies gedaan en waarom

### Productiecode
- `MergeSubtaskHandler.kt`: KDoc bijgewerkt; `MergeConfig`-import, `mergeConfigFor`-lookup en de
  `when(mergeConfig)`-tak (incl. de tak die `AWAITING_HUMAN` zette) verwijderd. Bij `SubtaskPhase.START`
  wordt nu onvoorwaardelijk `performAutomaticMerge(subtask, parentKey)` aangeroepen. `performAutomaticMerge`
  en het Errored-pad bij `GitHubClientException` zijn ongewijzigd. De ongebruikt geworden
  constructor-parameter `projectRepoResolver` (en de bijbehorende import) is verwijderd. De nu overbodige
  `getIssue`-lookup voor de projectnaam is weggehaald.
- `SubtaskExecutionCoordinator.kt`: de `MergeSubtaskHandler(...)`-constructie aangepast aan de nieuwe
  signatuur (zonder `projectRepoResolver`). De resolver blijft in de coördinator voor de deploy-handler.
- `ProjectRepoResolver.kt`: `sealed class MergeConfig` verwijderd, plus `mergeConfigByName`-veld,
  init-loop, `mergeConfigFor()`, de constructor-param `mergeConfigs`, `ParsedProjects.mergeConfigs` en
  het inlezen van het `merge:`-blok in `parse()`. De `fromYaml`-aanroep is aangepast. `DeployConfig` en
  `manualApprove` blijven ongemoeid.
- `projects.yaml.example`: de `merge:`-blokken bij beide projecten en de `merge.mode`-documentatie
  verwijderd; de merge/deploy-uitleg herschreven naar "merge altijd automatisch".

### Tests
- `MergeSubtaskHandlerTest.kt`: het manual/automatic-onderscheid uit `buildHandler` gehaald (geen
  `mergeConfig`-param/resolver meer). Nieuwe/aangepaste tests:
  - `START always merges automatically MERGING then MERGE_APPROVED and never AWAITING_HUMAN` — bevestigt de
    fase-volgorde en dat `AWAITING_HUMAN` nooit wordt gezet (AC6).
  - `merge failure sets ERROR and resets phase to START` — bevestigt Errored-gedrag zonder awaiting-human (AC4).
  - `MERGE_APPROVED advances the chain to DEPLOY` (AC5) en `MERGING stays in progress`.
- `ProjectRepoResolverMergeDeployTest.kt`: alle `mergeConfigFor`/`MergeConfig`-assertions verwijderd; de
  yaml-fixtures ontdaan van `merge:`-blokken; tests hernoemd naar deploy-only varianten.

### Specs
- `docs/factory/technical-spec.md`: per-project-config beschrijft niet langer `merge`; toegevoegd dat de
  MERGE-subtaak altijd automatisch mergt en bij fouten Error geeft (geen `AWAITING_HUMAN`).
- `docs/factory/functional-spec.md`: nieuwe sectie "Merge altijd automatisch (SF-244)".

## Verificatie

`mvn -f softwarefactory/pom.xml test -Dtest='MergeSubtaskHandlerTest,ProjectRepoResolverMergeDeployTest'`:
- MergeSubtaskHandlerTest: Tests run: 5, Failures: 0, Errors: 0
- ProjectRepoResolverMergeDeployTest: Tests run: 7, Failures: 0, Errors: 0

Volledige module-compilatie (main + test) slaagde als onderdeel van de testrun. De bredere suite is
niet integraal gedraaid (bekende main-failures: ModulithArchitectureTest, AgentResultFileCompletionPollerTest,
en Docker-afhankelijke e2e-tests draaien niet in deze omgeving) — laat de pipeline die volledig draaien.

## Review (SF-245, reviewer)

Statische review van de volledige story-diff (`git diff main...HEAD`). Bevindingen:

- [info] AC1–AC6 afgedekt. `MergeSubtaskHandler.process` voert bij START onvoorwaardelijk
  `performAutomaticMerge` uit; geen AWAITING_HUMAN-tak meer. `MergeConfig` (sealed class,
  `mergeConfigFor`, `mergeConfigByName`, constructor-param, parsing van het `merge:`-blok) is
  volledig verwijderd uit `ProjectRepoResolver`; geen dode verwijzingen meer in de codebase
  (grep schoon). `SubtaskExecutionCoordinator` roept de nieuwe constructor-signatuur correct aan.
- [info] `projects.yaml.example`: `merge:`-blokken en `merge.mode`-documentatie verwijderd bij
  beide projecten; deploy-blok en `manualApprove` ongemoeid. Geen secrets in de diff.
- [info] Specs consistent: `functional-spec.md` en `technical-spec.md` beschrijven nu de
  onvoorwaardelijke auto-merge, het Error-pad (geen AWAITING_HUMAN) en het verwijderde
  `merge.mode`. Geen spec-inconsistenties.
- [info] Tests: nieuwe test bevestigt dat START altijd MERGING→MERGE_APPROVED doorloopt en nooit
  AWAITING_HUMAN zet (AC6); Errored-pad bij merge-conflict gedekt (AC4); MERGE_APPROVED→advanceChain
  (AC5). `ProjectRepoResolverMergeDeployTest` ontdaan van merge-assertions, deploy-dekking intact.
- [info] Legacy persisted MERGE-subtaken op `AWAITING_HUMAN`/`MANUAL_ACTION_DONE` vallen nu in de
  `else ->`-tak → `Skipped` (no-op, blijven staan). Conform de bewuste "geen DB-migratie"-aanname
  in de story; betreft alleen eventueel al lopende runs. Akkoord.

Conclusie: coherent, getest en passend binnen de specs. Akkoord.

## Test (SF-246, tester)

Lokale verificatie op branch `ai/SF-244` (geen preview-deploy ingericht voor deze factory-repo;
geen browser/screenshot-context van toepassing → /work/screenshots leeg).

### Statische verificatie
- `grep` naar `MergeConfig` / `mergeConfigFor` / `merge.mode` / `mergeConfigs` over `softwarefactory/`
  en `projects.yaml*`: geen treffers meer → geen dode verwijzingen (AC2/AC3).
- `MergeSubtaskHandler.kt`: geen `AWAITING_HUMAN`/`MANUAL_ACTION_DONE` meer; `SubtaskPhase.START`
  roept onvoorwaardelijk `performAutomaticMerge` aan (AC1). `SubtaskExecutionCoordinator` gebruikt de
  nieuwe constructor zonder `projectRepoResolver`.
- `projects.yaml.example`: `merge:`-blokken bij beide projecten weg, documentatie herschreven naar
  "altijd automatisch" (AC2). Geen secrets in de diff.

### Tests
- `mvn -f softwarefactory/pom.xml test -Dtest='MergeSubtaskHandlerTest,ProjectRepoResolverMergeDeployTest'`:
  Tests run: 12, Failures: 0, Errors: 0 → BUILD SUCCESS.
  - `MergeSubtaskHandlerTest` dekt expliciet: START doorloopt MERGING→MERGE_APPROVED en zet nooit
    AWAITING_HUMAN (AC6), merge-fout → ERROR + reset naar START zonder awaiting-human (AC4),
    MERGE_APPROVED → advanceChain naar DEPLOY (AC5).
- Volledige module-suite `mvn -f softwarefactory/pom.xml test -Dsurefire.runOrder=alphabetical`:
  Tests run: 350, **Failures: 0**, Errors: 13. Alle 13 errors zijn pre-existing/omgevings­gebonden:
  1× `ModulithArchitectureTest` (bekende modulith-cycle op schone main), 11× Docker-afhankelijke
  e2e-tests (`PipelineFlowsE2eTest` 9, `FactoryUiDriverLoginTest` 1, `FullRefineToDevelopE2eTest` 1 —
  geen docker-daemon in tester-omgeving) en 1× `FactoryDashboardRepositoryScreenshotTest`. Deze laatste
  geverifieerd als pre-existing: faalt identiek op een schone `main`-worktree. Geen regressies.

### Conclusie
Alle acceptatiecriteria (AC1–AC6) bevestigd; story-relevante tests groen, geen nieuwe failures. **tested**.
