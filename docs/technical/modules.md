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
  `audit`, `bridge`, `config`, `core`, `knowledge`, `merge`, `orchestrator`, `pipeline`,
  `runtime`, `telegram`, `tracker`, `web`. De publieke API-conventie en inventaris staan in
  `module-api-convention.md` en `public-module-api-inventory.md`.
- **`agentworker`** — het standalone agentproces dat in de Docker-container draait.
- **`dashboard-backend`** — JSON-API voor de Flutter-frontend.

## factory-contracts en factory-common

- `factory-contracts` bevat package `contract`: `AgentResultFile` (inclusief de additieve/defaulted
  `AgentResultRateLimit` met Claude-status en reset-timestamps), bridgeframes/-params en de
  frame-reader, met golden contracttests en een productieartifact-boundarytest.
- `factory-common` bevat packages `config` (`FactorySecrets`, `ProjectConfiguration`),
  `core` (`AgentRole`, `TrackerField`, `DeploymentConfig`,
  `AgentComments`), `docs` (`FactoryDocsLoader`, `DocsSkeletonInstaller`,
  `DeploymentConfigParser`, `StoryLogWriter` + de `docs-skeleton`-resources), `git`
  (`GitCommandClient`, `GitRepositoryUrl`, `ProcessRunner`), `github` (`GitHubCliClient`),
  `preview` (`PreviewTemplateRenderer`, `PreviewEnvironmentCleaner`), `support`
  (`SecretRedactor`, `CallMetrics`, `ControlJsonStripper` — knipt trailing `{"phase":...}`/
  `{"agent_tips_update":...}`-controleblokken van agent-output af vóórdat die aan een mens getoond
  wordt; gebruikt door `AuditGatewayAdapter`, `FactoryOperationsService.testerReportFor`,
  `DashboardQueryService.storyDetail()`'s `allAgentRuns` (SF-1446) en
  `TelegramResultNotifyPoller`'s deploy-samenvatting (SF-1830)).
- Verantwoordelijkheid: alles wat zowel de factory als de agentworker nodig hebben, zodat
  drift tussen kopieën structureel onmogelijk is.

## softwarefactory: root

- Package: `nl.vdzon.softwarefactory`
- Belangrijkste bestand: `SoftwareFactoryApplication.kt`
- Verantwoordelijkheid: Spring Boot applicatie starten en scheduling activeren. Een
  Spring-Modulith-test (`ModulithArchitectureTest`) dwingt de modulegrenzen af.

## softwarefactory: config

- Belangrijkste bestanden: `ConfigApi.kt`, `services/SecretsEnvLoader.kt`,
  `configurations/DatabaseConfiguration.kt`, `services/OrchestratorSettingsFactory.kt`,
  `configurations/ProjectConfigurationWiring.kt`, `BearerTokenAuthorizer.kt`.
- Verantwoordelijkheid: gelaagde configuratie (`properties.default.env` → `properties.env` →
  `secrets.env`, env-vars winnen), verplichte secrets valideren, PostgreSQL datasource en
  Flyway, en het bouwen van `OrchestratorSettings` uit de omgeving (env-parsing hoort hier,
  niet in core). `BearerTokenAuthorizer` (internal) is de gedeelde Bearer-token-autorisatie
  tegen `SF_FACTORY_API_TOKEN`, gebruikt door `FactoryApiController`, `TrackerStoryApiController`
  en `CompletionOperationsController` in `web` (SF-1415/1416).

## softwarefactory: core

- Belangrijkste bestanden: `WorkflowModels.kt` (o.a. `SubtaskType`), `StoryPhase.kt`,
  `SubtaskPhase.kt`, `AiPhase.kt` (legacy), `OrchestratorSettings.kt`, `BoardState.kt`,
  `HumanActionPolicy.kt`, `StoryPipeline.kt`, `FactoryOperations.kt`, `AiRouting.kt`,
  `contracts/DeploymentStatusProbe.kt`, `contracts/ApkReleaseProbe.kt` (SF-1134, adapter
  `dashboard.services.GitHubApkReleaseProbe`), repositories-poorten.
- Verantwoordelijkheid: gedeelde domeinmodellen, enums en poort-interfaces waar de overige
  modules op leunen. `HumanActionPolicy` is sinds de refactor de ene bron voor "wacht dit
  issue op een mens / geldt auto-approve" (voorheen drie handgesynchroniseerde kopieën).

## softwarefactory: knowledge

- Belangrijkste bestanden: `KnowledgeApi.kt`, `services/AgentKnowledgeService.kt`.
- Verantwoordelijkheid: agentkennis (tips) per target repo en rol bewaren en beschikbaar
  maken; opslag in de tabel `agent_knowledge`. Ook de Telegram-assistent leert via rol
  `ASSISTANT`.

## softwarefactory: audit

- Belangrijkste bestanden: `AuditScheduler.kt`, `AuditPlanner.kt`, `AuditRepositories.kt`,
  `AuditJobsReader.kt`, `AuditGateway.kt` (poort; adapter `dashboard.services.AuditGatewayAdapter`).
- Verantwoordelijkheid: per project een instelbaar aantal audits per nacht (`audit_count`, default
  1; oudste-eerst) automatisch plannen en draaien, op de per project instelbare starttijd (globale
  `audit_settings.start_time`, default 08:00, als terugval) — vervangt de vroegere `nightly`-module (nightly jobs pasten zelf code aan,
  tot en met automerge/deploy; die module is volledig verwijderd). De gateway dispatcht
  rechtstreeks een `AgentRuntime`-container (rol `AUDITOR`, geen tracker-story, geen
  `AgentDispatcher`/Subtask-koppeling) i.p.v. een story aan te maken. Memory loopt via het
  bestaande `knowledge`-domein (rol `auditor`, category = audit-type); een gevonden issue kan
  hoogstens 1 vervolg-story opleveren (`StoryPhase.START_NEXT`, `questionsAllowed=true`).
  Zie `.factory/nightly/README.md` en `docs/technical/scheduled-jobs.md` §4.

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
  (skip / rest-restart / openshift-watch, via de `DeploymentStatusProbe`-poort). De story- en
  subtaakcoördinator behandelen `retry_after` vóór hard-timeout/recovery en hervatten de actieve
  Claude-rol automatisch zodra het tijdstip is bereikt.

## softwarefactory: runtime

- Belangrijkste bestanden: `DockerAgentRuntime.kt`, `RuntimeApi.kt`,
  `services/AgentRunCompletionService.kt`, `services/SubtaskPlanMaterializer.kt`,
  `services/AgentResultFileCompletionPoller.kt`, `services/AgentEventRetentionPoller.kt`,
  `services/AgentRunRetentionPoller.kt`, `services/CleanupLogWriter.kt`,
  `CleanupRunNowApi.kt`, `services/CleanupRunNowService.kt`, `services/CleanupRunner.kt`,
  `services/CompletionPayloadCleanup.kt`,
  `workspaces/StoryWorkspaceService.kt`, `workspaces/WorkCleanupPoller.kt`,
  `commands/CommandRunner.kt`.
- Verantwoordelijkheid: agentcontainers starten, volgen en afronden. `complete()` verwerkt
  het resultaat (commit/push, PR, fase-overgang, events, knowledge) en retourneert sinds de
  refactor een domeinresultaat (`CompletionOutcome`) in plaats van een Spring
  `ResponseEntity`; de subtaak-materialisatie zit in de aparte `SubtaskPlanMaterializer`
  (inclusief de afgedwongen documentation/manual-approve/merge/deploy-subtaken bij het planner-pad).
  `WorkCleanupPoller` is de uurlijkse `@Scheduled` achtervang die weesmappen onder `work/`
  opruimt na crashes/killed processes (zie `docs/technical/scheduled-jobs.md`).
- **Databaseretentie en opruim-log (SF-1921):** `AgentEventRetentionPoller` en
  `AgentRunRetentionPoller` zijn twee losse uurlijkse pollers met eigen vlaggen en termijn
  (`SF_AGENT_EVENT_RETENTION_*` / `SF_AGENT_RUN_RETENTION_*`); de run-retentie laat lopende runs en
  runs met een onafgeronde durable completion altijd staan. Deze twee, `WorkCleanupPoller` en de
  completion-payload-purge in `AgentRunCompletionService` schrijven hun ronde weg via
  `services/CleanupLogWriter` — alleen bij verwijderingen of een fout, en fail-soft. Daarvoor staat
  `maintenance :: repositories` in de `allowedDependencies` van `runtime`; een aparte poort-interface
  is bewust achterwege gelaten.
- **Handmatige opruimronde (SF-1929):** dezelfde vier opruimers implementeren `services/CleanupRunner`
  (`cleanupKind`, `cleanupEnabled()`, `runCleanupRoundLocked(trigger)`), zodat de `@Scheduled`-methode
  en de "Nu draaien"-knop op exact dezelfde ronde uitkomen. `services/CleanupRunNowService` is de impl
  van de root-package-poort `CleanupRunNowApi.runNow(kind)`: het pakt `maintenance.CleanupRunGuard`
  synchroon, zet de ronde op een executor en antwoordt meteen met een `CleanupRunNowOutcome`
  (`started`/`already_running`/`disabled`/`unknown_kind`; `kind = all` start de vrije soorten en meldt
  de overgeslagen). De GitHub-cleanup loopt daarbij via `maintenance.MaintenanceCleanupApi` — die
  woont in de andere module en kan dus geen `CleanupRunner` zijn.
- `AgentRunCompletionService` classificeert mislukte runs als quota/retryable/fatal. Een
  Claude-quota-uitkomst bewaart de actieve fase, wist `Error`, berekent `retry_after` uit een
  toekomstige reset plus één minuut (anders vijftien minuten) en sluit quotaruns uit van de
  transient-retrytelling zonder de omliggende reeks te onderbreken.
- De geëxposeerde poort `SubtaskMaterializationApi` (base-package `runtime`, impl
  `SubtaskPlanMaterializer`) biedt `materializeFromSpecs` voor het nightly-config-pad: exact de
  gedeclareerde subtaken, idempotent op titel, GEEN auto-append. `web`
  (`DashboardQueryService`) injecteert deze poort i.p.v. de niet-geëxposeerde
  `runtime.services.SubtaskPlanMaterializer`, zodat de Spring-Modulith module-grens intact blijft.
- **Env-grens van de agent-container (SF-1725):** `workspaces/AgentWorkspace.kt` schrijft per run
  een `factory.env` (mode `rw-------`) met alle `SF_`-variabelen van de factory, mínus
  `AGENT_ENV_DENYLIST`. Die denylist bevat tien namen (`SF_GITHUB_TOKEN`, `SF_COPILOT_TOKEN`,
  `SF_BRIDGE_TOKEN`, `SF_DASHBOARD_REMEMBER_SECRET`, `SF_DASHBOARD_PASSWORD`, `SF_ALLOWED_EMAILS`,
  `SF_GOOGLE_CLIENT_ID`, `SF_GITHUB_PACKAGES_TOKEN`, `SF_TELEGRAM_BOT_TOKEN`,
  `SF_FACTORY_API_TOKEN`) en werkt rolonafhankelijk. `SF_DATABASE_URL` en `SF_AI_OAUTH_TOKEN`
  staan er bewust niet in: die worden in de container gelezen. De default is dus *doorgeven* —
  een nieuwe secret die agents niet nodig hebben, hoort meteen op de denylist. Bewaakt door
  `DockerAgentRuntimeTest.denylisted factory secrets never reach the agent env file`, dat op zowel
  de sleutelnamen als hun waarden asserteert. Per-run-extra's (`SF_AI_SUPPLIER`, `SF_BRANCH_NAME`,
  … ) zet `DockerAgentRuntime` los als `-e`; de assistent-container bouwt een eigen `docker run`
  (`telegram/clients/ClaudeAssistantClient.kt`) en valt buiten deze denylist.
- **SF-1038:** tweede geëxposeerde poort `AgentLogApi` (impl `AgentLogService`) biedt
  `recentLogLines(agentRunId, limit)`: de laatste (begrensd, default 500) `docker-stdout`/
  `docker-stderr`-`agent_events`-regels van één agent-run, chronologisch (oudste eerst).
  `DashboardQueryService.agentLog()` injecteert deze poort i.p.v. rechtstreeks
  `runtime.repositories.AgentEventRepository`, zelfde Modulith-grensreden als
  `SubtaskMaterializationApi`. Ontsloten via bridge-operatie `agent.log` /
  `GET /api/v1/agents/{agentRunId}/events` (zie `docs/ontwerp-bridge-dashboard.md` §5).

## softwarefactory: telegram

- Belangrijkste bestanden: `clients/TelegramClient.kt`, `services/TelegramNotificationService.kt`,
  `services/TelegramPoller.kt`, `services/TelegramReplyService.kt`, `repositories/TelegramStore.kt`,
  `services/TelegramAssistantService.kt`, `clients/ClaudeAssistantClient.kt`,
  `services/TelegramResultNotifyPoller.kt`.
- Verantwoordelijkheid: tweerichtings Telegram — vraag-/klaar-/fout-meldingen (incl.
  testrapport, preview-link en screenshots), replies naar antwoorden/commands vertalen, en
  de conversationele assistent. Respecteert de meldingen-as (SF-1261, `notify_mode`): geen
  status-/foutmeldingen bij `geen`; een QUESTION-melding gaat wel altijd door zolang
  `questions_allowed` aan staat. De Claude-quota-wachtmelding is informatief, gebruikt de
  DB-idempotentiesleutel `claude-quota:<retryAfter>` en gaat uitsluitend door bij `na-elke-stap`.
- `TelegramResultNotifyPoller` (SF-1134, `@Scheduled`): aparte "eindresultaat écht
  live"-melding per story (`notify_mode=als-klaar-en-gedeployed`, SF-1261), in plaats van de
  gewone `als-klaar`-melding; zie `docs/technical/scheduled-jobs.md` §6. Het bericht bestaat sinds
  SF-1830 uit een kop, een korte functionele samenvatting en de eventuele URL. De kop draagt sinds
  SF-1858 ook de story-titel: `🚀 Story <KEY>: <TITEL> is deployed!`, en zonder (of met een
  whitespace-only) titel `🚀 Story <KEY> is deployed!`; een titel langer dan `TITLE_LIMIT` (120
  tekens) wordt afgekapt met `…`. Voor die samenvatting leest de poller via de poort
  `FactoryOperations` (`deploySummaryFor`) het `deploy-summary`-blok van de summarizer, met de
  `## Samenvatting` uit de story-description als terugval.

## softwarefactory: web

- Belangrijkste bestanden: `WebApi.kt`, `controllers/FactoryApiController.kt`,
  `controllers/AgentRunCompletionController.kt`, `controllers/AgentKnowledgeController.kt`,
  `controllers/CompletionOperationsController.kt`, `controllers/TrackerStoryApiController.kt`.
  `DashboardQueryService`, `FactoryOperationsService` en `WorkspaceDesktopLauncher` wonen in de
  `dashboard`-module (`dashboard/services/`), niet meer in `web`.
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
  `V15__tracker_issues.sql`; `retry_after` + partial index sinds V27) achter de capabilitypoorten
  `IssueReader`, `IssueLifecyclePort`,
  `CommentPort`, `AttachmentPort` en `ProcessedCommentPort`. Keygeneratie is afgescheiden in
  `PostgresIssueKeySequence`. Herkent agentcomments en
  markeert verwerkte comments. Er is geen externe issue-tracker meer.

## softwarefactory: maintenance

- Belangrijkste bestanden: `services/MaintenanceCleanupScheduler.kt`,
  `services/MaintenanceCleanupSettings.kt`, `services/ReleaseRetentionPlanner.kt`,
  `services/PackageVersionRetentionPlanner.kt`, `services/GitHubReleaseCleanupClient.kt`,
  `services/GitHubPackageCleanupClient.kt`, `services/GitHubProtectedShaSource.kt`,
  `repositories/MaintenanceCleanupRunRepository.kt`, en sinds SF-1929 de root-package-poorten
  `MaintenanceCleanupApi.kt` en `CleanupRunGuard.kt` (impl `services/InMemoryCleanupRunGuard.kt`)
  plus `types/CleanupRunStatus.kt`.
- Verantwoordelijkheid: nachtelijke, niet-AI-gedreven opruiming van oude GitHub-Releases en
  ghcr.io-package-versions per project met een `releaseCleanup:`-blok in `projects.yaml`. De
  retentieregels zelf zitten in de twee pure planners; de clients doen de HTTP-calls naar
  `api.github.com` (zie `external-systems.md` §3). Zie `scheduled-jobs.md` §7 voor de tick.
- `repositories` is sinds SF-1913 een named interface (`maintenance :: repositories`) met de
  historie in `maintenance_cleanup_runs` (migraties `V30`/`V31`): `add`, `get(id)`,
  `recent(project?, kind?, limit)` en `deleteOlderThan(cutoff)`. `dashboard` leest die historie via
  de named interface; de `bridge`-module raakt de maintenance-module niet rechtstreeks aan. Sinds
  SF-1921 is de tabel de opruim-log van álle mechanismen: de vijf afgesproken `kind`-waarden staan
  als constanten in `CleanupKinds` (vrije TEXT-kolom, geen DB-constraint), `project` is nullable
  (NULL = factory-breed) en de tellers zijn generiek (`items_deleted`/`items_kept`). Naast
  `dashboard` schrijft ook `runtime` op deze named interface, via `runtime/services/CleanupLogWriter`.
  Sinds SF-1929 heeft de tabel ook een `trigger` (`scheduled`/`manual`, migratie `V32`), die in de
  lees-DTO's van `maintenance.cleanupsList`/`cleanupDetail` meegaat.
- Twee poorten in het **root-package** (SF-1929), volgens het precedent `runtime.AgentLogApi` /
  `pipeline.DeployTargetStatusApi`: `MaintenanceCleanupApi.runCleanupRoundLocked(trigger)` is exact
  de ronde die de cron draait, en `CleanupRunGuard` is de gedeelde dubbel-draaien-bescherming
  (`tryStart`/`finish`/`runningKinds`/`withKind`, in-memory per JVM). Beide worden door `runtime`
  gebruikt en zijn daarmee de reden dat `maintenance` (root) en `maintenance :: types` in de
  `allowedDependencies` van `runtime` staan — géén interne subpackage over de module-grens.
- `allowedDependencies` is sinds SF-1913 alleen nog `config`: de Telegram-melding over een
  opruimronde is vervallen, dus `telegram` is geen dependency meer.

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
  uit factory-contracts). Gedeelde git/github/docs/preview/support-code komt uit factory-common;
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
  env-, process- of HTTP-mechanics bezit (formaat `exact-path|runtime|capability|reason`,
  alfabetisch op pad). `tools/check-composition-roots` faalt bij nieuwe of stale paden;
  wildcards zijn niet toegestaan.
- Het script zoekt met `grep -rlE --include='*.kt'` naar `System.getenv`, `ProcessBuilder` en
  `HttpClient.new` onder `softwarefactory/`, `agentworker/`, `dashboard-backend/` en
  `factory-common/` `src/main` (SF-1561: bewust `grep` i.p.v. `rg`, dat hier niet geïnstalleerd
  is). `tools/test-check-composition-roots` is de contracttest eromheen (scriptvorm, registerformaat,
  drift in beide richtingen én het echte script op de huidige checkout) en draait via de stap
  `repository-contract-tests` in `tools/verify-repository` mee in de repositorygate, samen met de
  drie andere `tools/test-*`-contracttests. Losstaand draaien kan met
  `bash tools/check-composition-roots` of `bash tools/test-check-composition-roots`.
- Dezelfde contracttest bewaakt sinds SF-1857 dat géén van de zeven gate- en contractscripts
  (`tools/verify-repository`, `tools/audit-documentation`, `tools/audit-branch-protection` en de
  vier `tools/test-*`-scripts) nog `rg` aanroept; ripgrep is hier niet geïnstalleerd en de
  resulterende exit 127 is niet te onderscheiden van een echte bevinding. De controle filtert
  commentaarregels (`grep -v '^[[:space:]]*#'`) weg, zodat elk script de keuze voor `grep` in een
  hele commentaarregel mag toelichten; ontbreekt een bewaakt pad, dan faalt de test ook.

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
