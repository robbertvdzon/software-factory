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
- `softwarefactory` — de orchestrator/factory zelf, inclusief een ingebouwd
  HTML-dashboard (`web`-package) dat standaard op poort `8080` draait.
- `agentworker` — het standalone agentproces dat in de Docker-container draait.
- `dashboard-backend` — een aparte Spring Boot service die een read-mostly
  JSON-API levert bovenop de factory-database, YouTrack en GitHub (lokaal op
  poort `9090`).
- `dashboard-frontend` — een Flutter (Dart) web-app die de dashboard-backend-API
  consumeert (lokaal op poort `9080`); geen Maven-module, eigen Docker-build.

## Config

Alle eigen environment variables beginnen met `SF_`. De loader
(`SecretsEnvLoader`) leest gelaagd: `properties.default.env` (committed defaults)
→ `properties.env` (lokaal) → `secrets.env` (lokaal, geheim); system environment
variables winnen altijd van de bestanden.

Verplichte keys:

- `SF_YOUTRACK_BASE_URL`
- `SF_YOUTRACK_TOKEN`
- `SF_GITHUB_TOKEN`
- `SF_DATABASE_URL`
- `SF_DATABASE_SCHEMA`

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

- Polling staat altijd aan zodra de applicatie draait.
- `SF_POLL_INTERVAL_MS=1000`
- `SF_POLL_INTERVAL_IDLE_MS=1000`
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

## Per-project config (`projects.yaml`)

Naast `repo` en `deploy` kent een project de optionele vlag `manualApprove`
(boolean, default `true`). Die schakelt de handmatige goedkeur-poort (een vaste
`manual-approve`-subtaak vlak vóór de merge) per project aan/uit; alleen een expliciete
`manualApprove: false` zet 'm uit. Gelezen via `ProjectRepoResolver.manualApproveFor(...)`.

`ProjectRepoResolver.fromYaml(...)` parseert `projects.yaml` met SnakeYAML's `SafeConstructor`
(`Yaml(SafeConstructor(LoaderOptions()))`): alleen standaard YAML-typen (maps/lijsten/scalars),
geen instantiatie van willekeurige Java-typen via expliciete tags. Dat sluit deserialisatie-RCE
uit en is gedragsneutraal — geldige config levert exact dezelfde structuren op (SF-565).

De MERGE-subtaak is niet meer configureerbaar: hij merget bij fase START altijd automatisch
de PR via de GitHub API (`MergeSubtaskHandler.performAutomaticMerge`). Er is geen `merge.mode`
of handmatige merge-poort meer; een merge-conflict of GitHub-fout zet de subtaak op Error en
stopt de keten (geen `AWAITING_HUMAN`). De handmatige goedkeuring zit volledig in de
voorafgaande `manual-approve`-subtaak.

De documentatie-stap (`documentation`-subtaak, rol DOCUMENTER, SF-213) is daarentegen altijd aan
en niet per project uit te zetten. Die wordt afgedwongen ná de planner-subtaken en vóór de
manual-approve-poort; volledige ketenvolgorde:
`development → review → test → summary → documentation → manual-approve → merge → deploy`.

## YouTrack custom fields

De factory garandeert haar custom fields via schema-bootstrap (`YouTrackClient.factoryFieldSpecs`).
Enum-booleans worden gemodelleerd als een `enum[1]`-veld met waarden `false`/`true` en uitgelezen als
`SingleEnumIssueCustomField`. Voorbeelden: `Paused` en — sinds SF-335 — `Silent` (default `false`).
`Silent` staat op story-niveau; subtaken lezen de waarde van hun parent-story (best-effort
parent-lookup), net als `Auto-approve`. De gedeelde helper `YouTrackApi.effectiveSilent(issue)` bepaalt
"effectief silent" (eigen veld óf parent) zodat coördinatoren, notificaties en dashboard dezelfde
beslissing nemen. Clarification-errors (uit `*-with-questions` bij silent) worden in de error-tekst
gemarkeerd met `ErrorCategory.CLARIFICATION` (`[CLARIFICATION]`), onderscheidbaar van technische errors.

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
  `createNightlyStory` (silent=true, start=true). Een fout (story- of subtaak-error) markeert
  alleen die job `failed`; de rest van het project loopt door.
- **Completion-detectie** (`NightlyGatewayAdapter.storyOutcome`): klaar = alle subtaken
  terminaal (`SubtaskPhase.isTerminal`); mislukt = error-veld op de story óf een subtaak gezet.
- **Digest**: exact één digest per run (`summary_sent_at` borgt de idempotentie), nooit vóór de
  omgerekende `summary_time`. Een `scheduled` run stuurt op de summary-tijd (ook als een job nog
  hangt); een `manual` run wacht bovendien tot al z'n jobs terminaal zijn. Telegram via
  `TelegramClient.sendMessage` (één bericht per project-kanaal) + opslag in
  `summary_text`/`summary_sent_at` voor de UI, gegroepeerd per project met per job een feitelijke
  kopregel (story, titel, status, duur, kosten), klikbare links (merge-commit bij voorkeur, anders
  PR, plus YouTrack) en — wanneer beschikbaar — een AI-samenvatting van wát er veranderde
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
- YouTrack blijft de zichtbare workflow-bron voor gebruiker en agents.
- Postgres is de bron voor run history, event logging en agent knowledge.
- Agents werken in tijdelijke clones en schrijven alleen via hun toegestane rol.
- Gebruik kleine, gerichte tests rond state-machine, config en adapters.
