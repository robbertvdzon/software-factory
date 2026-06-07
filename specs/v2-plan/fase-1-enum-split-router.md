# Fase 1 — Phase-enums splitsen + router (structurele refactor)

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md).
> Deze fase staat op zichzelf en kan los gerefined en opgepakt worden.

## Doel

De twee niveaus (story / subtask) in code scheiden: `StoryPhase` + `SubtaskPhase`
introduceren, en `OrchestratorService.processIssue()` een dunne **router op het
`Type`-veld** maken. Puur structureel:

- de **STORY-tak** draait in fase 1 nog de bestaande `AiPhase`-flow, zodat de
  codebase functioneel én test-groen blijft (story-gedrag onveranderd);
- de **SUBTASK-tak** is een **stub** (overslaan) — subtask-uitvoering komt in fase 5.

Fase 2 vervangt de STORY-tak door de refinement-coördinator (`StoryPhase` +
goedkeuringen); fase 7 verwijdert de oude `AiPhase`.

## Wijzigingen

- Splits `AiPhase` (`orchestrator/AiPhase.kt`) in twee enums, gekoppeld aan de
  twee velden uit fase 0:

  **`StoryPhase`** (veld `Story Phase`) — puur de refinement-lifecycle. De
  enum-constanten (refine-stap, daarna plan-stap):
  ```
  // refine-stap
  REFINING, REFINED_WITH_QUESTIONS, QUESTIONS_ANSWERED,
  REFINED, REFINED_REJECTED, REFINED_APPROVED,
  // plan-stap
  PLANNING, PLANNED_WITH_QUESTIONS, PLANNING_QUESTIONS_ANSWERED,
  PLANNED, PLANNING_REJECTED, PLANNING_APPROVED,
  ```
  De **transities** (wie zet welke status en wat de orchestrator doet) staan in de
  status→actie-tabel in [README §3](./README.md#3-twee-laags-state-machine) en
  worden in **fase 2** geïmplementeerd. `PLANNING_APPROVED` is terminaal voor de
  orchestrator; development is tag-gedreven (fase 4). **Geen**
  `DEVELOPING`/`DONE`/`SUMMARIZING` op story-niveau.
  - In déze fase is dit puur de enum-definitie + router-structuur; de echte
    refinement-transities (goedkeuringen) komen in fase 2, de tag-gedreven
    development in fase 4. Het oude story-niveau dev/review/test-pad wordt in
    **fase 7** opgeruimd.

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
  AWAITING_HUMAN, MANUAL_ACTION_DONE
  ```
  De terminale status per type is het laatste `*-approved` (development/review:
  `REVIEW_APPROVED`; test: `TEST_APPROVED`; summary: `SUMMARY_APPROVED`); manual
  eindigt op `MANUAL_ACTION_DONE`. Er is dus geen generieke `DONE`-subtaskstatus.
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
- De story-coördinator is in deze fase structureel: hij routeert en leest het
  juiste phase-veld. De inhoudelijke refinement-transities (refiner/planner +
  goedkeuringen) worden in **fase 2** ingevuld; het SUBTASK-pad in fase 5.

## Aandachtspunten

- We starten vers (nieuw project, **geen lopende v1-issues**). Deze fase is een
  kleine, testbare structurele stap: **story-gedrag blijft gelijk** (de bestaande
  `AiPhase`-flow draait onder de STORY-tak); alleen de nieuwe SUBTASK-tak komt
  erbij als stub. Het oude pad wordt in fase 2 vervangen en in fase 7 verwijderd.
- Recovery-/timeout-/budgetlogica blijft ongewijzigd in deze fase.
- Subtaken worden nog niet uitgevoerd (dat is fase 5); de SUBTASK-tak slaat ze over.

## Betrokken bestanden

- `.../orchestrator/StoryPhase.kt` (nieuw), `.../orchestrator/SubtaskPhase.kt` (nieuw)
- `.../orchestrator/services/OrchestratorService.kt` (router op IssueType + `processStory`)
- `.../youtrack/TrackerModels.kt` (`IssueType` — uit fase 0)
- `.../orchestrator/AiPhase.kt` (blijft; STORY-flow draait er nog op; weg in fase 7)

## Test

- `StoryPhase`/`SubtaskPhase` bestaan en matchen de README/provisioning-waarden.
- Router kiest STORY bij `Type = User Story` (bestaande flow,
  `OrchestratorServiceTest` 18/18 groen) en SUBTASK bij `Type = Task` (overgeslagen
  tot fase 5).
- Volledige suite groen, op de 2 pre-existing `FactoryDashboardViewsTest`-failures
  na (bestonden al op master).

## Klaar wanneer

`StoryPhase`/`SubtaskPhase` bestaan, de router routeert op `Type`, story-gedrag is
onveranderd en de codebase compileert met groene tests. De inhoudelijke
refinement-flow volgt in fase 2.
