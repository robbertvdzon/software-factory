# Scheduled jobs

Er zijn 3 scheduled jobs. Scheduling staat aan via `@EnableScheduling` in `SoftwareFactoryApplication`.

## 1. Orchestrator poller

- Klasse: `orchestrator/OrchestratorPoller.kt`
- Methode: `poll()`
- Schedule: `@Scheduled(fixedDelayString = "#{@orchestratorSettings.pollInterval.toMillis()}")`
- Default interval: `SF_POLL_INTERVAL_MS`, default `15000` ms
- Altijd actief zodra de applicatie draait.

Verantwoordelijkheid:

- Zoekt werkbare YouTrack issues.
- Past handmatige commands toe.
- Controleert budget, pauzes, errors en concurrency.
- Dispatcht refiner/developer/reviewer/tester agenten.
- Monitort actieve pull requests op merge-status en nieuwe `@factory` comments.

## 2. Cost monitor poller

- Klasse: `orchestrator/CostMonitorPoller.kt`
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
