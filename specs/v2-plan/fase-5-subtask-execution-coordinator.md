# Fase 5 — SubtaskExecutionCoordinator (per-type pipelines + findings-loopback)

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md).
> Deze fase staat op zichzelf en kan los gerefined en opgepakt worden.

## Doel

Elk subtask-type z'n eigen kleine state machine geven, draaiend op de **gedeelde
story-branch**, met een loopback voor gevonden problemen.

## Per-type state machines (`SubtaskPhase`)

- **development**: `DEVELOPING → DEVELOPED → DONE` (+ manual feedback /
  user-vragen-loop zoals in de huidige developer-flow).
- **review**: `REVIEWING → REVIEW_FINISHED` | `REVIEW_WITH_FINDINGS`.
- **test**: `TESTING → TESTED_OK` | `TESTED_WITH_FINDINGS`.
- **manual**: `AWAITING_HUMAN → DONE` — geen dispatch; de gebruiker zet 'm op done
  via `@factory:command` of een veld. De coördinator slaat dit type over in de
  dispatch-route.

## Findings-loopback (beslissing 6)

Bij `REVIEW_WITH_FINDINGS` / `TESTED_WITH_FINDINGS`:

1. de reviewer/tester declareert findings in `agent-result.json` (zelfde kanaal
   als de planner in fase 3);
2. de orchestrator maakt daaruit een nieuwe **`development`-subtask** ("fix
   findings") aan;
3. `StoryDevelopmentCoordinator` (fase 4) pikt die als volgende op;
4. daarna draait de review/test desgewenst opnieuw.

- **Cap** toevoegen (analoog aan `maxDeveloperLoopbacks`) om eindeloos
  re-reviewen te voorkomen; bij overschrijding → error/handmatige triage.

## Aandachtspunten

- Subtask-dispatch hergebruikt de bestaande developer/reviewer/tester-rollen,
  maar nu **gescoped op een subtask** en werkend op de story-branch (zie fase 6).
- De oude story-niveau loopback (`REVIEWED_WITH_FEEDBACK_FOR_DEVELOPER` /
  `TESTED_WITH_FEEDBACK_FOR_DEVELOPER`) verhuist hiernaartoe; opruimen gebeurt in
  fase 7.

## Betrokken bestanden

- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/orchestrator/services/OrchestratorService.kt`
  (+ nieuwe `SubtaskExecutionCoordinator`)
- `.../runtime/RuntimeApi.kt` (findings in result, hergebruik `subtasks`-kanaal)
- `.../runtime/services/AgentRunCompletionService.kt` (findings → dev-subtask)

## Test

- Dev-subtask: `developing → developed → done`.
- Review met findings → er verschijnt een dev-subtask; na fix → re-review → ok.
- Cap stopt een blijvende findings-loop met een nette error.
- Manual-subtask wacht op de mens en dispatcht geen agent.

## Klaar wanneer

Elk subtask-type draait z'n eigen pipeline op de gedeelde branch, en gevonden
problemen leiden tot een nieuwe dev-subtask binnen de cap.
