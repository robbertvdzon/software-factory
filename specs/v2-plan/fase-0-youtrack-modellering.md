# Fase 0 — YouTrack-modellering (fundament)

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md).
> Deze fase staat op zichzelf en kan los gerefined en opgepakt worden.

## Doel

Een **vers, geschikt YouTrack-project** opzetten met de juiste custom fields en
tags, en de code-bouwstenen om subtaken te **herkennen, koppelen en aanmaken**.
Nog géén nieuwe flow-logica (dat is fase 1+); fase 0 is puur de YouTrack-setup +
bouwstenen.

## Gevalideerd tegen de echte instance (juni 2026)

Getest tegen `https://youtrack.vdzonsoftware.nl` (project **PF**):

- YouTrack heeft een **ingebouwd "Subtask" link-type** (`parent for` /
  `subtask of`, aggregation). Subtask = die link, **geen** custom Parent-veld.
- Phase-velden zijn **EnumBundles** (geen vrije tekst): `Story Phase` en
  `Subtask Phase` worden dus elk een enum-bundle waarvan wij de waarden bepalen.
- Het board zet **swimlanes op het `Type`-veld, waarde "User Story"**. Alleen
  `Type = User Story` wordt een swimlane; subtaken met `Type = Task` verschijnen
  als kaart eronder.
- `createSubtask` werkt end-to-end via: `POST /api/issues` (project, summary,
  description) → customFields zetten → de Subtask-link leggen met command
  `{"query":"parent for <subtaskKey>","issues":[{"idReadable":"<storyKey>"}]}`.
  (Het zetten van een **tag** is óók mogelijk via command, maar createSubtask doet
  dat bewust **niet** — zie hieronder.)

## Vers project — geen migratie

We beginnen in een **nieuw, schoon project**. Er zijn geen lopende v1-issues, dus
**geen migratiepad**: de bundles en velden worden vers aangemaakt en het oude
`AI Phase`-veld wordt niet overgenomen.

## Custom fields voor het nieuwe project

| Veld | Status | Type / waarden |
|------|--------|----------------|
| `Type` | **kies bundle** | enum; moet **`User Story`** en **`Task`** bevatten (discriminator + board-swimlane) |
| `Story Phase` | **nieuw** | enum-bundle; waarden in fase 1 |
| `Subtask Phase` | **nieuw** | enum-bundle; waarden in fase 1 |
| `Subtask Type` | **nieuw** | enum: `development` / `review` / `test` / `manual` / `summary` (rol/pipeline van een subtask) |
| `AI Model` | **nieuw** | enum (zie lijst hieronder) |
| `AI Reasoning Effort` | **nieuw** | enum: `low` / `medium` / `high` |
| `AI-supplier` | kept | enum: `claude` / `openai` / `copilot` / `mock` (selecteert de AI-client) |
| `Paused` | kept | bool/enum (pauze; story-niveau) |
| `Error` | kept | text (systeemfout per issue) |
| `AI Token Budget` | kept | integer (story-cap) |
| `AI Tokens Used` | kept | integer (per issue) |
| `AgentStartedAt` | kept | date-time (timeout-detectie) |
| `AI Max Developer Loopbacks` | kept | integer (cap interne fix-loop, per subtask) |
| `Priority`, `Assignee`, `State` | kept | standaard / board-kolommen (geen factory-logica) |
| `AI Phase` | **weg** | vervangen door `Story Phase` + `Subtask Phase` |
| `AI Level` | **weg** | vervangen door `AI Model` + `AI Reasoning Effort` |
| `StoryType` (Story/Subtask) | **weg** | overbodig — `Type` discrimineert STORY vs SUBTASK |

### `AI Model`-enumwaarden (alle modellen die nu in de code staan)

Suppliers door elkaar (kan voorlopig niet anders; kies een model dat bij de
gekozen `AI-supplier` past — het model wordt als `--model` aan de CLI meegegeven):

- claude (CLI): `claude-haiku-4-5`, `claude-sonnet-4-6`, `claude-opus-4-7`
- copilot: `gpt-4.1`, `claude-haiku-4.5`, `claude-sonnet-4.5`, `claude-opus-4.5`
- mock: `dummy-ai-client`
- **openai/codex**: de code hardcodet hier géén model-id (Codex krijgt het model
  puur via `--model`). Wil je openai gebruiken → voeg zelf de juiste model-id toe
  aan de enum.

## Tags (triggering)

- Maak twee tags aan: **`ai-refinement`** (op stories) en **`ai-development`** (op
  subtaken). Deze vervangen de huidige enkele `WORK_TAG = "AI-Develop"`.
- Pas `findWorkIssues()` aan zodat 'ie issues met **`ai-refinement` OF
  `ai-development`** ophaalt (nu: `project: KEY tag: {AI-Develop}`).

## Code-modellen

- `TrackerField` uitbreiden met `SUBTASK_TYPE`, `AI_MODEL`, `AI_REASONING_EFFORT`
  en de gesplitste phase-velden; `AI_LEVEL` (+ evt. `AI_PHASE`, `STORY_TYPE`)
  verwijderen — `youtrack/TrackerModels.kt`.
- `TrackerIssueFields` + de YouTrack-parser uitbreiden met de nieuwe velden.
- **`IssueType`** (STORY/SUBTASK) afleiden uit het standaard **`Type`-veld**:
  `User Story` → STORY, `Task` → SUBTASK. De Subtask-link wordt alleen gebruikt om
  de parent-story terug te vinden, niet voor de discriminatie.
- `AI Level` uit `AiRouting`, dashboard en command-parser verwijderen (model +
  effort komen nu rechtstreeks uit de velden; de oude level→bucket-mapping vervalt).

## createSubtask

**`YouTrackApi.createSubtask(parentKey, spec)`** toevoegen + implementeren in
`YouTrackClient`. `spec = { type, title, description, model?, effort? }`. Zet:

- `summary = title`, `description`;
- `Subtask Type`, `AI Model`, `AI Reasoning Effort`;
- **YouTrack `Type = Task`** (zodat het een kaart wordt, geen swimlane);
- **`Subtask Phase` leeg laten** — de coördinator (fase 5) start vanaf "(net
  getagd)" de eerste agent en zet dan pas de eerste actieve status;
- **géén tag** bij creatie: de subtask is inert tot 'ie `ai-development` krijgt (de
  mens tagt de 1e subtask, daarna ketent het — fase 4);
- de **Subtask-link** naar de parent via de commands-API.

Een story is `Type = User Story` (zodat 'ie als swimlane toont); de mens maakt
stories zo aan (of zet de project-default issue-type daarop).

## Aandachtspunten

- `findWorkIssues()` levert na deze fase stories (tag `ai-refinement`) én subtaken
  (tag `ai-development`); de router (fase 1) splitst op het `Type`-veld.
- Handmatige subtask door de gebruiker: die maakt 'm aan **als** subtask (UI),
  waardoor de Subtask-link automatisch ontstaat, en zet alleen `Subtask Type`.
  Vang het randgeval af: `Subtask Type`/`Type=Task` zonder parent-link → nette
  fout i.p.v. crash.
- De `AI Model`-enum mengt bewust suppliers; een ongeldige combinatie (bv.
  supplier `claude` + model `gpt-4.1`) is nu mogelijk en wordt **niet**
  gevalideerd. Bewust voor nu; later eventueel supplier-afhankelijk maken.

## Betrokken bestanden

- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/youtrack/TrackerModels.kt`
- `.../youtrack/YouTrackApi.kt`
- `.../youtrack/clients/YouTrackClient.kt` (`createSubtask`, tags, `findWorkIssues`)
- `.../youtrack/parsers/` (issue-parsing)
- `.../orchestrator/services/AiRouting.kt` (AI Level eruit)

## Test

- Unit: parser leidt STORY vs SUBTASK correct af op `Type` (User Story / Task).
- Unit: parser leest de gesplitste phase-velden + `Subtask Type` + Model/Effort.
- Integratie: `createSubtask` maakt een sub-issue met parent-link, `Subtask Type`
  en `Type = Task`, **zonder tag** en met **lege `Subtask Phase`** (tegen
  YouTrack-sandbox of mock).
- Integratie: `findWorkIssues()` vindt zowel een `ai-refinement`- als een
  `ai-development`-getagd issue.

## Klaar wanneer

Het nieuwe project heeft de juiste velden (twee phase-velden, `Subtask Type`,
`AI Model`, `AI Reasoning Effort`; geen `AI Phase`/`AI Level`/`StoryType`) en de
twee tags; subtaken kunnen programmatorisch worden aangemaakt en door
`findWorkIssues()` worden teruggevonden, zonder nieuwe flow-logica.
