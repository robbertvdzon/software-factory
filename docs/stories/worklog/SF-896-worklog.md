# SF-896 - Worklog

Story-context bij eerste pickup:
Vervang adaptieve poll-interval door vast backup-interval + event-driven wake vanuit PostgresTrackerClient

Verwijder de adaptieve poll-logica: `OrchestratorPoller.loop()` gebruikt na elke poll altijd `settings.pollInterval` (geen active/idle-tak meer); verwijder `pollIntervalIdle`/`DEFAULT_IDLE_POLL_SECONDS` uit `OrchestratorSettings`; verwijder het uitlezen van `SF_POLL_INTERVAL_IDLE_MS` uit `OrchestratorSettingsFactory` en verhoog de default van `SF_POLL_INTERVAL_MS` naar 60000. Injecteer `ApplicationEventPublisher` in `PostgresTrackerClient` (incl. aanpassen van alle constructor-call-sites) en publiceer na elke succesvolle write in `createStory`, `createSubtask`, `updateIssueFields`, `updateIssueSummary`, `updateIssueDescription`, `transitionIssue` en `postComment` een `FactoryStateChangedEvent`, volgens het bestaande `runCatching { eventPublisher.publishEvent(...) }`-patroon uit `TelegramReplyService`/`AgentRunCompletionService`. Werk `OrchestratorSettingsTest.kt` en `E2eTestConfig.kt` bij aan het verdwenen veld/env-var, voeg een test toe die de event-publicatie vanuit `PostgresTrackerClient` verifieert, en werk `properties.default.env`, `docs/factory/secrets-local.md`, `docs/factory/technical-spec.md` en `docs/technical/scheduled-jobs.md` bij zodat ze geen `SF_POLL_INTERVAL_IDLE_MS`/adaptieve cadans meer noemen en de nieuwe 60000ms-default en event-driven wake beschrijven.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `OrchestratorSettings.kt`: `pollIntervalIdle`-veld en `DEFAULT_IDLE_POLL_SECONDS`-constante verwijderd; alleen `pollInterval` (vast backup-interval) blijft over.
- `OrchestratorSettingsFactory.kt`: uitlezen van `SF_POLL_INTERVAL_IDLE_MS` verwijderd; default van `SF_POLL_INTERVAL_MS` verhoogd naar 60000 (60s).
- `OrchestratorPoller.kt`: `loop()` gebruikt na elke poll altijd `settings.pollInterval` als sleep-duur; de `active`/`hasActiveWork()`-tak en het bijbehorende `Boolean`-retourtype van `runOnce()` zijn verwijderd (was puur voor de nu-verdwenen snel/traag-keuze). Het bestaande `@EventListener onStateChanged`-wake-mechanisme is ongewijzigd gelaten. KDoc bijgewerkt.
- `PostgresTrackerClient.kt`: `ApplicationEventPublisher?` (default `null`, zelfde optioneel-patroon als `AgentRunCompletionService`) toegevoegd aan de constructor. Een private `publishStateChanged(origin)`-helper (`runCatching { eventPublisher?.publishEvent(FactoryStateChangedEvent("tracker-write:$origin")) }`, zelfde patroon als `TelegramReplyService`/`AgentRunCompletionService`) wordt na elke succesvolle write aangeroepen in `createStory`, `createSubtask`, `updateIssueFields` (alleen bij een echte write, niet bij de lege-update early-return), `updateIssueSummary`, `updateIssueDescription`, `transitionIssue` en `postComment`. Geen `@Transactional`-wrapper nodig (writes zijn al autocommit per statement).
- `TrackerClientConfiguration.kt`: de `@Bean`-factory injecteert nu de echte Spring `ApplicationEventPublisher` in `PostgresTrackerClient`.
- Test-call-sites (`PostgresTrackerClientTest.kt`, `TrackerTestState.kt`) blijven de 3-arg-constructor gebruiken (default `null`-publisher) — behalve `PostgresTrackerClientTest`, die nu een recording `ApplicationEventPublisher`-fake meegeeft om de nieuwe event-publicatie te verifiëren.
- `OrchestratorSettingsTest.kt`: assert op `pollIntervalIdle` en de losse "45 seconden default"-test verwijderd; default-assert voor `pollInterval` aangepast naar 60000ms.
- `E2eTestConfig.kt`: `SF_POLL_INTERVAL_IDLE_MS`-override verwijderd uit `TEST_CONFIG_VALUES` (e2e blijft snel via `SF_POLL_INTERVAL_MS=100`).
- Nieuwe test toegevoegd: `PostgresTrackerClientTest` — `` `every write publishes a FactoryStateChangedEvent, failures never break the write` `` — verifieert dat elk van de 7 write-paden precies één `FactoryStateChangedEvent` publiceert (origin start met `tracker-write:`) en dat de no-op `updateIssueFields`-early-return geen extra event geeft. Deze test (en de rest van `PostgresTrackerClientTest`) vereist Docker/Testcontainers en kon in deze devomgeving niet lokaal gedraaid worden (geen Docker) — wel test-compiled.
- Docs bijgewerkt: `properties.default.env`, `docs/factory/secrets-local.md`, `docs/factory/technical-spec.md` en `docs/technical/scheduled-jobs.md` — geen verwijzingen meer naar `SF_POLL_INTERVAL_IDLE_MS`/adaptieve snel-traag-cadans; `SF_POLL_INTERVAL_MS` staat overal op de nieuwe default 60000 en de cadans-beschrijving is aangepast naar "vast interval als vangnet + event-driven wake via `FactoryStateChangedEvent` bij elke tracker-write".

Getest:
- `mvn -pl factory-common -am install -DskipTests` (nodig voor lege `~/.m2`, zie agent-tips) + `mvn -f softwarefactory/pom.xml test-compile`: compileert schoon.
- `mvn -f softwarefactory/pom.xml test -Dtest='OrchestratorSettingsTest,PostgresTrackerClientTest'`: `OrchestratorSettingsTest` groen (3/3); `PostgresTrackerClientTest` faalt op "Could not find a valid Docker environment" (geen Docker in deze devomgeving, bekende beperking — niet door deze wijziging veroorzaakt).
- `mvn -f softwarefactory/pom.xml test -Dtest='!ModulithArchitectureTest,!PostgresTrackerClientTest,!*E2eTest,!AgentResultFileCompletionPollerTest'`: 434 tests, 0 failures, 2 errors — beide errors zijn de bekende Docker-afhankelijke tests (`NightlyRepositoriesTest`, `FactoryDashboardRepositoryScreenshotTest`), niet gerelateerd aan deze story. Geen regressies.
- e2e-/Testcontainers-tests (`mvn verify`) en `PostgresTrackerClientTest` zelf konden niet lokaal gedraaid worden (geen Docker beschikbaar); verifieer die in de CI-pipeline.

Niet gedaan:
- Geen wijziging aan `FactoryStateChangedEvent` zelf of aan `onStateChanged` (buiten scope).
- Geen event-publicatie toegevoegd aan andere schrijfpaden dan `PostgresTrackerClient` (bv. een eventuele resterende `YouTrackClient` — buiten scope, YouTrack is niet meer in gebruik).
- `runOnce()`'s oude `Boolean`-retourtype is verwijderd (impliciet toegestaan door de story/aannames); geen extern gedrag gewijzigd.

## Review (SF-897)

- `git diff main...HEAD` bekeken (13 bestanden): `OrchestratorPoller.kt`, `OrchestratorSettings.kt`,
  `OrchestratorSettingsFactory.kt`, `PostgresTrackerClient.kt`, `TrackerClientConfiguration.kt`,
  tests en docs/properties-bestanden — komt overeen met de scope in `.task.md`.
- `hasActiveWork()`/active-idle-tak correct verwijderd; `IssueProcessResult`/`OrchestratorPollResult`
  blijven elders (o.a. `OrchestratorService`) in gebruik, dus geen dode code overgebleven.
- `publishStateChanged` wordt na elke van de 7 vereiste writes aangeroepen, met `runCatching` en
  optionele (`ApplicationEventPublisher?`, default `null`) publisher — geen breaking change voor
  bestaande call-sites (`TrackerTestState.kt` blijft de 3-arg-constructor gebruiken).
  `updateIssueFields`'s lege-update early-return publiceert terecht geen event.
  `TrackerClientConfiguration` injecteert de echte Spring-publisher in productie.
  `postComment` is correct omgezet naar een `return`-expressie (was `=`) om na de query nog het
  event te publiceren vóór de return.
- Nieuwe test `PostgresTrackerClientTest` verifieert alle 7 event-publicaties + de no-op-case;
  `OrchestratorSettingsTest`/`E2eTestConfig.kt` correct opgeschoond voor het verwijderde veld/env-var.
- `SF_POLL_INTERVAL_IDLE_MS`/`pollIntervalIdle`/`DEFAULT_IDLE_POLL_SECONDS` komen nergens meer voor
  in code, `properties.default.env` of de vier genoemde docs (alleen nog referenties in `.task.md`
  zelf en oudere, historische worklogs — terecht ongewijzigd).
  `docs/factory/technical-spec.md` en `docs/technical/scheduled-jobs.md` beschrijven de nieuwe
  cadans (vast interval + event-driven wake) consistent.
- `mvn -pl factory-common,softwarefactory -am test-compile` slaagt lokaal zonder fouten
  (mvn is in deze reviewer-omgeving beschikbaar via de root-aggregator-pom).
  Testcontainers-tests (`PostgresTrackerClientTest`, e2e) blijven CI-only verifieerbaar, zoals ook
  in het worklog van de developer vermeld.
- Geen scope creep, geen config/secret-risico's gevonden. Akkoord.

## Test (SF-898)

- `git diff main...HEAD` (13 bestanden) bevestigd tegen scope in `.task.md`: alle vereiste
  code-, test- en docbestanden, geen extra's.
- `OrchestratorSettings.kt`/`OrchestratorSettingsFactory.kt`/`OrchestratorPoller.kt`: geen
  `pollIntervalIdle`/`DEFAULT_IDLE_POLL_SECONDS`/`hasActiveWork()`/active-tak meer; sleep gebruikt
  altijd `settings.pollInterval`; `SF_POLL_INTERVAL_MS`-default nu 60000.
- `PostgresTrackerClient.kt`: alle 7 vereiste writes (`createStory`, `createSubtask`,
  `updateIssueFields`, `updateIssueSummary`, `updateIssueDescription`, `transitionIssue`,
  `postComment`) publiceren via `publishStateChanged` (`runCatching`, event ná de write) een
  `FactoryStateChangedEvent`; `TrackerClientConfiguration` injecteert de echte
  `ApplicationEventPublisher`.
- `grep -rn "pollIntervalIdle|SF_POLL_INTERVAL_IDLE_MS|DEFAULT_IDLE_POLL_SECONDS"`: geen treffers
  meer in code/properties/de vier genoemde docs; enige overgebleven hits zijn `.task.md` zelf en
  oudere, ongerelateerde historische worklogs (terecht ongewijzigd).
- `mvn -pl softwarefactory -am -Dtest='OrchestratorSettingsTest' test`: 2/2 groen.
- `mvn -pl softwarefactory -am -Dtest='!ModulithArchitectureTest,!PostgresTrackerClientTest,!*E2eTest,!AgentResultFileCompletionPollerTest' test`:
  434 tests, Failures 0, 2 Errors (beide bekende Docker/Testcontainers-afhankelijke tests:
  `NightlyRepositoriesTest`, `FactoryDashboardRepositoryScreenshotTest` — niet gerelateerd aan deze
  story, tester-omgeving heeft geen Docker).
- `mvn -pl softwarefactory -am test-compile`: compileert schoon, incl. de nieuwe
  `PostgresTrackerClientTest`-testmethode die alle 7 event-publicaties + de no-op-case verifieert.
  Kon zelf niet uitgevoerd worden zonder Docker (Testcontainers/PostgreSQL) — logica statisch
  nagelopen en komt overeen met de acceptance criteria.
- Geen aanwijzingen voor secrets in logs of ontbrekende fail-fast-config die door deze wijziging
  geraakt worden (geen nieuwe env-var toegevoegd, alleen default gewijzigd en één env-var
  verwijderd).
- Conclusie: alle acceptance criteria voldaan, geen regressies. Akkoord.
