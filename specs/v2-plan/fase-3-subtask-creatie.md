# Fase 3 — Subtask-creatie (planner declareert → orchestrator materialiseert)

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md).
> Deze fase staat op zichzelf en kan los gerefined en opgepakt worden.

## Doel

De planner-output omzetten in echte YouTrack-subtaken — **idempotent**, en
**zonder de agent rechtstreeks naar YouTrack te laten schrijven**.

## Wijzigingen

- **`AgentRunCompleteRequest` uitbreiden** met `subtasks: List<SubtaskSpec>`,
  waarbij `SubtaskSpec = { type, title, description, model?, effort? }`
  (`runtime/RuntimeApi.kt`). De PLANNER-agent vult dit in `agent-result.json`.
  - De planner declareert óók de **story-brede review/test** en als állerlaatste
    een **`summary`-subtask** (zie beslissing 3 + de summary-keuze): bijv.
    `[dev, dev, ..., review, test, manual?, summary]`. Elke `development`-subtask
    doet z'n eigen ingebouwde review (fase 5); de losse `review`/`test` dekken de
    hele story; de `summary`-subtask (SUMMARIZER-rol) sluit af.
  - `model`/`effort` per subtask zijn optioneel; de planner mag per subtask een
    lichter/zwaarder model kiezen (niet elke subtask heeft het zwaarste model
    nodig). Leeg → story-default.
- **`AgentRunCompletionService`** (`runtime/services/AgentRunCompletionService.kt`):
  bij een geslaagde PLANNER-run → voor elke spec
  `youTrackApi.createSubtask(parentKey, spec)` (uit fase 0).
- **Idempotentie (fijnkorrelig):** sla de aangemaakte subtask-keys op (in
  `StoryRun` of `agent_runs`, zie fase 6) en maak per spec alleen aan wat nog niet
  bestaat. Niet vertrouwen op de grove check "story heeft al children", want bij
  een gedeeltelijke fout (3 specs, 2 aangemaakt, crash) moet de retry de 3e
  alsnog aanmaken. Een planner-rerun mag geen duplicaten maken.
- **Re-plan bij `PLANNING_REJECTED`:** als de mens het plan afkeurt, draait de
  planner opnieuw en moet de nieuwe declaratie de bestaande subtaken
  **reconciliëren** (toevoegen / bijwerken / verwijderen) — dus een diff tegen de
  opgeslagen subtask-keys, niet "niets doen omdat er al children zijn". Pas op met
  subtaken die de mens op de gate handmatig heeft aangepast.

## Aandachtspunten

- Dit hergebruikt bewust het bestaande result-file-kanaal: agents blijven
  afgeschermd van YouTrack (geen creds in de container). Zie beslissing 5 in het
  overzicht.
- Gedrag bij rerun na user-vragen: als de refiner/planner opnieuw draait, niet
  opnieuw aanmaken maar bestaande subtaken respecteren (idempotentie hierboven).
- De subtaken worden aangemaakt rond de overgang `PLANNING → PLANNED`, vóór de
  gate, zodat de mens ze op de gate kan inspecteren en handmatig kan aanpassen.

## Betrokken bestanden

- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/runtime/RuntimeApi.kt`
- `.../runtime/services/AgentRunCompletionService.kt`
- `.../youtrack/YouTrackApi.kt` (`createSubtask` uit fase 0)
- `.../orchestrator/RunRepositories.kt` (opslag aangemaakte subtask-keys)

## Test

- Planner-result met N specs → N subtaken met de juiste types onder de parent,
  inclusief de story-brede review/test als laatste.
- Rerun van de planner maakt geen duplicaten.
- Gesimuleerde gedeeltelijke fout → retry maakt alleen de ontbrekende subtaken.
- Subtaken krijgen `Type = Task` + `Subtask Type` + model/effort, en **geen** tag
  bij creatie (inert tot ze `ai-development` krijgen — fase 4).

## Klaar wanneer

Een geplande story resulteert in correct getypeerde subtaken in YouTrack
(inclusief story-brede review/test), idempotent aangemaakt, zonder dat de agent
zelf naar YouTrack schrijft.
