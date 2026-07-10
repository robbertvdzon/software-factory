# SF-896 - Orchestrator-poller: vast backup-interval i.p.v. adaptief, event-driven wake bij elke DB-update

## Story

Orchestrator-poller: vast backup-interval i.p.v. adaptief, event-driven wake bij elke DB-update

<!-- refined-by-factory -->

## Scope

De adaptieve poll-interval-logica in `OrchestratorPoller` wordt vervangen door één vast backup-interval, aangevuld met event-driven wakes bij elke schrijf-operatie in `PostgresTrackerClient`.

1. **`OrchestratorPoller.kt`**: verwijder de `active`/`hasActiveWork()`-tak in `loop()`; de sleep gebruikt voortaan altijd `settings.pollInterval` als vaste vangnet-cadans. `runOnce()` hoeft geen `Boolean` meer terug te geven puur voor dat doel (behoud/verwijder naar inzicht van de developer, zolang gedrag klopt). Bestaande `@EventListener onStateChanged` (wake-mechanisme) blijft ongewijzigd.
2. **`OrchestratorSettings.kt`**: verwijder het veld `pollIntervalIdle` (en de bijbehorende `DEFAULT_IDLE_POLL_SECONDS`-constante).
3. **`OrchestratorSettingsFactory.kt`**: verwijder het uitlezen van `SF_POLL_INTERVAL_IDLE_MS`; verhoog de default van `SF_POLL_INTERVAL_MS` naar 60000 (60s) als nieuwe backup-cadans.
4. **`PostgresTrackerClient.kt`**: injecteer `ApplicationEventPublisher` in de constructor en publiceer na elke succesvolle schrijf een `FactoryStateChangedEvent(origin=...)` in: `createStory`, `createSubtask`, `updateIssueFields`, `updateIssueSummary`, `updateIssueDescription`, `transitionIssue`, `postComment`. Volg hetzelfde patroon als in `AgentRunCompletionService`/`TelegramReplyService` (`runCatching { eventPublisher.publishEvent(...) }`, event pas na de daadwerkelijke DB-write, geen `@Transactional`-constructie nodig aangezien writes al autocommit zijn).
5. **Config/documentatie**: verwijder `SF_POLL_INTERVAL_IDLE_MS` en werk `SF_POLL_INTERVAL_MS`-default bij naar 60000 in `properties.default.env`, `docs/factory/secrets-local.md`, `docs/factory/technical-spec.md` en `docs/technical/scheduled-jobs.md` (cadans-beschrijving aanpassen naar "vast interval als vangnet + event-driven wake").
6. **Tests**: bestaande tests die op `pollIntervalIdle` steunen (`OrchestratorSettingsTest.kt`, `E2eTestConfig.kt`) moeten worden aangepast aan het verwijderde veld.

Buiten scope: wijzigen van het event-mechanisme zelf (`FactoryStateChangedEvent`, `onStateChanged`), en wijzigen van andere schrijfpaden buiten `PostgresTrackerClient` (bv. YouTrackClient, indien nog aanwezig).

## Acceptance criteria

- `OrchestratorSettings` bevat geen `pollIntervalIdle`-veld meer; `OrchestratorPoller` gebruikt na elke poll altijd hetzelfde vaste `pollInterval` als sleep-duur, ongeacht of er actief werk was.
- `SF_POLL_INTERVAL_IDLE_MS` wordt nergens meer gelezen; `SF_POLL_INTERVAL_MS` heeft een nieuwe default van 60000 ms.
- `PostgresTrackerClient` publiceert een `FactoryStateChangedEvent` direct na een succesvolle write in elk van: `createStory`, `createSubtask`, `updateIssueFields`, `updateIssueSummary`, `updateIssueDescription`, `transitionIssue`, `postComment`. Een falende event-publicatie mag de write niet laten falen (`runCatching` of gelijkwaardig).
- Bestaande gedrag van de poller blijft functioneel intact: bij elke DB-wijziging via `PostgresTrackerClient` wordt de poller direct gewekt (in plaats van te wachten op het volgende vaste interval), en het vaste interval blijft werken als vangnet wanneer er geen events binnenkomen.
- Alle bestaande én aangepaste tests (`OrchestratorSettingsTest`, `OrchestratorPoller`-gerelateerde tests, e2e-config) compileren en slagen na het verwijderen van `pollIntervalIdle`.
- Documentatie (`docs/factory/secrets-local.md`, `docs/factory/technical-spec.md`, `docs/technical/scheduled-jobs.md`, `properties.default.env`) is bijgewerkt en bevat geen verwijzingen meer naar `SF_POLL_INTERVAL_IDLE_MS` of de adaptieve snel/traag-cadans.

## Aannames

- De nieuwe default van `SF_POLL_INTERVAL_MS` wordt 60000 ms (60s), zoals expliciet als voorbeeld genoemd in de issue-beschrijving.
- Alleen `PostgresTrackerClient` is de relevante actieve schrijflaag (YouTrack is buiten gebruik per de issue-context "geen YouTrack meer"); een eventuele resterende `YouTrackClient`-implementatie hoeft niet van events te worden voorzien.
- Het `runOnce()`-retourtype (`Boolean` voor `hasActiveWork`) mag door de developer vrij worden opgeschoond zolang het geen extern gedrag beïnvloedt; dit is een implementatiedetail, geen acceptatiecriterium op zich.
- Geen `@Transactional`-wrapper nodig rond de writes in `PostgresTrackerClient`, conform de constatering in de issue dat writes al autocommit per statement zijn.

## Eindsamenvatting

## Eindsamenvatting SF-896

**Wat is gebouwd**

De adaptieve poll-interval-logica in de orchestrator is vervangen door een vast backup-interval, aangevuld met event-driven wakes:

- `OrchestratorPoller.loop()` gebruikt na elke poll altijd hetzelfde vaste `settings.pollInterval` als sleep-duur; de oude `active`/`hasActiveWork()`-tak (snel pollen bij werk, traag bij idle) en het bijbehorende `Boolean`-retourtype van `runOnce()` zijn verwijderd.
- `OrchestratorSettings` bevat het veld `pollIntervalIdle` en de constante `DEFAULT_IDLE_POLL_SECONDS` niet meer.
- `OrchestratorSettingsFactory` leest `SF_POLL_INTERVAL_IDLE_MS` niet meer uit; de default van `SF_POLL_INTERVAL_MS` is verhoogd naar 60000 ms (60s).
- `PostgresTrackerClient` publiceert nu na elke succesvolle write (`createStory`, `createSubtask`, `updateIssueFields`, `updateIssueSummary`, `updateIssueDescription`, `transitionIssue`, `postComment`) een `FactoryStateChangedEvent`, via een optionele `ApplicationEventPublisher?` (default `null`) en `runCatching`, zodat een falende publicatie de write nooit kan breken. `TrackerClientConfiguration` injecteert hiervoor de echte Spring-publisher in productie.
- Documentatie en configuratie (`properties.default.env`, `docs/factory/secrets-local.md`, `docs/factory/technical-spec.md`, `docs/technical/scheduled-jobs.md`) zijn bijgewerkt: geen verwijzingen meer naar `SF_POLL_INTERVAL_IDLE_MS` of de adaptieve snel/traag-cadans; overal de nieuwe 60000ms-default en de beschrijving "vast interval als vangnet + event-driven wake".

**Belangrijke keuzes**

- `ApplicationEventPublisher` is optioneel (default `null`) gehouden in `PostgresTrackerClient`, zodat bestaande test-call-sites (3-arg-constructor) niet hoefden te wijzigen — alleen de productieconfiguratie injecteert de echte publisher.
- Het bestaande `@EventListener onStateChanged`-wake-mechanisme is bewust ongewijzigd gelaten (buiten scope).
- Geen event-publicatie toegevoegd aan andere schrijfpaden (bv. een eventuele resterende `YouTrackClient`) — YouTrack is niet meer in gebruik.

**Getest**

- `OrchestratorSettingsTest`: 2/2 (developer) resp. 3/3 groen.
- Bredere suite (`mvn test -Dtest='!ModulithArchitectureTest,!PostgresTrackerClientTest,!*E2eTest,!AgentResultFileCompletionPollerTest'`): 434 tests, 0 failures, 2 pre-existing Docker-gerelateerde errors, niet gerelateerd aan deze wijziging.
- Nieuwe test in `PostgresTrackerClientTest` verifieert dat elk van de 7 writes precies één `FactoryStateChangedEvent` publiceert en dat de no-op `updateIssueFields`-early-return geen event geeft — statisch nagelopen en test-compiled, maar niet lokaal uitgevoerd (geen Docker/Testcontainers in de dev-/testomgeving); reviewer en tester bevestigen dit als bekende, niet-story-gerelateerde beperking en hebben logica geverifieerd.
- Reviewer en tester hebben beide de scope tegen `.task.md` geverifieerd (geen scope creep) en akkoord gegeven.

**Bewust niet gedaan**

- `FactoryStateChangedEvent` zelf en het `onStateChanged`-mechanisme zijn niet aangepast.
- Geen event-publicatie in schrijfpaden buiten `PostgresTrackerClient`.
- Testcontainers-afhankelijke tests (`PostgresTrackerClientTest`, e2e) konden niet lokaal draaien door het ontbreken van Docker; verificatie hiervan moet via de CI-pipeline gebeuren.
