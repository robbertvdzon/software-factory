# KAN-008 - Budget, Tokens En AI Credits

Story:
Als gebruiker wil ik tokenverbruik, per-ticket budgetten en systeem-brede
AI-credit pauzes kunnen bewaken, zodat de factory niet onbeperkt kosten of
credits verbruikt.

Subtaken:
[x]: Token totals bijhouden per story
[x]: Cost-monitor thresholds 75/90/100%
[x]: `BUDGET=N` en `CONTINUE` triggers
[x]: System-wide AI credits pause
[x]: `factory credits pause/resume` CLI commands

Stappen:
[x]: persist token usage per agent run
[x]: aggregate usage into story run totals
[x]: sync `AI Tokens Used` back to Jira
[x]: post cost-monitor comments idempotently
[x]: pause ticket at 100 percent budget
[x]: parse budget/continue triggers
[x]: detect credits-exhausted outcomes
[x]: write and observe system-wide pause state
[x]: implement manual credits CLI override

Done / rationale:
- Start KAN-008: specs voor cost-monitor, budget thresholds, Jira budget-triggers en systeem-brede AI-credit pauze gelezen. De bestaande completion-flow bewaart usage per `agent_runs` row en telt die op in `story_runs`; de resterende implementatie hangt budgetbewaking en credits-pauzes aan die data.
- `StoryRunRecord` exposeert nu totalen uit `story_runs`, completion haalt de bijgewerkte run op en triggert direct de budgetcheck.
- De cost-monitor schrijft `AI Tokens Used`, post idempotente `[COST-MONITOR]` comments voor 75/90/100%, en zet `Paused = true` wanneer totaalgebruik het budget bereikt.
- `BUDGET=N` en `CONTINUE` worden verwerkt via dezelfde processed-comment markers als andere Jira-comment flows, zodat budgetwijzigingen niet herhaald worden.
- `credits-exhausted` is een dummy outcome geworden die de completion-flow omzet naar een systeem-brede pauze in `system_state`; de orchestrator blokkeert nieuwe dispatches zolang die pauze actief is.
- `./factory credits pause --until ...` en `./factory credits resume` zijn toegevoegd en tegen de echte `software_factory.system_state` getest; de testpauze is direct weer gereset.
- Tests dekken budget-thresholds, pauzeren op 100%, budget/continue triggers, credits-pauze, orchestrator dispatch-blokkade en completion handling.
