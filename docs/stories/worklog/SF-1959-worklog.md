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
