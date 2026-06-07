# SF-13 — Fase 7 — Legacy opruimen + PR-comment-route

Verwijdert het oude pad en trekt PR-feedback in het subtask-model.

- Het lineaire `AiPhase`-story-pad (processStory) en de bijbehorende recovery zijn verwijderd; de router draait alleen nog de v2-flow.
- Transient-retry is veld-agnostisch (leeg `Error`, recovery herstart de actieve stap).
- Late `@factory` PR-comments maken nu een development-subtask i.p.v. een story-phase-reset.
- `specs/specs.md` beschrijft de actuele applicatie.
