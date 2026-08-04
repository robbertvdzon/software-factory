# Externe systemen

Er zijn 6 hoofdgroepen externe systemen waarmee de code praat.

## 1. PostgreSQL

- Code: `config/DatabaseConfiguration.kt`, `tracker/clients/PostgresTrackerClient.kt` (achter smalle
  tracker-capabilities), `tracker/clients/PostgresIssueKeySequence.kt`, en de repository-klassen in
  `orchestrator`, `runtime`, `knowledge`, `telegram`,
  `audit` en `maintenance`.
- Aanroepwijze: Spring JDBC via HikariCP connection pool; schema via Flyway (`V1`–`V31`).
- Configuratie: `SF_DATABASE_URL`, `SF_DATABASE_SCHEMA`, optioneel `SF_TRACKER_PROJECTS`.
- Lokale dependency: `docker/docker-compose.yml` bevat een Postgres 16 container.

Gebruik:

- Stories en subtaken (tracker-issues) in de unified `issues`-tabel (`V15__tracker_issues.sql`):
  aanmaken, zoeken en fase-/budget-/`Error`-velden bijwerken. Het nullable `retry_after`
  (`V27__claude_quota_retry_after.sql`) bewaart de automatische Claude-quota-wachtstand los van
  de handmatige `paused`-vlag; de partial index houdt deze issues buiten poll-limieten vindbaar.
  V29 zet uitsluitend de `notify_mode`-aanmaakdefault op `als-klaar-en-gedeployed`, zonder
  bestaande rijen bij te werken.
- Issues zoeken in de geconfigureerde projecten (`SF_TRACKER_PROJECTS`, of alle projecten als die
  leeg is); de pipeline filtert op een actieve `AI-supplier`. Er is geen `Stage`-veldfilter en geen
  work-tag meer: de fase-gate (lege fase = niet starten, `start` = oppakken) bepaalt het werk.
- Agentcomments posten (`issue_comments`) en attachments opslaan (`issue_attachments`);
  comment-verwerking bijhouden als markering.
- Story runs, agent runs, agent events en usage bijhouden. `agent_runs` bewaart sinds V28 ook de
  Claude-rate-limitstatus en beide reset-timestamps, zodat mislukte quotaruns persistent uit de
  transient-retryboekhouding kunnen worden gefilterd.
- Historie van álle opruimrondes opslaan en uitlezen (`maintenance_cleanup_runs`,
  `V30__maintenance_cleanup_runs.sql` + `V31__maintenance_cleanup_kinds.sql`): één rij per ronde met
  een `kind`-kolom en een nullable `project`, nieuwste eerst voor het dashboard en optioneel op
  project en/of soort gefilterd, met eigen retentie via `deleteOlderThan`.
- Retentie op de agent-tabellen: `AgentEventRetentionPoller` verwijdert oude `agent_events`,
  `AgentRunRetentionPoller` (SF-1921) oude afgeronde `agent_runs` — batchgewijs, met `ON DELETE
  CASCADE` naar events en completion-rijen. Zie `scheduled-jobs.md` §8.
- Agent knowledge opslaan.
- Verwerkte comments en globale system state opslaan.
- Telegram-meldingen/threads idempotent bijhouden; audit-runs en -rapporten.

## 2. Docker

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

## 3. GitHub en Git repositories

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
- Oude GitHub-Releases (+ hun git-tag) en ghcr.io-package-versions opruimen: de `maintenance`-module
  praat daarvoor rechtstreeks met `api.github.com` via een raw `HttpClient` en de gedeelde
  `SF_GITHUB_TOKEN` (`maintenance/services/GitHubReleaseCleanupClient.kt`,
  `GitHubPackageCleanupClient.kt`, `GitHubProtectedShaSource.kt`), niet via de `gh` CLI.

## 4. AI suppliers

- Code (agentworker): `agent/AiClient.kt`, `agent/ai/claude/ClaudeCodeAiClient.kt`,
  `agent/ai/copilot/CopilotAiClient.kt`, `agent/ai/codex/CodexAiClient.kt`.
- Aanroepwijze: suppliers leveren argv en een expliciete subprocess-env aan de gedeelde
  `agent/ai/shared/CliProcessRunner`; alleen die adapter start het proces en sluit stdin direct.
- Configuratie: `SF_AI_SUPPLIER`, `SF_AI_MODEL`, `SF_AI_EFFORT`, `SF_AI_OAUTH_TOKEN`,
  `SF_AI_CREDENTIALS_DIR` of `SF_COPILOT_CREDENTIALS_DIR`.
- Directe environment-, process- en HTTP-boundaries staan exact geregistreerd in
  `architecture/composition-root-boundaries.txt` en worden fail-closed gecontroleerd met
  `tools/check-composition-roots`.

Gebruik:

- `mock`, `dummy`, `none` gebruiken `DummyAiClient`.
- `claude` gebruikt Claude Code met stream-json output. De parser bewaart van het laatste bruikbare
  `rate_limit_event` status, `resetsAt` en `overageResetsAt`; een mislukte run met een blokkerend
  signaal of quota-specifieke fouttekst wordt automatisch tot `retry_after` uitgesteld.
- `openai` gebruikt de OpenAI/Codex adapter.
- `copilot` gebruikt de GitHub Copilot CLI adapter. Als `SF_COPILOT_CREDENTIALS_DIR` is gezet,
  mount Docker die login naar `/home/runner/.copilot` en wordt geen host `gh auth token`
  opgehaald. Alleen een expliciete token, of zonder credentials-mount de host `gh auth token`
  fallback, wordt tijdelijk als `COPILOT_GITHUB_TOKEN` aan Docker doorgegeven.
- `microsoft` bestaat als toekomstige supplierwaarde, maar is nog niet geimplementeerd.

Modelrouting (`core/AiRouting.kt`):

- `AI Level` (0–10, default 3) wordt vertaald naar `SF_AI_MODEL` en `SF_AI_EFFORT`.
- `claude` gebruikt **altijd `claude-opus-5`** als default, ongeacht rol of level; alleen de
  effort schaalt mee met het level (0–2 `low`, 3–7 `medium`, 8–10 `high`). De oude
  rol-specifieke modelmatrix bestaat niet meer. Per story/subtaak kan een expliciet `AI Model`
  gekozen worden uit `AiRouting.MODELS_BY_SUPPLIER`.
- `copilot` gebruikt level 0 `gpt-4.1`, level 1-3 `claude-haiku-4.5`, level 4-9
  `claude-sonnet-4.5`, level 10 `claude-opus-4.5`.
- Voor `openai` kan expliciet worden gekozen uit `gpt-5.6-sol` (hoogste kwaliteit),
  `gpt-5.6-terra` (balans tussen kwaliteit en kosten) en `gpt-5.6-luna`
  (snelste/goedkoopste variant).
- Claude krijgt effort als CLI-argument (`--effort`). Copilot krijgt effort alleen voor modellen
  die dat ondersteunen; voor `gpt-4.1` wordt geen `--effort` meegestuurd.

## 5. Preview/OpenShift/Kubernetes

- Code: `preview/` in factory-common (`PreviewTemplateRenderer`, `PreviewEnvironmentCleaner`),
  `agentworker/flows/TesterPreviewFlow.kt`, en voor de deploy-subtaak de poort
  `core/contracts/DeploymentStatusProbe` met adapter `runtime/commands/KubectlDeploymentStatusProbe.kt`.
- Aanroepwijze: HTTP readiness checks met Java `HttpClient`, shell recipe via `bash -lc`,
  cleanup via `oc` CLI, deploy-status via `kubectl`.
- Configuratie: deployment config uit `docs/factory/deployment.md` van de target repo,
  `SF_KUBECONFIG`, preview env vars/templates in `projects.yaml`.

Gebruik:

- Tester wacht tot preview URL HTTP 200 geeft; de wachttijd staat default op 1200s
  (`SF_PREVIEW_WAIT_TIMEOUT_SECONDS`, interval `SF_PREVIEW_WAIT_INTERVAL_SECONDS` default 15s).
- Preview database URL kan via een configureerbare shell recipe worden opgehaald.
- Na merge kan een preview namespace/project met `oc delete project` worden verwijderd.
- De deploy-subtaak verifieert op de daadwerkelijk live SHA i.p.v. blind te wachten (SF-771),
  default-timeout 1200s (per project via `timeoutMinutes`):
  - `rest-restart` — pollt `versionUrl` (`/api/version`) tot `commitHash` prefix-matcht met de
    verwachte merge-SHA (base-branch HEAD ná merge). Ontbreekt de SHA-info, dan terugval op het
    "opnieuw opgestart"-gedrag.
  - `openshift-watch` — met `argocdApp` + `argocdNamespace` is ArgoCD de waarheidsbron: de
    `Application`-CR moet `Synced` + `Healthy` + `operationState=Succeeded` op de verwachte revisie
    zijn (`DeploymentStatusProbe.argoApplicationStatus(...)` via `kubectl get application`). Zonder
    die velden geldt de bestaande "image niet-leeg"-heuristiek.

## 6. Telegram en lokale desktop

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
  replies/commands; bij meldingen=`na-elke-stap` bovendien hoogstens één informatieve
  Claude-quotamelding per ingesteld `retry_after`. De conversationele assistent draait in een
  eigen container.
- De knop "Open in IntelliJ" opent alleen een bekende story-workspace op de lokale machine;
  de browser/Flutter UI start geen shell-command direct.
