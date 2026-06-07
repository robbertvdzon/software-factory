# SF-11 — Fase 5 — Subtask-uitvoering (per-type pipelines)

De SubtaskExecutionCoordinator voert per subtask-type een pipeline uit op de gedeelde story-branch.

- Types: development (developer -> ingebouwde review), review en test (story-breed, simpele fix-developer), manual (awaiting-human), summary (summarizer).
- Elke AI-stap: `*-ing -> (*-with-questions <-> *-questions-answered) -> *-ed -> [goedkeuring] *-approved | *-rejected`.
- `*-rejected` start een developer-fix (interne loopback, begrensd door de cap); reviewer/tester mogen zelf `*-rejected` zetten.
- Dev/review/test/summary-agents (agentworker) emitten Subtask Phase-waarden.
