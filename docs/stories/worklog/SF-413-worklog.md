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

## SF-414 — Review (reviewer)

Statische review (geen Docker in reviewer-omgeving; build via CI). Diff t.o.v. `main`:
alleen testcode + worklog, geen productiecode.

Geverifieerd tegen de productiecode:
- **Loopback-cap** (`AgentDispatcher.kt:99-105`): leest `AI Max Developer Loopbacks` van de
  subtaak zelf (`TrackerModels.kt:220`), cap-conditie `developerRuns >= max+1`, Error-tekst
  "Developer-loopback cap bereikt". Cap=1 → 2 dispatches → Error: test correct.
- **review-rejected → developer-loopback** (`SubtaskExecutionCoordinator.kt:241-242`): correct.
- **test-chain reset** (`handleTestRejection`): `testerRuns >= maxTestChainResets+1` (3+1=4);
  test doet 3 tester-runs onder de cap, reset re-dispatcht bij een test-only keten alleen de
  tester (geen developer): test correct.
- **Silent/effectiveSilent** (`YouTrackApi.effectiveSilent`, `questionsOutcome`): vraag →
  clarification-`Error` met marker `[CLARIFICATION]` (`TrackerModels.kt:50`): test correct.

Bevindingen:
- [info] Tests hergebruiken bestaande infra/conventies (E2eTestBase, AgentScript, AwaitDsl,
  FakeYouTrackServer); unieke story-keys; Awaitility voor async — deterministisch.
- [info] `AwaitDsl.awaitErrorContains` is een nette, beperkte testhelper.
- [bug-fixed] Het worklog eindigde op een verdwaalde `</content>`-tag (tool-artefact);
  verwijderd door de reviewer.

Geen scope creep, geen productiegedrag gewijzigd, geen spec-inconsistenties. Akkoord.

## SF-415 — Story-brede test (tester)

Omgeving: JDK 21 + Maven voorgeïnstalleerd, **geen Docker-daemon** (Testcontainers-Postgres
kan niet starten — bekende tester-beperking; e2e-tests worden in CI/factory groen geverifieerd).

### Diff-scope
`git diff main...HEAD` raakt uitsluitend testcode + worklog:
`PipelineLoopbackE2eTest.kt` (nieuw), `AwaitDsl.kt` (helper `awaitErrorContains` + `textFieldOf`),
`SF-413-worklog.md`. Geen productiecode gewijzigd — voldoet aan acceptatiecriterium.

### Build / test
- `mvn -f softwarefactory/pom.xml test-compile` → groen.
- `mvn -f softwarefactory/pom.xml test -Dsurefire.runOrder=alphabetical` → **395 tests,
  Failures: 0**, 19 Errors. Alle 19 Errors zijn omgeving/pre-existing, géén code-failure:
  - 14 baseline-env-errors (FactoryUiDriverLoginTest 1, FullRefineToDevelopE2eTest 1,
    PipelineFlowsE2eTest 9, NightlyRepositoriesTest 1, ModulithArchitectureTest 1 [pre-existing
    cycle], FactoryDashboardRepositoryScreenshotTest 1);
  - **5 nieuw**: `PipelineLoopbackE2eTest` — falen uitsluitend op `ApplicationContext failure` /
    `DockerClientProviderStrategy` (geen Docker), niet op een assertion.
- `agentworker`/`dashboard-backend` worden door de diff niet geraakt → geen regressie mogelijk.

### Statische verificatie van de nieuwe e2e-assertions tegen productiecode
Omdat de 5 nieuwe e2e-tests lokaal niet uitvoerbaar zijn (geen Docker), is elk scenario los tegen
de productiecode geverifieerd; alle verwachtingen kloppen:
- Developer-loopback-cap (`AgentDispatcher.kt:100-101`): `developerRuns >= maxDeveloperLoopbacks+1`,
  Error-tekst "Developer-loopback cap bereikt", cap leest het subtaak-veld `AI Max Developer
  Loopbacks` → cap=1 ⇒ exact 2 dispatches dan Error.
- Test-chain reset (`SubtaskExecutionCoordinator.handleTestRejection`): `testerRuns >=
  maxTestChainResets+1`, default `DEFAULT_MAX_TEST_CHAIN_RESETS=3` → 3 tester-runs blijven onder cap.
- Clarification-Error: marker `[CLARIFICATION]` (`TrackerModels.kt:50`) via `effectiveSilent`
  (`YouTrackApi.kt:101`) + `questionsOutcome`.
- review-rejected → developer-loopback (`SubtaskExecutionCoordinator.kt:241-242`).
- Alle gebruikte test-helpers bestaan (`setRawField`, `setEnumField`, `childrenOf`, `startDeveloping`,
  `setSubtaskPhase`, `AgentScript.subtasks`, `awaitDispatchCount`, etc.).

### Conclusie
Geen code-failures, geen regressies, geen productiegedrag gewijzigd. De nieuwe deterministische
e2e-tests compileren, hergebruiken bestaande infra en hun assertions stemmen overeen met de
geverifieerde productiecode. De feitelijke groene e2e-uitvoering loopt via CI (Docker/Testcontainers),
conform de bestaande repo-conventie. **Getest: akkoord.**
