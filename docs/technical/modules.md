# Modules

De software-factory applicatiecode staat onder `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory`. De agent worker staat onder `agentworker/src/main/kotlin/nl/vdzon/softwarefactory/agentworker`. Er zijn 14 directe packages/modules in de software-factory applicatie.

## Root

- Package: `nl.vdzon.softwarefactory`
- Belangrijkste bestand: `SoftwareFactoryApplication.kt`
- Verantwoordelijkheid: Spring Boot applicatie starten en scheduling activeren.

## agent

- Belangrijkste bestanden: `AgentApi.kt`, `AiClient.kt`, `ClaudeCodeAiClient.kt`.
- Verantwoordelijkheid: AI-client abstractie en supplier-implementaties.

Taken:

- AI supplier kiezen en uitvoeren.
- Claude Code CLI aanroepen.
- Dummy/mock resultaten maken voor lokale tests.
- AI usage, events en knowledge drafts modelleren.

## agentworker

- Belangrijkste bestanden: `AgentWorkerApi.kt`, `cli/AgentCli.kt`, `flows/TargetRepositoryFlow.kt`, `flows/TesterPreviewFlow.kt`.
- Verantwoordelijkheid: standalone agentproces dat in de Docker-container draait.

Taken:

- Env vars, taakcontext en agent tips uit de workspace lezen.
- Target repository voorbereiden.
- Factory docs en previewcontext toevoegen aan de taakprompt.
- `agent` module aanroepen voor de gekozen AI supplier.
- Developer-resultaten committen, pushen en in een PR zetten.
- Resultaat schrijven naar `/work/agent-result.json`; YouTrack en factory-server HTTP worden niet direct aangeroepen.

## cli

- Belangrijkste bestand: `CreditsCli.kt`.
- Verantwoordelijkheid: command-line tooling rond credits/pauzes.

## config

- Belangrijkste bestanden: `FactorySecrets.kt`, `FactorySecretsConfiguration.kt`, `SecretsEnvLoader.kt`, `DatabaseConfiguration.kt`.
- Verantwoordelijkheid: secrets, environment en databaseconfiguratie.

Taken:

- Vereiste secrets valideren.
- `secrets.env` en environment values laden.
- PostgreSQL datasource maken.
- Flyway schema/migraties configureren.

## docs

- Belangrijkste bestanden: `FactoryDocs.kt`, `DeploymentConfigParser.kt`, `InitRepoCli.kt`, `StoryLog.kt`.
- Verantwoordelijkheid: factory documentatie in target repositories.

Taken:

- `docs/factory` laden en interpreteren.
- Deploymentconfig uit docs parsen.
- Docs skeleton installeren.
- Story logs aanmaken en bijwerken.

## git

- Belangrijkste bestanden: `GitCommandClient.kt`, `GitRepositoryUrl.kt`, `ProcessRunner.kt`.
- Verantwoordelijkheid: generieke Git- en procesuitvoering.

Taken:

- GitHub repo URL normaliseren.
- Clone, fetch, checkout, commit en push uitvoeren.
- Token-auth voor `git` via askpass configureren.
- Procesresultaten en timeouts afhandelen.

## github

- Belangrijkste bestand: `GitHubGitHubApi.kt`.
- Verantwoordelijkheid: pull request lifecycle en PR comment feedback.

Taken:

- PR openen of hergebruiken.
- Merge-status controleren.
- `@factory` comments vinden.
- Comment reactions zetten voor claimed/done/failed.
- PR sluiten, mergen of branch verwijderen.

## knowledge

- Belangrijkste bestand: `AgentKnowledge.kt`.
- Verantwoordelijkheid: agentkennis per target repo en rol bewaren en beschikbaar maken.

Taken:

- Kennis ophalen voor workspacevoorbereiding.
- Kennis upserten vanuit afgeronde agentresultaten.
- Target repo identifiers normaliseren.
- Data opslaan in de tabel `agent_knowledge`.

## orchestrator

- Belangrijkste bestanden: `OrchestratorService.kt`, `OrchestratorPoller.kt`, `CostMonitorService.kt`, `CostMonitorPoller.kt`, `RunRepositories.kt`, `ManualCommandService.kt`.
- Verantwoordelijkheid: centrale state machine voor stories en agentrollen.

Taken:

- YouTrack issues selecteren.
- AI Phase naar agentrol mappen.
- Concurrency, budgetten, pauzes, retries en hard timeouts bewaken.
- Agentdispatch aanvragen.
- Story/agent runs in PostgreSQL registreren.
- PR merges en PR feedback monitoren.
- Handmatige commands verwerken.

## preview

- Belangrijkste bestanden: `TesterPreviewFlow.kt`, `PreviewTemplateRenderer.kt`, `PreviewEnvironmentCleaner.kt`.
- Verantwoordelijkheid: previewcontext voor tester-agenten en cleanup na merge.

Taken:

- Preview URL en namespace uit templates renderen.
- Wachten tot preview HTTP 200 retourneert.
- Preview DB URL via recipe ophalen.
- OpenShift project/namespace opruimen.

## runtime

- Belangrijkste bestanden: `DockerAgentRuntime.kt`, `RuntimeApi.kt`, `services/AgentRunCompletionService.kt`, `AgentWorkspace.kt`, `AgentEventRepository.kt`, `DockerLogFollower.kt`.
- Verantwoordelijkheid: agentcontainers starten, volgen en afronden.

Taken:

- Workspaces en env-files voor agentcontainers maken.
- Agent tips in de workspace schrijven.
- Docker commands bouwen en uitvoeren.
- Runtime status/concurrency uit Docker labels bepalen.
- `agent-result.json` na container-exit lezen.
- Agent completion verwerken en YouTrack/knowledge bijwerken.
- Agent events opslaan.
- Workspaces opruimen.

## support

- Belangrijkste bestand: `SecretRedactor.kt`.
- Verantwoordelijkheid: module-onafhankelijke hulpfuncties die geen businessmodule mogen koppelen.

Taken:

- Secrets redacteren in logs, exceptions en opgeslagen event payloads.

## youtrack

- Belangrijkste bestanden: `YouTrackApi.kt`, `YouTrackClient.kt`, `TrackerModels.kt`, `TrackerCommentParser.kt`, `ProcessedCommentService.kt`.
- Verantwoordelijkheid: issue tracker domein en YouTrack-integratie.

Taken:

- Tracker issue-, field- en commentmodellen definieren.
- YouTrack REST API aanroepen.
- Factory custom fields en enumwaarden bootstrapppen.
- Agentcomments herkennen en relevante user comments selecteren.
- Verwerkte comments lokaal en/of via reactions markeren.

## web

- Belangrijkste bestanden: `FactoryDashboardController.kt`, `FactoryDashboardService.kt`, `FactoryDashboardRepository.kt`, `FactoryDashboardViews.kt`, `FactoryDashboardAuth.kt`.
- Verantwoordelijkheid: HTML dashboard.

Taken:

- Authenticatie en sessies.
- Dashboarddata uit repositories en runtime verzamelen.
- HTML views renderen.
- Story commands vanuit de UI queueen.
- Settings en redacted configuratie tonen.
