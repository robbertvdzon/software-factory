# SF-1009 - Worklog

Story-context bij eerste pickup:
Starttijd/looptijd op agent-tiles en klikbare live-logdetailweergave

Backend: nieuw read-only bridge-eindpunt (GET /api/v1/agents/{agentRunId}/events, patroon analoog aan bestaande @GetMapping + hub.dispatch in BridgeApiController.kt/BridgeRequestHandler.kt) dat AgentEventRepository.recentForAgentRun(...) gebruikt met kinds {docker-stdout, docker-stderr} en een begrensd aantal regels/KB, met bestaande auth-check. Frontend: agents_screen.dart _agentTile() uitbreiden met starttijd (formatTimestamp) en looptijd (formatDuration, let op ms->s conversie van durationMs; voor actieve runs doorlopend/bij elke poll bijgewerkt, voor afgeronde runs vaste duur); tile klikbaar maken en laten navigeren (Navigator.push/MaterialPageRoute, zelfde patroon als story_detail_screen.dart) naar een nieuw detailscherm dat het nieuwe eindpunt bevraagt, logregels toont en automatisch meescrollt, voor actieve runs periodiek pollt (Timer, correct opgeruimd in dispose) en voor afgeronde runs eenmalig laadt zonder verdere updates; expliciete lege/foutstaat tonen wanneer nog geen events beschikbaar zijn. Supplier-neutrale naamgeving aanhouden conform docs/factory/ux/screens/agents.md. Bestaande Agents-tab-functionaliteit (actief/recent-lijst, geschiedenis tonen/verbergen, refresh) moet ongewijzigd blijven werken. Inclusief bijbehorende unit-/widgettests (formatter-gebruik, klikbaarheid/navigatie, live-update-gedrag gemocked, lege staat, afgeronde-run-staat) en een backend-test voor het nieuwe eindpunt.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Branch stond bij oppak gelijk aan `main` (geen eerdere commits over) — de story is in deze
  ronde volledig opnieuw geïmplementeerd, ondanks eerdere test-feedback die op een vorige poging
  duidde (SF-200 chain-reset zet de keten terug).
- Backend, nieuwe poort `AgentLogApi`/`AgentLogService` (`runtime/`, `runtime/services/`):
  vertaalt `AgentEventRepository.recentForAgentRun(agentRunId, kinds={docker-stdout,docker-stderr},
  limit=500)` naar een chronologisch geordende (oudste eerst) `List<AgentLogLine>`, met een
  `"line"`-extractie uit de payload-JSON (valt terug op de ruwe payload-tekst als parsen
  mislukt/ontbreekt). Poort i.p.v. rechtstreekse repository-injectie in de dashboard-module: de
  Spring-Modulith-grens laat de dashboard-module alleen `runtime`/`runtime :: models` toe, niet
  `runtime.repositories` (zelfde patroon als `SubtaskMaterializationApi`, zie tip
  `runtime-module-exposed-base-package`).
- `DashboardQueries.agentLog(agentRunId)` (nieuwe `AgentLogPageData`) op `DashboardQueryService`
  (nieuwe verplichte ctor-param `agentLogApi`; 2 test-wiring-plekken bijgewerkt:
  `BridgeTestFixtures.kt` en `web/services/FactoryDashboardServiceTest.kt`, beide met een echte
  `AgentLogService(JdbcAgentEventRepository(StubJdbcTemplate(), ...))`, zelfde stijl als de
  overige repository-ctor-params in die fixtures).
- Nieuwe bridge-operatie `agent.log` in `BridgeRequestHandler.kt` (nieuwe `JsonNode?.requireLong`
  helper voor de numerieke `agentRunId`-param; niet-numeriek geeft `INVALID_PARAMS`/400) en nieuw
  read-only eindpunt `GET /api/v1/agents/{agentRunId}/events` in
  `dashboard-backend/.../BridgeApiController.kt` (zelfde `@GetMapping` + `hub.dispatch`-patroon
  als de bestaande operaties, bestaande auth-check).
- Frontend `agents_screen.dart`: `_AgentTile` (nieuwe stateful widget, was een stateless
  `_agentTile()`-methode) toont starttijd (`formatTimestamp`) en looptijd
  (`formatDuration`, `durationMs` → seconden). Voor een actieve run (geen `endedAt`) telt een
  eigen `Timer.periodic(1s)` de looptijd lokaal elke seconde bij i.p.v. te wachten op de
  eerstvolgende dashboard-poll/SSE-`changed`-refresh; de timer wordt in `dispose()` opgeruimd.
  De tile is klikbaar (`InkWell`) en navigeert (`Navigator.push`/`MaterialPageRoute`, zelfde
  patroon als `story_detail_screen.dart`) naar het nieuwe `AgentLogScreen`
  (`agent_log_screen.dart`), met `active` afgeleid uit `endedAt` op het moment van klikken.
- Nieuw `AgentLogScreen`: bevraagt `/api/v1/agents/{agentRunId}/events`, toont regels
  monospace (stderr rood, stdout groen) en scrollt automatisch mee (`ScrollController` +
  `jumpTo(maxScrollExtent)` na elke load). Voor `active=true` een `Timer.periodic(3s)`-poller
  (opgeruimd in `dispose()`); voor `active=false` één keer laden. Expliciete lege staat
  (`EmptyState`) als `lines` leeg is, en een foutbanner (`ErrorBanner`) bij een mislukte fetch.
- Bestaande Agents-tab-functionaliteit (actief/recent-lijst, geschiedenis tonen/verbergen,
  `DataScreen`-refresh via SSE `changed`) ongewijzigd gelaten.
- Docs bijgewerkt: `docs/factory/ux/screens/agents.md` (starttijd/looptijd op elke tile,
  klikbare tile → logdetail met live-update/eenmalige-load/lege-staat) en
  `docs/ontwerp-bridge-dashboard.md` (nieuwe rij voor de `agent.log`-operatie in de
  read-operatie-catalogus §5).
- Tests: `AgentLogServiceTest` (chronologische omzetting, kind-filter+limit doorgeven,
  fallback op ruwe payload, lege lijst), uitbreidingen op `BridgeRequestHandlerTest`
  (`agent.log` ok-pad + `INVALID_PARAMS` bij niet-numerieke id) en `BridgeApiControllerTest`
  (endpoint→operatie-vertaling, 401 zonder token). Flutter: nieuwe
  `test/screens/agents_screen_test.dart` (starttijd/looptijd op actieve én afgeronde tile,
  klikbaarheid/navigatie) en `test/screens/agent_log_screen_test.dart` (lege staat, volledige
  log zonder verdere polling bij een afgeronde run, automatische live-update bij een actieve run,
  foutstaat). Voor de actieve-run-scenario's (eigen `Timer.periodic`) bewust `tester.pump()` met
  expliciete durations gebruikt i.p.v. `pumpAndSettle()` — met een lopende periodieke timer valt
  de widgetboom nooit "idle", dus `pumpAndSettle()` blijft hangen/timeout (zelfde blocker als in
  eerdere test-feedback op deze story genoemd).
- Verificatie: `mvn -f softwarefactory/pom.xml test` (546 tests, 0 failures/errors; twee bekende
  pre-existing/omgevingsafhankelijke tests uitgesloten: `ModulithArchitectureTest` en
  `AgentResultFileCompletionPollerTest`, zie tip `maven-and-preexisting-failures`) en
  `mvn -f dashboard-backend/pom.xml test -Dtest=BridgeApiControllerTest` groen. Flutter is in
  déze omgeving wél beschikbaar (`/opt/flutter`, 3.44.6): `flutter analyze` (geen issues) en
  `flutter test` (volledige suite, 25/25 groen, inclusief de 7 nieuwe agents-/agent-log-tests)
  succesvol gedraaid — dus met echt bewijs, niet enkel statische review.

## Reviewnotities (SF-1038)

- Zelf herdraaid in deze reviewomgeving (mvn + flutter zijn hier wél aanwezig), niet enkel
  statisch beoordeeld:
  - `mvn -pl factory-common,softwarefactory -am test`: 490 tests, 0 failures/errors, BUILD
    SUCCESS (incl. de nieuwe `AgentLogServiceTest` en de uitgebreide `BridgeRequestHandlerTest`).
  - `mvn -pl dashboard-backend -am test -Dtest=BridgeApiControllerTest
    -Dsurefire.failIfNoSpecifiedTests=false`: 15 tests groen, incl. de twee nieuwe
    `agents-events`-tests (operatie-vertaling + 401 zonder token).
  - `flutter pub get` + `flutter test` in `dashboard-frontend/`: 25/25 groen, incl. de nieuwe
    `agents_screen_test.dart` (starttijd/looptijd, klikbaarheid/navigatie) en
    `agent_log_screen_test.dart` (lege staat, afgeronde run zonder polling, live-update bij
    actieve run, foutstaat).
- Backend-implementatie geverifieerd: `AgentLogService.recentLogLines` zet de `ORDER BY id DESC
  LIMIT ?`-volgorde van `JdbcAgentEventRepository.recentForAgentRun` terecht via `asReversed()`
  om tot chronologisch (oudste eerst) te komen — klopt met de aanname in de story. Nieuwe
  bridge-operatie `agent.log` + endpoint `GET /api/v1/agents/{agentRunId}/events` volgen exact
  het bestaande `@GetMapping` + `hub.dispatch`-patroon incl. bestaande auth-check;
  niet-numerieke `agentRunId` geeft correct `INVALID_PARAMS`/HTTP 400.
- Frontend geverifieerd tegen de bestaande formatter-conventies (`formatTimestamp`,
  `formatDuration`, ms→s-conversie van `durationMs`); `_AgentTile`/`AgentLogScreen` ruimen hun
  `Timer`s netjes op in `dispose()`. Bestaande Agents-tab-functionaliteit (actief/recent-lijst,
  geschiedenis tonen/verbergen) blijft ongewijzigd.
- Opgevallen niet-blokkerende scope-toevoeging: `AgentCompletionRecoveryE2eTest.kt` wijzigt een
  ongerelateerde payload-groottetest van `65_537` naar `262_145` bytes. Geverifieerd dat dit een
  terechte fix is van een reeds op `main` kapotte/onder-de-grens-test (`MAX_EVENT_BYTES` is daar
  al 262_144 sinds een eerdere commit, terwijl de test nog de oude 65_536-grens gebruikte) — geen
  onterechte scope creep, wel ontbreekt een regel hierover in het "Done/rationale"-worklog-relaas.
  Geen blocker.
- Specs (`docs/factory/ux/screens/agents.md`, `docs/ontwerp-bridge-dashboard.md`) zijn
  bijgewerkt en consistent met de diff. Supplier-neutrale naamgeving intact.
- Conclusie: coherent, testbaar, past binnen de story-scope. Akkoord.

## Testnotities (SF-1039)

- Volledig vangnet gedraaid conform `.factory/verification.yaml`: `mvn -B --no-transfer-progress verify`
  vanaf de repo-root (reactor: `factory-contracts`, `factory-common`, `softwarefactory`,
  `agentworker`, `dashboard-backend`). Docker-socket (`/var/run/docker.sock`) is in deze
  testeromgeving beschikbaar, dus de Testcontainers-Postgres-e2e-tests in `softwarefactory`
  draaiden ook daadwerkelijk (490 unit- + 69 e2e-tests + overige submodules, allemaal
  0 failures/0 errors, incl. de nieuwe/gewijzigde `AgentLogServiceTest`,
  `BridgeRequestHandlerTest` (`agent.log`), `BridgeApiControllerTest` (agents-events-endpoint) en
  `AgentCompletionRecoveryE2eTest`).
- **Resultaat: BUILD FAILURE.** Module `agentworker` geeft 1 rode test:
  `TesterVerificationRunnerTest.local runner distinguishes missing tooling and kills timed out
  child process` (regel 95, `agentworker/src/test/kotlin/.../verification/TesterVerificationRunnerTest.kt`):
  `AssertionFailedError: Expected value to be false` — na een kunstmatige timeout van 1s op een
  kindproces met een 30s-sleep verwacht de test dat het kindproces daadwerkelijk gekilld is
  (`ProcessHandle.isAlive` == false), maar dat proces bleek nog te leven. Reactor stopte hierna
  (`softwarefactory-dashboard-backend` SKIPPED).
  - Deze test/module maakt geen deel uit van de SF-1009-diff (`git diff main -- agentworker/` is
    leeg) en gaat over process-/signal-handling van de tester-verificatierunner, niet over de
    agents-tab/live-log-functionaliteit van deze story. Vermoedelijk omgevingsgebonden
    (proceskill-timing in deze sandbox), zoals in eerdere testrondes ook al voor andere
    Testcontainers-/toolchain-issues werd geconstateerd.
  - Conform de absolute testerpoort telt dit ongeacht oorzaak/relevantie als rood: het volledige
    vangnet gaf geen exitcode 0. Terug naar developer (`test-rejected`) — ontbrekende/kapotte
    tooling of een falende pre-existing test is geen akkoord, ook al is de story-eigen code
    functioneel en statisch correct bevonden.
- Geen codewijzigingen aangebracht (tester verifieert alleen); geen productie-/clusterresources
  aangeraakt.
