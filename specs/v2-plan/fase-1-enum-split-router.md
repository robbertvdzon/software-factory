# Fase 1 — Phase-enums splitsen + router (refactor, gedrag gelijk)

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md).
> Deze fase staat op zichzelf en kan los gerefined en opgepakt worden.

## Doel

De twee niveaus (story / subtask) in code scheiden **zonder gedragsverandering**.
Subtaken bestaan in deze fase nog niet; de story draait nog het oude lineaire pad.

## Wijzigingen

- Splits `AiPhase` (`orchestrator/AiPhase.kt`) in:
  - **`StoryPhase`**: `REFINING → REFINED_WITH_QUESTIONS ⇄ QUESTIONS_ANSWERED →
    REFINED → PLANNING → PLANNED → DEVELOPING → SUMMARIZING → DONE`.
  - **`SubtaskPhase`**: per type (gedetailleerd in fase 5).
- Maak `OrchestratorService.processIssue()` een **dunne router**:

  ```
  when (issue.type) {
    STORY   -> storyCoordinator.process(issue, storyPhase)
    SUBTASK -> subtaskCoordinator.process(issue, subtaskPhase)
  }
  ```

- In deze fase voert de story-coördinator voorlopig nog het bestaande lineaire
  pad uit (refine → develop → review → test → summarize). Puur structuur; de
  echte opsplitsing komt in fase 2 e.v.

## Aandachtspunten

- Houd de mapping van/naar het `AI Phase`-trackerveld 1-op-1 met het oude gedrag,
  zodat lopende issues niet stuklopen.
- Recovery-/timeout-/budgetlogica blijft ongewijzigd in deze fase.

## Betrokken bestanden

- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/orchestrator/AiPhase.kt`
- `.../orchestrator/services/OrchestratorService.kt`

## Test

- Bestaande orchestrator-tests blijven groen (gedrag onveranderd).
- Een story doorloopt nog steeds de volledige keten zoals voorheen.

## Klaar wanneer

De router bestaat en routeert op issue-type, alle bestaande tests slagen, en er
is geen waarneembare gedragsverandering.
