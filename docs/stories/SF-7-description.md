# SF-7 — Fase 1 — Phase-enums splitsen + router op Type

Scheidt de twee niveaus in code.

- `AiPhase` gesplitst in `StoryPhase` (refinement-lifecycle) en `SubtaskPhase` (per-type subtask-pipeline).
- `OrchestratorService.processIssue` is een dunne router op het `Type`-veld: `User Story` -> story-flow, `Task` -> subtask-flow.
- Leest per IssueType het juiste phase-veld (`Story Phase` resp. `Subtask Phase`).
