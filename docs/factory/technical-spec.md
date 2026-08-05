# Technical Spec

## Stack

- Kotlin
- JDK 21
- Spring Boot
- Maven
- Flyway voor DB-migraties
- Postgres, remote via Neon of lokaal via Docker
- Docker Engine voor agent-containers
- Dart/Flutter voor de externe dashboard-frontend (Docker-build, los van Maven)

## Modules

De repo bevat vijf Maven-modules (root `pom.xml` als parent en aggregator) plus een
losse Flutter-frontend:

- `factory-contracts` — lichte Jackson/Kotlin-wiretypes voor agentresultaten en bridgeframes,
  zonder Spring-context, YAML of productiefixtures.
- `factory-common` — gedeelde code tussen de modules (git/github-clients, docs +
  docs-skeleton, preview, support, `AgentRole` en `ProjectConfiguration`).
- `softwarefactory` — de orchestrator/factory zelf, met interne HTTP-adapters
  (`web`-package) standaard op poort `8080`. Het ingebouwde HTML-dashboard is
  verwijderd (SF-825); de Flutter-frontend in `dashboard-backend`/`dashboard-frontend`
  is de enige UI.
- `agentworker` — het standalone agentproces dat in de Docker-container draait.
- `dashboard-backend` — een aparte Spring Boot service (lokaal op poort `9090`) die als dunne
  makelaar ("de bridge", zie `docs/ontwerp-bridge-dashboard.md`) verzoeken doorzet naar de factory
  zelf — geen eigen tracker- of database-toegang. Authenticatie loopt sinds SF-794/SF-795 via
  **Google-SSO (OIDC)**:
  `POST /api/v1/auth/google` ontvangt een Google **ID-token**, verifieert dat via de
  `GoogleIdTokenVerifier`-seam (RS256-signature via Google JWKS, audience
  `SF_GOOGLE_CLIENT_ID`, issuer `accounts.google.com`, expiry, `email_verified`) en
  checkt het e-mailadres tegen de `SF_ALLOWED_EMAILS`-allowlist — verplichte config die
  niet-leeg moet zijn, anders start de service niet (fail-fast, `IllegalStateException`
  met de key in de melding). Bij toegang volgt een
  HMAC-getekend sessie-token (`SF_DASHBOARD_REMEMBER_SECRET`) met het e-mailadres als
  identiteit, dat `requireAuthorization` accepteert op de `Bearer`-header. De verifier
  is injecteerbaar zodat tests met een eigen RSA-keyset netwerkloos test-ID-tokens
  kunnen ondertekenen (`nimbus-jose-jwt`). De oude username/password-login is verwijderd.
- `dashboard-frontend` — een Flutter (Dart) web-app die de dashboard-backend-API
  consumeert (lokaal op poort `9080`); geen Maven-module, eigen Docker-build.

## Config

Alle eigen environment variables beginnen met `SF_`. De loader
(`SecretsEnvLoader`) leest gelaagd: `properties.default.env` (committed defaults)
→ `properties.env` (lokaal) → `secrets.env` (lokaal, geheim); system environment
variables winnen altijd van de bestanden.

Verplichte keys:

- `SF_GITHUB_TOKEN`
- `SF_DATABASE_URL`
- `SF_DATABASE_SCHEMA`

Optioneel: `SF_TRACKER_PROJECTS` beperkt de tracker-scan tot specifieke projectkeys (leeg = alle
project_key's die al in de tracker-database voorkomen).

`SF_DATABASE_URL` bepaalt welke Postgres gebruikt wordt. Thuis kan dit Neon
zijn; op werk kan dit de lokale Docker Postgres uit `docker/docker-compose.yml` zijn.

`SF_DATABASE_SCHEMA` moet een geldige Postgres identifier zijn. Gebruik nooit
het schema `factory`; dat schema bestaat al in de gedeelde Neon database en
hoort bij een ander systeem. Gebruik voor branches/stories een eigen schema,
bijvoorbeeld `software_factory_dev` of `software_factory_sf_020`.

Lokale Postgres starten:

```bash
./factory local-db
```

De lokale composition root is `docker/docker-compose.yml`, aangeroepen via
`./factory local-services` met repositoryroot als backend-buildcontext. De dunne backend ontvangt
geen database- of GitHubconfig; alleen Google-login-, sessie- en bridgeconfig. De factory verbindt
uitgaand met `SF_BRIDGE_URLS=ws://localhost:9090/bridge` en dezelfde `SF_BRIDGE_TOKEN` als de
backend.

Standaard URL voor `secrets.env`:

```env
SF_DATABASE_URL=postgresql://software_factory:software_factory@localhost:5432/software_factory
SF_DATABASE_SCHEMA=software_factory_dev
```

Orchestrator tuning gebruikt ook `SF_` env-vars. Defaults:

- Polling staat altijd aan zodra de applicatie draait: vast interval als vangnet, aangevuld
  met event-driven wake bij elke tracker-write (`FactoryStateChangedEvent`).
- `SF_POLL_INTERVAL_MS=60000`
- `SF_MAX_PARALLEL_REFINER=1`
- `SF_MAX_PARALLEL_DEVELOPER=2`
- `SF_MAX_PARALLEL_REVIEWER=2`
- `SF_MAX_PARALLEL_TESTER=1`
- `SF_MAX_PARALLEL_TOTAL=4`
- `SF_MAX_DEVELOPER_LOOPBACKS=5`
- `SF_MAX_TEST_CHAIN_RESETS=3`
- `SF_MAX_TRANSIENT_RETRIES=2`
- `SF_AGENT_HARD_TIMEOUT_MINUTES=60`
- `SF_ACTIVE_PHASE_RECOVERY_DELAY_MS=60000`
- `SF_BLOCKED_QUEUE_WARN_THRESHOLD_MINUTES=240` — drempel voor een WARN-logregel
  (`OrchestratorService.promoteQueuedStories`) zodra een openstaande story-run
  (`StoryRunRepository.activeRunForRepo`) de per-repo `start-next`-wachtrij langer
  dan dit blokkeert; puur zichtbaarheid voor blokkades door écht ander werk, geen
  automatische sluiting. Sinds SF-1480 sluit `promoteQueuedStories` vóór die check
  wél automatisch elke nog openstaande `story_run` af (`final_status=requeued`) van
  een story die zelf in de huidige `start-next`-batch zit (`closeOwnDanglingRuns`) —
  zo kan een story niet meer zichzelf blokkeren via een eigen achtergebleven run.
- `SF_COST_MONITOR_INTERVAL_MS=300000`
- `SF_CREDITS_PAUSE_DEFAULT_MINUTES=30`
- `SF_COMPLETION_RECOVERY_POLL_MS=2000`
- `SF_COMPLETION_MAX_ATTEMPTS=8`
- `SF_COMPLETION_LEASE_SECONDS=300`
- `SF_COMPLETION_BACKOFF_MS=2000`
- `SF_COMPLETION_RETENTION_DAYS=30`

De committe defaults staan in `properties.default.env`; `secrets.env`/de system
environment overschrijven ze per key.

Agent-workspace-opruiming (`AgentWorkspaceCleaner`) heeft eigen vlaggen:

- `SF_AGENT_WORKSPACE_CLEANUP_ENABLED=true` — ruimt de tijdelijke workspace onder
  `work/` op na elke agent-run; op `false` blijft alles staan.
- `SF_AGENT_WORKSPACE_PRESERVE_ON_FAILURE=false` — op `true` blijft de workspace
  van een mislukte run bewaard voor analyse (geslaagde runs worden wél opgeruimd).

Deze event-gedreven cleaners ruimen alleen op bij een succesvolle run-completion of
een expliciete purge/merge, en laten dus weesmappen achter na crashes, gekilde
processen of afgebroken flows. Als achtervang daarbovenop draait `WorkCleanupPoller`
(`runtime/workspaces/WorkCleanupPoller.kt`, `@Scheduled` elk uur): die scant periodiek
de vier `work/`-subroots die de runtime zelf aanmaakt —
`work/agent-workspaces/<story>-<role>-<random>/`, `work/stories/<storyKey>/repo`,
`work/assistant-checkouts/<naam>/repo` en `work/assistant/<chatId>/<sessionId>/work/{in,out}`
— en verwijdert per top-level entry recursief zodra de meest recente mtime binnenin
op of voorbij de retentieperiode ligt. Voor mtime wordt bekeken, worden actieve story- en
agentpaden uit Postgres en lopende assistantsessie-/checkoutpaden uit een klein runtime-register
genormaliseerd uitgesloten, inclusief noodzakelijke ancestors en descendants. Een fout in een
actieve bron slaat de volledige tick veilig over; een entryfout of verdwijnrace blijft tot die
entry beperkt. Recursieve verwijdering volgt geen symlinks buiten de beheerde root. Eigen vlaggen
(analoog aan de agent-workspace-vlaggen hierboven):

- `SF_WORK_CLEANUP_ENABLED=true` — zet de scheduled achtervang-cleanup aan/uit.
- `SF_WORK_CLEANUP_RETENTION_DAYS=7` — inactieve mappen die sinds hun laatste wijziging
  exact dit aantal dagen of langer stilstaan worden verwijderd; actieve mappen worden
  ongeacht mtime nooit geraakt.

`attachments/`, `logs/`, `qualityrun/` en `target/` vallen buiten deze scan — dat
zijn geen door de Kotlin-runtime beheerde agent-workmappen.

Databaseretentie op de twee agent-tabellen heeft eigen vlaggen. `AgentEventRetentionPoller`
(`runtime/services`, `@Scheduled` elk uur) ruimt de agent-logregels op; `AgentRunRetentionPoller`
(SF-1921, zelfde opzet) ruimt de agent-runs zelf. Bewust twee losse pollers: `agent_runs` bewaart de
kostenhistorie van het agent-log-scherm en mag langer blijven staan dan de logregels erbij, en moet
los aan/uit te zetten zijn. Beide verwijderen batchgewijs met een bovengrens per ronde; wat over die
grens valt gaat de volgende tick mee.

- `SF_AGENT_EVENT_RETENTION_ENABLED=true` — zet de `agent_events`-retentie aan/uit.
- `SF_AGENT_EVENT_RETENTION_DAYS=30` — bewaartermijn (geklemd op 1–3650).
- `SF_AGENT_EVENT_RETENTION_BATCH_SIZE=5000` — rijen per delete-batch (geklemd op 100–100000).
- `SF_AGENT_EVENT_RETENTION_MAX_BATCHES=20` — batches per ronde (geklemd op 1–1000).
- `SF_AGENT_RUN_RETENTION_ENABLED=true` — zet de `agent_runs`-retentie aan/uit.
- `SF_AGENT_RUN_RETENTION_DAYS=90` — bewaartermijn (geklemd op 1–3650).
- `SF_AGENT_RUN_RETENTION_BATCH_SIZE=1000` — rijen per delete-batch (geklemd op 100–100000).
- `SF_AGENT_RUN_RETENTION_MAX_BATCHES=20` — batches per ronde (geklemd op 1–1000).

De `agent_runs`-retentie heeft twee veiligheidsregels boven de leeftijdsgrens: een lopende run
(`ended_at IS NULL`) en een run met een onafgeronde durable completion (`PENDING`, `IN_PROGRESS` of
`FAILED_RETRYABLE`) blijven altijd staan, ongeacht leeftijd. Er wordt alleen op `agent_runs`
gedelete; `agent_events`, `agent_run_completions` en `agent_run_completion_steps` volgen via de
bestaande `ON DELETE CASCADE`.

## Per-project config (`projects.yaml`)

Het `deploy:`-blok (`ProjectConfiguration.DeployConfig`) kent twee actieve modes met SHA-gebaseerde
deploy-verificatie (SF-771, zie functional-spec):

- `rest-restart`: `restartUrl`, `versionUrl`, `tokenEnvVar`, `pollIntervalSeconds` (default 15),
  `timeoutMinutes` (default 20). `DeploySubtaskHandler` pollt `versionUrl` tot `commitHash`
  prefix-matcht met de verwachte merge-SHA (base-branch HEAD via `GitHubApi.latestCommitSha(...)`);
  bij ontbrekende SHA-info terugval op het `startedAt`-gedrag.
- `openshift-watch`: `namespace`, `deployment`, `timeoutMinutes` (default 20) plus de optionele
  `argocdApp` + `argocdNamespace`. Zijn beide gezet, dan leest `DeploymentStatusProbe.argoApplicationStatus(...)`
  (kubectl-adapter `KubectlDeploymentStatusProbe`) de ArgoCD `Application`-CR en keurt pas goed bij
  `Synced` + `Healthy` + `Succeeded` op de verwachte revisie; anders de bestaande image-heuristiek.
  Optioneel ook `liveUrl` (SF-1134) — de publieke URL van de live component; alleen gebruikt door de
  telegram-result-notify-poller (zie §Telegram-resultaatmelding) voor een extra HTTP-200-check,
  geen effect op `DeploySubtaskHandler` zelf.

De default deploy-timeout staat als `ProjectConfiguration.DEFAULT_DEPLOY_TIMEOUT_MINUTES = 20`.

`ProjectConfiguration.fromYaml(...)` parseert `projects.yaml` met SnakeYAML's `SafeConstructor`
(`Yaml(SafeConstructor(LoaderOptions()))`): alleen standaard YAML-typen (maps/lijsten/scalars),
geen instantiatie van willekeurige Java-typen via expliciete tags. Dat sluit deserialisatie-RCE
uit en is gedragsneutraal — geldige config levert exact dezelfde structuren op (SF-565).

Alle onomkeerbare PR-merges lopen via de publieke `merge.PullRequestMergeService`; alleen de
interne `ProjectAwarePullRequestMergeService` roept `GitHubApi.mergePullRequest` aan.
`MergeSubtaskHandler` en `ManualCommandService` leveren projectnaam, repo en PR-nummer aan dezelfde
use-case. `ProjectConfiguration` leest per project een verplichte, niet-lege
`merge.requiredChecks`-lijst en de mergeservice valideert bij bean-opstart dat geen repository
zonder policy bestaat.

`GitHubCliClient.requiredChecks` leest eerst de actuele `headRefOid` en haalt daarna check-runs
voor exact die commit op. Het resultaat is getypeerd als `Ready(verifiedHeadSha)`, `Pending` of
`Blocked`. Alleen queued/in-progress is pending; missing, skipped, cancelled, failed en API-/
parsefouten zijn fail-closed blocked. Bij ready voert de client `gh pr merge --squash` uit met
`--match-head-commit <verifiedHeadSha>`. Een headwijziging tussen controle en merge wordt daardoor
door GitHub geweigerd en als retrybare pending teruggegeven. Pending zet geen Error en blijft ook
voor een handmatig mergecommando ongeprocessed zodat de volgende poll opnieuw beoordeelt.

De documentatie-stap (`documentation`-subtaak, rol DOCUMENTER, SF-213) is daarentegen altijd aan
en niet per project uit te zetten. Die wordt afgedwongen ná de planner-subtaken en vóór de
manual-approve-poort; volledige ketenvolgorde:
`development → review → test → summary → documentation → manual-approve → merge → deploy`.

## Revisiongebonden testerbewijs

Iedere actieve target-repository heeft `.factory/verification.yaml` schema `version: 1`.
`VerificationConfigParser` gebruikt SnakeYAML `SafeConstructor` en weigert ontbrekende/onbekende
versies, lege/dubbele command-id's, lege argv, absolute/uitbrekende of via symlink ontsnappende
working directories en timeouts
buiten 1..7200 seconden. `VerificationConfigValidatorCli <repo-root> [...]` valideert rollout/config
met exact dezelfde parser. Commands gaan als `List<String>` rechtstreeks naar `ProcessBuilder`;
er is geen impliciete shell of stringevaluatie.

Na een tester-AI-resultaat `tested` voert `TesterVerificationRunner` in agentworker alle commands
deterministisch uit. Output wordt tijdens het proces begrensd gelezen om pipe-deadlocks en onbegrensde
result-files te voorkomen. `AgentResultFile.verificationEvidence` is additive/defaulted en bevat
configversie, command-id, ISO-start/eind, duur, exitcode, status, rapport/samenvatting en HEAD/tree.
Oude niet-testerpayloads blijven leesbaar; een oude testerpayload die `tested` claimt mist bewust
bewijs en wordt geweigerd.

Toolingdetectie resolveert het executable-pad vóór start. Bij timeout worden parent en descendants
geforceerd gestopt; een mislukte output-reader is `execution-error`, nooit groen. Factoryvalidatie
eist bovendien dat `durationMs` exact overeenkomt met ISO-start/eind en begrenst zowel samenvatting
als rapportlocatie.

`TesterVerificationEvidenceValidator` is een tweede, onafhankelijke factory-gate vóór persistence.
Hij leest config en Git-identiteit opnieuw uit de actieve workspace en normaliseert ieder ongeldig
`tested` naar `test-rejected`, waarna de bestaande volledige ketenreset loopt. Alleen alle commands
`passed`/exit 0, complete tijden, begrensd rapport en exact dezelfde HEAD plus worktree-tree passeren.
De worktree-tree wordt zonder mutatie via een tijdelijk `GIT_INDEX_FILE`, `git add -A` en
`git write-tree` berekend, zodat ook legitieme nog niet gecommitte testinput exact gebonden is.

## Tracker-database en -velden

Stories en subtaken leven in de eigen Postgres-tabellen van de factory (Flyway-migratie
`V15__tracker_issues.sql`: één unified `issues`-tabel, `issue_comments`, `issue_attachments`,
`project_key_sequences`), via capabilitygerichte interfaces in package `tracker` (`IssueReader`,
`IssueLifecyclePort`, `CommentPort`, `AttachmentPort` en `ProcessedCommentPort`; implementatie
`PostgresTrackerClient`). De atomische issue-keyreeks heeft een eigen
`PostgresIssueKeySequence`. Er is geen externe issue-tracker.

Sinds SF-1261 (migratie `V20__story_option_axes.sql`, ná V18) heeft elke story drie onafhankelijke
assen — deze vervangen de vroegere, elkaar overlappende `auto_approve`/`silent`/
`telegram_result_notify`-kolommen (gedropt in dezelfde migratie, ná backfill):

- `questions_allowed` (echte Postgres `BOOLEAN`, default `true`) — `TrackerField.QUESTIONS_ALLOWED`,
  opgeslagen als `"on"`/`"off"` via `updateIssueFields`, net als voorheen `Silent`.
- `approval_mode` (`TEXT`, default `'automatisch'`) — `TrackerField.APPROVAL_MODE`, waarden
  `automatisch`/`alleen-manual-poort`/`elke-stap` (enum `ApprovalMode`). Dit veld bepaalt als enige
  of `SubtaskPlanMaterializer` de vaste `manual-approve`-poort toevoegt.
- `notify_mode` (`TEXT`, default `'als-klaar-en-gedeployed'`) — `TrackerField.NOTIFY_MODE`, waarden
  `geen`/`na-elke-stap`/`als-klaar`/`als-klaar-en-gedeployed` (enum `NotifyMode`).

Sinds SF-1959 komt daar een vierde as bij (migratie `V33__story_hotfix.sql`):

- `hotfix` (echte Postgres `BOOLEAN`, default `false`) — `TrackerField.HOTFIX`,
  `TrackerIssueFields.hotfix`. De migratie raakt bestaande rijen bewust niet aan. De vlag is alleen
  bij het aanmaken te zetten: `TrackerCapabilities.createStory(hotfix = ...)` (in de INSERT),
  `CreateTrackerStoryRequest.hotfix` (`POST /api/tracker/stories`, `sf-story create --hotfix`),
  `CreateStoryCommand.hotfix` (bridge-operatie `story.create`) en `CreateStoryRequest.hotfix`
  (dashboard-backend `POST /api/v1/stories`, gevoed door de Hotfix-schakelaar in de
  aanmaakdialoog). `AuditGatewayAdapter.proposeStoryIfAny` geeft expliciet `hotfix = false` mee.

De default geldt uitsluitend bij het aanmaken van nieuwe stories (dashboard, bridge-operatie
`story.create`, tracker-API, Telegram en auditvoorstellen); migratie V29 wijzigt geen bestaande
rijen. Een expliciet gekozen andere meldingenstand, inclusief `als-klaar`, wordt altijd opgeslagen.

Alle drie staan op story-niveau; subtaken lezen de waarde van hun parent-story (best-effort
parent-lookup). De gedeelde helpers in de tracker-capabilitycompositie —
`effectiveQuestionsAllowed(issue)` en `effectiveNotifyMode(issue)` — zorgen dat coördinatoren,
notificaties en dashboard dezelfde beslissing nemen; `HumanActionPolicy.autoApproveActive` doet
hetzelfde voor `approval_mode`. Clarification-errors (uit `*-with-questions` bij vragen=uit) worden
in de error-tekst gemarkeerd met `ErrorCategory.CLARIFICATION` (`[CLARIFICATION]`), onderscheidbaar
van technische errors.

### Claude-quota en `retry_after`

Migratie `V27__claude_quota_retry_after.sql` voegt de nullable `TIMESTAMPTZ`-kolom
`issues.retry_after` plus een partial index toe. Het veld is via `TrackerField.RETRY_AFTER`,
`TrackerIssueFields.retryAfter`, de Postgres-mapping en de bridge-JSON zowel op stories als subtaken
beschikbaar. `findAiIssues` uniont alle rijen met `retry_after IS NOT NULL` ongelimiteerd bij de
normale recente top-N en de begrensde niet-terminale subtaaksubset.

`ClaudeStreamParser` leest het laatste bruikbare `rate_limit_event` (nested `rate_limit_info` of
top-level, camelCase en snake_case timestamps) naar het additieve/defaulted
`AgentResultFile.rateLimit`-contract. `AgentFailurePolicy.classify` kent `QUOTA`, `RETRYABLE` en
`FATAL`; quota heeft precedence boven de generieke `rate limit`-retry, maar classificatie geldt
alleen voor mislukte runs en `allowed`/`allowed_warning` zijn geen blokkerend signaal.

`AgentRunCompletionService` berekent `retry_after` als een toekomstige `resetsAt` plus één minuut,
anders `now + 15 minuten`, en laat fase en `Error` ongemoeid/leeg. De story- en
subtaakcoördinator controleren dit veld vóór actieve-fase hard-timeout of dispatch. Zodra het
tijdstip is bereikt wissen zij wacht- en oud starttijdstip en dispatchen zij dezelfde actieve rol;
`AgentDispatcher` schrijft altijd een nieuw `agent_started_at` en wist `retry_after`. Quota-runs
worden via `AgentRunRepository.recentForRole(excludeQuotaFailures=true)` al in de persistencequery
uitgefilterd voordat de door `SF_MAX_TRANSIENT_RETRIES` bepaalde limiet wordt toegepast. De query
filtert daarbij uitsluitend mislukte quotaruns; successen met quotatekst of een blokkerende status
blijven zichtbaar als grens van een transientreeks. Daardoor onderbreekt ook een onbeperkt lange
quotareeks de omliggende transienttelling niet en blijven caps boven 999 werkzaam.
Migratie `V28__agent_run_rate_limit.sql` bewaart daarvoor status en beide reset-timestamps ook in
`agent_runs`; zo blijft een uitsluitend door het gestructureerde signaal herkende quota-run na de
completion als quota herkenbaar in de persistente runhistorie.

`TelegramNotificationService` classificeert de toestand als informatieve `QUOTA` met signature
`claude-quota:<retryAfter>`. Anders dan bij vragen en voortgang wordt geen context-hash toegevoegd,
zodat herstelde parent-/dashboardcontext bij hetzelfde tijdstip geen tweede melding veroorzaakt.
Alleen `NotifyMode.EVERY_STEP` laat die melding door. De Flutter-UI
toont hetzelfde absolute tijdstip, naar lokale tijd geconverteerd en als quota-wachtbadge/banner,
los van de foutpresentatie. Voor het storyoverzicht levert `findQuotaWaitingIssues` alle wachtende
issues zonder top-N-limiet en aggregeert `DashboardQueryService` de laatste wachttijd per
parent-story. Storydetail doet dezelfde read-only aggregatie uit de al geladen subtaken. Het
parent-issue krijgt daarbij bewust geen persistent `retry_after`: dat zou na afloop ten onrechte de
storycoördinator kunnen activeren in plaats van de getroffen subtaakcoördinator.

## Telegram-resultaatmelding (SF-1134 / SF-1261)

Naast de gewone `als-klaar`-melding van `TelegramNotificationService` (bij afronding van de laatste
subtaak) kan een story via meldingen=`als-klaar-en-gedeployed` een latere melding krijgen zodra het
eindresultaat écht extern zichtbaar/live is — pas ná deploy/merge, wanneer de nieuwe versie
daadwerkelijk bereikbaar is.

- **As**: `notify_mode = 'als-klaar-en-gedeployed'` op de story (keuze in de Flutter
  story-detail-schermen, bridge-operatie `story.setNotifyMode`, endpoint
  `POST /api/v1/stories/{storyKey}/notify-mode`).
- **Poller**: `TelegramResultNotifyPoller` (`telegram/services/`, `@Scheduled`, interval
  `softwarefactory.telegram-result-notify-poll-ms`, default 60s). Filtert eerst `findWorkIssues()`
  op stories met `notify_mode=als-klaar-en-gedeployed`; zijn die er niet, dan stopt de tick direct
  zonder cluster-/GitHub-calls. Omdat dit dezelfde enum is als `meldingen=geen`, respecteert de
  poller die stand nu inherent (voorheen een losse boolean-inconsistentie, SF-1261-fix).
- **Hergebruik i.p.v. duplicatie**: de poller herhaalt de ArgoCD-/image-/SHA-verificatie van
  `DeploySubtaskHandler` niet. Zodra de DEPLOY-subtaak `deploy-approved` bereikt (terminaal, niet
  `deploy-failed`), heeft die handler dat al vastgesteld. De poller voegt alleen de checks toe die de
  deploy-handler niet doet:
  - **openshift-watch**: optioneel `liveUrl` op `DeployConfig.OpenshiftWatch` (YAML `deploy.liveUrl`)
    → extra HTTP-200-check; niet geconfigureerd → direct bevestigd.
  - **rest-restart**: direct bevestigd (de SHA-check is al gebeurd in `DeploySubtaskHandler`).
  - **projecten zonder deploy-config** (proxy voor "APK-project"): een nieuwe `.apk`-release ná de
    deploy-referentietijd, via de nieuwe poort `ApkReleaseProbe` (`core.contracts`) met adapter
    `GitHubApkReleaseProbe` (`dashboard.services`, hergebruikt `GitHubReleaseClient.apkDownloads`).
  - Referentietijd = deploy-subtaak `agentStartedAt` (fallback `updatedAt`/`createdAt`).
- **Berichtopbouw (SF-1830, kop uitgebreid in SF-1858)**: `🚀 Story <KEY>: <TITEL> is deployed!`
  (bij een lege/whitespace-only titel `🚀 Story <KEY> is deployed!`; een titel langer dan
  `TITLE_LIMIT` = 120 tekens wordt afgekapt met `…`), daaronder een korte functionele
  samenvatting, daaronder (indien aanwezig) de URL; lege regel tussen elk blok. De bevestigende zin
  staat niet meer in het bericht (het interne `Confirmation`-model draagt alleen nog de URL); de
  checks hierboven bepalen nog steeds ÓF, WANNEER en met welke URL er gemeld wordt. Bron van de
  samenvatting, eerste niet-lege wint: (1) het blok tussen `<!-- deploy-summary:start -->` /
  `<!-- deploy-summary:end -->` uit de meest recente SUMMARIZER-run, via de poortmethode
  `FactoryOperations.deploySummaryFor(storyKey)` (implementatie `FactoryOperationsService`, met de
  pure companion-helper `deploySummaryFrom(runs)` — zelfde patroon als `testerReportFor`/
  `testerReportFrom`), (2) de `## Samenvatting`-sectie uit `TrackerIssue.description`, (3) niets.
  Elke bron is soft-fail (`runCatching`) en de tekst wordt gestript via `ControlJsonStripper` en
  afgekapt op 1000 tekens. De summarizer-prompt (`RolePrompts.summarizerPrompt()`) en
  `docs/factory/agents/summarizer.md` (+ de docs-skeleton-kopie) vragen dat blok expliciet op.
- **Opgeef-timeout**: 4 uur na de referentietijd zonder bevestiging → alleen een warn-logregel, geen
  Telegram-bericht, geen foutmelding; de story wordt wel als "afgehandeld" gemarkeerd.
- **Idempotentie**: hergebruikt `TelegramStore.alreadyNotified`/`recordNotified` (DB-backed via
  `telegram_notifications`, overleeft een herstart) met signature `"result-notify"` — geen aparte
  timestampkolom nodig.
- **Modulith**: de poller (en `ApkReleaseProbe`) leven bewust niet in `pipeline` — die module mag
  `dashboard`/`telegram` niet importeren (`ModulithArchitectureTest`). `ApkReleaseProbe` is een poort
  in `core.contracts` (overal injecteerbaar, zelfde patroon als `DeploymentStatusProbe`); de poller
  zelf zit in `telegram/services/` (die module mag al `TelegramClient`/`TelegramStore` gebruiken en
  `config`/`core`/`tracker` importeren).

## Audit-systeem

De vroegere "nightly scheduler" (die zelf code aanpaste, tot en met automerge/deploy) is vervangen
door read-only audits: één AI-agent-run per project per nacht die onderzoekt, een rapport schrijft
en hooguit 1 vervolg-story voorstelt — nooit zelf code wijzigt. De oude `Nightly*`-machinery
(`NightlyScheduler`/`NightlyPlanner`/`NightlyGateway`/`NightlyJobsReader`, tabellen
`nightly_settings`/`nightly_run`/`nightly_run_job`, migraties `V11`–`V14`) bestaat niet meer in de
code; het `audit`-package (`nl.vdzon.softwarefactory.audit`) is de vervanging, analoog qua opzet:

- `AuditScheduler` (`@Scheduled`-tick, ~30s, `sf.audit.tick-ms`) draait volledig op DB-state — géén
  in-memory run-status — zodat een rest-restart de lopende run weer oppikt. De beslis-kern zit in
  het pure `AuditPlanner`; de scheduler voert de acties uit tegen de repositories en de
  `AuditGateway`-poort (implementatie `AuditGatewayAdapter` in `dashboard/services`).
- `AuditJobsReader` leest `.factory/nightly/<audit>/job.yaml` uit de project-repo's (`SafeConstructor`,
  zelfde untrusted-YAML-aanpak als voorheen, SF-565); `prompt.md` bevat de vaste auditor-instructie.
  Zie `.factory/nightly/README.md` voor het volledige configuratieformaat (single source of truth).
- Per project seedt de scheduler doorgaans de 1 oudst-gedraaide enabled audit (per project instelbaar
  aantal, `audit_count`); de gekozen audits draaien sequentieel binnen een run.
- Migraties `V21__audit_jobs.sql` (tabellen `audit_settings`, `audit_run`, `audit_report`,
  `audit_run_job`), `V22__audit_run_job_agent_columns.sql` (agent-containerkolommen op
  `audit_run_job`), `V23__audit_report_duration.sql` (`duration_ms` op `audit_report`),
  `V24__audit_project_settings.sql` (per-project `audit_project_settings` met `start_time`/
  `audit_count`, valt terug op de globale `audit_settings` als er geen rij is) en
  `V25__audit_run_job_kind.sql` (`kind` op `audit_run_job`, `TEXT NOT NULL DEFAULT 'scheduled'`,
  zodat "Run now" een handmatige audit (`kind = manual`) achter een al lopende run kan zetten).
- Het rapport komt uit een **bestand**, niet uit de chatoutput: de auditor schrijft zijn markdown
  naar `/work/audit-report.md` (`AgentPaths.AUDIT_REPORT_FILE`), de agentworker leest dat terug in
  `AgentResultFile.auditReportMarkdown` en `AuditGatewayAdapter.reportContent()` slaat het zo op.
  `summaryText` (het laatste agent-bericht) is nog alleen fallback voor oudere containers/suppliers —
  dat veld bevatte soms alleen de JSON-controleblokken (leeg rapport) of JSON tússen de tekst; die
  fallback wordt daarom eerst door `support.ControlJsonStripper.stripTrailingControlJson`
  (`factory-common`, SF-1446) ontdaan van trailing controleblokken. Dezelfde helper strip ook de
  Telegram-testrapportmelding (zie hierboven) en `summaryText` in `allAgentRuns` van het
  story-detail-endpoint, zodat geen van de drie consumenten rauwe `{"phase":...}`/
  `{"agent_tips_update":...}`-blokken aan een mens toont.
- Een auditor kan een **blokkerende vraag** stellen (fase `audit-questions`) in plaats van door te
  gaan op een aanname. Hij wacht daarbij nooit binnen z'n run: de run eindigt, vraag + tussenstand
  gaan naar `audit_question` (`V26`), en het antwoord plant via `AuditScheduler.answerQuestion()`
  binnen ~30s een vervolgrun in die de vraag, het antwoord en de eerdere bevindingen terugkrijgt.
  De job wordt daarbij terminaal gezet (`AuditJobStatus.ASKED`) — een wachtende job zou de run open
  houden en daarmee alle audits van alle projecten blokkeren.
- Een audit stelt via `AuditGatewayAdapter.proposeStoryIfAny` hoogstens 1 vervolg-story voor
  (`tracker.createStory`, `questionsAllowed = true`, `StoryPhase.START_NEXT` — géén silent story,
  start in de wachtrij i.p.v. meteen).
- Frontend: navigatie-item "Audits" → `AuditScreen` (`dashboard-frontend/lib/screens/audit_screen.dart`);
  geen aparte `/nightly`-pagina of Nightly-sectie op `/settings` meer.

## Opruimen: cleanup-log en GitHub-cleanup (SF-1913 / SF-1921 / SF-1929 / SF-1938 / SF-1939)

`maintenance/services/MaintenanceCleanupScheduler` ruimt 's nachts (cron
`sf.maintenance.cleanup-cron`, default `0 30 2 * * *` UTC) per project met een `releaseCleanup:`-blok
in `projects.yaml` oude GitHub-Releases en ghcr.io-package-versions op. Het opruim-algoritme zit in
`ReleaseRetentionPlanner`/`PackageVersionRetentionPlanner`; individuele deletes zijn fail-soft.

- **Paginatie in de GitHub-clients (SF-1938).** De lijstcalls haalden maar één pagina op, waardoor
  één ronde hooguit ~100 items zag en een achterstand dagen bleef staan. `GitHubPagination`
  (`maintenance/services/GitHubPagination.kt`) is de gedeelde, pure paginatielus: hij krijgt een
  "haal pagina n op"-functie (`GitHubPage.Fetched(items, rawCount)` / `GitHubPage.Failed`) en is dus
  zonder HTTP te testen. De lus stopt zodra een pagina minder dan `per_page` (100) *ruwe* elementen
  teruggeeft — dus zonder extra call — of bij de bovengrens `sf.maintenance.github-page-limit`
  (default 20 pagina's = 2000 items, in `MaintenanceCleanupSettings`); dat laatste levert een
  waarschuwing met naam en aantal op. Gebruikt door `GitHubPackageCleanupClient.listVersions`,
  `GitHubReleaseCleanupClient.listReleases` en de `/pulls?state=open`-call van
  `GitHubProtectedShaSource`; de `contents`-call blijft ongepagineerd (geen lijst). Foutafhandeling:
  faalt pagina 1 dan komt er een lege lijst uit (ongewijzigd gedrag), faalt pagina *n>1* dan wordt
  teruggegeven wat al is opgehaald, met een waarschuwing. Uitzondering is
  `GitHubProtectedShaSource`: dat is een *veiligheids*lijst, dus een gefaalde of op de paginagrens
  afgekapte lijst (`PagedItems.complete == false`) laat de scheduler de package-cleanup voor dat
  project deze ronde overslaan; dat komt als `error` op de logregel van die projectronde te staan,
  terwijl de release-cleanup van dezelfde ronde gewoon doorgaat. Ontbrekend
  `SF_GITHUB_PACKAGES_TOKEN` levert nog steeds een lege lijst met één waarschuwing en zonder ook
  maar één HTTP-call.
- **Geen Telegram-melding meer.** De ronde meldde zichzelf voorheen in Telegram; dat is vervangen
  door historie in de database. De `maintenance`-module hangt daarmee nog uitsluitend van `config` af.
- **Gedeelde opruim-log.** `maintenance_cleanup_runs` (migraties `V30`/`V31`, repository
  `maintenance/repositories/MaintenanceCleanupRunRepository`) is sinds SF-1921 de log van *alle*
  opruimmechanismen. Per rij: `kind` (`github-releases`, `agent-events`, `agent-runs`,
  `completion-payloads`, `workspaces`), een optioneel `project` (NULL = factory-breed), begin/eind,
  generieke `items_deleted`/`items_kept`, `dry_run`, een eventuele `error` en een JSON-`details`-veld.
  `kind` is een vrije TEXT-kolom met de vijf waarden als afspraak in code (`CleanupKinds`), net als
  `outcome`/`role` in `agent_runs`.
- **Schrijfregel.** De nachtelijke GitHub-cleanup schrijft élke projectronde een rij — óók bij 0
  verwijderingen, bij een dry-run (met de *geplande* aantallen) en bij een gefaalde ronde; anders is
  "er viel niets op te ruimen" niet te onderscheiden van "de scheduler heeft niet gedraaid". De vier
  factory-brede opruimers (`AgentEventRetentionPoller`, `AgentRunRetentionPoller`, `WorkCleanupPoller`
  en de completion-payload-purge, sinds SF-1929 los in `services/CompletionPayloadCleanup`) schrijven
  via `runtime/services/CleanupLogWriter` alléén bij
  `items_deleted > 0` of bij een fout: de payload-purge hangt aan de completion-recovery van elke
  ~2 s en zou het scherm anders binnen een dag vol lege rijen zetten. Die onderdrukking geldt sinds
  SF-1929 alleen nog voor `trigger = scheduled`; een handmatige ronde levert áltijd een rij op. Het wegschrijven is overal
  fail-soft — een mislukte insert laat de opruimronde zelf slagen. Een project zonder GitHub-slug
  wordt overgeslagen en levert géén rij.
- **Retentie.** Aan het eind van elke GitHub-cleanup-tick verdwijnen log-rijen ouder dan
  `sf.maintenance.run-retention-days` (default 90) — voor alle soorten; geen aparte poller.
- **Leespad.** `DashboardQueries.maintenanceCleanups(project?, kind?)`/`maintenanceCleanupDetail(id)`
  → bridge-operaties `maintenance.cleanupsList`/`maintenance.cleanupDetail` →
  `GET /api/v1/maintenance/cleanups[?project=…][&kind=…]` en `/{id}` op de dashboard-backend
  (onbekende id = 404). Lijstlimiet 200, geen paginering. Het scherm heet `Opruimen`.
- **Samenvatting per soort (SF-1939).** `maintenance.cleanupsList` geeft naast `runs`, `errors` en
  `runningKinds` ook `summary`: de laatste ronde per `kind` en — voor `github-releases` — per project.
  Die lijst komt uit een eigen query `MaintenanceCleanupRunRepository.latestPerKindAndProject()`
  (`SELECT DISTINCT ON (kind, COALESCE(project, ''))` … `ORDER BY … started_at DESC, id DESC`) en
  bewust niet uit de op 200 rijen afgekapte `recent()`-lijst: een drukke soort zou anders de laatste
  ronde van een rustige soort uit beeld duwen. Per regel dezelfde velden als een lijstregel
  (`MaintenanceCleanupRunSummaryView`: `id`, `kind`, `project`, `startedAt`, `finishedAt`,
  `itemsDeleted`, `itemsKept`, `dryRun`, `failed`, `trigger`). `summary` is een extra veld met een
  default, en de bestaande velden/endpoints zijn ongewijzigd, zodat een al uitgerolde APK blijft
  werken. De samenvatting wordt niet meegefilterd met `project`/`kind`. Geen databasemigratie nodig.
- **Schermindeling (SF-1939).** `dashboard-frontend/lib/screens/maintenance_screen.dart` toont één
  blok per soort uit de vaste `cleanupKinds`-lijst (alle vijf altijd zichtbaar, ook zonder gelogde
  ronde) met de naam van de actie, het resultaat van de laatste ronde als label/waarde-paren
  (`verwijderd:` / `blijft staan:` / `duur:`, met `formatCleanupDuration` → `1 m 7 s`, `43 s`,
  `< 1 s` en `-` als `finishedAt` ontbreekt), het tijdstip, de badges `dry-run`/`handmatig`/`fout`,
  een knop **Nu draaien** (`Key('run-now-<kind>')`) en een knop **Runs bekijken**
  (`Key('view-runs-<kind>')`). Bij `github-releases` één regel per project met een gelogde ronde
  (alleen weergave; de knop draait één ronde over álle projecten). Zonder gelogde ronde staat er
  "laatste ronde: geen wijzigingen gelogd". Bovenaan blijven de foutbanners uit `errors` en één knop
  **Alles draaien** (`Key('run-now-all')`, `kind = all`); de soort-dropdown en de
  `Nu draaien:`-knoppenbalk zijn vervallen. **Runs bekijken** duwt `CleanupRunsScreen` op de stack:
  eigen `Scaffold`/`AppBar` (`Rondes: <kind>`), laadt `/api/v1/maintenance/cleanups?kind=<kind>` en
  toont de rondes nieuwste-eerst; tikken opent het bestaande rondedetail ongewijzigd. Titel, blokken
  en regels stapelen onder 560px, zodat het scherm op een telefoon binnen de breedte blijft.
- **Nu draaien (SF-1929).** Elke opruimsoort is ook handmatig te starten:
  `DashboardCommands.runCleanupNow(kind)` → bridge-operatie `maintenance.runNow` →
  `POST /api/v1/maintenance/run`. `kind` is een `CleanupKinds`-waarde of `all`. De ronde loopt via
  exact dezelfde code als de scheduler (`runtime/CleanupRunNowApi` voor de vier factory-brede
  opruimers, `maintenance/MaintenanceCleanupApi` voor de GitHub-cleanup), start niet-blokkerend op
  een executor en antwoordt meteen met een status (`started`, `already_running`, `disabled`,
  `unknown_kind`) — HTTP 200, net als `audit.runNow`. `CleanupRunGuard` (in-memory, per JVM) laat per
  soort hooguit één ronde tegelijk toe en geldt óók voor het geplande pad: draait er een handmatige
  ronde, dan slaat de scheduler zijn tick over. Een handmatige ronde levert áltijd een rij op (ook
  bij 0 items en bij een fout) met `trigger = 'manual'` (migratie `V32`, default `scheduled`);
  `maintenance.cleanupsList` geeft daarnaast `runningKinds` terug voor de knop-status van het scherm.

## Ontwerpregels

De Spring-Modulith-modules declareren hun uitgaande richting expliciet via
`@ApplicationModule(allowedDependencies = …)`. Alleen module-root-API's en benoemde interfaces zijn
toegestaan; wildcards en transport-naar-transportdependencies zijn verboden. De codewaarheid wordt
deterministisch gepubliceerd in `docs/technical/module-dependencies.md` via
`tools/generate-module-dependencies`; `tools/verify-repository` voert de bijbehorende driftcheck uit.

- Orchestrator-state blijft idempotent en herstelbaar.
- De eigen tracker-database (Postgres) is de zichtbare workflow-bron voor gebruiker en agents.
- Postgres is ook de bron voor run history, event logging en agent knowledge.
- Agents werken in tijdelijke clones en schrijven alleen via hun toegestane rol.
- Gebruik kleine, gerichte tests rond state-machine, config en adapters.
