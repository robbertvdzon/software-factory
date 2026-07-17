# Overzicht

Software Factory is een Spring Boot 3 / Kotlin applicatie die AI-agenten orkestreert voor werk uit de eigen tracker-database (Postgres). De applicatie bewaart run-status in PostgreSQL, start agenten als Docker-containers, laat die agenten werken in target GitHub repositories en synchroniseert resultaten terug naar de tracker-database en GitHub.

## Hoofdcomponenten

- Web dashboard: ingebouwde HTML-pagina's (login, dashboard, stories, my-actions, projects, nightly, agents, merged, downloads, settings) plus een aparte `dashboard-backend`/`dashboard-frontend` (Flutter) als extern dashboard.
- Orchestrator/pipeline: pollt de eigen tracker-database en stuurt het twee-laags procesmodel aan — `Story Phase` (refine/plan) op story-niveau en `Subtask Type`/`Subtask Phase` (de subtaak-keten) op subtaak-niveau.
- Agent runtime: start Docker containers met taakcontext, agent tips, secrets en repository-informatie.
- Agent worker CLI: draait binnen de container, bereidt de target repo voor, roept de gekozen AI supplier aan en schrijft `agent-result.json`.
- Persistence: PostgreSQL via JDBC en Flyway voor story runs, agent runs, events, kennis, verwerkte comments, Telegram-state en de nightly scheduler.
- Integraties: PostgreSQL (tracker-database), Git/GitHub CLI, Docker CLI, AI-CLI's (Claude Code/Codex/Copilot), Telegram Bot API en OpenShift/Kubernetes CLI voor previews/deploy-status.

## End-to-end flow

1. `OrchestratorPoller` (daemon-thread) roept `OrchestratorService.pollOnce()` aan.
2. `PostgresTrackerClient.findWorkIssues()` (intern `findAiIssues()`) zoekt issues in de geconfigureerde projecten (`SF_TRACKER_PROJECTS`, of alle projecten als die leeg is); `StoryPipelineService` filtert op een actieve `AI-supplier` (niet leeg/niet `none`), `Paused` en `Error`. Er is geen `Stage`-veldfilter en geen work-tag meer: de fase-gate bepaalt of een issue wordt opgepakt (lege fase = niet starten, `start` = oppakken). De query is een `UNION` van de top-N op `updated_at DESC` (de normale "recent bijgewerkt"-window, die rijen met een afgeronde `status` uitsluit via `core.FinishedStatus` — `done`/`fixed`/`verified`/`closed`/`resolved`, genormaliseerd lowercase) en alle issues met een niet-terminale `subtask_phase` (begrensd via `PENDING_SUBSET_LIMIT`, 500) — die union-tak filtert niet op `status`, zodat een actieve subtaak van een al-op-Done-gezette story bereikbaar blijft. Zo valt een (sub)taak die op een mens wacht (bv. `manual-approve-needed`) nooit buiten de poll, ook niet als er meer dan de top-N issues recenter zijn bijgewerkt of de story zelf al afgerond is.
3. `StoryPipelineService` routeert op het `Type`-veld: een story gaat naar `StoryRefinementCoordinator` (refine- en plan-stap op `Story Phase`), een subtaak naar `SubtaskExecutionCoordinator` (de keten op `Subtask Phase`).
4. Voor een agent-stap zet de coordinator de fase op de actieve waarde (`refining`, `planning`, `developing`, `reviewing`, `testing`, `summarizing`, `documenting`) en dispatcht via `AgentDispatcher`.
5. `StoryWorkspaceService` maakt of hergebruikt de story-workspace en de gedeelde story-branch; vóór een developer-run merget de factory de laatste main in de branch. `DockerAgentRuntime` schrijft taakcontext, agent tips en env, en start een agentcontainer (`agent:local`).
6. `AgentCli` (agentworker) draait in de container, bereidt de target repository voor en roept via `AiClientFactory` de AI-CLI aan; voor `mock` wordt een dummy resultaat gemaakt.
7. De agent laat wijzigingen uncommitted in de working tree staan; de agentworker faalt de run als de agent zelf een commit maakt. Het resultaat (outcome, usage, events, knowledge-updates) gaat naar `/work/agent-result.json` (gedeeld contract-DTO `AgentResultFile` in `factory-common`).
8. `AgentResultFileCompletionPoller` ziet dat de container klaar is en leest het resultaatbestand.
9. `AgentRunCompletionService.complete()` sluit de agent run: commit en pusht wijzigingen (altijd — er is geen uitgestelde sync meer), opent of hergebruikt een GitHub PR, schrijft events, werkt de fase in de tracker-database bij en materialiseert bij een planner-run de subtaken (`SubtaskPlanMaterializer`, inclusief de afgedwongen documentation-, manual-approve-, merge- en deploy-subtaken).
10. Zodra een subtaak zijn terminale fase bereikt, zet de keten de volgende subtaak op `start`. De merge-subtaak merget de PR automatisch (squash); de deploy-subtaak volgt de deploy-config uit `projects.yaml`.
11. Telegram meldt vragen/klaar/fouten en accepteert antwoorden en commands als reply; het dashboard toont openstaande menselijke acties op `/my-actions`.

## Belangrijkste fasen

Twee-laags model (zie `core/StoryPhase.kt` en `core/SubtaskPhase.kt`):

- **`Story Phase`**: `start → refining → refined[-with-questions] → refined-approved → planning → planned[-with-questions] → planning-approved → in-progress`, met reject-varianten (`refined-rejected`, `planning-rejected`) en antwoord-fasen (`questions-answered`, `planning-questions-answered`).
- **`Subtask Phase`**: per stap het patroon `start → *-ing → (*-with-questions ↔ *-questions-answered) → *-ed → *-approved | *-rejected`; niet-AI-stappen hebben eigen fasen (`awaiting-human`/`manual-action-done`, `manual-approve-needed`/`manually-approved`, `merging`/`merge-approved`, `deploying`/`deploy-approved`/`deploy-failed`).
- Het oude één-niveau `AI Phase`-veld (`core/AiPhase.kt`) bestaat nog als legacy-veld voor o.a. dispatch-bron en recovery, maar stuurt het proces niet meer.

## Dataopslag

Flyway maakt en beheert deze tabellen (migraties `V1`–`V17`):

- `story_runs`: overkoepelende run per story, inclusief target repo, workspace-pad, PR en preview metadata.
- `agent_runs`: individuele agentuitvoeringen per rol/container met usage en outcome.
- `agent_events`: events/logpayloads per agent run.
- `agent_knowledge`: herbruikbare agentkennis per target repo en rol.
- `processed_comments`: comments die al door een rol verwerkt zijn.
- `system_state`: globale state zoals credits-pauzes.
- `telegram_notifications`, `telegram_pending_questions`, `telegram_state`, `telegram_conversations`, `telegram_threads`: idempotente Telegram-meldingen en gespreksstate.
- `nightly_settings`, `nightly_run`, `nightly_run_job`: de nightly scheduler.
