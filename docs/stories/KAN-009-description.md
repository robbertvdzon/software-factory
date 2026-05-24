# KAN-009 - Handmatige Bediening Via Comments

Story:
Als gebruiker wil ik de factory via Jira-comments kunnen pauzeren, hervatten,
killen, mergen, deleten of opnieuw laten implementeren, zodat ik controle houd
over lopende automatisering.

Subtaken:
[x]: `@factory:command:pause`
[x]: `@factory:command:resume`
[x]: `@factory:command:kill`
[x]: `@factory:command:delete`
[x]: `@factory:command:merge`
[x]: `@factory:command:re-implement`
[x]: `LEVEL=N` trigger

Stappen:
[x]: parse command comments idempotently
[x]: set `Paused=true` for pause
[x]: set `Paused=false` and clear applicable errors for resume
[x]: kill active containers for kill/delete/merge/re-implement
[x]: close PR/branch and cleanup preview resources where needed
[x]: squash merge PR for merge command
[x]: clear phase and restart flow for re-implement
[x]: update AI Level for `LEVEL=N`
[x]: add command parser and orchestration tests

Done / rationale:
- Start KAN-009: specs voor comment-commando's, status-uitzonderingen voor delete/merge en `LEVEL=N` gelezen. Implementatie splitst parsing/idempotentie van command-uitvoering, en hergebruikt bestaande Docker labels, GitHub PR metadata en preview-cleaner.
- `ManualCommandService` verwerkt alleen user-comments, markeert verwerkte comments via de bestaande Jira-marker/DB-fallback en voert `pause`, `resume`, `kill`, `delete`, `merge`, `re-implement` en `LEVEL=N` uit voordat normale orchestration verdergaat.
- Docker-runtime kan nu alle containers voor een story killen via de bestaande labels. GitHub PR-client kan PR's sluiten, branches verwijderen en PR's squash-mergen.
- `delete` ruimt container, PR, branch en preview namespace op, prefixet de Jira-summary met `(CANCELLED)`, sluit de story-run en zet Jira naar `Done`. `merge` squash-merget de PR, ruimt preview op, sluit de story-run en zet Jira naar `Done`.
- `re-implement` killt containers, sluit PR en branch, verwijdert preview namespace en agent-comments, sluit de huidige story-run en wist `AI Phase`, `Paused` en `Error` zodat de factory opnieuw vanaf het begin kan starten.
- Tests toegevoegd voor manual command orchestration, Docker kill, GitHub manual PR-acties en Jira summary/comment delete. Verificatie: `mvn test`, Spring-start tegen schema `software_factory`, en `docker ps`.
