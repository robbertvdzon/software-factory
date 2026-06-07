# SF-10 — Fase 4 — Subtask-keten (sequencing)

Subtaken worden een voor een afgewerkt via een keten (poll-gedreven).

- De mens tagt na `planning-approved` de eerste subtask `ai-development`.
- Zodra een subtask z'n eindstatus bereikt (`*-approved` / `manual-action-done`) tagt de OrchestratorPoller de eerstvolgende niet-afgeronde sibling (aanmaakvolgorde) en haalt de tag van de afgeronde.
- Altijd hooguit een subtask getagd -> samen met de parent-key-guard nooit twee agents op dezelfde branch.
