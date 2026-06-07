# Fase 3 — Subtask-creatie (planner declareert → orchestrator materialiseert)

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md).
> Deze fase staat op zichzelf en kan los gerefined en opgepakt worden.

## Doel

De planner-output omzetten in echte YouTrack-subtaken — **idempotent**, en
**zonder de agent rechtstreeks naar YouTrack te laten schrijven**.

## Al aanwezig uit fase 0

- **`SubtaskSpec = { type, title, description, model?, effort? }`** bestaat al
  (`youtrack/TrackerModels.kt`).
- **`YouTrackApi.createSubtask(parentKey, spec, supplier)`** is al geïmplementeerd
  (`YouTrackClient`): POST issue + `Type=Task` + `Subtask Type`/model/effort +
  `AI-supplier` (= story-default, README §7; nodig zodat de poller de subtask oppakt) +
  Subtask-link, **zonder tag** en **zonder `Subtask Phase`**.

Fase 3 voegt dus alleen toe: het **subtasks-kanaal** in het result + het
**materialiseren** + **idempotentie**.

## Cross-module (zoals fase 2: orchestrator + agentworker)

- **Orchestrator-kant (test-groen met fakes):** `AgentRunCompleteRequest.subtasks`
  lezen en per spec `createSubtask` aanroepen. De test-fake levert de specs.
- **Agentworker-kant:** `AgentWorkerResult` krijgt hetzelfde `subtasks`-veld, en de
  **planner produceert de breakdown** (de echte subtask-lijst). In fase 2c emit de
  planner alleen z'n fase; het declareren van subtaken komt hier.

Beide kanten zijn klein genoeg om in **één keer** te doen (geen aparte 3a/3b nodig).

## Wijzigingen

- **`AgentRunCompleteRequest` uitbreiden** met `subtasks: List<SubtaskSpec>`
  (`runtime/RuntimeApi.kt`) **én `AgentWorkerResult`** (agentworker) met hetzelfde
  veld, zodat `agent-result.json` de specs draagt. De PLANNER-agent vult dit.
  - De planner declareert óók de **story-brede review/test** en als állerlaatste
    een **`summary`-subtask** (zie beslissing 3 + de summary-keuze): bijv.
    `[dev, dev, ..., review, test, manual?, summary]`. Elke `development`-subtask
    doet z'n eigen ingebouwde review (fase 5); de losse `review`/`test` dekken de
    hele story; de `summary`-subtask (SUMMARIZER-rol) sluit af.
  - `model`/`effort` per subtask zijn optioneel; de planner mag per subtask een
    lichter/zwaarder model kiezen (niet elke subtask heeft het zwaarste model
    nodig). Leeg → story-default.
- **`AgentRunCompletionService`** (`runtime/services/AgentRunCompletionService.kt`):
  **alleen wanneer de planner `planned` bereikt** (dus niet bij
  `planned-with-questions` of een error) → voor elke spec
  `youTrackApi.createSubtask(parentKey, spec)`. Dit hangt aan de
  PLANNER-success-tak die in fase 2b/2c het `Story Phase`-veld op `planned` zet.
- **Idempotentie (fijnkorrelig):** maak per spec alleen aan wat nog niet bestaat.
  Niet vertrouwen op de grove check "story heeft al children" (bij een
  gedeeltelijke fout — 3 specs, 2 aangemaakt, crash — moet de retry de 3e alsnog
  aanmaken). Twee opties:
  - **YouTrack als bron:** query de bestaande children van de parent (Subtask-link)
    en match per spec (op titel + `Subtask Type`). Geen DB nodig, geen
    fase-6-koppeling.
  - **DB-opslag:** sla aangemaakte subtask-keys op (`agent_runs.subtask_key`, fase
    6) — koppelt fase 3 dan wel aan fase 6.
- **Re-plan bij `PLANNING_REJECTED`:** als de mens het plan afkeurt, draait de
  planner opnieuw en moet de nieuwe declaratie de bestaande subtaken
  **reconciliëren** (toevoegen / bijwerken / verwijderen) — dus een diff tegen de
  opgeslagen subtask-keys, niet "niets doen omdat er al children zijn". Pas op met
  subtaken die de mens op de `PLANNED`-goedkeuring handmatig heeft aangepast.

## Aandachtspunten

- Dit hergebruikt bewust het bestaande result-file-kanaal: agents blijven
  afgeschermd van YouTrack (geen creds in de container). Zie beslissing 5 in het
  overzicht.
- Gedrag bij rerun na user-vragen: als de refiner/planner opnieuw draait, niet
  opnieuw aanmaken maar bestaande subtaken respecteren (idempotentie hierboven).
- De subtaken worden aangemaakt rond de overgang `PLANNING → PLANNED`, vóór de
  goedkeuring, zodat de mens ze op de `PLANNED`-status kan inspecteren en handmatig
  kan aanpassen.

## Betrokken bestanden

Orchestrator (3a):
- `softwarefactory/.../runtime/RuntimeApi.kt` (`AgentRunCompleteRequest.subtasks`)
- `.../runtime/services/AgentRunCompletionService.kt` (materialiseren bij `planned`)
- `.../youtrack/YouTrackApi.kt` + `clients/YouTrackClient.kt` (`createSubtask` — al uit fase 0)
- `.../orchestrator/RunRepositories.kt` (alleen bij DB-idempotentie-variant)

Agentworker (3b):
- `agentworker/.../AgentWorkerApi.kt` (`AgentWorkerResult.subtasks`)
- `agentworker/.../agent/ai/...` + planner-flow/prompt (planner produceert de breakdown)

## Test

- Planner-result met N specs (fase `planned`) → N subtaken met de juiste types
  onder de parent, inclusief de story-brede review/test + `summary` als laatste.
- Planner met `planned-with-questions` → **geen** subtaken aangemaakt.
- Rerun van de planner maakt geen duplicaten.
- Gesimuleerde gedeeltelijke fout → retry maakt alleen de ontbrekende subtaken.
- Subtaken krijgen `Type = Task` + `Subtask Type` + model/effort, en **geen** tag
  bij creatie (inert tot ze `ai-development` krijgen — fase 4).

## Klaar wanneer

Een geplande story resulteert in correct getypeerde subtaken in YouTrack
(inclusief story-brede review/test), idempotent aangemaakt, zonder dat de agent
zelf naar YouTrack schrijft.
