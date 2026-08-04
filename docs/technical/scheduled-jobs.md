# Scheduled jobs

De `@Scheduled` jobs (cost monitor, agent result completion, de nightly scheduler — die zelf twee
`@Scheduled`-methodes heeft: de hoofd-tick en de AI-verrijking-tick —, de work-cleanup poller, de
Telegram-resultaatmelding poller en de maintenance-cleanup scheduler)
staan aan via `@EnableScheduling` in `SoftwareFactoryApplication`. De orchestrator poller en de
Telegram poller zijn geen `@Scheduled` jobs, maar eigen daemon-threads (zie hieronder).

## 1. Orchestrator poller

- Klasse: `orchestrator/schedulers/OrchestratorPoller.kt`
- Methode: `loop()` / `runOnce()`
- Schedule: geen `@Scheduled`, maar een daemon-thread (`orchestrator-poller`) die op
  `ApplicationReadyEvent` start en slaapt met een wekbare sleep.
- Cadans: vast interval (`SF_POLL_INTERVAL_MS`, default `60000` ms) als vangnet. Elke schrijf-
  operatie in `PostgresTrackerClient` (`createStory`, `createSubtask`, `updateIssueFields`,
  `updateIssueSummary`, `updateIssueDescription`, `transitionIssue`, `postComment`) publiceert
  direct na de write een `FactoryStateChangedEvent` dat de wachtende sleep meteen wekt, zodat de
  keten zonder vertraging doorzet; het vaste poll-interval is dan alleen nog het vangnet wanneer er
  geen events binnenkomen.
- Idempotentie-guard (SF-903/SF-904): `transitionIssue` en `updateIssueFields` slaan de `UPDATE`
  (en dus het publiceren van het event en de `updated_at`-bump) over wanneer de opgegeven
  waarde(n) al gelijk zijn aan de huidige rij (`... WHERE issue_key = ? AND (... IS DISTINCT
  FROM ?)`). Zo blijft `updated_at` van een reeds afgeronde (terminale) subtask/story ongewijzigd
  en valt die uit de `findAiIssues`-window, zodat ze zichzelf niet langer eeuwig opwekt via een
  no-op transitie. `advanceSubtaskChain` (`SubtaskExecutionCoordinator`) roept `transitionIssue`
  daarnaast alleen nog aan wanneer de subtask/parent-story niet al de doelstatus heeft.
- Altijd actief zodra de applicatie draait.

Verantwoordelijkheid:

- Zoekt werkbare tracker-issues (fase-gate: lege fase = niet starten, `start` = oppakken).
  `PostgresTrackerClient.findAiIssues` combineert hiervoor de top-N op `updated_at DESC` met alle
  issues in een niet-terminale `subtask_phase` (begrensd via `PENDING_SUBSET_LIMIT`, 500), zodat een
  wachtende (sub)taak (bv. `manual-approve-needed`) niet buiten de LIMIT kan vallen en een geldig
  `@factory:command:approve`-comment altijd bij de eerstvolgende poll wordt verwerkt. Een derde,
  ongelimiteerde tak voegt alle issues met `retry_after` toe. Vóór dat tijdstip wordt zo'n
  Claude-quota-issue vóór recovery/hard-timeout overgeslagen; op of erna wordt dezelfde actieve rol
  met een nieuw starttijdstip gedispatcht.
- Done-filter (SF-918): de top-N-tak sluit rijen met een afgeronde `status` uit
  (`core.FinishedStatus` — `done`/`fixed`/`verified`/`closed`/`resolved`, lowercase-genormaliseerd;
  dezelfde set als `StoryStatusPresenter.classifyStatus`), zodat een afgeronde story niet telkens
  opnieuw wordt opgehaald zolang er geen event binnenkomt. De niet-terminale-`subtask_phase`-tak
  filtert niet op `status`, dus een nog actieve subtaak van een al-op-Done-gezette story blijft
  bereikbaar.
- Past handmatige commands toe.
- Controleert budget, pauzes, errors en concurrency.
- Dispatcht de agent-rollen van het twee-laags model: refiner/planner op story-niveau,
  developer/reviewer/tester/summarizer/documenter op subtaak-niveau; de merge- en
  deploy-subtaken worden zonder agent afgehandeld.
- Monitort actieve pull requests op merge-status en nieuwe `@factory` comments.

## 1b. Telegram poller

- Klasse: `telegram/TelegramPoller.kt`
- Schedule: geen `@Scheduled`, maar een daemon-thread (`telegram-poller`) die op
  `ApplicationReadyEvent` start; assistent-gesprekken draaien op een aparte thread-pool
  (`telegram-assistant`).
- Alleen actief met geconfigureerde Telegram-secrets.

Verantwoordelijkheid:

- Leest updates van de Telegram Bot API (long polling) en vertaalt replies naar antwoorden op
  vragen, `@factory`-commands en assistent-gesprekken.

## 2. Cost monitor poller

- Klasse: `orchestrator/schedulers/CostMonitorPoller.kt`
- Methode: `poll()`
- Schedule: `@Scheduled(fixedDelayString = "#{@orchestratorSettings.costMonitorInterval.toMillis()}")`
- Default interval: `SF_COST_MONITOR_INTERVAL_MS`, default `300000` ms
- Altijd actief zodra de applicatie draait.

Verantwoordelijkheid:

- Controleert alle actieve stories op token- en kostenbudget.
- Werkt budgetvelden in de tracker-database bij.
- Kan stories of het systeem pauzeren als budget- of creditsgrenzen geraakt worden.

## 3. Agent result file completion poller

- Klasse: `runtime/services/AgentResultFileCompletionPoller.kt`
- Methode: `poll()`
- Schedule: `@Scheduled(fixedDelayString = "\${softwarefactory.agent-result-poll-ms:2000}")`
- Default interval: `2000` ms

Verantwoordelijkheid:

- Zoekt actieve agent runs in PostgreSQL.
- Wacht zolang de bijbehorende Docker-container nog draait.
- Leest na container-exit `/work/agent-result.json` uit de workspace.
- Roept `RuntimeApi.complete(...)` aan zodat usage, events, tracker-updates, PR metadata, knowledge
  updates en de optionele Claude-rate-limitinformatie centraal worden verwerkt. Een quota-uitkomst
  resulteert in `retry_after` in plaats van een `Error` of fase-overgang.

## 4. Audit scheduler

Vervangt de vroegere nightly scheduler (`nightly/NightlyScheduler.kt`, met een eigen digest-tick):
nightly jobs pasten zelf code aan (tot en met automerge/deploy); audits zijn read-only en stellen
hoogstens 1 vervolg-story voor. De hele `nightly`-module (scheduler, planner, jobs-reader, gateway,
digest, dashboardschermen/-bridge-operaties) is verwijderd.

- Klasse: `audit/services/AuditScheduler.kt`
- Methode: `tick()` (delegeert naar `runOnce()`)
- Schedule: `@Scheduled(fixedDelayString = "\${sf.audit.tick-ms:30000}", initialDelayString = "\${sf.audit.initial-delay-ms:30000}")`
- Default interval: `30000` ms

Verantwoordelijkheid:

- Leest elke tick de hele run-status uit de DB (geen in-memory state) en laat de pure `AuditPlanner`
  de acties bepalen — zelfde restart-veilige opzet als de oude nightly scheduler.
- Maakt één automatische (`SCHEDULED`) run per kalenderdag aan, zodra het eerste project z'n
  starttijd bereikt heeft. De starttijd is **per project** instelbaar
  (`audit_project_settings.start_time`, migratie `V24`); is er voor dat project geen rij of staat
  daar geen tijd in, dan geldt de globale `audit_settings.start_time` (default 08:00). De run is een
  lege container: elk project wordt pas geseed (`AuditAction.SeedProject`) zodra zíjn eigen
  starttijd bereikt is, dus projecten kunnen op verschillende momenten van de dag instromen.
- Bij het seeden kiest de scheduler per project de **N** enabled audits met de oudste
  `audit_report.generated_at` (nooit gedraaid = oudste), waarbij N = `audit_project_settings.audit_count`
  (default 1, zie `AuditProjectSettings.DEFAULT_AUDIT_COUNT`). Zo komen alle geconfigureerde audits
  van dat project om beurten aan bod. `audit_count = 0` betekent: dit project wordt niet geseed (en
  telt ook niet mee voor het aflopen van de run). Meerdere audits van hetzelfde project draaien
  sequentieel, nooit tegelijk.
- "Run now" in het dashboard (`audit.runNow` → `startManualAudit()`) zet één audit klaar, ook als er
  al een run loopt: de job hangt dan als `kind = manual` (migratie V25) aan de lopende run en start
  zodra dat project geen andere audit meer heeft draaien. Zo'n handmatige job telt níet als "dit
  project is geseed" (`AuditSeeding.isSeeded`), zodat de geplande ronde van dat project die dag
  gewoon doorgaat. Antwoord: `started` (geaccepteerd ja/nee) + `status` (`ManualAuditResult`:
  `started`/`queued`/`already_queued`/`unknown_audit`).
- Dispatcht per gekozen audit rechtstreeks een agent-container via `AgentRuntime` (`AuditGateway`/
  `AuditGatewayAdapter`, in `dashboard/services/`) — **geen** tracker-story, **geen**
  `AgentDispatcher`/Subtask-koppeling. Rol `AUDITOR` (zie `core.AgentRole`); prompt + JSON-
  outputcontract in `agentworker` (`AgentPromptContracts.RolePrompts.auditorPrompt()`).
- Het rapport zelf schrijft de auditor als markdown naar `/work/audit-report.md`; de agentworker
  leest dat bestand terug en zet het als `auditReportMarkdown` in `agent-result.json`. De chatoutput
  (`summaryText`) is expliciet **niet** de bron van het rapport: dat is het laatste agent-bericht en
  bevat vaak alleen het JSON-besluit (leeg rapport) of JSON tússen de tekst. Ontbreekt het bestand
  (oudere agentworker, andere supplier), dan valt `AuditGatewayAdapter.reportContent()` terug op
  `summaryText` mét de JSON-strip.
- **Vragen stellen (twee runs).** Een auditor die niet verder kan zonder menselijke beslissing
  eindigt met `{"phase":"audit-questions","questions":[...]}` en zet z'n tussenstand in
  `/work/audit-findings.md`. `AuditGatewayAdapter` schrijft vraag + bevindingen naar `audit_question`
  (migratie `V26`) en levert `AuditOutcomeStatus.ASKED`; de planner markeert de job terminaal met
  `AuditJobStatus.ASKED` en er komt géén rapport.
  **Terminaal is hier essentieel:** de planner sluit een run pas als álle jobs terminaal zijn en
  maakt alleen een nieuwe run aan als er geen loopt — een niet-terminale "wachtende" job zou dus
  alle audits van alle projecten stilleggen zolang er één vraag openstond. De vraag leeft daarom los
  van de run-levenscyclus.
  `AuditScheduler.answerQuestion()` slaat het antwoord op en plant via `startManualAudit()` meteen
  een vervolg-job in, zodat de eerstvolgende tick 'm binnen ~30s start i.p.v. pas de volgende nacht.
  Die vervolgrun krijgt vraag, antwoord en bevindingen terug via `auditTaskContext()` en hoeft het
  onderzoek dus niet over te doen; `consumed_at` voorkomt dat het daarna in élke run blijft plakken.
  Draait de audit opnieuw terwijl de vraag nog openstaat, dan krijgt hij 'm ook terug — met de
  instructie 'm niet nogmaals te stellen maar af te ronden op een expliciet benoemde aanname.
  Zichtbaar/beantwoordbaar op twee plekken: op de audit-kaart in het Audits-scherm (met een
  tel-badge op het nav-item, want een audit heeft geen story en valt dus buiten "My actions"), en in
  Telegram. Die laatste loopt via de poort `AuditQuestionNotifier` (telegram-module) en, voor het
  antwoord, `AuditQuestionAnswering` (`core :: contracts`) — telegram mag niet van `audit` afhangen.
  De `telegram_pending_questions`-rij gebruikt `issue_level='AUDIT'`, `issue_key='AUDIT:<p>:<type>'`
  en het vraag-id in `source_phase`.
- Zodra de container stopt: leest `agent-result.json` (uitgebreid met `auditReportMarkdown`/
  `auditQuestions`/`auditFindingsMarkdown`/
  `auditScore`/`auditScoreLabel`/`proposedStoryTitle`/`proposedStoryDescription`), upsert eventuele
  memory-tips via het bestaande `knowledge`-domein (rol `auditor`, category = audit-type — dus
  automatisch weer meegenomen in `agent-tips.md` bij de volgende run, zonder extra code), maakt
  desgevraagd de voorgestelde vervolg-story aan (`questionsAllowed=true`, fase `start-next` — zie
  `StoryPhase.START_NEXT`) en persisteert het rapport in `audit_report`.
- Geen digest-stap: rapporten staan meteen in het dashboard (Audits-tab, `audit_screen.dart`).

Zie ook `.factory/nightly/README.md` voor het volledige audit-verhaal.

## 5. Work cleanup poller (achtervang)

- Klasse: `runtime/workspaces/WorkCleanupPoller.kt`
- Methode: `poll()` (delegeert naar `cleanupOnce()`)
- Schedule: `@Scheduled(fixedDelayString = "\${softwarefactory.work-cleanup-poll-ms:3600000}")`
- Default interval: `3600000` ms (1 uur)
- Uit te zetten via `SF_WORK_CLEANUP_ENABLED` (default `true`).

Verantwoordelijkheid:

- Scant elke tick de vier `work/`-subroots die de runtime zelf aanmaakt:
  `work/agent-workspaces/<story>-<role>-<random>/`, `work/stories/<storyKey>/repo`,
  `work/assistant-checkouts/<naam>/repo` en `work/assistant/<chatId>/<sessionId>/work/{in,out}`.
- Verwijdert per top-level entry recursief zodra de meest recente mtime binnenin ouder is dan
  `SF_WORK_CLEANUP_RETENTION_DAYS` (default `7` dagen). Exact op de grens geldt als verlopen.
- Vraagt vóór iedere leeftijdsbepaling alle actieve paden op uit story-/agent-runs in Postgres en
  het runtime-register van lopende assistantsessies. Een actief pad, zijn ancestors/top-level entry
  en zijn descendants worden ongeacht mtime overgeslagen. Als een actieve bron faalt, wordt die
  hele cleanup-tick fail-safe overgeslagen.
- Entryfouten en verdwenen racekandidaten worden gelogd en isoleren de overige entries. Paden
  worden genormaliseerd en recursieve verwijdering volgt geen symlinks buiten de beheerde root.
- Is een achtervang bovenop de bestaande event-gedreven cleaners (`AgentWorkspaceCleaner`,
  `StoryWorkspaceService.cleanup`), die alleen bij succesvolle run-completion of expliciete
  purge/merge opruimen en dus weesmappen achterlaten na crashes of gekilde processen.
- Logt elke verwijdering (pad + berekende leeftijd) voor traceerbaarheid, en schrijft de ronde
  sinds SF-1921 ook weg in de gedeelde opruim-log (`kind = workspaces`) — alleen bij verwijderingen
  of bij een fout, fail-soft.
- Raakt `attachments/`, `logs/`, `qualityrun/` en `target/` niet aan — die worden niet door de
  Kotlin-runtime als agent-workmap beheerd.

Zie ook `docs/factory/technical-spec.md` (achtervang work-cleanup) en `docs/factory/secrets-local.md`
voor de env-var-defaults.

## 6. Telegram-resultaatmelding poller (SF-1134 / SF-1261)

- Klasse: `telegram/services/TelegramResultNotifyPoller.kt`
- Methode: `poll()`
- Schedule: `@Scheduled(fixedDelayString = "\${softwarefactory.telegram-result-notify-poll-ms:60000}")`
- Default interval: `60000` ms
- Alleen actief met geconfigureerde Telegram-secrets (`telegramClient.enabled`).

Verantwoordelijkheid:

- Stuurt een aparte Telegram-melding zodra het eindresultaat van een story écht extern
  zichtbaar/live is, in plaats van de gewone `als-klaar`-melding van `TelegramNotificationService`.
- "Alleen pollen wanneer nodig": stopt direct zonder cluster-/GitHub-calls zodra geen enkele story
  `notify_mode=als-klaar-en-gedeployed` heeft staan (SF-1261; vervangt de vroegere losse
  `telegram_result_notify`-vlag). Omdat dit dezelfde enum is als `meldingen=geen`, respecteert de
  poller die stand nu inherent (voorheen een losse boolean-inconsistentie).
- Hergebruikt de bevestiging die `DeploySubtaskHandler` (`pipeline`) al doet zodra de DEPLOY-subtaak
  `deploy-approved` bereikt, en voegt alleen de ontbrekende externe check toe: een HTTP-200 op het
  optionele `deploy.liveUrl` (openshift-watch) of een nieuwe `.apk`-release na de deploy-referentietijd
  (projecten zonder deploy-config, via de poort `ApkReleaseProbe`/adapter `GitHubApkReleaseProbe`).
- Berichtopbouw (SF-1830, kop uitgebreid in SF-1858): kop `🚀 Story <KEY>: <TITEL> is deployed!`
  (lege/whitespace-only titel: `🚀 Story <KEY> is deployed!`; titel langer dan `TITLE_LIMIT` = 120
  tekens wordt afgekapt met `…`), daaronder een korte functionele
  samenvatting, daaronder (indien aanwezig) de URL — lege regel tussen elk blok. De bevestigende zin
  ("De live-URL is bereikbaar." e.d.) staat niet meer in het bericht; het interne `Confirmation`-model
  draagt alleen nog de eventuele URL, de checks hierboven bepalen nog steeds ÓF, WANNEER en met welke
  URL er gemeld wordt. Bron van de samenvatting, eerste niet-lege wint: (1) het blok tussen
  `<!-- deploy-summary:start -->` / `<!-- deploy-summary:end -->` uit de meest recente SUMMARIZER-run
  via `FactoryOperations.deploySummaryFor(storyKey)` (de poller krijgt `FactoryOperations` als extra
  dependency), (2) de `## Samenvatting`-sectie uit de story-description, (3) niets — dan bestaat het
  bericht alleen uit de kop (+ eventuele URL). Elke bron is soft-fail (`runCatching`): een fout bij
  ophalen of parsen houdt de melding nooit tegen. De tekst wordt gestript via `ControlJsonStripper`
  en afgekapt op 1000 tekens.
- Opgeef-timeout van 4 uur na de deploy-referentietijd: alleen een warn-logregel, geen Telegram-
  bericht en geen foutmelding; de story wordt wel als afgehandeld gemarkeerd.
- Idempotent via `TelegramStore` (DB-backed, signature `"result-notify"`), overleeft een herstart.

Zie ook `docs/factory/technical-spec.md` §Telegram-resultaatmelding voor het volledige verhaal.

## 7. Maintenance-cleanup scheduler

- Klasse: `maintenance/services/MaintenanceCleanupScheduler.kt`
- Methode: `tick()`
- Schedule: `@Scheduled(cron = "\${sf.maintenance.cleanup-cron:0 30 2 * * *}", zone = "UTC")`
- Default: elke nacht om 02:30 UTC.
- Config: `sf.maintenance.dry-run` (default `false`) en `sf.maintenance.run-retention-days`
  (default `90`), gebundeld in `MaintenanceCleanupSettings`.

Verantwoordelijkheid:

- Ruimt per project met een `releaseCleanup:`-blok in `projects.yaml` oude GitHub-Releases (incl.
  hun git-tag) en ghcr.io-package-versions op. Welke weg mogen bepalen de pure
  `ReleaseRetentionPlanner`/`PackageVersionRetentionPlanner`; tags die aan een beschermde manifest-SHA
  hangen (`GitHubProtectedShaSource`) en `alwaysKeepTags` blijven staan. Dit algoritme is niet
  gewijzigd in SF-1913.
- Legt per project precies één rij vast in `maintenance_cleanup_runs` (migraties `V30`/`V31`, via
  `maintenance/repositories/MaintenanceCleanupRunRepository`) met `kind = 'github-releases'` — óók bij 0 verwijderingen, bij een
  dry-run (met de *geplande* aantallen; er wordt dan niets verwijderd) en bij een mislukte
  projectronde (`error` gevuld). Zonder rij is "er viel niets op te ruimen" niet te onderscheiden
  van "de opruimer heeft niet gedraaid". Een project zonder GitHub-slug wordt overgeslagen en levert
  géén rij.
- Fail-soft op drie niveaus: een individuele delete die faalt telt niet mee als verwijderd en zet
  géén `error` op de run; een gefaalde projectronde wordt gelogd, vastgelegd en houdt de overige
  projecten niet tegen; en het wegschrijven van de historie zelf kan falen zonder de opruiming om te
  gooien (alleen een warn-log).
- Sluit elke tick af met retentie op de eigen historie: rijen ouder dan
  `sf.maintenance.run-retention-days` worden verwijderd (fail-soft, met een info-log over het
  aantal) — sinds SF-1921 dus voor álle soorten in de gedeelde opruim-log. Er is bewust geen aparte
  poller voor.
- Stuurt sinds SF-1913 géén Telegram-bericht meer over een opruimronde; het Opruimen-scherm van
  de dashboard-app leest de historie via `maintenance.cleanupsList`/`maintenance.cleanupDetail`.

Zie ook `docs/factory/technical-spec.md` §Opruimen en `runbook.md` voor de triage.

## 8. Agent-retentie pollers (`agent_events` / `agent_runs`)

- Klassen: `runtime/services/AgentEventRetentionPoller.kt` en (SF-1921)
  `runtime/services/AgentRunRetentionPoller.kt`
- Methode: `poll()` (delegeert naar `cleanupOnce()`, publiek als test-seam)
- Schedule: `@Scheduled(fixedDelayString = "\${softwarefactory.agent-event-retention-poll-ms:3600000}")`
  respectievelijk `"\${softwarefactory.agent-run-retention-poll-ms:3600000}"`, met eigen
  `initialDelay`-properties (120000 / 180000 ms)
- Default interval: `3600000` ms (1 uur)
- Config: `SF_AGENT_EVENT_RETENTION_{ENABLED,DAYS,BATCH_SIZE,MAX_BATCHES}` (30 dagen, 5000, 20) en
  `SF_AGENT_RUN_RETENTION_{ENABLED,DAYS,BATCH_SIZE,MAX_BATCHES}` (90 dagen, 1000, 20); alle waarden
  worden geklemd.

Verantwoordelijkheid:

- `agent_events` (de agent-logregels) en `agent_runs` (de runs zelf, met hun kostenhistorie) hebben
  bewust een eigen poller en eigen termijn: de runs mogen langer blijven staan dan de logregels
  erbij, en moeten los aan/uit te zetten zijn. De event-retentie is per definitie een no-op zodra
  een run verdwijnt — de events gaan dan mee via `ON DELETE CASCADE`.
- Beide verwijderen batchgewijs (`BATCH_SIZE` rijen per delete, hoogstens `MAX_BATCHES` batches per
  ronde) en stoppen zodra een deel-batch terugkomt; loopt een ronde tegen de bovengrens, dan gaat de
  volgende tick verder.
- De `agent_runs`-retentie laat een lopende run (`ended_at IS NULL`) en een run met een onafgeronde
  durable completion (`PENDING`, `IN_PROGRESS`, `FAILED_RETRYABLE`) altijd staan, ongeacht leeftijd.
  Er wordt alleen op `agent_runs` gedelete; `agent_events`, `agent_run_completions` en
  `agent_run_completion_steps` volgen via de bestaande `ON DELETE CASCADE`.
- Schrijven hun ronde weg in de gedeelde opruim-log (`maintenance_cleanup_runs`, `kind` =
  `agent-events` / `agent-runs`) via `runtime/services/CleanupLogWriter` — alléén bij verwijderingen
  of bij een fout, en fail-soft. Datzelfde geldt voor `WorkCleanupPoller` (`kind = workspaces`) en de
  completion-payload-purge in `AgentRunCompletionService.reconcileDurableCompletions()`
  (`kind = completion-payloads`).
