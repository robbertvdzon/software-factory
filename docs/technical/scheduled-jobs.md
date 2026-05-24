# Scheduled jobs

Er zijn 2 scheduled jobs. Scheduling staat aan via `@EnableScheduling` in `SoftwareFactoryApplication`.

## 1. Orchestrator poller

- Klasse: `orchestrator/OrchestratorPoller.kt`
- Methode: `poll()`
- Schedule: `@Scheduled(fixedDelayString = "#{@orchestratorSettings.pollInterval.toMillis()}")`
- Default interval: `SF_POLL_INTERVAL_MS`, default `15000` ms
- Feature flag: `SF_ORCHESTRATOR_POLLING_ENABLED`, default `false`

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
- Feature flag: `SF_ORCHESTRATOR_POLLING_ENABLED`, default `false`

Verantwoordelijkheid:

- Controleert alle actieve stories op token- en kostenbudget.
- Werkt budgetvelden in YouTrack bij.
- Kan stories of het systeem pauzeren als budget- of creditsgrenzen geraakt worden.

