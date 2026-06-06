# Fase 3 — Subtask-creatie (planner declareert → orchestrator materialiseert)

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md).
> Deze fase staat op zichzelf en kan los gerefined en opgepakt worden.

## Doel

De planner-output omzetten in echte YouTrack-subtaken — **idempotent**, en
**zonder de agent rechtstreeks naar YouTrack te laten schrijven**.

## Wijzigingen

- **`AgentRunCompleteRequest` uitbreiden** met `subtasks: List<SubtaskSpec>`,
  waarbij `SubtaskSpec = { type, title, description }` (`runtime/RuntimeApi.kt`).
  De PLANNER-agent vult dit in `agent-result.json`.
- **`AgentRunCompletionService`** (`runtime/services/AgentRunCompletionService.kt`):
  bij een geslaagde PLANNER-run → voor elke spec
  `youTrackApi.createSubtask(parentKey, type, title, description)` (uit fase 0).
- **Idempotentie:** maak subtaken alleen aan als de story er nog geen heeft
  (check parent → children), of koppel het aan de phase-overgang
  `PLANNING → PLANNED`. Een planner-rerun mag geen duplicaten maken.

## Aandachtspunten

- Dit hergebruikt bewust het bestaande result-file-kanaal: agents blijven
  afgeschermd van YouTrack (geen creds in de container). Zie beslissing 5 in het
  overzicht.
- Bepaal het gedrag bij rerun na user-vragen: als de refiner/planner opnieuw
  draait, niet opnieuw aanmaken maar bestaande subtaken respecteren/bijwerken.

## Betrokken bestanden

- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/runtime/RuntimeApi.kt`
- `.../runtime/services/AgentRunCompletionService.kt`
- `.../youtrack/YouTrackApi.kt` (`createSubtask` uit fase 0)

## Test

- Planner-result met 3 specs → 3 subtaken met de juiste types onder de parent.
- Rerun van de planner maakt geen duplicaten.
- Subtaken dragen de WORK_TAG en worden door de poller opgepikt.

## Klaar wanneer

Een geplande story resulteert in correct getypeerde subtaken in YouTrack, één
keer aangemaakt, zonder dat de agent zelf naar YouTrack schrijft.
