# Overzicht

Software Factory is een Spring Boot 3 / Kotlin applicatie die AI-agenten orkestreert voor werk uit YouTrack. De applicatie bewaart run-status in PostgreSQL, start agenten als Docker-containers, laat die agenten werken in target GitHub repositories en synchroniseert resultaten terug naar YouTrack en GitHub.

## Hoofdcomponenten

- Web dashboard: HTML endpoints voor login, dashboard, stories, agents, merged PRs, downloads en settings.
- Orchestrator: pollt YouTrack, bepaalt de volgende AI-fase en start de juiste agentrol.
- Agent runtime: start Docker containers met taakcontext, agent tips, secrets en repository-informatie.
- Agent worker CLI: draait binnen de container, cloned/checkt de target repo uit, roept de gekozen AI supplier aan en schrijft `agent-result.json`.
- Persistence: PostgreSQL via JDBC en Flyway voor story runs, agent runs, events, kennis en verwerkte comments.
- Integraties: YouTrack REST API, Git/GitHub CLI, Docker CLI, Claude Code CLI en OpenShift/Kubernetes CLI voor previews.

## End-to-end flow

1. `OrchestratorPoller` draait periodiek en roept `OrchestratorService.pollOnce()` aan.
2. `YouTrackClient` zoekt issues in `Stage: Develop` met een actieve `AI-supplier`.
3. `OrchestratorService` leest de `AI Phase` en kiest de volgende rol: refiner, developer, reviewer of tester.
4. De orchestrator zet de issuefase op een actieve waarde zoals `refining` of `developing`.
5. `DockerAgentRuntime` maakt een workspace, schrijft taakcontext, agent tips en env, en start een agentcontainer.
6. `AgentCli` draait in de container, bereidt de target repository voor en roept `AiClientFactory` aan.
7. Voor `mock` wordt een dummy resultaat gemaakt; voor `claude` wordt `claude` als CLI-proces gestart.
8. De developer-flow commit en pusht wijzigingen en opent of hergebruikt een GitHub PR.
9. De agent schrijft outcome, usage, events en eventuele knowledge updates naar `/work/agent-result.json`.
10. `AgentResultFileCompletionPoller` ziet dat de container klaar is en leest het resultaatbestand.
11. `AgentRunCompletionService` sluit de agent run, schrijft events, verwerkt comments, werkt YouTrack bij en slaat PR metadata op.
12. De orchestrator monitort later PR status en `@factory` PR-comments.

## Belangrijkste AI-fasen

- Actief: `refining`, `developing`, `reviewing`, `testing`.
- Klaar: `refined-finished`, `developed`, `review-finished`, `tested-successfully`.
- Feedbackloops: `reviewed-with-feedback-for-developer`, `tested-with-feedback-for-developer`.
- Wachtstand: `refined-with-questions-for-user`.
- Herstart refinement: `questions-answered-for-refinement`.

## Dataopslag

Flyway maakt en beheert deze tabellen:

- `story_runs`: overkoepelende run per issue/story, inclusief target repo, PR en preview metadata.
- `agent_runs`: individuele agentuitvoeringen per rol/container met usage en outcome.
- `agent_events`: events/logpayloads per agent run.
- `agent_knowledge`: herbruikbare agentkennis per target repo en rol.
- `processed_comments`: comments die al door een rol verwerkt zijn.
- `system_state`: globale state zoals credits-pauzes.
