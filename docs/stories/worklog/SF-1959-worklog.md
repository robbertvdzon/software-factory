# SF-1959 - Worklog

Story-context bij eerste pickup:
Story-veld Hotfix end-to-end (TrackerField, V33, tracker-client, aanmaakroutes, UI-toggle)

Voeg het boolean story-veld `Hotfix` toe zonder pipeline-gedrag te wijzigen. Raakt: factory-common core/TrackerField.kt (HOTFIX("Hotfix")); migratie V33 met `hotfix BOOLEAN NOT NULL DEFAULT false` op ${schema}.issues (laatste op main is V32, bestaande rijen niet aanraken); WorkflowModels.kt TrackerIssueFields.hotfix=false + exhaustieve applying(...)/applyingLifecycleField (accepteer String én Boolean, net als questionsAllowed); PostgresTrackerClient ISSUE_COLUMNS, mapRow, columnForLifecycleField, columnValue en createStory(...) met `hotfix: Boolean = false` in de INSERT; aanmaakroutes TrackerStoryApiController + CreateTrackerStoryRequest, tools/sf-story `create --hotfix` (incl. usage), bridge-operatie story.create in BridgeRequestHandler/CreateStoryCommand, en dashboard-backend POST /api/v1/stories in BridgeApiController; dashboard-frontend lib/screens/stories_screen.dart met een Hotfix-schakelaar in de aanmaakdialoog (state + payload naast _questionsAllowed); e2e-testsupport TrackerTestState.fieldFor moet "Hotfix" kennen (gooit anders hard error()). AuditGatewayAdapter.proposeStoryIfAny blijft expliciet op hotfix=false. Schrijf zelf de unittests: round-trip van het veld (persist/mapRow/applying) en default false op alle vier de aanmaakroutes. Afronden met de eigen review-stap, `mvn verify` groen vanaf de repo-root en `flutter analyze` schoon in dashboard-frontend.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

## SF-1960 — Story-veld Hotfix end-to-end (developer)

In eigen woorden: er komt één nieuw boolean story-veld `Hotfix` naast de bestaande drie
story-assen. Dit deel voegt alléén het veld toe — van tracker-enum tot en met de UI-schakelaar —
zonder ook maar iets aan de pipeline te veranderen. De hotfix-keten zelf is SF-1961.

Checklist:
[x]: `TrackerField.HOTFIX("Hotfix")` in factory-common
[x]: migratie `V33__story_hotfix.sql` (`hotfix BOOLEAN NOT NULL DEFAULT false`, bestaande rijen ongemoeid)
[x]: `TrackerIssueFields.hotfix` + exhaustieve `applying`/`applyingLifecycleField`
[x]: `PostgresTrackerClient`: ISSUE_COLUMNS, mapRow, columnFor(Lifecycle)Field, columnValue, INSERT in createStory
[x]: `TrackerCapabilities.createStory(hotfix: Boolean = false)`
[x]: aanmaakroutes: `POST /api/tracker/stories`, `sf-story create --hotfix`, bridge `story.create`, dashboard-backend `POST /api/v1/stories`
[x]: Hotfix-schakelaar in de aanmaakdialoog (`dashboard-frontend/lib/screens/stories_screen.dart`)
[x]: `AuditGatewayAdapter.proposeStoryIfAny` expliciet op `hotfix = false`
[x]: testsupport: `TrackerTestState.fieldFor("Hotfix")`, fakes bijgewerkt
[x]: eigen unit-/e2e-tests geschreven en gedraaid
[x]: docs (`functional-spec.md`, `technical-spec.md`, `ux/screens/stories.md`) bijgewerkt
[x]: volledig vangnet groen (`mvn clean verify`, `flutter analyze`, `flutter test`, `tools/audit-documentation`)

Wat en waarom:

- **Veld en kolom.** `TrackerField.HOTFIX("Hotfix")` staat bij de andere story-assen; de kolom komt
  via `V33__story_hotfix.sql` met `ADD COLUMN IF NOT EXISTS ... DEFAULT false` en raakt bestaande
  rijen bewust niet aan (AC 2: een bestaande story wordt hierdoor nooit een hotfix).
- **Exhaustieve `when`-blokken.** Zowel `TrackerIssueFields.applying` als
  `PostgresTrackerClient.columnFor`/`columnValue` zijn exhaustief over `TrackerField`; de nieuwe
  waarde is in de *lifecycle*-groep gezet, naast `QUESTIONS_ALLOWED`. In `applying` valt een
  onbekende/lege waarde bewust terug op `false` (bij `questionsAllowed` is die fallback `true`) —
  een story mag nooit stilzwijgend een hotfix worden.
- **Aanmaakroutes.** `createStory` kreeg `hotfix: Boolean = false` als laatste parameter, zodat alle
  bestaande callers (en de handgeschreven testfakes) ongewijzigd blijven werken en de default
  automatisch "geen hotfix" is. Alle vier de routes geven de waarde door; `sf-story create` heeft nu
  een `--hotfix`-vlag inclusief usage-regel. `AuditGatewayAdapter.proposeStoryIfAny` geeft expliciet
  `hotfix = false` mee, zodat dat pad zichtbaar (en niet alleen via de default) veilig is.
- **UI.** De aanmaakdialoog heeft een `Hotfix`-schakelaar (`Key('create-story-hotfix')`, default
  uit) die `hotfix` in de POST-payload zet. Achteraf wijzigen op een bestaande story kan bewust
  niet — dat staat expliciet buiten scope.
- **Tests (zelf geschreven).**
  - `core/contracts/TrackerIssueFieldsTest.kt` (nieuw): default uit, String- én Boolean-invoer,
    fallback naar uit bij onbekende waarde, en dat de andere drie assen ongemoeid blijven.
  - `TrackerCapabilityPersistenceE2eTest`: twee nieuwe tests tegen een echte Postgres —
    createStory-round-trip (`hotfix = true` blijft true, default blijft false) en een
    `updateIssueFields`-round-trip die tegen `applying` wordt gespiegeld.
  - `TrackerStoryApiControllerTest`: default false én expliciet true op `POST /api/tracker/stories`
    (`FakeTrackerApi` kreeg daarvoor een `createStory`-override die de vlag registreert).
  - `BridgeRequestHandlerTest`: `story.create` zonder vlag → false, met `hotfix=true` → true.
  - `BridgeApiControllerTest` (dashboard-backend): `hotfix` wordt doorgezet, default false.
  - `stories_screen_test.dart`: bestaande aanmaaktest asserteert nu `hotfix == false`; nieuwe test
    zet de schakelaar aan en asserteert `hotfix == true` in de payload.
- **Specs bijgewerkt.** `docs/factory/functional-spec.md` (nieuwe "As 4 — Hotfix" bij de
  story-assen, plus de audit-alinea), `docs/factory/technical-spec.md` (kolom, migratie V33 en de
  vier aanmaakroutes) en `docs/factory/ux/screens/stories.md` (de schakelaar in de aanmaakdialoog).

Bewijs vangnet (05-08-2026, branch `ai/SF-1959`):

- `mvn -B --no-transfer-progress clean verify` vanaf de repo-root: **BUILD SUCCESS** in 4m44,
  0 failures / 0 errors over alle vijf modules.
- `flutter analyze` in `dashboard-frontend`: `No issues found!`.
- `flutter test`: 136 tests groen.
- `tools/audit-documentation`: `documentation-audit/v1: PASS`.

## SF-1960 — review (reviewer, 05-08-2026)

Gereviewd: volledige story-diff `git diff main...HEAD` (28 bestanden). Akkoord, geen blockers.

- Veld/kolom kloppen: `V33` is het eerstvolgende vrije nummer, `ADD COLUMN IF NOT EXISTS ...
  DEFAULT false` raakt bestaande rijen niet (AC 2). `hotfix` staat in `ISSUE_COLUMNS` en `mapRow`
  leest op naam, dus de plek in de kolomlijst is onschadelijk; de enige andere
  `INSERT INTO issues` (createSubtask) leunt op de kolomdefault.
- Alle vier de aanmaakroutes geven de vlag door en defaulten op `false`; `applying(HOTFIX, ...)`
  valt bewust terug op `false` (fail-safe, anders dan `questionsAllowed`), met testdekking op
  null/leeg/niet-boolean. `AuditGatewayAdapter` staat expliciet op `hotfix = false`.
- Gerichte hercontrole in de reviewsandbox (geen docker, dus geen Testcontainers-e2e):
  `mvn -B -pl factory-common,softwarefactory,dashboard-backend -am test
  -Dtest=TrackerIssueFieldsTest,TrackerStoryApiControllerTest,BridgeRequestHandlerTest,
  BridgeApiControllerTest,DashboardQueryServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
  → BUILD SUCCESS (39s); `flutter analyze` → No issues found; `flutter test
  test/screens/stories_screen_test.dart` → 3 groen; `tools/audit-documentation` → PASS.
  Werktree bleef schoon (o.a. `pubspec.lock` ongewijzigd).
- [info] `functional-spec.md` "As 4 — Hotfix" beschrijft al het overslaan van
  refine/plan/review/test/documentatie; dat gedrag landt pas in SF-1961 op deze zelfde
  story-branch, dus bij merge is de spec consistent met de code.
- [info] `TrackerTestState.fieldFor` kent nog steeds geen `"ApprovalMode"` (pre-existing, buiten
  scope van deze subtaak).

## SF-1961 — Hotfix-keten: SubtaskType/Phase, start-routing, developer-only handler (developer, 05-08-2026)

In eigen woorden: het veld uit SF-1960 krijgt nu gedrag. Een story met `Hotfix = true` slaat bij
`Story Phase = start` de refiner én planner over en krijgt exact drie subtaken: `hotfix`, `merge`,
`deploy`. De hotfix-subtaak is één DEVELOPER-run zonder reviewer en zonder mens-poort; merge en
deploy blijven volledig ongewijzigd.

Checklist:
[x]: `SubtaskType.HOTFIX("hotfix")` (incl. `fromTracker`)
[x]: `SubtaskPhase.HOTFIX_APPROVED("hotfix-approved")` + toegevoegd aan `isTerminal`
[x]: `hotfixSubtask`-handler in de handler-map van `SubtaskExecutionCoordinator`
[x]: `StoryRefinementCoordinator`: START-tak takt af op `hotfix = true` → `materializeFromSpecs`
[x]: `runtime` toegevoegd aan `allowedDependencies` in `pipeline/package-info.java` + module-matrix hergegenereerd
[x]: `case 'hotfix'` in `dashboard-frontend/lib/phase_stepper.dart`
[x]: unit- en e2e-tests zelf geschreven
[x]: docs (`functional-spec.md`, `technical-spec.md`, `ux/screens/stories.md`) bijgewerkt
[x]: volledig vangnet groen

Wat en waarom:

- **Fase en type.** `SubtaskPhase.HOTFIX_APPROVED` is een eigen terminale fase. Bewust géén
  `DEVELOPMENT_APPROVED` terminaal maken: dat is in een `development`-subtaak juist de overgang
  developer → reviewer binnen dezelfde subtaak, en terminaal maken zou die keten breken. Geen
  DB-migratie: `subtask_type`/`subtask_phase` zijn vrije `TEXT`-kolommen zonder CHECK.
- **Start-routing.** De `StoryPhase.START`-tak in `StoryRefinementCoordinator` splitst nu op
  `issue.fields.hotfix`. De refiner-dispatch is naar een eigen `dispatchRefiner(...)` getrokken zodat
  de andere twee refiner-fasen (`questions-answered`, `refined-rejected`) exact hetzelfde blijven
  doen. `startHotfixChain` gebruikt `SubtaskMaterializationApi.materializeFromSpecs` (het
  exact-list-pad dat bewust niets auto-toevoegt), zet daarna de eerste nog niet-terminale subtaak op
  `start` (alleen als z'n fase nog leeg is — idempotent) en de story op `in-progress`.
  `StoryPhase.START_NEXT` is niet aangeraakt.
- **Modulith-grens.** `pipeline` mocht `runtime` niet zien. Opgelost zoals de planner voorstelde:
  `runtime` (root, dus alleen de poort `SubtaskMaterializationApi`) toegevoegd aan
  `allowedDependencies`; cyclusvrij want `runtime` kent `pipeline` niet. `tools/generate-module-dependencies`
  opnieuw gedraaid (`docs/technical/module-dependencies.md`). Let op de val: een commentaarregel
  binnen de `{ ... }` van `@ApplicationModule` mag geen dubbele quotes bevatten — de generator leest
  álle quoted strings in dat blok als dependency en faalt dan op "moeten uniek en alfabetisch staan".
- **Injectie-seam.** `SubtaskMaterializationApi` is als laatste constructorparameter met default
  `null` toegevoegd, zodat de bestaande handmatige constructies (`OrchestratorTestHarness`,
  `StoryRefinementCoordinatorAutoStartTest`) ongewijzigd blijven; Spring injecteert de bean wél.
  Ontbreekt 'ie tóch, dan faalt de hotfix-start luid (Error op de story) i.p.v. stil.
- **Handler.** `hotfixSubtask` is de developer-flow zonder reviewer: `start`/
  `development-questions-answered` → dispatch DEVELOPER; `development-rejected` → loopback mét de
  eigen `[FACTORY VERIFICATION]`-comment als reden (hergebruik `developmentRejectedReason`);
  `developing` → recovery; `developed-with-questions` → bestaande `questionsOutcome`;
  `developed` → **onvoorwaardelijk** `hotfix-approved` (dus niet via `autoAdvanceSubtask`, want die
  leest `ApprovalMode`); `hotfix-approved` → `advanceSubtaskChain`.
- **HumanActionPolicy ongewijzigd.** De `developed`-goedkeuringsgate daar geldt alleen voor
  `subtaskType == "development"`; dat is nu met tests vastgelegd (hotfix op `developed` → geen gate,
  hotfix op `developed-with-questions` → wél een QUESTION-gate).
- **Testpoort/loopback is hergebruik.** `AgentCli` draait de `TesterVerificationRunner` op basis van
  de **rol** (DEVELOPER), niet van het subtaaktype — dus de hotfix-subtaak krijgt de deterministische
  poort en de bestaande cap `AI Max Developer Loopbacks` gratis. `MergeSubtaskHandler` en de
  deploy-subtaak zijn niet aangeraakt.

Tests (zelf geschreven):

- `pipeline/HotfixSubtaskFlowTest.kt` (nieuw, via `OrchestratorTestHarness`): start → DEVELOPER,
  `developed` → `hotfix-approved` ook bij goedkeuring=elke-stap en zonder enige andere dispatch,
  loopback op `development-rejected`, keten-advance + Done op `hotfix-approved`, "geen fase = niets
  doen", de spec-lijst is exact `[hotfix, merge, deploy]` met stabiele titels, en type/fase zijn uit
  de tracker leesbaar.
- `pipeline/StoryRefinementCoordinatorAutoStartTest.kt`: vier tests erbij voor de START-routing
  (materialisatie exact `[hotfix, merge, deploy]`, eerste subtaak op `start`, story `in-progress`,
  nooit `refining`/`planning`; ApprovalMode genegeerd; idempotent bij een al lopende subtaak; en een
  regressie dat een niet-hotfix-story níet in de hotfix-tak belandt).
- `pipeline/SubtaskPhaseTerminalTest.kt` + `core/contracts/HumanActionPolicyTest.kt` uitgebreid.
- `e2e/HotfixChainE2eTest.kt` (nieuw, echte Spring-app + Testcontainers): volledige flow `start` →
  hotfix → merge → deploy → story Done, met expliciete assertie dat er geen review-/test-/summary-/
  documentation-/manual-approve-subtaak ontstaat, nul reviewer-/tester-/summarizer-/documenter-/
  refiner-/planner-runs, en (machinaal) nul `refining`/`planning`-fases — bij
  `ApprovalMode = elke-stap`. Plus: vragen aan (vraag → antwoord → `hotfix-approved`), vragen uit
  (`[CLARIFICATION]`-error, merge blijft ongestart) en rode projecttests (nooit `hotfix-approved`,
  merge en deploy blijven ongestart, subtaak in Error op de loopback-cap).
- Testsupport: `AgentScript.developerVerificationFails` simuleert de verificatie-poort-override,
  `E2eTestBase.createStory(hotfix = ...)` en een `childOfType`-helper. `AwaitDsl.NON_AI_SUBTASK_TYPES`
  en `E2eTestBase.ENFORCED_SUBTASK_TYPES` konden ongewijzigd blijven: hotfix is een AI-subtaak en de
  hotfix-tests gebruiken `childOfType` i.p.v. `plannedChild`.
- AC 10 (niet-hotfix-story doorloopt exact de bestaande keten) is gedekt door de ongewijzigde
  `FullRefineToDevelopE2eTest` en `ChainCompositionE2eTest`.

Specs bijgewerkt: `docs/factory/functional-spec.md` (As 4 — Hotfix: wat de keten precies doet bij
groen/rood), `docs/factory/technical-spec.md` (nieuwe paragraaf "Hotfix-keten (SF-1959)" met
subtaaktype, fase, routing, modulith-grens en de hergebruikte testpoort) en
`docs/factory/ux/screens/stories.md` (de drie subtaken + de eigen stepper-tak).
- `dashboard-frontend/test/phase_stepper_test.dart` (nieuw): de hotfix-tak toont één "Hotfix"-stap
  (geel bij `developing`, groen bij `hotfix-approved`, grijs zonder fase) en een onbekend
  subtaaktype valt nog steeds in de default-tak.

Bewijs vangnet (05-08-2026, branch `ai/SF-1959`, SF-1961):

- `mvn -B --no-transfer-progress clean verify` vanaf de repo-root: **BUILD SUCCESS** in 4m54,
  0 failures / 0 errors over alle vijf modules (waaronder `HotfixChainE2eTest` 4/4 en
  `HotfixSubtaskFlowTest` 7/7).
- `flutter analyze` in `dashboard-frontend`: `No issues found!`; `flutter test`: 140 tests groen
  (`pubspec.lock` ongewijzigd).
- `tools/audit-documentation`: `documentation-audit/v1: PASS`.
