# SF-1075 - selecteerbare en bewerkbare story

## Story

selecteerbare en bewerkbare story

<!-- refined-by-factory -->

## Scope

In de story-detailweergave (`dashboard-frontend/lib/screens/story_detail_screen.dart`) worden tekstvelden nu als niet-selecteerbare `Text`-widgets getoond, en er is geen manier om de omschrijving of de AI-velden (supplier/model) van een bestaande story te bewerken. Bovendien wordt het AI-model helemaal niet getoond in de story-detailweergave. Deze story maakt story-teksten selecteerbaar/kopieerbaar, voegt een edit-knop toe voor omschrijving en AI-velden, en toont het AI-model.

**Frontend (`dashboard-frontend`)**
- Maak de volgende teksten selecteerbaar/kopieerbaar (bv. via `SelectableText` i.p.v. `Text`):
  - Story-titel/omschrijving (summary + description-panel).
  - Foutmelding (`ErrorBanner`, `widgets/common.dart`).
  - Agent-vragen (`PendingActionCard`, `pending_action.dart`).
  - Comment/timeline-items (title + body) op het story-scherm.
- Voeg een edit-knop toe bij de omschrijving (Panel "Omschrijving") die een bewerkdialoog opent om de omschrijving aan te passen en op te slaan.
- Voeg een edit-knop toe bij de Details-sectie (of bij de betreffende key/value-rijen) om `AI-supplier` en `AI-model` te bewerken, analoog aan het bestaande `_CreateStoryDialog`-patroon in `stories_screen.dart` (dropdown/keuzelijst per supplier, met `_aiModelsBySupplier`).
- Toon `AI-model` (`fields['aiModel']`) als aparte rij in de Details key/value-tabel, naast de bestaande `AI-supplier`-rij.
- Bewaaracties tonen een foutmelding bij falen en verversen de storydata bij succes (zelfde patroon als `_toggleAutoApprove`/`_toggleSilent`).

**Backend (`dashboard-backend`)**
- Voeg in `BridgeApiController` een nieuw endpoint toe (bv. `POST /api/v1/stories/{storyKey}/edit`) dat, analoog aan `setAutoApprove`/`setSilent`, via `hub.dispatch(...)` de omschrijving en/of AI-supplier/AI-model van een bestaande story bijwerkt. De onderliggende schrijfacties (`updateIssueDescription`, `AI_SUPPLIER`/`AI_MODEL`-veldupdate) bestaan al in `TrackerStoryApiController`/`trackerApi`; dit endpoint ontsluit dezelfde functionaliteit via de sessie-geauthenticeerde bridge in plaats van het token-geauthenticeerde `/api/tracker/stories/{key}`-endpoint.

Buiten scope: bewerken van subtaak-specifieke velden, bewerken van de story-titel/summary (alleen omschrijving + AI-supplier/model), en wijzigingen aan het token-geauthenticeerde `/api/tracker`-endpoint zelf.

## Acceptance criteria

1. Op het story-detailscherm kan de gebruiker de tekst van de omschrijving, foutmeldingen, agent-vragen en comment/timeline-items selecteren en kopiëren (bv. via long-press/drag + copy, of desktop text-select).
2. Bij de omschrijving staat een edit-knop; deze opent een dialoog waarin de omschrijving bewerkt en opgeslagen kan worden. Na opslaan toont het scherm de nieuwe omschrijving.
3. Bij de AI-velden staat een edit-knop; deze opent een dialoog waarin AI-supplier en AI-model gewijzigd kunnen worden (keuze uit de bekende suppliers/modellen, zelfde lijst als bij het aanmaken van een story). Na opslaan tonen `AI-supplier` en `AI-model` de nieuwe waarden.
4. Het AI-model van een story is zichtbaar in de Details-sectie van het story-detailscherm (voorheen ontbrak dit veld volledig in de UI).
5. Een mislukte edit-actie (bv. netwerkfout) toont een foutmelding aan de gebruiker en wijzigt de getoonde data niet.
6. Bestaande functionaliteit (auto-approve, silent, andere acties) blijft ongewijzigd werken.

## Aannames

- "Selecteerbaar en kopieerbaar" wordt uitgelegd als standaard Flutter tekstselectie (`SelectableText`), niet als een aparte "kopieer"-knop per veld.
- Edit is alleen bedoeld voor omschrijving, AI-supplier en AI-model; titel/summary en overige velden (repo, fase, etc.) blijven read-only, tenzij een reviewer/mens hier expliciet om vraagt.
- Er komt een nieuw sessie-geauthenticeerd bridge-endpoint voor het bewerken, in lijn met het bestaande `auto-approve`/`silent`-patroon in `BridgeApiController`, in plaats van hergebruik van het token-geauthenticeerde `/api/tracker/stories/{key}`-endpoint (dat is bedoeld voor externe/agent-toegang, niet voor de ingelogde dashboardgebruiker).
- De edit-UI voor AI-supplier/model hergebruikt de bestaande `_aiModelsBySupplier`-lijst uit `stories_screen.dart` (geen nieuwe modellenlijst nodig).

## Eindsamenvatting

Ik heb voldoende context uit het worklog. De eindsamenvatting voor de PO:

**SF-1075 — Selecteerbare en bewerkbare story (eindsamenvatting)**

**Gebouwd**
- Story-detailscherm (`dashboard-frontend`): tekst (titel/omschrijving, foutmeldingen, agent-vragen, comment/timeline-items) is nu selecteerbaar/kopieerbaar via `SelectableText`.
- Omschrijving-panel heeft een edit-knop met dialoog om de omschrijving te wijzigen en op te slaan.
- Details-sectie heeft een edit-knop om AI-supplier en AI-model te wijzigen (dropdowns, hergebruik van de bestaande suppliers/modellen-lijst, nu gedeeld in `lib/ai_catalog.dart` i.p.v. gedupliceerd).
- AI-model wordt nu getoond als aparte rij in de Details-tabel (voorheen ontbrekend).
- Nieuw backend-endpoint `POST /api/v1/stories/{storyKey}/edit` (dashboard-backend), dat via een nieuwe `story.edit` bridge-operatie en `DashboardCommandService.editStory` de bestaande tracker-schrijfacties hergebruikt, met partial-update-semantiek (alleen meegegeven velden worden gewijzigd).
- Mislukte edits tonen een foutmelding zonder de getoonde data te wijzigen; succesvolle edits verversen de data — zelfde patroon als bestaande auto-approve/silent-acties.

**Belangrijke keuze tijdens review**
- Er zat een inconsistentie in het "automatisch model"-pad: koos een gebruiker expliciet "automatisch" om een eerder ingesteld AI-model te wissen, dan werd dat veld niet meegestuurd en bleef het oude model staan. Dit is opgelost door bij die keuze een lege string mee te sturen in plaats van het veld weg te laten; de bestaande partial-update-laag (backend) ondersteunde dit al correct.

**Getest**
- Backend: nieuwe unit-tests op alle lagen (`BridgeApiControllerTest`, `BridgeRequestHandlerTest`) voor zowel de normale edit-flow als het "wis AI-model"-pad. `mvn verify` op repo-root: BUILD SUCCESS, 0 failures/errors (incl. 507 unit- en 69 e2e-tests in `softwarefactory`, 42 tests in dashboard-backend).
- Frontend: 5 nieuwe widget-tests in `story_detail_screen_test.dart` (selecteerbare tekst + AI-model-rij, omschrijving bewerken, mislukte edit, AI-velden bewerken, "automatisch" wist model). `flutter analyze`: geen issues. `flutter test`: 42/42 groen.
- Documentatie (`ux/screens/story-detail.md`, `ontwerp-bridge-dashboard.md`) is bijgewerkt in lijn met de nieuwe functionaliteit.

**Bewust niet gedaan**
- Story-titel/summary blijft read-only (buiten scope); alleen omschrijving en AI-velden zijn bewerkbaar.
- Geen wijzigingen aan het bestaande token-geauthenticeerde `/api/tracker`-endpoint; het nieuwe edit-endpoint is een apart, sessie-geauthenticeerd bridge-endpoint.
- Overige velden (repo, fase, etc.) zijn niet bewerkbaar gemaakt.

Alle acceptatiecriteria zijn gedekt en geverifieerd; reviewer gaf akkoord voor merge, tester bevestigde oordeel `tested`.
