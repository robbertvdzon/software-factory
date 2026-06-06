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
- Aanroepwijze: Spring JDBC via HikariCP connection pool; schema via Flyway.
- Configuratie: `SF_DATABASE_URL`, `SF_DATABASE_SCHEMA`.
- Lokale dependency: `docker-compose.yml` bevat een Postgres 16 container.

Gebruik:

- Story runs, agent runs, agent events en usage bijhouden.
- Agent knowledge opslaan.
- Verwerkte comments en globale system state opslaan.

## 3. Docker

- Code: `runtime/DockerAgentRuntime.kt`, `runtime/DockerLogFollower.kt`.
- Aanroepwijze: `docker` CLI via `CommandRunner`/`ProcessBuilder`.
- Image: `agent:local` (één gedeelde image voor alle rollen). Rol-specifieke rechten lopen via secrets: de kubeconfig wordt alleen aan tester en refiner gemount.

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

## 5. AI suppliers

- Code: `agent/AiClient.kt`, `agent/ai/claude/ClaudeCodeAiClient.kt`, `agent/ai/copilot/CopilotAiClient.kt`, `agent/ai/codex/CodexAiClient.kt`.
- Aanroepwijze: suppliers starten hun CLI met `ProcessBuilder`.
- Configuratie: `SF_AI_SUPPLIER`, `SF_AI_MODEL`, `SF_AI_EFFORT`, `SF_AI_OAUTH_TOKEN`, `SF_AI_CREDENTIALS_DIR` of `SF_COPILOT_CREDENTIALS_DIR`.

Gebruik:

- `mock`, `dummy`, `none` gebruiken `DummyAiClient`.
- `claude` gebruikt Claude Code met stream-json output.
- `openai` gebruikt de OpenAI/Codex adapter.
- `copilot` gebruikt de GitHub Copilot CLI adapter. Als `SF_COPILOT_CREDENTIALS_DIR` is gezet, mount Docker die login naar `/home/runner/.copilot` en wordt geen host `gh auth token` opgehaald. Alleen een expliciete token, of zonder credentials-mount de host `gh auth token` fallback, wordt tijdelijk als `COPILOT_GITHUB_TOKEN` aan Docker doorgegeven.
- `microsoft` bestaat als toekomstige supplierwaarde, maar is nog niet geimplementeerd.

Modelrouting:

- `AI Level` wordt in `AiRouting` vertaald naar `SF_AI_MODEL` en `SF_AI_EFFORT`.
- `claude` gebruikt de rol-specifieke legacy matrix uit de PNF factory (`claude-haiku-4-5`, `claude-sonnet-4-6`, `claude-opus-4-7`).
- `copilot` gebruikt level 0 `gpt-4.1`, level 1-3 `claude-haiku-4.5`, level 4-9 `claude-sonnet-4.5`, level 10 `claude-opus-4.5`.
- Claude krijgt effort als CLI-argument (`--effort`). Copilot krijgt effort alleen voor modellen die dat ondersteunen; voor `gpt-4.1` wordt geen `--effort` meegestuurd.

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
