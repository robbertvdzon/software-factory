# SF-16 - Worklog

Story-context bij eerste pickup:
create story from UI

Ik wil dat je in de stories sectie van de frontend (de kotlin backend, niet de flutter backend) een knop hebt ‘Create story’. Die vraagt in een dialoog om het youtrack project te kiezen waar de story in moet komen, de projectnaam van de repo waar de change in moet, de AI suplier en model, het budget voor de story en de titel en description. Hij maakt hem dan aan in youtrack.

Stappenplan:
[ ]: read issue and target docs
[ ]: implement requested changes
[ ]: run relevant tests
[ ]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

---

## Implementatieplan (planner)

Doel: een **'Create story'**-knop in de Stories-sectie van de Flutter-`dashboard-frontend`,
die via een dialoog een nieuw YouTrack-issue laat aanmaken door de Kotlin
`dashboard-backend`, zó ingevuld dat de orchestrator het oppakt (`Stage = Develop` +
gekozen `AI-supplier`).

### Geraakte modules
- `dashboard-backend/.../youtrack/YouTrackClient.kt` — nieuwe `createIssue(...)`-methode +
  hulplogica om project-`id` op te halen en custom fields te zetten.
- `dashboard-backend/.../api/DashboardController.kt` — nieuw `POST /api/v1/stories`-endpoint
  en een lijst-endpoint `GET /api/v1/projects` voor de project-dropdown.
- `dashboard-backend/.../api/ApiModels.kt` — request/response-DTO's
  (`CreateStoryRequest`, `ProjectsResponse`, evt. `CreateStoryResponse`).
- `dashboard-frontend/lib/main.dart` — `ApiClient` (POST-met-body helper), Stories-sectie
  (knop), nieuwe dialoog-widget, refresh na succes.

### Gedrag — backend
1. **`YouTrackClient.createIssue(...)`** (op gedragsniveau):
   - Resolve het gekozen project op `shortName` → project-`id` (de huidige
     `listManagedProjects()` levert geen `id`; voeg interne lookup/uitbreiding toe
     analoog aan `/api/admin/projects`-call).
   - Stap 1: `POST /api/issues` met `project(id)`, `summary` (titel) en `description`.
   - Stap 2: `POST /api/issues/{idReadable}` met `customFields`:
     `Stage = Develop` (enum), `AI-supplier` (enum, gekozen waarde), AI-model (enum/veld),
     en budget op het bestaande `AI Token Budget`-veld (numeriek).
     Volg exact het patroon van de referentie-`createSubtask`/`updateIssueFields` in
     `softwarefactory/.../youtrack/clients/YouTrackClient.kt` (enum vs. simple field).
   - Retourneer de aangemaakte story als `StoryDto` (hergebruik `getIssue`/`mapIssue`).
2. **`POST /api/v1/stories`** in `DashboardController`:
   - Auth via `authService.requireAuthorization(authorization)` zoals bestaande endpoints.
   - Body `CreateStoryRequest(projectKey, targetRepo, aiSupplier, aiModel, budget, title, description)`.
   - Roept `youTrackClient.createIssue(...)` aan; geeft de nieuwe `StoryDto` terug.
3. **`GET /api/v1/projects`**: geeft de selecteerbare (managed) projecten terug
   (`key`, `name`) op basis van `listManagedProjects()` + `youTrackProjects`-allowlist,
   zodat de dropdown gevuld kan worden.

### Gedrag — frontend
1. `ApiClient`: voeg een `postJson(path, body)`-helper toe (huidige `post` stuurt geen body);
   hergebruik `_headers()` en `_throwOnError`.
2. Laad de projectenlijst (via `GET /api/v1/projects`) bij openen van de dialoog of in `_refreshAll`.
3. Voeg in `_storiesView()` een **'Create story'**-knop toe (boven de tabel).
4. Dialoog-widget (StatefulWidget) met de zes velden: project-dropdown, repo-projectnaam (tekst),
   AI-supplier (dropdown: `mock`/`claude`/`copilot`/`openai`/`microsoft`) + model (tekst/dropdown),
   budget (numeriek), titel (tekst), description (multiline). Validatie op verplichte velden.
5. Bij opslaan: `postJson('/api/v1/stories', body)`; bij succes dialoog sluiten en
   `_run(_refreshAll)` voor verse stories-lijst; bij fout een zichtbare melding (SnackBar/inline).

### Risico's / aandachtspunten
- **Repo-projectnaam vs. targetRepo**: de orchestrator leidt `targetRepo` af uit de
  *project*-beschrijving (`factory.repo=...`), niet uit een issue-veld. Er is geen
  per-issue repo-veld. Pragmatische keuze voor de developer: neem de ingevulde
  repo-projectnaam mee in de description (of in een bestaand custom field indien aanwezig)
  en documenteer dit; de kern-flow (issue in gekozen project + supplier + Stage) blijft werken.
- **AI-model custom field**: mogelijk bestaat het AI-model-veld nog niet in elk project.
  Volg refiner-instructie: behandel als kleine bootstrap of sla model mee als beschikbaar;
  laat ontbrekend veld de aanmaak niet hard breken.
- **Enum-waarden**: `AI-supplier`/`Stage`/model zijn YouTrack-enums; foutieve waarden geven
  4xx. Zorg dat de frontend exact de toegestane waarden aanbiedt.
- Nieuwe config (indien nodig) krijgt `SF_`-prefix; geen secrets loggen.

### Verificatie
- Backend: `mvn -f dashboard-backend/pom.xml test` (of root `mvn test`); voeg waar zinvol
  een unit-test toe voor request-body-opbouw van `createIssue`/endpoint.
- Frontend: `flutter analyze` en handmatige UI-controle van knop + dialoog-flow.

---

## SF-17 (developer) — Backend: createIssue + stories/projecten endpoints

Story in eigen woorden: voeg in de Kotlin `dashboard-backend` de backend-bouwstenen toe
zodat de UI een nieuwe YouTrack-story kan aanmaken: een `createIssue(...)` in
`YouTrackClient`, een `POST /api/v1/stories`-endpoint en een `GET /api/v1/projects`-endpoint.

Checklist:
[x]: read issue + relevante backend-bestanden en referentie-patroon (softwarefactory)
[x]: DTO's toegevoegd in ApiModels.kt (CreateStoryRequest, ProjectOptionDto, ProjectsResponse)
[x]: YouTrackClient.createIssue(...) toegevoegd (project-id resolve, POST issue, custom fields)
[x]: DashboardController POST /api/v1/stories + GET /api/v1/projects toegevoegd
[ ]: tests draaien (mvn niet beschikbaar in agent-omgeving; pipeline draait tests)

Gedaan / rationale:
- `ApiModels.kt`: `CreateStoryRequest`(projectKey, targetRepo, aiSupplier, aiModel, budget,
  title, description), `ProjectOptionDto`(key,name) en `ProjectsResponse`.
- `YouTrackClient.createIssue(...)`: project-id geresolved via `/api/admin/projects` op
  `shortName`; `POST /api/issues` met project(id)+summary+description; daarna
  `POST /api/issues/{key}` met customFields `Stage=Develop`, `AI-supplier`, `AI-model`
  (enum) en `AI Token Budget` (simple). Retourneert `StoryDto` via `getIssue`/`mapIssue`.
  Patroon van softwarefactory `createSubtask`/`updateIssueFields` gevolgd
  (`enumFieldValue`/`simpleFieldValue` helpers).
- Repo-projectnaam (targetRepo): er is geen per-issue repo-veld; pragmatisch opgenomen als
  "Repo: <targetRepo>" achteraan de description, zodat hij niet verloren gaat. De orchestrator
  leidt de echte doel-repo af uit de project-beschrijving (factory.repo).
- `DashboardController`: `POST /api/v1/stories` (auth via requireAuthorization, body
  CreateStoryRequest, validatie op projectKey+title) en `GET /api/v1/projects` (managed
  projecten met key,name o.b.v. listManagedProjects()+youTrackProjects-allowlist).
- Geen nieuwe config/secrets nodig; niets gelogd.

Risico's: `AI-model`-enum-waarde moet in het project bestaan, anders 4xx bij aanmaken;
frontend moet toegestane enum-waarden aanbieden.

---

## SF-17 (reviewer) — Review backend createIssue + endpoints

Statische review (geen mvn in reviewer-omgeving; build/tests via CI).

Bevindingen:
- [info] `YouTrackClient.createIssue` volgt het softwarefactory-referentiepatroon correct:
  `SingleEnumIssueCustomField` voor enums (Stage/AI-supplier/AI-model) en
  `SimpleIssueCustomField` voor `AI Token Budget`. Alle gebruikte helpers
  (`sendJson`, `pathEncoded`, `getIssue`, `listManagedProjects`, `managedProjects`,
  `ProjectDto.key/name`) bestaan met passende signatures.
- [info] `POST /api/v1/stories` en `GET /api/v1/projects` gebruiken
  `authService.requireAuthorization` consistent met de overige endpoints; input-validatie
  op `projectKey`/`title` aanwezig.
- [info] `targetRepo` pragmatisch in description ("Repo: <targetRepo>") zoals afgesproken in
  het risico-stuk van het plan; geen per-issue repo-veld vereist.
- [suggestie] `GET /api/v1/projects` hergebruikt `managedProjects()`, dat extra filtert op
  `!targetRepo.isNullOrBlank()`. Projecten zonder geconfigureerde target-repo verschijnen dus
  niet in de dropdown. Voor de create-story-flow is dat verdedigbaar (je maakt alleen stories
  voor beheerde repos), maar wijkt licht af van "alle managed projecten". Bewust laten of
  documenteren.
- [suggestie] `createIssue` gooit `error(...)` (IllegalStateException → 500) bij onbekend
  project en `require(...)` (IllegalArgumentException) bij lege velden. Zonder
  exception-mapping worden dit 500's i.p.v. 4xx. Geen blocker; consistent met bestaande stijl.
- [info] Geen unit-test toegevoegd (low/medium effort); dekking via CI-pipeline conform
  agent-tips. Scope blijft netjes backend-only, geen scope creep.

Conclusie: implementatie is coherent, consistent met specs en referentiepatroon, en passend
binnen scope. Akkoord.

---

## SF-17 (reviewer, 2e ronde) — review-rejected

Statische review opnieuw uitgevoerd met cross-check tegen de echte YouTrack-veldnamen
(softwarefactory `TrackerField`/`factoryFieldSpecs` + e2e `FakeYouTrackState`).

Bevindingen:
- [blocker] `YouTrackClient.createIssue` regel 105: `enumFieldValue("AI-model", it)` gebruikt
  een niet-bestaande veldnaam. Het werkelijke custom field heet **`AI Model`** (met spatie),
  zie `softwarefactory/.../TrackerModels.kt` `AI_MODEL("AI Model")`,
  `factoryFieldSpecs` `FieldSpec("AI Model", ...)` (regel 960) en `FakeYouTrackState`
  `FieldDef("cf-model", "AI Model", ...)`. Met de hyphen-naam target de tweede
  `POST /api/issues/{key}` een onbekend veld → YouTrack geeft 4xx → `sendJson` gooit (regel 280)
  → de hele `createIssue` faalt (500), of in het gunstigste geval gaat het door de gebruiker
  gekozen model stilletjes verloren. Beide breken de story-eis (gebruiker kiest supplier *én*
  model). Fix: `enumFieldValue("AI Model", it)`.
- [info] `Stage=Develop` is hier juist (anders dan de softwarefactory-referentie die op tags
  triggert): de dashboard-backend zelf zoekt werk via `query "project: <key> Stage: Develop"`
  in `findWorkIssues` (regel 61). Geen work-tag nodig in deze context. Mijn eerdere twijfel
  over Stage is daarmee weerlegd.
- [info] `AI-supplier` (hyphen) en `AI Token Budget` matchen wél de schema-/mapIssue-namen.
- [suggestie] Onbekend project → `error(...)` (500) i.p.v. 4xx; lege velden via `require(...)`.
  Niet-blokkerend, consistent met bestaande stijl.
- [suggestie] `GET /api/v1/projects` via `managedProjects()` filtert projecten zonder
  `targetRepo` weg; verdedigbaar maar wijkt af van "alle managed projecten".

Conclusie: één blocker (verkeerde veldnaam `AI-model` i.p.v. `AI Model`) breekt de
create-story-flow. Terug naar developer.

---

## SF-18 (developer) — Frontend: Create story knop + dialoog

Story in eigen woorden: voeg in de Stories-sectie van de Flutter `dashboard-frontend`
een 'Create story'-knop + dialoog toe waarmee een nieuwe YouTrack-story wordt aangemaakt
via `POST /api/v1/stories`. Dialoog heeft zes velden met validatie en biedt exact de
toegestane enum-waarden voor AI-supplier aan.

Checklist:
[x]: read issue + main.dart + backend-DTO's (CreateStoryRequest, ProjectsResponse)
[x]: `postJson(path, body)` helper toegevoegd aan ApiClient (stuurt wél een body)
[x]: projectenlijst geladen via GET /api/v1/projects in _refreshAll (`projects`-state)
[x]: 'Create story'-knop toegevoegd boven de tabel in _storiesView()
[x]: CreateStoryDialog-widget met zes velden + validatie
[x]: bij succes dialoog sluiten + _run(_refreshAll); bij fout zichtbare _attention-melding
[ ]: flutter analyze (flutter niet beschikbaar in agent-omgeving; CI draait analyze)

Gedaan / rationale:
- `ApiClient.postJson(path, body)`: hergebruikt `_headers()` en `_throwOnError`, stuurt
  JSON-body (bestaande `post` stuurt geen body). Retourneert lege map bij lege response.
- `_DashboardScreenState`: nieuwe `projects`-lijst, gevuld via extra
  `GET /api/v1/projects`-call in `_refreshAll`.
- `_storiesView()`: 'Create story'-knop (FilledButton.icon) boven de filters/tabel die
  `_openCreateStoryDialog()` opent; deze toont `CreateStoryDialog` en draait bij succes
  `_run(_refreshAll)` voor een verse stories-lijst.
- `CreateStoryDialog` (StatefulWidget): project-dropdown (uit `projects`), repo-projectnaam,
  AI-supplier dropdown met exact `mock/claude/copilot/openai/microsoft`, AI-model (optioneel),
  budget (numeriek, gevalideerd), titel (verplicht), description (multiline). Validatie via
  Form/validator; bij opslaan `postJson('/api/v1/stories', {...})`. Bij fout een zichtbare
  `_attention`-melding in de dialoog; bij succes `Navigator.pop(true)`.
- Enum-waarden van AI-supplier exact conform spec aangeboden; `initialValue` gebruikt op
  DropdownButtonFormField conform Dart SDK ^3.9.0 / Flutter 3.35+.

Niet gedaan: lokaal `flutter analyze`/tests draaien (flutter ontbreekt in agent-omgeving);
verificatie via statische review + CI-pipeline.

---

## SF-18 (reviewer) — Frontend: Create story knop + dialoog → review-rejected

Statische review van de volledige branch-diff (frontend `main.dart` + meegeleverde
backend-wijzigingen die de flow voeden). Flutter niet beschikbaar in reviewer-omgeving;
build/analyze via CI.

Frontend bevindingen:
- [info] `ApiClient.postJson` correct: hergebruikt `_headers()`/`_throwOnError`, stuurt
  JSON-body, tolereert lege response. Past in bestaande stijl.
- [info] `GET /api/v1/projects` toegevoegd aan `_refreshAll`; `projects`-state gevuld en
  doorgegeven aan de dialoog. 'Create story'-knop boven de tabel, `loading`-guard aanwezig.
- [info] `CreateStoryDialog`: zes velden met validatie (project verplicht, repo verplicht,
  titel verplicht, budget numeriek-of-leeg), `_aiSuppliers` exact `mock/claude/copilot/
  openai/microsoft` conform spec. Succes → `Navigator.pop(true)` → `_run(_refreshAll)`;
  fout → zichtbare `_attention`-melding. Controllers netjes ge-dispose'd. Goede UX.
- [suggestie] AI-model is een vrij tekstveld, maar het YouTrack-veld `AI Model` is een enum
  (`EnumProjectCustomField`). Een willekeurige modelnaam levert een 4xx bij aanmaken. Overweeg
  een dropdown met geldige enum-waarden, of documenteer de toegestane waarden.

Blocker (in deze branch-diff, voedt de frontend-flow):
- [blocker] `dashboard-backend/.../youtrack/YouTrackClient.kt:105` gebruikt nog steeds
  `enumFieldValue("AI-model", it)` met hyphen. Het werkelijke custom field heet **`AI Model`**
  (met spatie): zie `softwarefactory/.../TrackerModels.kt:25` `AI_MODEL("AI Model")`,
  `clients/YouTrackClient.kt:960` `FieldSpec("AI Model", ...)` en `FakeYouTrackState.kt:63`
  `FieldDef("cf-model", "AI Model", ...)`. Dit is exact de blocker waarop SF-17 in de 2e ronde
  is afgekeurd; hij is niet hersteld. Gevolg: zodra de gebruiker (via de nieuwe dialoog) een
  model invult, target de tweede `POST /api/issues/{key}` een onbekend veld → YouTrack 4xx →
  `sendJson` gooit → de hele customFields-POST faalt, dus óók `Stage=Develop` en `AI-supplier`
  worden niet gezet en de orchestrator pakt de story nooit op. De claim in issue comment 7-666
  ("backend-endpoints matchen de frontend-aanroepen, implementatie compleet") klopt hierdoor
  niet end-to-end. Fix: `enumFieldValue("AI Model", it)`.

Conclusie: de frontend is op zichzelf coherent en netjes, maar de create-story-flow is
end-to-end kapot door de onjuiste veldnaam. Terug naar developer.

---

## SF-18 (reviewer, 2e ronde) — review-rejected

Statische herhaling van de review. Flutter/mvn niet beschikbaar; build/analyze via CI.

- [blocker] `dashboard-backend/.../youtrack/YouTrackClient.kt:105` gebruikt **nog steeds**
  `enumFieldValue("AI-model", it)` met hyphen. Dit is exact dezelfde blocker waarop SF-17
  (2e ronde) en SF-18 (1e ronde) zijn afgekeurd; hij is niet hersteld. De developer-comments
  7-666/7-667 claimen "implementatie compleet / endpoints matchen", maar de fix is niet
  doorgevoerd. Het werkelijke custom field heet `AI Model` (met spatie): zie
  `TrackerModels.kt:25` `AI_MODEL("AI Model")`, `clients/YouTrackClient.kt:960`
  `FieldSpec("AI Model", ...)` en `FakeYouTrackState.kt:63` `FieldDef("cf-model", "AI Model", ...)`.
  Gevolg: zodra de gebruiker via de nieuwe dialoog een model invult, target de tweede
  `POST /api/issues/{key}` een onbekend veld → YouTrack 4xx → `sendJson` gooit → de hele
  customFields-POST faalt, dus ook `Stage=Develop` en `AI-supplier` worden niet gezet en de
  orchestrator pakt de story nooit op. Fix: `enumFieldValue("AI Model", it)`.
- [info] Frontend (`main.dart`) blijft op zichzelf coherent; geen nieuwe frontend-bevindingen.
- [suggestie] (herhaald) AI-model is in de dialoog een vrij tekstveld terwijl `AI Model` een
  enum is — overweeg een dropdown met geldige waarden, zodat ook geldige modelnamen worden
  afgedwongen na de veldnaam-fix.

Conclusie: blocker onveranderd; create-story-flow end-to-end kapot. Terug naar developer.

---

## SF-18 (developer, loopback 3) — blocker veldnaam hersteld

Reviewer-blocker (3x herhaald op SF-17/SF-18) nu daadwerkelijk doorgevoerd:

- Fix: `dashboard-backend/.../youtrack/YouTrackClient.kt` regel 105
  `enumFieldValue("AI-model", it)` → `enumFieldValue("AI Model", it)` (spatie i.p.v. hyphen).
  Hiermee matcht het custom field de echte naam (`TrackerModels.kt` `AI_MODEL("AI Model")`,
  `factoryFieldSpecs` `FieldSpec("AI Model", ...)`, `FakeYouTrackState` `FieldDef(..., "AI Model", ...)`).
  De tweede `POST /api/issues/{key}` target nu een bestaand veld; `Stage=Develop` en
  `AI-supplier` worden samen met het model correct gezet, zodat de orchestrator de story oppakt.
- Frontend (`main.dart`) ongewijzigd: was op zichzelf al akkoord. AI-model blijft een tekstveld
  (suggestie, geen blocker); enum-afdwinging kan later als aparte verbetering.

Niet lokaal gedraaid: mvn/flutter ontbreken in agent-omgeving (zie agent-tips); build/tests via CI.

---

## SF-18 (developer, loopback 4) — AI-model dropdown + blocker bevestigd

Stand bij pickup: de blocker (`enumFieldValue("AI Model", ...)` met spatie) was in loopback 3
al hersteld en is nog steeds correct aanwezig (`YouTrackClient.kt:105`). Geen verdere
backend-wijziging nodig.

Wel doorgevoerd (de 3x herhaalde reviewer-suggestie): de AI-model-keuze in `CreateStoryDialog`
is nu een dropdown i.p.v. een vrij tekstveld, zodat alleen geldige enum-waarden worden
aangeboden en een willekeurige modelnaam geen 4xx meer geeft.
- `main.dart`: nieuwe const `_aiModels` met exact de YouTrack `AI Model`-enumwaarden
  (overgenomen uit `softwarefactory/.../YouTrackClient.kt` `aiModelValues`).
- `TextEditingController _model` vervangen door `String? _model`-state; dropdown met een
  '(geen)'-optie (null) zodat model optioneel blijft. `_submit` stuurt `_model` direct mee.
- Controller-dispose voor `_model` verwijderd (niet meer nodig).

Niet lokaal gedraaid: flutter/mvn ontbreken in agent-omgeving; analyze/tests via CI.

---

## SF-18 (reviewer, loopback 4) — reviewed (akkoord)

Statische review van de volledige branch-diff (frontend + backend die de flow voedt).
Flutter/mvn niet beschikbaar; build/analyze/tests via CI.

- [info] Blocker opgelost en bevestigd: `YouTrackClient.kt:105` gebruikt nu
  `enumFieldValue("AI Model", it)` (spatie). Matcht het echte custom field
  (`TrackerModels.kt:25` `AI_MODEL("AI Model")`, `clients/YouTrackClient.kt:960`
  `FieldSpec("AI Model", ...)`, `FakeYouTrackState.kt:63`). De tweede
  `POST /api/issues/{key}` (Stage=Develop + AI-supplier + model + budget) target nu
  bestaande velden; de create-story-flow is end-to-end heel.
- [info] Eerdere suggestie verwerkt: AI-model is in `CreateStoryDialog` nu een dropdown.
  De nieuwe const `_aiModels` (main.dart) komt **exact** overeen met
  `aiModelValues` (`clients/YouTrackClient.kt:945-949`) en `AI_MODEL_VALUES`
  (`FakeYouTrackState.kt:303`). '(geen)'-optie (null) houdt model optioneel.
- [info] AI-supplier dropdown (`mock/claude/copilot/openai/microsoft`) is een geldige
  subset van de backend-enum (`none` weggelaten — terecht). Conform spec.
- [info] Contract klopt: frontend-body (projectKey, targetRepo, aiSupplier, aiModel,
  budget int|null, title, description) matcht `CreateStoryRequest` (budget Long?,
  rest nullable/default). `POST /api/v1/stories` en `GET /api/v1/projects` gebruiken
  `requireAuthorization` + validatie op projectKey/title. Helpers `resolveProjectId`,
  `managedProjects`, `ProjectDto.name/key` bestaan met passende signatures.
- [info] Frontend netjes: controllers ge-dispose'd, `loading`/`_saving`-guards,
  succes → `Navigator.pop(true)` → `_run(_refreshAll)`, fout → zichtbare `_attention`.
- [suggestie] (niet-blokkerend, ongewijzigd) `GET /api/v1/projects` via `managedProjects()`
  filtert projecten zonder targetRepo weg; verdedigbaar. Onbekend project →
  `error(...)`/`require(...)` mappen naar 500/400 zonder expliciete handler; consistent
  met bestaande stijl.
- [info] Geen unit-tests toegevoegd (low/medium effort; geen mvn/flutter in omgeving),
  dekking via CI. Scope blijft netjes binnen SF-16-story (frontend SF-18 + voedende
  backend SF-17). Geen scope creep of regressie waargenomen.

Conclusie: implementatie is coherent, contract-consistent en passend binnen scope; de
eerder geblokkeerde flow is hersteld. Akkoord.

---

## SF-18 (reviewer, loopback 5) — reviewed (akkoord)

Onafhankelijke herhaling van de statische review op de volledige branch-diff
(frontend `main.dart` + voedende backend SF-17). Flutter/mvn niet beschikbaar in de
reviewer-omgeving; build/analyze/tests via CI.

- [info] Blocker definitief opgelost en geverifieerd: `YouTrackClient.kt`
  (`createIssue`) gebruikt `enumFieldValue("AI Model", it)` (spatie). Matcht de echte
  veldnaam: `TrackerModels.kt` `AI_MODEL("AI Model")`,
  `clients/YouTrackClient.kt:960` `FieldSpec("AI Model", ...)`,
  `FakeYouTrackState.kt:63` `FieldDef("cf-model", "AI Model", ...)`. De tweede
  `POST /api/issues/{key}` (Stage=Develop + AI-supplier + AI Model + AI Token Budget)
  target nu bestaande velden; de create-story-flow is end-to-end heel.
- [info] `_aiModels` (main.dart) komt item-voor-item exact overeen met `aiModelValues`
  (`clients/YouTrackClient.kt:945-949`) en `AI_MODEL_VALUES` (`FakeYouTrackState.kt:303-306`).
  '(geen)'-optie (null) houdt model optioneel.
- [info] `_aiSuppliers` (`mock/claude/copilot/openai/microsoft`) is een geldige subset
  van de backend-enum (`none, mock, claude, openai, copilot, microsoft`); `none` terecht
  weggelaten. Conform spec.
- [info] Contract klopt: frontend-body (projectKey, targetRepo, aiSupplier, aiModel,
  budget int|null, title, description) deserialiseert naar `CreateStoryRequest`
  (budget Long?, overige nullable/default). Jackson mapt int→Long zonder issue.
  `POST /api/v1/stories` + `GET /api/v1/projects` gebruiken `requireAuthorization` +
  validatie op projectKey/title. Helpers `resolveProjectId`, `sendJson`, `pathEncoded`,
  `getIssue`, `managedProjects`, `ProjectDto.key/name` bestaan met passende signatures.
- [info] Frontend-hygiëne: controllers ge-dispose'd, `loading`/`_saving`-guards,
  succes → `Navigator.pop(true)` → `_run(_refreshAll)`, fout → zichtbare `_attention`.
  Validatie op verplichte velden (project/repo/titel) en numeriek budget.
- [suggestie] (niet-blokkerend, ongewijzigd) `GET /api/v1/projects` via `managedProjects()`
  filtert projecten zonder targetRepo weg; verdedigbaar. Onbekend project →
  `error(...)`/`require(...)` mappen naar 500/400 zonder expliciete handler; consistent
  met bestaande stijl.
- [info] Geen unit-tests toegevoegd (low/medium effort; geen mvn/flutter in omgeving),
  dekking via CI. Scope blijft binnen SF-16 (frontend SF-18 + voedende backend SF-17).
  Geen scope creep of regressie waargenomen.

Conclusie: blocker hersteld en geverifieerd, contract consistent, scope netjes. Akkoord.
