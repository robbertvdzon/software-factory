# Scheduled jobs

De drie `@Scheduled` jobs (cost monitor, agent result completion en nightly scheduler) staan aan via
`@EnableScheduling` in `SoftwareFactoryApplication`. De orchestrator poller is geen `@Scheduled` job,
maar een eigen daemon-thread (zie hieronder).

## 1. Orchestrator poller

- Klasse: `orchestrator/schedulers/OrchestratorPoller.kt`
- Methode: `loop()` / `runOnce()`
- Schedule: geen `@Scheduled`, maar een daemon-thread (`orchestrator-poller`) die op
  `ApplicationReadyEvent` start en adaptief slaapt met een wekbare sleep.
- Cadans: snel (`SF_POLL_INTERVAL_MS`, default `1000` ms) zolang er actief werk loopt, anders traag
  (`SF_POLL_INTERVAL_IDLE_MS`, default `1000` ms). Een `FactoryStateChangedEvent` wekt de wachtende
  sleep direct, zodat de keten zonder vertraging doorzet; het poll-interval is dan het vangnet.
- Altijd actief zodra de applicatie draait.

Verantwoordelijkheid:

- Zoekt werkbare YouTrack issues.
- Past handmatige commands toe.
- Controleert budget, pauzes, errors en concurrency.
- Dispatcht refiner/developer/reviewer/tester agenten.
- Monitort actieve pull requests op merge-status en nieuwe `@factory` comments.

## 2. Cost monitor poller

- Klasse: `orchestrator/schedulers/CostMonitorPoller.kt`
- Methode: `poll()`
- Schedule: `@Scheduled(fixedDelayString = "#{@orchestratorSettings.costMonitorInterval.toMillis()}")`
- Default interval: `SF_COST_MONITOR_INTERVAL_MS`, default `300000` ms
- Altijd actief zodra de applicatie draait.

Verantwoordelijkheid:

- Controleert alle actieve stories op token- en kostenbudget.
- Werkt budgetvelden in YouTrack bij.
- Kan stories of het systeem pauzeren als budget- of creditsgrenzen geraakt worden.

## 3. Agent result file completion poller

- Klasse: `runtime/services/AgentResultFileCompletionPoller.kt`
- Methode: `poll()`
- Schedule: `@Scheduled(fixedDelayString = "\${softwarefactory.agent-result-poll-ms:5000}")`
- Default interval: `5000` ms

Verantwoordelijkheid:

- Zoekt actieve agent runs in PostgreSQL.
- Wacht zolang de bijbehorende Docker-container nog draait.
- Leest na container-exit `/work/agent-result.json` uit de workspace.
- Roept `RuntimeApi.complete(...)` aan zodat usage, events, YouTrack-updates, PR metadata en knowledge updates centraal worden verwerkt.

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
- Stuurt niet vóór de summary-tijd één digest per run naar Telegram en bewaart die voor de UI.

Zie ook `docs/factory/technical-spec.md` (Nightly scheduler) voor het volledige verhaal.
