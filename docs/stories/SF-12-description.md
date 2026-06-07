# SF-12 — Fase 6 — Gedeelde machinerie subtask-bewust

Dispatch/recovery/budget per subtask, met gedeelde branch/workspace/PR op story-niveau.

- Subtask-dispatch keyt storyRun + Docker `story-key`-label op de parent (serialisatie-guard ziet subtask-containers).
- Pauze en budget worden op de parent-story gecheckt; de subtask-agent krijgt de parent story-tekst als context; model/effort komen per subtask uit de velden.
- Per-subtask hard-timeout-recovery; agent-runs zijn per subtask traceerbaar (`agent_runs.subtask_key`).
- Budget pauzeert alleen bij 100% (geen tussentijdse comments).
