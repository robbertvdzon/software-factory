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

De repo bevat vier Maven-modules (root `pom.xml` als aggregator) plus een
losse Flutter-frontend:

- `factory-common` — gedeelde code tussen de modules (git/github-clients, docs +
  docs-skeleton, preview, support, `AgentRole`, `ProjectRepoResolver` en het
  gedeelde `AgentResultFile`-contract van het result-bestand).
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
  checkt het e-mailadres tegen de `SF_ALLOWED_EMAILS`-allowlist. Bij toegang volgt een
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
- `SF_COST_MONITOR_INTERVAL_MS=300000`
- `SF_CREDITS_PAUSE_DEFAULT_MINUTES=30`

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
`work/assistant-checkouts/<naam>/repo` en `work/assistant/<chatId>/<sessionId>/{in,out}`
— en verwijdert per top-level entry recursief zodra de meest recente mtime binnenin
ouder is dan de retentieperiode. Eigen vlaggen (analoog aan de agent-workspace-vlaggen
hierboven):

- `SF_WORK_CLEANUP_ENABLED=true` — zet de scheduled achtervang-cleanup aan/uit.
- `SF_WORK_CLEANUP_RETENTION_DAYS=7` — mappen die sinds hun laatste wijziging langer
  dan dit aantal dagen stilstaan worden verwijderd; mappen van nog actieve runs
  worden nooit geraakt omdat hun mtime steeds ververst.

`attachments/`, `logs/`, `qualityrun/` en `target/` vallen buiten deze scan — dat
zijn geen door de Kotlin-runtime beheerde agent-workmappen.

## Per-project config (`projects.yaml`)

Naast `repo` en `deploy` kent een project de optionele vlag `manualApprove`
(boolean, default `true`). Die schakelt de handmatige goedkeur-poort (een vaste
`manual-approve`-subtaak vlak vóór de merge) per project aan/uit; alleen een expliciete
`manualApprove: false` zet 'm uit. Gelezen via `ProjectRepoResolver.manualApproveFor(...)`.

Het `deploy:`-blok (`ProjectRepoResolver.DeployConfig`) kent twee actieve modes met SHA-gebaseerde
deploy-verificatie (SF-771, zie functional-spec):

- `rest-restart`: `restartUrl`, `versionUrl`, `tokenEnvVar`, `pollIntervalSeconds` (default 15),
  `timeoutMinutes` (default 20). `DeploySubtaskHandler` pollt `versionUrl` tot `commitHash`
  prefix-matcht met de verwachte merge-SHA (base-branch HEAD via `GitHubApi.latestCommitSha(...)`);
  bij ontbrekende SHA-info terugval op het `startedAt`-gedrag.
- `openshift-watch`: `namespace`, `deployment`, `timeoutMinutes` (default 20) plus de optionele
  `argocdApp` + `argocdNamespace`. Zijn beide gezet, dan leest `DeploymentStatusProbe.argoApplicationStatus(...)`
  (kubectl-adapter `KubectlDeploymentStatusProbe`) de ArgoCD `Application`-CR en keurt pas goed bij
  `Synced` + `Healthy` + `Succeeded` op de verwachte revisie; anders de bestaande image-heuristiek.

De default deploy-timeout staat als `ProjectRepoResolver.DEFAULT_DEPLOY_TIMEOUT_MINUTES = 20`.

`ProjectRepoResolver.fromYaml(...)` parseert `projects.yaml` met SnakeYAML's `SafeConstructor`
(`Yaml(SafeConstructor(LoaderOptions()))`): alleen standaard YAML-typen (maps/lijsten/scalars),
geen instantiatie van willekeurige Java-typen via expliciete tags. Dat sluit deserialisatie-RCE
uit en is gedragsneutraal — geldige config levert exact dezelfde structuren op (SF-565).

Alle onomkeerbare PR-merges lopen via de publieke `merge.PullRequestMergeService`; alleen de
interne `ProjectAwarePullRequestMergeService` roept `GitHubApi.mergePullRequest` aan.
`MergeSubtaskHandler` en `ManualCommandService` leveren projectnaam, repo en PR-nummer aan dezelfde
use-case. `ProjectRepoResolver` leest per project een verplichte, niet-lege
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
`project_key_sequences`), via de interface `TrackerApi` (package `tracker`, implementatie
`PostgresTrackerClient`). Er is geen externe issue-tracker.

Enum-booleans worden als tekstkolom opgeslagen met waarden `"false"`/`"true"`. Voorbeelden: `Paused`
en — sinds SF-335 — `Silent` (default `false`). `Silent` staat op story-niveau; subtaken lezen de
waarde van hun parent-story (best-effort parent-lookup), net als `Auto-approve`. De gedeelde helper
`TrackerApi.effectiveSilent(issue)` bepaalt "effectief silent" (eigen veld óf parent) zodat
coördinatoren, notificaties en dashboard dezelfde beslissing nemen. Clarification-errors (uit
`*-with-questions` bij silent) worden in de error-tekst gemarkeerd met `ErrorCategory.CLARIFICATION`
(`[CLARIFICATION]`), onderscheidbaar van technische errors.

## Nightly scheduler (SF-350)

De nachtelijke scheduler bouwt voort op drie tabellen (Flyway-migraties
`V11__nightly_scheduler.sql`; `V12__nightly_run_summary_text.sql` voegt `summary_text` toe
zodat de verstuurde digest in de UI zichtbaar blijft; `V13__nightly_run_multiple_per_day.sql`
laat meerdere runs per dag toe en voegt de `kind`-kolom toe; `V14__nightly_run_ai_detail_pending.sql`
voegt de vlag `ai_detail_pending` toe waarmee een run wordt gemarkeerd die zijn digest zónder
AI-details verstuurde, zodat een latere tick de samenvatting alsnog kan nasturen):

- `nightly_settings` — enkele rij (`id = 1`) met de master-switch `enabled` en de
  `start_time`/`summary_time` als `HH:MM` in lokale NL-tijd. Defaults: `enabled = false`,
  start `02:00`, summary `07:00`. Beheerd via `NightlySettingsRepository`.
- `nightly_run` — een run met `run_date` (NL-tijd, sinds V13 niet meer uniek),
  `kind` (`scheduled`/`manual`, `NightlyRunKind`), `status`
  (`pending`/`running`/`ended`, `NightlyRunStatus`), `started_at`/`ended_at`,
  `summary_sent_at` (idempotentie-borg voor de digest), `summary_text` (de verstuurde
  digest-tekst voor de UI) en `ai_detail_pending` (run wacht nog op een AI-detail-aanvulling).
  Beheerd via `NightlyRunRepository`. Per dag is er hooguit één `scheduled` run, maar daarnaast
  kun je handmatig `manual` runs starten; er loopt er hooguit één tegelijk (`activeRun()`).
- `nightly_run_job` — per run en project de job-queue met `status`
  (`pending`/`running`/`done`/`failed`/`cancelled`, `NightlyJobStatus`), `story_key`,
  `started_at`/`ended_at` en `error`. `cancelled` betekent dat de job nog liep toen de run
  handmatig werd onderbroken. Beheerd via `NightlyRunJobRepository`.

Tijden staan in lokale NL-tijd; `NightlyTime` (`ZoneId.of("Europe/Amsterdam")`,
DST-correct, injecteerbaar via `Clock`) rekent ze DST-correct naar UTC voor vergelijking
met de UTC-factory-klok en leidt de NL-`run_date` af.

### Reconciliation-scheduler (SF-352)

`NightlyScheduler` is een `@Scheduled`-tick (~30s, `sf.nightly.tick-ms`) die volledig op
DB-state draait — géén in-memory run-status — zodat een rest-restart de lopende run weer
oppikt. De beslis-kern zit in het pure `NightlyPlanner` (geen DB/tijd/netwerk): het krijgt
de huidige run + jobs + gepolde story-uitkomsten en geeft een lijst `NightlyAction`s terug
(`CreateRun`, `StartJob`, `MarkJobTerminal`, `SendDigest`, `EndRun`). De scheduler-executor
voert die acties uit tegen de repositories en de `NightlyGateway`-poort. Plan/uitvoer-scheiding
maakt idempotentie, sequentieel/parallel en restart-pickup puur testbaar (`NightlyPlannerTest`,
`NightlySchedulerTest`).

- **Run-creatie**: `enabled` + huidige tijd ≥ omgerekende `start_time` + nog geen `scheduled`
  run voor vandaag (`hasScheduledRunOn`) → precies één `scheduled` `nightly_run` met per project
  de queue van enabled jobs (job.yaml `enabled:true` via `NightlyJobsReader` + master-switch).
  `NightlyJobsReader` leest `.factory/nightly/<job>/job.yaml` uit geconfigureerde project-repo's
  (deels untrusted) en parseert die net als `projects.yaml` met `SafeConstructor`, zodat een
  kwaadaardige YAML-tag geen willekeurig Java-type kan instantiëren (SF-565).
  Daarnaast kan een mens via de "Run nu"-knop een `manual` run starten
  (`NightlyScheduler.startManualRun`); die lukt alleen als er nog geen run loopt en gebruikt
  dezelfde job-queue. De seeding controleert dat de run nog leeg is, zodat een race/herhaling
  geen dubbele jobs oplevert.
- **Reconcile**: per project parallel (onafhankelijke queues), binnen een project sequentieel.
  Lopende job-story terminaal → `done`/`failed` en de volgende pending job starten via
  `createNightlyStory` (silent=true; `start=true` op het refine+plan-pad, of `start=false` +
  directe subtaak-materialisatie op het config-pad, zie onder). Een fout (story- of subtaak-error) markeert
  alleen die job `failed`; de rest van het project loopt door.
- **Completion-detectie** (`NightlyGatewayAdapter.storyOutcome`): klaar = alle subtaken
  terminaal (`SubtaskPhase.isTerminal`); mislukt = error-veld op de story óf een subtaak gezet.
- **Digest**: exact één digest per run (`summary_sent_at` borgt de idempotentie), nooit vóór de
  omgerekende `summary_time`. Een `scheduled` run stuurt op de summary-tijd (ook als een job nog
  hangt); een `manual` run wacht bovendien tot al z'n jobs terminaal zijn. Telegram via
  `TelegramClient.sendMessage` (één bericht per project-kanaal) + opslag in
  `summary_text`/`summary_sent_at` voor de UI, gegroepeerd per project met per job een feitelijke
  kopregel (story, titel, status, duur, kosten), klikbare links (merge-commit bij voorkeur, anders
  PR, plus het dashboard) en — wanneer beschikbaar — een AI-samenvatting van wát er veranderde
  (`NightlySection`s, opgehaald via `NightlyGateway.describeChanges`), plus totale duur/kosten.
  `NightlyDigest` bouwt de tekst puur.
- **Uitgestelde AI-verrijking**: de feitelijke digest gaat direct de deur uit. Lukt de AI-samenvatting
  van afgeronde stories op dat moment niet (bv. de Claude-limiet is op direct na een zware run), dan
  zet de scheduler `ai_detail_pending = true`. Een aparte, rustiger `@Scheduled`-tick
  (`aiEnrichmentTick`, `sf.nightly.ai-retry-ms`, default 20 min) probeert de samenvatting later opnieuw
  en stuurt de details als aanvullend bericht na zodra het budget hersteld is; na `MAX_ENRICH_HOURS`
  wordt de verrijking opgegeven.
- **Einde**: alle jobs terminaal én digest verstuurd → run-status `ended`.
- **Handmatig onderbreken**: `NightlyScheduler.stopActiveRun` markeert alle nog niet-terminale
  jobs als `cancelled` en zet de run direct op `ended`. Een eventueel al lopende story-agent
  draait buiten de nightly om door (wordt niet gekild); de queue stopt en een nieuwe run kan weer
  gestart worden.

**Declaratief config-pad (SF-787).** Een nightly-job kan naast `job.yaml`/`story.md` een
`.factory/nightly/<job>/subtasks.yaml` bevatten: een GEORDENDE lijst subtaken (`type` + `title`, de
bestandsvolgorde = uitvoervolgorde) plus per AI-subtaak een gelijknamig `<title>.md`.
`NightlyJobsReader.readJob` leest en valideert die via dezelfde `gh`-contents/`decodeContent`-aanpak
(SafeConstructor, geen lokale checkout) en vult `NightlyJobDetail.subtasks` (`List<SubtaskSpec>?`;
`null` = geen config). Validatie: parseert + ≥1 subtaak; elk type in
`{development, review, test, summary, documentation, merge, deploy, manual-approve}` (bewust NIET
`manual`); titels uniek; elke AI-subtaak (development/review/test/summary/documentation) heeft zijn
`<title>.md`; `story.md` bestaat. Bij een fout gooit de reader `NightlySubtasksConfigException`,
waardoor `NightlyScheduler.startJob` de job `failed` markeert en de fout in de digest belandt (geen
story). Met een geldige config maakt `FactoryDashboardService.createNightlyStory` de story met
`start=false` (geen refiner/planner), materialiseert via de geëxposeerde runtime-poort
`SubtaskMaterializationApi.materializeFromSpecs` (implementatie `SubtaskPlanMaterializer`) exact de
gedeclareerde subtaken (idempotent op titel, erft de AI-supplier van de story, GEEN auto-append) en
zet de story-fase op `StoryPhase.PLANNING_APPROVED`. `FactoryDashboardService` (module `web`)
injecteert bewust deze poort uit het `runtime`-base-package i.p.v. de niet-geëxposeerde
`runtime.services.SubtaskPlanMaterializer`, zodat de Spring-Modulith module-grens intact blijft. Zonder `subtasks.yaml` blijft het
pad `start=true` (refine + plan, met factory-afgedwongen documentation/merge/deploy/manual-approve via
`materializeIfPlanned`) ongewijzigd.

De `nightly`-module blijft los gekoppeld via de `NightlyGateway`-poort; de implementatie
(`NightlyGatewayAdapter` in `web`) delegeert naar `FactoryDashboardService`, de tracker, de
story-run-repository en `TelegramClient`. `/nightly` toont bovenaan de status van de
huidige/laatste run (per project gescheiden met done/lopend/pending, inclusief de starttijd per
job); daaronder staan de handmatige job-lijst, een "Run nu"-knop (`POST /nightly/run-now` →
`startManualRun`) en — bij een lopende run — een "Onderbreek run"-knop (`POST /nightly/stop` →
`stopActiveRun`). Beide acties geven via een `?run=`-queryparameter (`started`/`busy`/`stopped`/
`stop-none`) feedback in de UI.

## Ontwerpregels

- Orchestrator-state blijft idempotent en herstelbaar.
- De eigen tracker-database (Postgres) is de zichtbare workflow-bron voor gebruiker en agents.
- Postgres is ook de bron voor run history, event logging en agent knowledge.
- Agents werken in tijdelijke clones en schrijven alleen via hun toegestane rol.
- Gebruik kleine, gerichte tests rond state-machine, config en adapters.
