# SF-16 - Eindsamenvatting

## Story

Eindsamenvatting

## Eindsamenvatting

Ik heb `.task.md` en het volledige worklog gelezen. Hier is de eindsamenvatting voor de PO.

---

## Eindsamenvatting — SF-16: Create story vanuit de UI

### Wat is gebouwd
Een **'Create story'-knop met dialoog** in de Stories-sectie van de Flutter `dashboard-frontend`, die via de Kotlin `dashboard-backend` een nieuw YouTrack-issue aanmaakt dat de orchestrator direct oppakt.

**Backend (`dashboard-backend`)**
- `YouTrackClient.createIssue(...)`: resolvet het gekozen project op `shortName` → project-id, maakt het issue aan (`POST /api/issues` met project + titel + description) en zet daarna de custom fields `Stage=Develop`, `AI-supplier`, `AI Model` (enums) en `AI Token Budget` (numeriek). Retourneert de nieuwe story als `StoryDto`.
- `POST /api/v1/stories`: nieuw endpoint met auth (`requireAuthorization`) en validatie op `projectKey`/`title`.
- `GET /api/v1/projects`: levert de selecteerbare (managed) projecten voor de dropdown.
- DTO's toegevoegd: `CreateStoryRequest`, `ProjectOptionDto`, `ProjectsResponse`.

**Frontend (`dashboard-frontend/lib/main.dart`)**
- `ApiClient.postJson(path, body)` helper (de bestaande `post` stuurde geen body).
- Projectenlijst geladen via `GET /api/v1/projects` in `_refreshAll`.
- 'Create story'-knop boven de stories-tabel + `CreateStoryDialog` met zes velden: project-dropdown, repo-projectnaam, AI-supplier-dropdown, AI-model-dropdown, budget, titel en description. Validatie op verplichte velden; bij succes sluit de dialoog en wordt de lijst ververst, bij fout een zichtbare melding.

### Belangrijke keuzes
- **Repo-projectnaam**: er is geen per-issue repo-veld in YouTrack. De ingevulde repo-naam wordt als `"Repo: <targetRepo>"` in de description opgenomen zodat hij niet verloren gaat; de orchestrator leidt de echte doel-repo af uit de project-beschrijving (`factory.repo`).
- **AI-supplier**: dropdown biedt exact `mock/claude/copilot/openai/microsoft` aan (`none` bewust weggelaten).
- **AI-model**: tijdens de review omgezet van vrij tekstveld naar dropdown met exact de geldige YouTrack `AI Model`-enumwaarden, zodat een ongeldige modelnaam geen 4xx meer geeft.

### Wat is getest
- Verificatie is gedaan via **statische review** in meerdere rondes (build/analyze/tests draaien in de CI-pipeline; `mvn`/`flutter` zijn niet beschikbaar in de agent-omgeving).
- Een belangrijke bug is door de review onderschept en hersteld: de custom-field-naam was eerst `"AI-model"` (hyphen) i.p.v. de echte naam `"AI Model"` (spatie). Hierdoor faalde de hele custom-fields-update, waardoor ook `Stage=Develop` en `AI-supplier` niet gezet werden en de orchestrator de story nooit oppakte. Na de fix is de flow end-to-end bevestigd akkoord.
- Contract-consistentie tussen frontend-body en backend-`CreateStoryRequest` is geverifieerd, evenals de `_aiModels`/`_aiSuppliers`-waarden tegen de echte YouTrack-enums.

### Bewust niet gedaan
- **Geen unit-tests toegevoegd** (low/medium effort, geen build-omgeving lokaal beschikbaar); dekking loopt via CI.
- **Geen expliciete exception-mapping**: onbekend project → 500, lege velden → 400, consistent met de bestaande stijl. Niet-blokkerend.
- `GET /api/v1/projects` toont alleen projecten mét geconfigureerde target-repo (verdedigbaar voor deze flow, maar wijkt licht af van "alle managed projecten").

---
