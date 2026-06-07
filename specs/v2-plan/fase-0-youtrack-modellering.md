# Fase 0 — YouTrack-modellering (fundament)

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md).
> Deze fase staat op zichzelf en kan los gerefined en opgepakt worden.

## Doel

Subtaken als first-class issues kunnen **herkennen, koppelen en aanmaken**, en de
velden op orde brengen, zodat de latere fases erop kunnen bouwen. Nog géén
gedragsverandering in de flow.

## Gevalideerd tegen de echte instance (juni 2026)

Tegen `https://youtrack.vdzonsoftware.nl` (in **PF**):

- YouTrack heeft een **ingebouwd "Subtask" link-type** (`parent for` /
  `subtask of`, aggregation). Subtask = die link, **geen** custom Parent-veld.
- `AI Phase` is een **EnumBundle** (geen vrije tekst): nieuwe phase-waarden
  moeten als bundle-values worden toegevoegd.
- Het board zet **swimlanes op het `Type`-veld, waarde "User Story"**. Alleen
  `Type = User Story` wordt een swimlane; subtaken met `Type = Task` verschijnen
  als kaart eronder.
- `createSubtask` werkt end-to-end via: `POST /api/issues` (project, summary,
  description) → customFields zetten → tag zetten → command
  `{"query":"parent for <subtaskKey>","issues":[{"idReadable":"<storyKey>"}]}`.

## Wijzigingen

### Velden
- **Twee aparte phase-velden** i.p.v. één gedeeld `AI Phase`:
  - `Story Phase` (enum) — alleen op stories.
  - `Subtask Phase` (enum) — alleen op subtaken.
  - **Geen migratie**: we beginnen in een nieuw, schoon project. Maak beide
    enum-bundles vers aan (concrete waarden: fase 1); het oude `AI Phase`-veld
    hoeft niet te worden gemigreerd.
- **Nieuw veld `Subtask Type`** (enum): `development` / `review` / `test` /
  `manual` / `summary`. Dit bepaalt de **rol/pipeline** van een subtask; het is
  **niet** de STORY/SUBTASK-discriminator (dat is het standaard `Type`-veld).
- **Nieuw veld `AI Model`** (enum) met **alle modellen die nu in de code staan**
  (suppliers door elkaar — kan voorlopig niet anders; kies een model dat bij de
  gekozen `AI-supplier` past):
  - claude (CLI): `claude-haiku-4-5`, `claude-sonnet-4-6`, `claude-opus-4-7`
  - copilot: `gpt-4.1`, `claude-haiku-4.5`, `claude-sonnet-4.5`, `claude-opus-4.5`
  - mock: `dummy-ai-client`
- **Nieuw veld `AI Reasoning Effort`** (enum: low/medium/high).
- **`AI Level` verwijderen** (geen overgangsperiode) — incl. uit
  `TrackerModels.kt`, parser, `AiRouting`, dashboard en command-parser.
- `AI Tokens Used` blijft per issue; `AI Token Budget` blijft (story-cap);
  `AI Max Developer Loopbacks` blijft (cap interne fix-loop op subtask).

### Code-modellen
- `TrackerField` uitbreiden met `SUBTASK_TYPE`, `AI_MODEL`, `AI_REASONING_EFFORT`,
  en de gesplitste phase-velden — `youtrack/TrackerModels.kt`.
- `TrackerIssueFields` + de YouTrack-parser uitbreiden met deze velden.
- Een **`IssueType`** (STORY/SUBTASK) afleiden uit het standaard **`Type`-veld**:
  `User Story` → STORY, `Task` → SUBTASK. De parent-link wordt alleen gebruikt om
  de story terug te vinden, niet voor de discriminatie. Vereist dat gemanagede
  projecten de conventie User Story/Task hanteren (een nieuw, geschikt project;
  SF voldoet niet en wordt niet gebruikt).

### createSubtask
- **`YouTrackApi.createSubtask(parentKey, spec)`** toevoegen en implementeren in
  `YouTrackClient`. `spec = { type, title, description, model?, effort? }`. Zet:
  - `summary = title`, `description`;
  - `Subtask Type`, begin-`Subtask Phase`, `AI Model`, `AI Reasoning Effort`;
  - **YouTrack `Type = Task`** (zodat het een kaart wordt, geen swimlane);
  - **géén tag bij creatie**: de subtask is inert tot 'ie de tag `ai-development`
    krijgt (de mens tagt de 1e subtask, de completion-handler ketent de rest —
    fase 4);
  - de **Subtask-link** naar de parent via de commands-API.
- Een story moet `Type = User Story` zijn om als swimlane te tonen — borg dit bij
  story-provisioning / projectsetup.

## Aandachtspunten

- `findWorkIssues()` levert na deze fase stories (tag `ai-refinement`) én subtaken
  (tag `ai-development`) op; de router (fase 1) splitst op het `Type`-veld.
- Handmatige subtask door de gebruiker: die maakt 'm aan **als** subtask (UI),
  waardoor de Subtask-link automatisch ontstaat, en zet alleen `Subtask Type`.
  Vang het randgeval af: `Subtask Type` gezet maar **geen** parent-link → nette
  fout i.p.v. crash.

## Betrokken bestanden

- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/youtrack/TrackerModels.kt`
- `.../youtrack/YouTrackApi.kt`
- `.../youtrack/clients/YouTrackClient.kt`
- `.../youtrack/parsers/` (issue-parsing)
- `.../orchestrator/services/AiRouting.kt` (AI Level eruit)

## Test

- Unit: parser leidt STORY vs SUBTASK correct af op basis van het `Type`-veld
  (User Story / Task).
- Unit: parser leest de gesplitste phase-velden + `Subtask Type` + Model/Effort.
- Integratie: `createSubtask` maakt een sub-issue met parent-link, `Subtask Type`
  en `Type = Task` (tegen YouTrack-sandbox of mock); nog zonder ai-development-tag.

## Klaar wanneer

Subtaken kunnen programmatorisch worden aangemaakt en door de bestaande
poll-query worden teruggevonden, de velden zijn op orde (twee phase-velden,
Subtask Type, Model/Effort; AI Level weg), zonder dat de huidige story-flow
verandert.
