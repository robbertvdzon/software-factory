# Externe systemen

Er zijn 7 hoofdgroepen externe systemen waarmee de code praat.

## 1. YouTrack

- Code: `youtrack/YouTrackClient.kt`
- Aanroepwijze: Java `HttpClient` met JSON requests.
- Configuratie: `SF_YOUTRACK_BASE_URL`, `SF_YOUTRACK_TOKEN`, optioneel `SF_YOUTRACK_PROJECTS`.

Gebruik:

- Projecten en issues ophalen.
- Factory custom fields aanmaken/controleren.
- Issues zoeken in `Stage: Develop`.
- Velden zoals `AI Phase`, `AI-supplier`, `Paused`, `Error` en tokenbudgetten bijwerken.
- Agentcomments posten.
- Comment-reactions gebruiken als verwerkingsmarker.
- Issue naar `Done` transitionen na merge.

## 2. PostgreSQL

- Code: `config/DatabaseConfiguration.kt`, `orchestrator/RunRepositories.kt`, `runtime/AgentEventRepository.kt`, `knowledge/AgentKnowledge.kt`.
- Aanroepwijze: Spring JDBC via `DriverManagerDataSource`; schema via Flyway.
- Configuratie: `SF_DATABASE_URL`, `SF_DATABASE_SCHEMA`.
- Lokale dependency: `docker-compose.yml` bevat een Postgres 16 container.

Gebruik:

- Story runs, agent runs, agent events en usage bijhouden.
- Agent knowledge opslaan.
- Verwerkte comments en globale system state opslaan.

## 3. Docker

- Code: `runtime/DockerAgentRuntime.kt`, `runtime/DockerLogFollower.kt`.
- Aanroepwijze: `docker` CLI via `CommandRunner`/`ProcessBuilder`.
- Images: `agent-base:local` voor refiner/developer/reviewer; `agent-tester:local` voor tester.

Gebruik:

- Agentcontainers starten met labels, env-file, workspace volume en rol-specifieke env vars.
- Lopende containers tellen voor concurrency-limieten.
- Containers killen voor handmatige stop/cleanup.
- Containerlogs volgen via `docker logs -f --timestamps`.

## 4. GitHub en Git repositories

- Code: `git/GitCommandClient.kt`, `github/GitHubGitHubApi.kt`, `agentworker/flows/TargetRepositoryFlow.kt`.
- Aanroepwijze: `git` CLI en `gh` CLI via process runners.
- Configuratie: `SF_GITHUB_TOKEN`.

Gebruik:

- Target repository clonen.
- Base- en storybranches uitchecken.
- Wijzigingen committen en pushen, automatisch of via handmatige `sync` afhankelijk van `SF_AUTO_SYNC_AFTER_AGENT`.
- Pull requests openen, vinden, sluiten, mergen en branch verwijderen.
- PR comments en reactions lezen/schrijven voor `@factory` feedback.

## 5. AI supplier / Claude Code

- Code: `agent/AiClient.kt`, `agent/ai/claude/ClaudeCodeAiClient.kt`.
- Aanroepwijze: voor `claude` wordt de `claude` CLI gestart met `ProcessBuilder`.
- Configuratie: `SF_AI_SUPPLIER`, `SF_AI_MODEL`, `SF_AI_EFFORT`, `SF_AI_OAUTH_TOKEN` of `SF_AI_CREDENTIALS_DIR`.

Gebruik:

- `mock`, `dummy`, `none` gebruiken `DummyAiClient`.
- `claude` gebruikt Claude Code met stream-json output.
- `openai` en `microsoft` bestaan als supplierwaarden, maar zijn nog niet geimplementeerd.

## 6. Preview/OpenShift/Kubernetes

- Code: `preview/TesterPreviewFlow.kt`, `preview/PreviewEnvironmentCleaner.kt`.
- Aanroepwijze: HTTP readiness checks met Java `HttpClient`, shell recipe via `bash -lc`, cleanup via `oc` CLI.
- Configuratie: deployment config uit `docs/factory`, `SF_KUBECONFIG`, preview env vars/templates.

Gebruik:

- Tester wacht tot preview URL HTTP 200 geeft.
- Preview database URL kan via een configureerbare shell recipe worden opgehaald.
- Na merge kan een preview namespace/project met `oc delete project` worden verwijderd.

## 7. Lokale desktop / IntelliJ IDEA

- Code: `web/services/FactoryDashboardService.kt`, `dashboard/api/DashboardController.kt`.
- Aanroepwijze: `open -a "IntelliJ IDEA" <repo-folder>` via `ProcessBuilder`.
- Configuratie: geen extra secrets; gebruikt het workspace-pad dat al in `story_runs.workspace_path` staat.

Gebruik:

- Story-detailpagina toont de work folder.
- De knop "Open in IntelliJ" opent alleen een bekende story-workspace op de lokale laptop.
- De browser/Flutter UI start geen shell-command direct; de lokale backend voert de actie uit.
