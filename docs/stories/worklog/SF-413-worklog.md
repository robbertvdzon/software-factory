# SF-413 - Worklog

Story-context bij eerste pickup:
Ontbrekende integratietests toevoegen

Breng de door productiecode ondersteunde orchestratie-/pipeline-scenario's in kaart, vergelijk met de bestaande e2e-suite (FullRefineToDevelopE2eTest, PipelineFlowsE2eTest) en voeg ontbrekende integratietests toe (of verbeter bestaande) in softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/. Hergebruik E2eTestBase, AgentScript, FactoryUiDriver, AwaitDsl/Awaitility, FakeYouTrackServer en Testcontainers; geen nieuwe infra tenzij strikt nodig. Wijzig GEEN functioneel gedrag van productiecode - alleen testcode/-fixtures/-helpers.

## Stappenplan
[x]: read issue and target docs
[x]: orchestratie-/pipeline-scenario's in kaart brengen en met bestaande e2e vergelijken
[x]: ontbrekende integratietests toevoegen
[x]: tests compileren + niet-Docker-suite verifiëren
[x]: update story-log with results

## SF-414 (developer) — uitvoering

### In kaart gebrachte scenario's (productiecode vs. bestaande e2e)

Bron: `SubtaskExecutionCoordinator`, `AgentDispatcher`, `MergeSubtaskHandler`, `DeploySubtaskHandler`,
`StoryRefinementCoordinator`, `OrchestratorSettings`, `YouTrackApi.effectiveSilent`.

Reeds gedekt door `FullRefineToDevelopE2eTest` + `PipelineFlowsE2eTest`:
- Volledige happy-path refine→plan→alle subtaken (incl. refiner- en developer-vraag).
- Vraag-flow per subtaak-soort (review/test/summary) en story-niveau-vraag (refiner).
- Eénmalige reject-flows: development-rejected (1x developer-loopback), test-rejected (1x keten-reset,
  SF-200), summary-rejected, refinement-rejected, planning-rejected.
- Manual-subtaak (wacht op mens, geen agent).

Ondersteund maar **niet** gedekt (gekozen als nieuwe dekking):
1. Developer-loopback **voorbij één iteratie** (`DEVELOPMENT_REJECTED` meermaals).
2. `REVIEW_REJECTED` **binnen een development-subtaak** → developer-loopback (eigen review-poort van de
   dev-subtaak, los van de losse review-subtaak).
3. Developer-loopback-**cap-overschrijding** → subtaak in `Error`, geen verdere dispatch
   (`AgentDispatcher` cap-tak, cap per subtaak via `AI Max Developer Loopbacks`).
4. Test-chain-reset **voorbij één iteratie** (SF-200, meerdere bevindingen onder de cap).
5. **Silent** subtaak: een agent-vraag wacht niet op een mens maar belandt in een clarification-`Error`
   (`questionsOutcome` + `effectiveSilent` via de parent).

### Toegevoegd / aangepast

- **Nieuw**: `softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/PipelineLoopbackE2eTest.kt`
  met vijf tests, één per bovenstaand scenario. Hergebruikt `E2eTestBase`, `AgentScript`,
  `FactoryUiDriver`, `AwaitDsl`/Awaitility en de `FakeYouTrackServer`-state; geen nieuwe infra. Unieke
  story-keys (`SP-100`..`SP-140`) zodat workspaces/story-runs niet vermengen.
- **Helper**: `AwaitDsl.awaitErrorContains(key, contains)` toegevoegd (leest het tekst-`Error`-veld
  `{"text": …}`) — nodig om deterministisch op de cap-/clarification-`Error` te wachten i.p.v. te pollen
  op fasen die dan niet veranderen. Puur testinfrastructuur.

Belangrijk implementatiedetail dat in een test is verwerkt: de developer-loopback-cap leest
`AI Max Developer Loopbacks` van de **subtaak zelf** (niet van de parent), dus de test zet dat veld op
de subtaak na materialisatie.

### Determinisme

Alle tests gebruiken de bestaande deterministische fakes (geen echte Docker/netwerk/klok): de scripted
`TestAgentRuntime`, de embedded `FakeYouTrackServer` en de lokale git-remote. Async-verificatie loopt via
Awaitility op de YouTrack-state. Wacht-/dispatch-tellingen zijn afgeleid van de eindfasen, niet van
timing.

### Bewust niet toegevoegd

- **Merge/deploy-afronding als e2e-keten**: de e2e-config zet de manual-approve-poort uit en stuurt geen
  echte GitHub-PR (lokale file-remote → `prNumber` is null), terwijl `MergeSubtaskHandler` bij
  ontbrekend PR-nummer/targetRepo bewust in `Error` gaat. Een e2e-test daarvoor zou óf nieuwe infra
  vereisen (gefakete `GitHubApi` + geseed PR-nummer) óf het `Error`-pad als "afronding" bevriezen. Beide
  vallen buiten "geen nieuwe infra / geen buggy gedrag bevriezen"; merge/deploy zijn al unit-gedekt
  (`MergeSubtaskHandlerTest`, `DeploySubtaskHandlerTest`).
- **Documentation-subtaak-flow via e2e**: `AgentScript` scripten van de `DOCUMENTER`-rol zou een
  uitbreiding van de fixture vergen; de documentatie-fase-overgangen zijn al gedekt via de
  coördinator-/fase-unittests. Niet opgenomen om de wijziging klein te houden.

Geen scenario aangetroffen dat alleen via een test te dekken is die aantoonbaar buggy gedrag zou
bevriezen; de run eindigt dus niet in error.

### Geraakte specs

Geen `docs/factory/`-spec-wijziging nodig: er is geen functioneel gedrag toegevoegd of gewijzigd, alleen
testdekking. De technische stack/conventies in `technical-spec.md` blijven kloppen.

### Verificatie

- `mvn -f softwarefactory/pom.xml test-compile` → groen (alle test-sources compileren, incl. de nieuwe
  test + helper).
- Niet-Docker-fixturetests `FakeYouTrackServerTest` (borgt het geseede schema) + `SubtaskPhaseTerminalTest`
  → groen (12 tests).
- De nieuwe `PipelineLoopbackE2eTest` (en de bestaande e2e-suite) vereisen Docker/Testcontainers-Postgres;
  die draait niet in de developer-omgeving (geen Docker) en wordt in de CI/factory-pipeline geverifieerd —
  conform de bestaande e2e-conventie in deze repo.
</content>
