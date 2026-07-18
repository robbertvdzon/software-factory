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
- `mvn verify` (repo-root): zie laatste commit-log/CI-run voor het exacte resultaat
  (wordt hieronder aangevuld na afronding van deze run).
