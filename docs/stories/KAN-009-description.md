# KAN-009 - Handmatige Bediening Via Comments

Story:
Als gebruiker wil ik de factory via Jira-comments kunnen pauzeren, hervatten,
killen, mergen, deleten of opnieuw laten implementeren, zodat ik controle houd
over lopende automatisering.

Subtaken:
[ ]: `@factory:command:pause`
[ ]: `@factory:command:resume`
[ ]: `@factory:command:kill`
[ ]: `@factory:command:delete`
[ ]: `@factory:command:merge`
[ ]: `@factory:command:re-implement`
[ ]: `LEVEL=N` trigger

Stappen:
[ ]: parse command comments idempotently
[ ]: set `Paused=true` for pause
[ ]: set `Paused=false` and clear applicable errors for resume
[ ]: kill active containers for kill/delete/merge/re-implement
[ ]: close PR/branch and cleanup preview resources where needed
[ ]: squash merge PR for merge command
[ ]: clear phase and restart flow for re-implement
[ ]: update AI Level for `LEVEL=N`
[ ]: add command parser and orchestration tests

Done / rationale:
- Nog niet geimplementeerd.
