# SF-6 — Fase 0 — YouTrack-modellering (story/subtask)

Modelleert het story/subtask-onderscheid in YouTrack.

- Custom fields, geprovisioneerd bij startup (ensureProjectSchema): `Story Phase`, `Subtask Phase`, `Subtask Type` (development/review/test/manual/summary), `AI Model`, `AI Reasoning Effort`.
- IssueType wordt afgeleid uit het standaard `Type`-veld: `User Story` -> STORY, `Task` -> SUBTASK.
- Twee work-tags: `ai-refinement` (op stories) en `ai-development` (op subtaken).
- `YouTrackClient.createSubtask` maakt een Task-subtask onder een story (Subtask-link via commands API) en erft de AI-supplier van de parent. `createCustomField` is idempotent (must-be-unique -> hergebruik).
- `AI Phase` en `AI Level` zijn uit de provisioning verwijderd.
