# Modules

De repo bevat vijf Maven-modules; de root `pom.xml` is hun parent en aggregator met de modules
`factory-contracts`, `factory-common`, `softwarefactory`, `agentworker` en `dashboard-backend`. De Flutter
`dashboard-frontend` valt buiten de Maven build.

De twintig Spring-Modulith-modules op het applicatieclasspath declareren ieder expliciete
`allowedDependencies`, zonder wildcard. De gegenereerde, gemotiveerde matrix en Mermaid-bron staan
in [module-dependencies.md](module-dependencies.md). Regenereren gebeurt met
`tools/generate-module-dependencies`; `--check` is onderdeel van de repositorygate. Root-API's en
named interfaces (`models`, `types`, `errors` en de vastgelegde nightly-contracten) zijn de enige
toegestane cross-moduleoppervlakken.

- **`factory-contracts`** — gedeelde agent-result- en bridgewiretypes/readers; alleen Jackson en
  Kotlin op het runtimeclasspath, zonder Spring, YAML of productiefixtures.
- **`factory-common`** — gedeelde tooling en projectconfig tussen factory en agentworker.
- **`softwarefactory`** — de hoofdapplicatie onder
  `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory`, met 12 directe packages:
  `bridge`, `config`, `core`, `knowledge`, `merge`, `nightly`, `orchestrator`, `pipeline`,
  `runtime`, `telegram`, `tracker`, `web`. De publieke API-conventie en inventaris staan in
  `module-api-convention.md` en `public-module-api-inventory.md`.
- **`agentworker`** — het standalone agentproces dat in de Docker-container draait.
- **`dashboard-backend`** — JSON-API voor de Flutter-frontend.

## factory-contracts en factory-common

- `factory-contracts` bevat package `contract`: `AgentResultFile`, bridgeframes/-params en de
  frame-reader, met golden contracttests en een productieartifact-boundarytest.
- `factory-common` bevat packages `config` (`FactorySecrets`, `ProjectConfiguration`),
  `core` (`AgentRole`, `TrackerField`, `DeploymentConfig`,
  `AgentComments`), `docs` (`FactoryDocsLoader`, `DocsSkeletonInstaller`,
  `DeploymentConfigParser`, `StoryLogWriter` + de `docs-skeleton`-resources), `git`
  (`GitCommandClient`, `GitRepositoryUrl`, `ProcessRunner`), `github` (`GitHubCliClient`),
  `preview` (`PreviewTemplateRenderer`, `PreviewEnvironmentCleaner`), `support`
  (`SecretRedactor`, `CallMetrics`).
- Verantwoordelijkheid: alles wat zowel de factory als de agentworker nodig hebben, zodat
  drift tussen kopieën structureel onmogelijk is.

## softwarefactory: root

- Package: `nl.vdzon.softwarefactory`
- Belangrijkste bestand: `SoftwareFactoryApplication.kt`
- Verantwoordelijkheid: Spring Boot applicatie starten en scheduling activeren. Een
  Spring-Modulith-test (`ModulithArchitectureTest`) dwingt de modulegrenzen af.

## softwarefactory: config

- Belangrijkste bestanden: `ConfigApi.kt`, `services/SecretsEnvLoader.kt`,
  `DatabaseConfiguration.kt`, `OrchestratorSettingsFactory.kt`,
  `configurations/ProjectConfigurationWiring.kt`.
- Verantwoordelijkheid: gelaagde configuratie (`properties.default.env` → `properties.env` →
  `secrets.env`, env-vars winnen), verplichte secrets valideren, PostgreSQL datasource en
  Flyway, en het bouwen van `OrchestratorSettings` uit de omgeving (env-parsing hoort hier,
  niet in core).

## softwarefactory: core

- Belangrijkste bestanden: `WorkflowModels.kt` (o.a. `SubtaskType`), `StoryPhase.kt`,
  `SubtaskPhase.kt`, `AiPhase.kt` (legacy), `OrchestratorSettings.kt`, `BoardState.kt`,
  `HumanActionPolicy.kt`, `StoryPipeline.kt`, `FactoryOperations.kt`, `AiRouting.kt`,
  `DeploymentStatusProbe.kt`, `contracts/ApkReleaseProbe.kt` (SF-1134, adapter
  `dashboard.services.GitHubApkReleaseProbe`), repositories-poorten.
- Verantwoordelijkheid: gedeelde domeinmodellen, enums en poort-interfaces waar de overige
  modules op leunen. `HumanActionPolicy` is sinds de refactor de ene bron voor "wacht dit
  issue op een mens / geldt auto-approve" (voorheen drie handgesynchroniseerde kopieën).

## softwarefactory: knowledge

- Belangrijkste bestanden: `KnowledgeApi.kt`, `services/AgentKnowledgeService.kt`.
- Verantwoordelijkheid: agentkennis (tips) per target repo en rol bewaren en beschikbaar
  maken; opslag in de tabel `agent_knowledge`. Ook de Telegram-assistent leert via rol
  `ASSISTANT`.

## softwarefactory: nightly

- Belangrijkste bestanden: `NightlyScheduler.kt`, `NightlyPlanner.kt`,
  `NightlyRepositories.kt`, `NightlyTime.kt`, `NightlyDigest.kt`, `NightlyJobsReader.kt`,
  `NightlyGateway.kt`.
- Verantwoordelijkheid: nachtelijke jobs (`.factory/nightly/<job>/job.yaml` per repo)
  automatisch plannen, draaien en per Telegram-digest rapporteren (SF-350). Scheduler →
  pure planner-functie → gateway; volledig DB-gedreven zodat een restart veilig is.
- `NightlyJobsReader` leest naast `job.yaml`/`story.md` ook een optionele `subtasks.yaml`
  (declaratief config-pad, SF-787) en valideert de geordende subtaak-lijst + `<title>.md`-bestanden;
  bij een geldige config slaat de factory refine + plan over en materialiseert de gedeclareerde
  subtaken direct. Bij een fout gooit hij `NightlySubtasksConfigException` en wordt de job
  overgeslagen (fout in de digest).

## softwarefactory: orchestrator

- Belangrijkste bestanden: `services/OrchestratorService.kt`,
  `schedulers/OrchestratorPoller.kt`, `services/CostMonitorService.kt`,
  `schedulers/CostMonitorPoller.kt`, `services/ManualCommandService.kt`,
  `services/StoryPurgeService.kt`.
- Verantwoordelijkheid: de poll-loop, budget/credits-bewaking, handmatige commands
  (`@factory:...`), story-purge en PR-monitoring. De eigenlijke fase-logica zit in
  `pipeline`; de orchestrator kent alleen de poort `core.StoryPipeline`.

## softwarefactory: pipeline

- Belangrijkste bestanden (`pipeline/service`): `StoryPipelineService.kt` (router op het
  `Type`-veld), `StoryRefinementCoordinator.kt` (story-fasen: refine + plan),
  `SubtaskExecutionCoordinator.kt` (subtaak-keten), `AgentDispatcher.kt`,
  `MergeSubtaskHandler.kt`, `DeploySubtaskHandler.kt`.
- Verantwoordelijkheid: het twee-laags procesmodel — fase-overgangen, vragen-loops,
  loopbacks, resets, de automatische merge (squash via de GitHub API) en de deploy-afhandeling
  (skip / rest-restart / openshift-watch, via de `DeploymentStatusProbe`-poort).

## softwarefactory: runtime

- Belangrijkste bestanden: `DockerAgentRuntime.kt`, `RuntimeApi.kt`,
  `services/AgentRunCompletionService.kt`, `services/SubtaskPlanMaterializer.kt`,
  `services/AgentResultFileCompletionPoller.kt`, `workspaces/StoryWorkspaceService.kt`,
  `workspaces/WorkCleanupPoller.kt`, `commands/CommandRunner.kt`.
- Verantwoordelijkheid: agentcontainers starten, volgen en afronden. `complete()` verwerkt
  het resultaat (commit/push, PR, fase-overgang, events, knowledge) en retourneert sinds de
  refactor een domeinresultaat (`CompletionOutcome`) in plaats van een Spring
  `ResponseEntity`; de subtaak-materialisatie zit in de aparte `SubtaskPlanMaterializer`
  (inclusief de afgedwongen documentation/manual-approve/merge/deploy-subtaken bij het planner-pad).
  `WorkCleanupPoller` is de uurlijkse `@Scheduled` achtervang die weesmappen onder `work/`
  opruimt na crashes/killed processes (zie `docs/technical/scheduled-jobs.md`).
- De geëxposeerde poort `SubtaskMaterializationApi` (base-package `runtime`, impl
  `SubtaskPlanMaterializer`) biedt `materializeFromSpecs` voor het nightly-config-pad: exact de
  gedeclareerde subtaken, idempotent op titel, GEEN auto-append. `web`
  (`DashboardQueryService`) injecteert deze poort i.p.v. de niet-geëxposeerde
  `runtime.services.SubtaskPlanMaterializer`, zodat de Spring-Modulith module-grens intact blijft.
- **SF-1038:** tweede geëxposeerde poort `AgentLogApi` (impl `AgentLogService`) biedt
  `recentLogLines(agentRunId, limit)`: de laatste (begrensd, default 500) `docker-stdout`/
  `docker-stderr`-`agent_events`-regels van één agent-run, chronologisch (oudste eerst).
  `DashboardQueryService.agentLog()` injecteert deze poort i.p.v. rechtstreeks
  `runtime.repositories.AgentEventRepository`, zelfde Modulith-grensreden als
  `SubtaskMaterializationApi`. Ontsloten via bridge-operatie `agent.log` /
  `GET /api/v1/agents/{agentRunId}/events` (zie `docs/ontwerp-bridge-dashboard.md` §5).

## softwarefactory: telegram

- Belangrijkste bestanden: `TelegramClient.kt`, `TelegramNotificationService.kt`,
  `TelegramPoller.kt`, `TelegramReplyService.kt`, `TelegramStore.kt`,
  `TelegramAssistantService.kt`, `ClaudeAssistantClient.kt`,
  `services/TelegramResultNotifyPoller.kt`.
- Verantwoordelijkheid: tweerichtings Telegram — vraag-/klaar-/fout-meldingen (incl.
  testrapport, preview-link en screenshots), replies naar antwoorden/commands vertalen, en
  de conversationele assistent. Respecteert de meldingen-as (SF-1261, `notify_mode`): geen
  status-/foutmeldingen bij `geen`; een QUESTION-melding gaat wel altijd door zolang
  `questions_allowed` aan staat.
- `TelegramResultNotifyPoller` (SF-1134, `@Scheduled`): aparte "eindresultaat écht
  live"-melding per story (`notify_mode=als-klaar-en-gedeployed`, SF-1261), in plaats van de
  gewone `als-klaar`-melding; zie `docs/technical/scheduled-jobs.md` §6.

## softwarefactory: web

- Belangrijkste bestanden: `controllers/FactoryApiController.kt`,
  `controllers/AgentRunCompletionController.kt`, `controllers/AgentKnowledgeController.kt`,
  `services/DashboardQueryService.kt`, `services/FactoryOperationsService.kt`,
  `services/WorkspaceDesktopLauncher.kt`, `repositories/FactoryDashboardRepository.kt`.
- Verantwoordelijkheid: interne HTTP-adapters (agent-callbacks, knowledge-endpoints, publieke
  API). Het voormalige HTML-dashboard (FactoryDashboardController, DashboardAuthConfig en de
  `views/`-laag) is verwijderd (SF-825); de Flutter-frontend in `dashboard-backend`/
  `dashboard-frontend` neemt de UI-rol over. De page-data-assemblage voor de bridge leeft
  nog steeds in `DashboardQueryService`.

## softwarefactory: dashboard

- Publieke applicationports staan in `dashboard/DashboardApi.kt`; immutable bridge-/UI-contracten
  staan in de named interface `dashboard.models`.
- Query-, command-, persistence- en externe adapterimplementaties zijn intern aan de module.
  `web` en `bridge` injecteren uitsluitend de publieke ports; er bestaat geen `web.services`
  named interface meer.
- Mutaties leven in `DashboardCommandService`; read-side page assembly en bijbehorende caches in
  `DashboardQueryService`. Nightly gebruikt dezelfde query-/commandports en kent de concrete
  services niet.

## softwarefactory: tracker

- Belangrijkste bestanden: `TrackerApi.kt`, `clients/PostgresTrackerClient.kt`,
  `clients/TrackerClientConfiguration.kt`, `repositories/ProcessedCommentStore.kt`,
  `services/ProcessedCommentService.kt`.
- Verantwoordelijkheid: de eigen Postgres-tracker (unified `issues`-tabel,
  `issue_comments`, `issue_attachments`, `project_key_sequences`, migratie
  `V15__tracker_issues.sql`) achter de capabilitypoorten `IssueReader`, `IssueLifecyclePort`,
  `CommentPort`, `AttachmentPort` en `ProcessedCommentPort`. Keygeneratie is afgescheiden in
  `PostgresIssueKeySequence`. Herkent agentcomments en
  markeert verwerkte comments. Er is geen externe issue-tracker meer.

## agentworker

- Locatie: `agentworker/src/main/kotlin/nl/vdzon/softwarefactory` (packages `agent` en
  `agentworker`).
- Belangrijkste bestanden: `agentworker/cli/AgentCli.kt`,
  `agentworker/flows/TargetRepositoryFlow.kt`, `agentworker/flows/TesterPreviewFlow.kt`,
  `agent/AiClient.kt`, `agent/ai/shared/AgentPromptContracts.kt`,
  `agent/ai/shared/CliProcessRunner.kt` en de drie supplierclients.
- Verantwoordelijkheid: standalone agentproces dat in de Docker-container draait: env vars,
  taakcontext en agent tips lezen, de target repo voorbereiden, de AI supplier uitvoeren en
  het resultaat naar `/work/agent-result.json` schrijven (gedeeld `AgentResultFile`-contract
  uit factory-common). Gedeelde git/github/docs/preview/support-code komt uit factory-common;
  de vroegere lokale kopieën zijn verwijderd. Agents committen niet zelf; de factory commit,
  pusht en beheert de PR na elke run.
- Prompt- en outcomecontracten, tijdelijke taskfiles en subprocessmechanics zijn supplierneutraal.
  Claude, Codex en Copilot blijven ieder eigenaar van hun argv, credentials, streamparser, usage en
  supplierspecifieke foutcodes.

## Configuratie- en I/O-grenzen

- `ProjectConfiguration` wordt eenmaal uit YAML opgebouwd, maar productieconsumers injecteren
  uitsluitend de kleinste repository-, deploy-, merge-, Telegram-, assistant- of dashboardport.
- Factoryconfig behoudt de bestaande precedence via `ConfigApi.resolvedValues()`; ook
  `SF_PROJECTS_FILE` en deploytokens volgen daardoor de gelaagde config.
- `architecture/composition-root-boundaries.txt` registreert iedere exacte productiebron die direct
  env-, process- of HTTP-mechanics bezit. `tools/check-composition-roots` faalt bij nieuwe of stale
  paden; wildcards zijn niet toegestaan.

## dashboard-backend en dashboard-frontend

- Locatie backend: `dashboard-backend/src/main/kotlin/nl/vdzon/softwarefactory/dashboard`.
- Locatie frontend: `dashboard-frontend/lib`.
- Verantwoordelijkheid: Flutter dashboard bovenop de factory database en GitHub.
  Sinds de refactor queryt de backend het huidige procesmodel (`Story Phase`/`Repo`-veld via
  de smalle projectsettingsports uit factory-common), heeft een korte TTL-cache voor de
  tracker-calls en zit het IntelliJ-endpoint (`WorkspaceOpener`) achter
  `SF_DASHBOARD_LOCAL_MODE=true`.

## Teststructuur

- `mvn test` draait de snelle unit-run; de e2e-/Testcontainers-tests van `softwarefactory`
  draaien via failsafe in `mvn verify`.
- Tests gebruiken handgeschreven fakes (geen mock-frameworks); gedeelde fakes staan in
  `softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/testsupport`. De e2e-harness
  (`e2e/`) boot de echte app tegen Testcontainers-Postgres met de echte `PostgresTrackerClient`
  (`TrackerTestState`), een scripted agent-runtime en echte git (inclusief een fake GitHub die
  lokaal squash-merget).
