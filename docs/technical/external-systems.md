# Externe systemen

Er zijn 7 hoofdgroepen externe systemen waarmee de code praat.

## 1. YouTrack

- Code: `youtrack/clients/YouTrackClient.kt` (sinds de refactor gesplitst in client +
  `YouTrackHttpTransport`, `YouTrackIssueMapper` en `YouTrackSchemaBootstrapper`).
- Aanroepwijze: Java `HttpClient` met JSON requests.
- Configuratie: `SF_YOUTRACK_BASE_URL`, `SF_YOUTRACK_TOKEN`, optioneel `SF_YOUTRACK_PROJECTS`
  en `SF_YOUTRACK_PUBLIC_URL` (publieke URL voor links).

Gebruik:

- Projecten en issues ophalen.
- Factory custom fields aanmaken/controleren via de schema-bootstrap (o.a. `Story Phase`,
  `Subtask Phase`, `Subtask Type`, `Repo`, `AI-supplier`, `AI Level`, `AI Model`, `Paused`,
  `Silent`, `Auto-approve`, `Error`).
- Issues zoeken in de geconfigureerde projecten (`SF_YOUTRACK_PROJECTS`, of alle
  niet-gearchiveerde projecten als die leeg is); de pipeline filtert client-side op een actieve
  `AI-supplier`. Er is geen `Stage`-veldfilter en geen work-tag meer: de fase-gate
  (lege fase = niet starten, `start` = oppakken) bepaalt het werk.
- Fase-velden, budgetvelden en `Error` bijwerken; subtaken aanmaken/verwijderen bij het
  materialiseren van het plan.
- Agentcomments posten; comment-reactions als verwerkingsmarker gebruiken.
- Issue naar `Done` transitionen na merge.

## 2. PostgreSQL

- Code: `config/DatabaseConfiguration.kt`, de repository-klassen in `orchestrator`, `runtime`,
  `knowledge`, `telegram` en `nightly`.
- Aanroepwijze: Spring JDBC via HikariCP connection pool; schema via Flyway (`V1`–`V14`).
- Configuratie: `SF_DATABASE_URL`, `SF_DATABASE_SCHEMA`.
- Lokale dependency: `docker/docker-compose.yml` bevat een Postgres 16 container.

Gebruik:

- Story runs, agent runs, agent events en usage bijhouden.
- Agent knowledge opslaan.
- Verwerkte comments en globale system state opslaan.
- Telegram-meldingen/threads idempotent bijhouden; nightly-runs en -jobs.

## 3. Docker

- Code: `runtime/DockerAgentRuntime.kt`, `runtime/DockerLogFollower.kt`.
- Aanroepwijze: `docker` CLI via `CommandRunner`/`ProcessBuilder`.
- Image: `agent:local` (één gedeelde image voor alle rollen, `Dockerfile.agent`). Rol-specifieke
  rechten lopen via secrets: de kubeconfig wordt alleen aan tester en refiner gemount. De
  Telegram-assistent draait in een eigen image (`Dockerfile.assistant`, `SF_ASSISTANT_IMAGE`,
  default `assistant:local`).

Gebruik:

- Agentcontainers starten met labels, env-file, workspace volume en rol-specifieke env vars.
- Lopende containers tellen voor concurrency-limieten.
- Containers killen voor handmatige stop/cleanup.
- Containerlogs volgen via `docker logs -f --timestamps`.

## 4. GitHub en Git repositories

- Code: `git/services/GitCommandClient.kt` en `github/clients/GitHubCliClient.kt` in
  **factory-common** (gedeeld tussen `softwarefactory` en `agentworker`; de vroegere kopieën in
  agentworker zijn verwijderd), plus `agentworker/flows/TargetRepositoryFlow.kt`.
- Aanroepwijze: `git` CLI en `gh` CLI via process runners.
- Configuratie: `SF_GITHUB_TOKEN`.

Gebruik:

- Target repository clonen; base- en storybranches uitchecken.
- Vóór een developer-run de laatste main in de story-branch mergen (`mergeBaseIntoBranch`);
  de agent lost eventuele conflict-markers op.
- Wijzigingen committen en pushen — altijd automatisch na elke geslaagde agent-run die de repo
  raakt (`AgentRunCompletionService`); er is geen uitgestelde/handmatige sync-vlag meer.
- Pull requests openen, vinden, sluiten, mergen (squash) en branch verwijderen.
- PR comments en reactions lezen/schrijven voor `@factory` feedback.

## 5. AI suppliers

- Code (agentworker): `agent/AiClient.kt`, `agent/ai/claude/ClaudeCodeAiClient.kt`,
  `agent/ai/copilot/CopilotAiClient.kt`, `agent/ai/codex/CodexAiClient.kt`.
- Aanroepwijze: suppliers starten hun CLI met `ProcessBuilder`.
- Configuratie: `SF_AI_SUPPLIER`, `SF_AI_MODEL`, `SF_AI_EFFORT`, `SF_AI_OAUTH_TOKEN`,
  `SF_AI_CREDENTIALS_DIR` of `SF_COPILOT_CREDENTIALS_DIR`.

Gebruik:

- `mock`, `dummy`, `none` gebruiken `DummyAiClient`.
- `claude` gebruikt Claude Code met stream-json output.
- `openai` gebruikt de OpenAI/Codex adapter.
- `copilot` gebruikt de GitHub Copilot CLI adapter. Als `SF_COPILOT_CREDENTIALS_DIR` is gezet,
  mount Docker die login naar `/home/runner/.copilot` en wordt geen host `gh auth token`
  opgehaald. Alleen een expliciete token, of zonder credentials-mount de host `gh auth token`
  fallback, wordt tijdelijk als `COPILOT_GITHUB_TOKEN` aan Docker doorgegeven.
- `microsoft` bestaat als toekomstige supplierwaarde, maar is nog niet geimplementeerd.

Modelrouting (`core/AiRouting.kt`):

- `AI Level` (0–10, default 3) wordt vertaald naar `SF_AI_MODEL` en `SF_AI_EFFORT`.
- `claude` gebruikt **altijd `claude-opus-4-8`** als default, ongeacht rol of level; alleen de
  effort schaalt mee met het level (0–2 `low`, 3–7 `medium`, 8–10 `high`). De oude
  rol-specifieke modelmatrix bestaat niet meer. Per story/subtaak kan een expliciet `AI Model`
  gekozen worden uit `AiRouting.MODELS_BY_SUPPLIER`.
- `copilot` gebruikt level 0 `gpt-4.1`, level 1-3 `claude-haiku-4.5`, level 4-9
  `claude-sonnet-4.5`, level 10 `claude-opus-4.5`.
- Claude krijgt effort als CLI-argument (`--effort`). Copilot krijgt effort alleen voor modellen
  die dat ondersteunen; voor `gpt-4.1` wordt geen `--effort` meegestuurd.

## 6. Preview/OpenShift/Kubernetes

- Code: `preview/` in factory-common (`PreviewTemplateRenderer`, `PreviewEnvironmentCleaner`),
  `agentworker/flows/TesterPreviewFlow.kt`, en voor de deploy-subtaak de poort
  `core/DeploymentStatusProbe` met adapter `runtime/commands/KubectlDeploymentStatusProbe.kt`.
- Aanroepwijze: HTTP readiness checks met Java `HttpClient`, shell recipe via `bash -lc`,
  cleanup via `oc` CLI, deploy-status via `kubectl`.
- Configuratie: deployment config uit `docs/factory/deployment.md` van de target repo,
  `SF_KUBECONFIG`, preview env vars/templates in `projects.yaml`.

Gebruik:

- Tester wacht tot preview URL HTTP 200 geeft.
- Preview database URL kan via een configureerbare shell recipe worden opgehaald.
- Na merge kan een preview namespace/project met `oc delete project` worden verwijderd.
- De deploy-subtaak kan een OpenShift-rollout volgen (`openshift-watch`) of een
  rest-restart doen, volgens `projects.yaml`.

## 7. Telegram en lokale desktop

- Code: `telegram/` (`TelegramClient`, `TelegramNotificationService`, `TelegramPoller`,
  `TelegramAssistantService`); voor de desktop `web/services/WorkspaceDesktopLauncher.kt`
  (softwarefactory) en `dashboard/api/WorkspaceOpener.kt` (dashboard-backend).
- Aanroepwijze: Telegram Bot API over HTTPS; `open -a "IntelliJ IDEA" <repo-folder>` via
  `ProcessBuilder`.
- Configuratie: `SF_TELEGRAM_BOT_TOKEN`, `SF_TELEGRAM_CHAT_ID` (+ kanalen per project in
  `projects.yaml`); het IntelliJ-endpoint van de dashboard-backend zit achter
  `SF_DASHBOARD_LOCAL_MODE=true` (default uit, veilig in k8s).

Gebruik:

- Meldingen bij vragen/klaar/fouten (inclusief testrapport en screenshots) en tweerichtings
  replies/commands; de conversationele assistent draait in een eigen container.
- De knop "Open in IntelliJ" opent alleen een bekende story-workspace op de lokale machine;
  de browser/Flutter UI start geen shell-command direct.
