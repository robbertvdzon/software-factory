# Fase 1 — Phase-enums splitsen + router (refactor, gedrag gelijk)

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md).
> Deze fase staat op zichzelf en kan los gerefined en opgepakt worden.

## Doel

De twee niveaus (story / subtask) in code scheiden **zonder gedragsverandering**.
Subtaken bestaan in deze fase nog niet; de story draait nog het oude lineaire pad.

## Wijzigingen

- Splits `AiPhase` (`orchestrator/AiPhase.kt`) in twee enums, gekoppeld aan de
  twee velden uit fase 0:

  **`StoryPhase`** (veld `Story Phase`) — einddoel: puur de refinement-lifecycle:
  ```
  REFINING
    → REFINED_WITH_QUESTIONS ⇄ QUESTIONS_ANSWERED
    → REFINED
        ├ REFINED_REJECTED   → REFINING
        └ REFINED_APPROVED   → PLANNING
    → PLANNING
    → PLANNED_WITH_QUESTIONS ⇄ PLANNING_QUESTIONS_ANSWERED
    → PLANNED
        ├ PLANNING_REJECTED  → PLANNING
        └ PLANNING_APPROVED  (terminal; development is tag-gedreven)
  ```
  - De goedkeuringsstappen worden in **fase 2** actief; de tag-gedreven
    development (tag `ai-development`) in **fase 4**.
  - **Geen** `DEVELOPING`/`DONE`/`SUMMARIZING` in het einddoel. Tijdens déze fase
    blijft het oude lineaire pad (incl. een tijdelijke `DEVELOPING`) nog intact
    voor "gedrag gelijk"; dat wordt in **fase 7** opgeruimd.

  **`SubtaskPhase`** (veld `Subtask Phase`) — gedeeld over de subtask-typen,
  gedetailleerd in fase 5:
  ```
  // developer-stap
  DEVELOPING, DEVELOPED, DEVELOPED_WITH_QUESTIONS, DEVELOPMENT_QUESTIONS_ANSWERED,
  DEVELOPMENT_APPROVED, DEVELOPMENT_REJECTED,
  // reviewer-stap
  REVIEWING, REVIEWED, REVIEWED_WITH_QUESTIONS, REVIEW_QUESTIONS_ANSWERED,
  REVIEW_APPROVED, REVIEW_REJECTED,
  // tester-stap
  TESTING, TESTED, TESTED_WITH_QUESTIONS, TEST_QUESTIONS_ANSWERED,
  TEST_APPROVED, TEST_REJECTED,
  // summary-stap
  SUMMARIZING, SUMMARIZED, SUMMARY_WITH_QUESTIONS, SUMMARY_QUESTIONS_ANSWERED,
  SUMMARY_APPROVED, SUMMARY_REJECTED,
  // manual
  AWAITING_HUMAN,
  DONE
  ```
  Elke AI-stap volgt hetzelfde patroon: `*-ing → (*-with-questions ⇄
  *-questions-answered) → *-ed → [goedkeuring] *-approved | *-rejected`. De
  reviewer/tester mag z'n `*-rejected` ook **zelf** zetten (findings → direct terug
  naar de developer).

- Maak `OrchestratorService.processIssue()` een **dunne router op IssueType**
  (afgeleid uit het standaard `Type`-veld: `User Story` → STORY, `Task` → SUBTASK):

  ```
  when (issue.type) {           // STORY als Type==User Story, SUBTASK als Type==Task
    STORY   -> storyCoordinator.process(issue, issue.storyPhase)
    SUBTASK -> subtaskCoordinator.process(issue, issue.subtaskPhase)
  }
  ```

- **Tag-voorwaarde:** het poll-filter (`findWorkIssues`) levert alleen issues met
  tag `ai-refinement` (stories) of `ai-development` (subtaken). Een story zónder
  `ai-refinement` of een subtask zónder `ai-development` wordt nooit verwerkt.

- Lees per IssueType het **juiste phase-veld** (`Story Phase` resp.
  `Subtask Phase`).
- In deze fase voert de story-coördinator voorlopig nog het bestaande lineaire
  pad uit (refine → develop → review → test → summarize). Puur structuur; de
  echte opsplitsing komt in fase 2 e.v. De nieuwe StoryPhase-waarden
  `PLANNING`/`PLANNED` zijn hier nog dood (worden in fase 2 actief).

## Aandachtspunten

- Houd de mapping naar het oude gedrag 1-op-1 zodat lopende issues niet
  stuklopen. De oude lineaire transitie `REFINED → DEVELOPING` blijft in deze
  fase intact (de goedkeuringsstappen komen pas in fase 2).
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
