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
