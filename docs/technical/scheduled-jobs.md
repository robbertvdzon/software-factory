# Scheduled jobs

De `@Scheduled` jobs (cost monitor, agent result completion, de nightly scheduler — die zelf twee
`@Scheduled`-methodes heeft: de hoofd-tick en de AI-verrijking-tick — en de work-cleanup poller)
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
  `@factory:command:approve`-comment altijd bij de eerstvolgende poll wordt verwerkt.
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
- Roept `RuntimeApi.complete(...)` aan zodat usage, events, tracker-updates, PR metadata en knowledge updates centraal worden verwerkt.

## 4. Nightly scheduler

- Klasse: `nightly/NightlyScheduler.kt`
- Methode: `tick()` (delegeert naar `runOnce()`)
- Schedule: `@Scheduled(fixedDelayString = "\${sf.nightly.tick-ms:30000}", initialDelayString = "\${sf.nightly.initial-delay-ms:30000}")`
- Default interval: `30000` ms

Verantwoordelijkheid:

- Leest elke tick de hele run-status uit de DB (geen in-memory state) en laat de pure `NightlyPlanner`
  de acties bepalen, zodat een rest-restart vanzelf veilig is.
- Maakt op de start-tijd één automatische (`scheduled`) run per kalenderdag aan; daarnaast kunnen
  handmatige (`manual`) runs gestart worden (`startManualRun`). Er loopt er hooguit één tegelijk.
- Start per project sequentieel de enabled jobs, detecteert terminale story-uitkomsten en kan een
  lopende run handmatig onderbreken (`stopActiveRun`, jobs → `cancelled`).
- **Config-pad (SF-787)**: bevat een job een `.factory/nightly/<job>/subtasks.yaml`, dan leest en
  valideert `NightlyJobsReader` die geordende subtaak-lijst (`type` + `title`) plus de bijbehorende
  `<title>.md`-bestanden vóór story-aanmaak. Is de config geldig, dan slaat de factory refine + plan
  over: `createNightlyStory` maakt de story met `description = story.md` en `start=false`,
  materialiseert exact de gedeclareerde subtaken (geen factory-afgedwongen extra's) en zet de
  story-fase op `planning-approved`. Bij een validatiefout (parse / ongeldig type / dubbele titel /
  ontbrekend `<title>.md` of `story.md`) gooit de reader `NightlySubtasksConfigException`, wordt de
  job `failed` gemarkeerd en belandt de fout in de digest — er wordt geen story aangemaakt. Een job
  zonder `subtasks.yaml` behoudt het klassieke gedrag (`start=true`, refine + plan).
- Stuurt niet vóór de summary-tijd één digest per run naar Telegram en bewaart die voor de UI. De
  digest bevat per job een feitelijke kopregel, klikbare links (merge-commit bij voorkeur, anders PR,
  plus het dashboard) en — wanneer beschikbaar — een AI-samenvatting van de wijzigingen
  (`NightlyGateway.describeChanges`).

## 4b. Nightly AI-verrijking (uitgesteld)

- Klasse: `nightly/NightlyScheduler.kt`
- Methode: `aiEnrichmentTick()` (delegeert naar `enrichPendingDigests()`)
- Schedule: `@Scheduled(fixedDelayString = "\${sf.nightly.ai-retry-ms:1200000}", initialDelayString = "\${sf.nightly.ai-retry-initial-delay-ms:120000}")`
- Default interval: `1200000` ms (20 min)

Verantwoordelijkheid:

- De feitelijke digest gaat direct uit; lukt de AI-samenvatting op dat moment niet (bv. de
  Claude-limiet is op direct na een zware run), dan wordt de run gemarkeerd met
  `ai_detail_pending`.
- Deze rustiger tick probeert per openstaande run de AI-samenvatting later opnieuw en stuurt de
  details als aanvullend bericht na zodra het budget hersteld is; na `MAX_ENRICH_HOURS` (12 uur)
  wordt de verrijking opgegeven.

Zie ook `docs/factory/technical-spec.md` (Nightly scheduler) voor het volledige verhaal.

## 5. Work cleanup poller (achtervang)

- Klasse: `runtime/workspaces/WorkCleanupPoller.kt`
- Methode: `poll()` (delegeert naar `cleanupOnce()`)
- Schedule: `@Scheduled(fixedDelayString = "\${softwarefactory.work-cleanup-poll-ms:3600000}")`
- Default interval: `3600000` ms (1 uur)
- Uit te zetten via `SF_WORK_CLEANUP_ENABLED` (default `true`).

Verantwoordelijkheid:

- Scant elke tick de vier `work/`-subroots die de runtime zelf aanmaakt:
  `work/agent-workspaces/<story>-<role>-<random>/`, `work/stories/<storyKey>/repo`,
  `work/assistant-checkouts/<naam>/repo` en `work/assistant/<chatId>/<sessionId>/{in,out}`.
- Verwijdert per top-level entry recursief zodra de meest recente mtime binnenin ouder is dan
  `SF_WORK_CLEANUP_RETENTION_DAYS` (default `7` dagen); mappen van nog actieve runs worden nooit
  geraakt omdat hun mtime steeds ververst.
- Is een achtervang bovenop de bestaande event-gedreven cleaners (`AgentWorkspaceCleaner`,
  `StoryWorkspaceService.cleanup`), die alleen bij succesvolle run-completion of expliciete
  purge/merge opruimen en dus weesmappen achterlaten na crashes of gekilde processen.
- Logt elke verwijdering (pad + berekende leeftijd) voor traceerbaarheid.
- Raakt `attachments/`, `logs/`, `qualityrun/` en `target/` niet aan — die worden niet door de
  Kotlin-runtime als agent-workmap beheerd.

Zie ook `docs/factory/technical-spec.md` (achtervang work-cleanup) en `docs/factory/secrets-local.md`
voor de env-var-defaults.
