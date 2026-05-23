# KAN-008 - Budget, Tokens En AI Credits

Story:
Als gebruiker wil ik tokenverbruik, per-ticket budgetten en systeem-brede
AI-credit pauzes kunnen bewaken, zodat de factory niet onbeperkt kosten of
credits verbruikt.

Subtaken:
[ ]: Token totals bijhouden per story
[ ]: Cost-monitor thresholds 75/90/100%
[ ]: `BUDGET=N` en `CONTINUE` triggers
[ ]: System-wide AI credits pause
[ ]: `factory credits pause/resume` CLI commands

Stappen:
[ ]: persist token usage per agent run
[ ]: aggregate usage into story run totals
[ ]: sync `AI Tokens Used` back to Jira
[ ]: post cost-monitor comments idempotently
[ ]: pause ticket at 100 percent budget
[ ]: parse budget/continue triggers
[ ]: detect credits-exhausted outcomes
[ ]: write and observe system-wide pause state
[ ]: implement manual credits CLI override

Done / rationale:
- Nog niet geimplementeerd.
