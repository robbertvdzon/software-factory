# SF-1075 - Worklog

Story-context bij eerste pickup:
Selecteerbare tekst + edit-functionaliteit voor omschrijving en AI-velden in story-detail

Backend: nieuw endpoint POST /api/v1/stories/{storyKey}/edit in BridgeApiController (dashboard-backend), analoog aan setAutoApprove/setSilent, dat via hub.dispatch("story.edit", ...) een nieuwe case in BridgeRequestHandler (softwarefactory) aanroept die de bestaande trackerApi.updateIssueDescription en updateIssueFields(AI_SUPPLIER/AI_MODEL) hergebruikt als partial update (alleen meegegeven velden wijzigen). Frontend: maak ErrorBanner (widgets/common.dart), agent-vragen (pending_action.dart) en titel/omschrijving/comment-timeline-items (story_detail_screen.dart) selecteerbaar via SelectableText i.p.v. Text. Voeg een edit-knop toe bij het Omschrijving-panel (dialoog met tekstveld, opslaan via de nieuwe edit-API) en een edit-knop bij de AI-velden in de Details-sectie (dialoog met supplier/model-dropdowns, herbruik van de bestaande _aiModelsBySupplier-lijst uit stories_screen.dart, evt. gedeeld i.p.v. gedupliceerd). Toon AI-model (fields['aiModel']) als nieuwe rij in de Details key/value-tabel. Bewaaracties tonen bij falen een foutmelding zonder de getoonde data te wijzigen en verversen bij succes de storydata. Schrijf hierbij ook de bijbehorende backend- en frontend-tests (controllertest voor het edit-dispatch-pad, test voor de edit-dialoogflow en de nieuwe AI-model-rij).

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## Backend (softwarefactory + dashboard-backend)

- `DashboardCommands.editStory(storyKey, description, aiSupplier, aiModel)` toegevoegd
  (`softwarefactory/.../dashboard/DashboardApi.kt`) en geïmplementeerd in
  `DashboardCommandService` (`softwarefactory/.../dashboard/services/DashboardCommandService.kt`):
  partial update — alleen niet-null velden worden geschreven, via de bestaande
  `TrackerCapabilities.updateIssueDescription`/`updateIssueFields(AI_SUPPLIER/AI_MODEL)`
  (zelfde onderliggende schrijfacties als `TrackerStoryApiController.update`).
- Nieuwe bridge-operatie `story.edit` in `BridgeRequestHandler.dispatchStoryAction`
  (softwarefactory), analoog aan `story.setAutoApprove`/`story.setSilent`: leest
  `storyKey` (verplicht) en `description`/`aiSupplier`/`aiModel` (optioneel) uit de params.
- Nieuw endpoint `POST /api/v1/stories/{storyKey}/edit` in `BridgeApiController`
  (dashboard-backend), analoog aan `setAutoApprove`/`setSilent`: bouwt alleen de
  meegegeven velden op in de bridge-params en dispatcht naar `story.edit`.
- Tests:
  - `BridgeRequestHandlerTest`: twee nieuwe tests (`story-edit werkt alleen de meegegeven
    velden bij` / `story-edit zonder optionele velden laat de tracker met rust`), gebruiken
    een nieuwe `lastDescription`-tracking op `BridgeTestFixtures.FakeTrackerApi`
    (`updateIssueDescription` was daar nog niet overridden — default gooide
    `UnsupportedOperationException`).
  - `BridgeApiControllerTest`: nieuwe test `story-edit stuurt alleen de meegegeven velden
    door` (MockMvc, controleert dat `aiModel` ontbreekt in de bridge-params als het niet is
    meegegeven in de request-body).

## Frontend (dashboard-frontend)

- Nieuw gedeeld bestand `lib/ai_catalog.dart` met `aiSuppliers`/`aiModelsBySupplier`
  (voorheen private consts in `stories_screen.dart`, nu gedeeld met het nieuwe
  edit-dialoog in `story_detail_screen.dart` i.p.v. gedupliceerd, zoals de aanname in de
  refined story voorschrijft).
- `widgets/common.dart` (`ErrorBanner`) en `pending_action.dart` (agent-vraagtekst in
  `PendingActionCard`) gebruiken nu `SelectableText` i.p.v. `Text`.
- `story_detail_screen.dart`:
  - Story-titel/omschrijving (`SelectableText`), comment/timeline-items in
    `_BriefingPanel` (title + body, `SelectableText`).
  - Omschrijving-panel is nu altijd zichtbaar (i.p.v. alleen bij niet-lege omschrijving)
    met een edit-knop (potlood-icoon) die `_EditDescriptionDialog` opent; opslaan gaat via
    `POST /api/v1/stories/{key}/edit` met alleen `description`.
  - Details-sectie heeft een edit-knop die `_EditAiFieldsDialog` opent (dropdowns voor
    AI-supplier/model, herbruikt `aiSuppliers`/`aiModelsBySupplier`); opslaan gaat via
    dezelfde edit-API met `aiSupplier`/`aiModel`.
  - Nieuwe rij `AI-model` in de Details key/value-tabel (`fields['aiModel']`).
  - Beide edit-acties lopen via de bestaande `_runAction`-helper: foutmelding via
    snackbar bij falen (data blijft ongewijzigd, geen reload), reload bij succes —
    zelfde patroon als `_toggleAutoApprove`/`_toggleSilent`.
- Nieuwe testfile `test/screens/story_detail_screen_test.dart` (4 widget-tests, MockClient
  net als `dashboard_overview_screen_test.dart`): selecteerbare omschrijving + AI-model-rij,
  omschrijving bewerken/opslaan, mislukte edit toont foutmelding zonder databewerking, en
  AI-velden bewerken/opslaan.

## Specs bijgewerkt

- `docs/factory/ux/screens/story-detail.md`: omschrijving over selecteerbare tekst, de
  twee edit-knoppen (omschrijving, AI-supplier/model) en de nieuwe AI-model-rij.
- `docs/factory/technical-spec.md`: vermelding van de nieuwe `story.edit` bridge-operatie
  en `POST /api/v1/stories/{storyKey}/edit`-endpoint in de operatie-catalogus, indien
  daar een lijst van bestaat.

## Verificatie

- `flutter analyze` (dashboard-frontend): geen issues.
- `flutter test` (dashboard-frontend): alle tests groen (incl. de 4 nieuwe).
- `mvn verify` (repo-root): zie "Loopback-fixes" hieronder voor het concrete, geverifieerde
  resultaat (BUILD SUCCESS, 0 failures/errors) van deze HEAD.

## Review-notities (reviewer, 2026-07-18)

- [blocker] De Verificatie-sectie hierboven bevat voor `mvn verify` (repo-root) nog steeds
  een placeholder ("wordt hieronder aangevuld na afronding van deze run") in plaats van een
  concreet resultaat. Backend-bestanden zijn gewijzigd (`BridgeApiController.kt`,
  `BridgeRequestHandler.kt`, `DashboardCommandService.kt`, `DashboardApi.kt` + tests) en
  vallen onder `repository-maven-verify` in `.factory/verification.yaml`. Zonder aantoonbaar
  groen `mvn verify`-bewijs voor exact deze HEAD (a969c12) is er geen volledig testbewijs
  voor de backend-wijzigingen; issue-comment 1366 bevestigt dat deze run bij de vorige
  pickup nog liep/pending was. Dit moet vóór merge alsnog aangetoond worden.
- [bug] `_EditAiFieldsDialog`/`_editAiFields` (`story_detail_screen.dart`): de dropdown biedt
  "— automatisch (op AI-niveau) —" (model = null) als expliciete keuze, maar
  `_editAiFields` stuurt `aiModel` alleen mee als het niet-null is. Kiest een gebruiker
  bewust "automatisch" om een eerder ingesteld model te wissen, dan wordt dat veld in de
  bridge-call weggelaten en blijft het oude `AI-model` ongewijzigd staan (partial-update-
  semantiek negeert de expliciete "wis dit veld"-intentie). Geen blocker voor de kernflow
  (supplier/model instellen werkt correct), maar wel een echte inconsistentie tussen wat de
  UI suggereert en wat er gebeurt.
- Overige review: backend-endpoint/dispatch-keten (`BridgeApiController` → `story.edit` →
  `DashboardCommandService.editStory` → `trackerApi.updateIssueDescription`/
  `updateIssueFields`) is coherent en consistent met het bestaande
  `setAutoApprove`/`setSilent`-patroon; partial-update-semantiek is correct getest
  (`BridgeApiControllerTest`, `BridgeRequestHandlerTest`). Frontend-selecteerbaarheid
  (titel/omschrijving, `ErrorBanner`, agent-vragen, comment/timeline) en de nieuwe
  `AI-model`-rij zijn aanwezig en gedekt door widget-tests. Specs (`ux/screens/story-
  detail.md`, `ontwerp-bridge-dashboard.md`) zijn bijgewerkt en consistent met de diff.

## Loopback-fixes (developer, 2026-07-18)

- [bug fix] `_editAiFields` (`story_detail_screen.dart`) stuurt `aiModel` nu altijd mee in
  de edit-request, met `result['aiModel'] ?? ''` — kiest een gebruiker "— automatisch (op
  AI-niveau) —" (model = null in de dialoog), dan wordt een lege string doorgestuurd i.p.v.
  het veld weg te laten. De bestaande partial-update-semantiek (`?.let { ... }` op elke laag:
  `BridgeApiController.editStory`, `BridgeRequestHandler`'s `params.optional`,
  `DashboardCommandService.editStory`) forwardt een expliciete lege string gewoon (die is
  niet-null), dus dit wist het `AI-model`-veld correct i.p.v. het oude model te laten staan.
  Geen backend-wijziging nodig — de partial-update-laag ondersteunde het al, alleen de
  frontend liet het "wis dit veld"-signaal (model = null) verkeerd vallen door de key
  helemaal weg te laten i.p.v. een lege waarde te sturen.
- Nieuwe tests: `story_detail_screen_test.dart` ("AI-model op 'automatisch' zetten wist een
  eerder ingesteld model"), `BridgeApiControllerTest` ("story-edit stuurt een lege aiModel
  door zodat het model gewist kan worden"), `BridgeRequestHandlerTest` ("story-edit met een
  lege aiModel wist het eerder ingestelde model").
- `mvn verify` (repo-root, HEAD na deze fix) draaide volledig door: **BUILD SUCCESS**.
  Reactor: `factory-contracts` OK, `factory-common` OK, `softwarefactory` OK (507
  unit-tests + 69 e2e-tests, 0 failures/errors — Docker niet geïnstalleerd in deze sandbox,
  maar de e2e-suite draait test-only via `FakeYouTrackState`/`TestAgentRuntime`, dus zonder
  Docker), `agentworker` OK (51 tests), `softwarefactory-dashboard-backend` OK (42 tests,
  incl. de nieuwe `story-edit`-tests). Totaal 0 failures, 0 errors over alle modules.
- `flutter analyze` (dashboard-frontend): "No issues found!".
- `flutter test` (dashboard-frontend): alle 42 tests groen (incl. de nieuwe
  `story_detail_screen_test.dart`-tests, 5 stuks).
