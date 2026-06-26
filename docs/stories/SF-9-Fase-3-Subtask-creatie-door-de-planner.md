# SF-9 — Fase 3 — Subtask-creatie door de planner

De planner declareert subtaken; de orchestrator materialiseert ze.

- De planner-agent zet `subtasks: [...]` in agent-result.json; de completion-handler roept per spec `createSubtask` aan zodra de planner `planned` bereikt (niet bij vragen).
- Idempotent (op bestaande child-titels) zodat een re-plan geen duplicaten maakt.
- Subtaken krijgen `Type=Task`, `Subtask Type`, geërfde AI-supplier, optioneel model/effort, en geen tag (inert tot `ai-development`).
