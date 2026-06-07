# Fase 1 — Phase-enums splitsen + router (refactor, gedrag gelijk)

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md).
> Deze fase staat op zichzelf en kan los gerefined en opgepakt worden.

## Doel

De twee niveaus (story / subtask) in code scheiden **zonder gedragsverandering**.
Subtaken bestaan in deze fase nog niet; de story draait nog het oude lineaire pad.

## Wijzigingen

- Splits `AiPhase` (`orchestrator/AiPhase.kt`) in twee enums, gekoppeld aan de
  twee velden uit fase 0:

  **`StoryPhase`** (veld `Story Phase`):
  ```
  REFINING
    → REFINED_WITH_QUESTIONS ⇄ QUESTIONS_ANSWERED
    → REFINED
    → PLANNING
    → PLANNED
    → DEVELOPING
    → SUMMARIZING
    → DONE
  ```

  **`SubtaskPhase`** (veld `Subtask Phase`) — gedeeld over de subtask-typen,
  gedetailleerd in fase 5:
  ```
  DEVELOPING, DEVELOPED,
  REVIEWING, REVIEW_WITH_FINDINGS, REVIEWED_OK,
  TESTING, TESTED_WITH_FINDINGS, TESTED_OK,
  SUBTASK_WITH_QUESTIONS, SUBTASK_QUESTIONS_ANSWERED,
  AWAITING_HUMAN,
  DONE
  ```

- Maak `OrchestratorService.processIssue()` een **dunne router op IssueType**
  (afgeleid uit het `Subtask Type`-veld, fase 0):

  ```
  when (issue.type) {
    STORY   -> storyCoordinator.process(issue, issue.storyPhase)
    SUBTASK -> subtaskCoordinator.process(issue, issue.subtaskPhase)
  }
  ```

- Lees per IssueType het **juiste phase-veld** (`Story Phase` resp.
  `Subtask Phase`).
- In deze fase voert de story-coördinator voorlopig nog het bestaande lineaire
  pad uit (refine → develop → review → test → summarize). Puur structuur; de
  echte opsplitsing komt in fase 2 e.v. De nieuwe StoryPhase-waarden
  `PLANNING`/`PLANNED` zijn hier nog dood (worden in fase 2 actief).

## Aandachtspunten

- Houd de mapping naar het oude gedrag 1-op-1 zodat lopende issues niet
  stuklopen. De oude lineaire transitie `REFINED → DEVELOPING` blijft in deze
  fase intact (de gate komt pas in fase 2).
- Recovery-/timeout-/budgetlogica blijft ongewijzigd in deze fase.
- Subtaken bestaan nog niet, dus het SUBTASK-pad in de router wordt nog niet
  geraakt; alleen de structuur staat.

## Betrokken bestanden

- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/orchestrator/AiPhase.kt`
- `.../orchestrator/services/OrchestratorService.kt`

## Test

- Bestaande orchestrator-tests blijven groen (gedrag onveranderd).
- Een story doorloopt nog steeds de volledige keten zoals voorheen.
- Router kiest correct STORY-pad op basis van leeg `Subtask Type`.

## Klaar wanneer

De router bestaat en routeert op IssueType, leest het juiste phase-veld, alle
bestaande tests slagen, en er is geen waarneembare gedragsverandering.
