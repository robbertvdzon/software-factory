# SF-1009 - Worklog

Story-context bij eerste pickup:
Starttijd/looptijd tonen en klikbare live-logweergave voor agent-runs

Voeg op de Agents-tab (dashboard-frontend/lib/screens/agents_screen.dart, _agentTile) starttijd en looptijd toe per agent-tile, met hergebruik van formatTimestamp/formatDuration (api_client.dart): voor actieve runs een doorlopend bijgewerkte duur (minstens per refresh/poll), voor afgeronde runs de vaste durationMs. Maak elke agent-tile klikbaar en bouw een nieuw detailscherm dat de output-log van die specifieke agent-run toont. Backend: voeg een nieuwe bridge-operatie 'agent.log' toe (softwarefactory: BridgeRequestHandler.dispatch, DashboardApi/DashboardQueryService) die AgentEventRepository.recentForAgentRun(agentRunId, kinds=docker-stdout/docker-stderr) gebruikt en de resultaten begrensd (laatste N regels/KB) teruggeeft; ontsluit dit in dashboard-backend als nieuw GET /api/v1/agents/{agentRunId}/log endpoint in BridgeApiController.kt, zelfde auth-patroon als bestaande endpoints. Frontend: het nieuwe logscherm pollt dit endpoint periodiek (analoog aan bestaand refresh-patroon), stopt met pollen zodra de run een outcome heeft, scrollt automatisch mee bij nieuwe regels, en toont een expliciete lege/foutstaat als er nog geen events zijn. Houd naamgeving supplier-neutraal. Bestaande Agents-tab-functionaliteit (actief/recent-lijst, geschiedenis tonen/verbergen, refresh) blijft ongewijzigd. Schrijf bijbehorende unit tests voor de nieuwe bridge-operatie/endpoint en het frontend-tile/logscherm-gedrag als onderdeel van dit ontwikkelwerk.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## SF-1010 — implementatie

### Backend (softwarefactory + dashboard-backend)

- `runtime/AgentLogApi.kt` (nieuw): geëxposeerde runtime-poort `recentLines(agentRunId, limit)`.
  De `dashboard`-module mag `runtime.repositories.AgentEventRepository` niet direct injecteren
  (Spring-Modulith-grens: alleen `runtime`/`runtime :: models` staan toe in
  `dashboard/package-info.java`), dus is dit een nieuwe poort in het geëxposeerde
  `runtime`-basepakket, zelfde patroon als `SubtaskMaterializationApi`.
- `runtime/services/AgentLogService.kt` (nieuw): implementatie — gebruikt
  `AgentEventRepository.recentForAgentRun(agentRunId, kinds={docker-stdout,docker-stderr}, limit)`,
  keert de DESC-volgorde om naar chronologisch en leest het `line`-veld uit de JSON-payload.
- `runtime/models/RuntimeModels.kt`: `AgentLogLine(id, kind, line)` toegevoegd (in `runtime.models`,
  al toegestaan als dashboard-dependency).
- `dashboard/repositories/FactoryDashboardRepository.kt`: `agentRunById(agentRunId)` toegevoegd
  (voor `outcome`/`endedAt`, om te bepalen of de frontend moet blijven pollen).
- `dashboard/models/FactoryDashboardModels.kt`: `AgentLogPageData(agentRunId, lines, outcome,
  ended, errors)`.
- `dashboard/DashboardApi.kt`: `DashboardQueries.agentLog(agentRunId): AgentLogPageData`.
- `dashboard/services/DashboardQueryService.kt`: constructor kreeg `agentLogApi: AgentLogApi`
  (verplicht, geen default — zelfde norm als de overige Spring-beans hier); `agentLog(agentRunId)`
  begrenst op de laatste 500 regels (`AGENT_LOG_LINE_LIMIT`) en gebruikt de bestaande `load()`-
  soft-fail-helper (nooit een crashende bridge-call).
- `bridge/services/BridgeRequestHandler.kt`: nieuwe operatie `agent.log` ->
  `dashboardService.agentLog(params.requireLong("agentRunId"))`; nieuwe `requireLong`-helper
  (zelfde stijl als `require`/`requireBool`).
- `dashboard-backend/.../BridgeApiController.kt`: nieuw `GET /api/v1/agents/{agentRunId}/log`,
  zelfde auth-patroon (`authService.requireAuthorization`) als de overige endpoints.
- Test-wiring bijgewerkt: `BridgeTestFixtures.kt` (nieuwe `FakeAgentLogApi`, standaard leeg) en
  `FactoryDashboardServiceTest.kt` (inline `AgentLogApi`-fake) — beide construeren
  `DashboardQueryService` en moesten de nieuwe constructor-parameter meegeven.
- `docs/ontwerp-bridge-dashboard.md` §5 (operatie-catalogus) en
  `docs/factory/ux/screens/agents.md` bijgewerkt met het nieuwe gedrag (zie git-diff).

### Frontend (dashboard-frontend)

- `screens/agents_screen.dart`: `_agentTile` toont nu starttijd + looptijd (hergebruik
  `formatTimestamp`/`formatDuration`; looptijd is `durationMs` voor afgeronde runs, anders
  `now - startedAt`, elke seconde ververst via een lokale `Timer.periodic` zonder refetch) en is
  klikbaar (`InkWell` -> `Navigator.push` naar `AgentLogScreen`). Bestaande
  actief/recent-lijst/geschiedenis-toggle/refresh-gedrag ongewijzigd.
- `screens/agent_log_screen.dart` (nieuw): pollt `GET /api/v1/agents/{agentRunId}/log` elke 3s,
  stopt zodra de respons `ended=true` heeft, scrollt automatisch mee, toont een expliciete lege
  staat ("Nog geen log-events...") en een foutbanner bij een falend endpoint.
  `overview_screens.dart` exporteert het nieuwe scherm.

### Tests

- Backend: `AgentLogServiceTest` (JSON-payload-parsing, DESC->chronologisch, lege payload),
  3 nieuwe cases in `BridgeRequestHandlerTest` (happy path, ontbrekende/ongeldige `agentRunId`),
  1 nieuwe case in `BridgeApiControllerTest` (routing + params), en
  `FactoryDashboardRepositoryAgentRunTest` (Testcontainers-Postgres, zelfde patroon als
  `FactoryDashboardRepositoryScreenshotTest`) voor `agentRunById`.
- Frontend: `agents_screen_test.dart` (starttijd/looptijd-tekst, tik opent `AgentLogScreen`) en
  `agent_log_screen_test.dart` (lege staat, regels + stopt met pollen bij `ended=true`,
  foutmelding bij HTTP-fout).

### Verificatie

- `mvn -pl factory-common,factory-contracts -am install -DskipTests` (eenmalig, lege lokale
  `~/.m2`), daarna `mvn -f softwarefactory/pom.xml test-compile` groen.
- `mvn -f softwarefactory/pom.xml test -Dtest='BridgeRequestHandlerTest,FactoryDashboardServiceTest,AgentLogServiceTest'`
  groen (0 failures/errors).
- `dashboard-backend`: `mvn test -Dtest='BridgeApiControllerTest'` groen.
- `mvn verify` vanaf de repo-root **niet volledig lokaal gedraaid**: deze omgeving heeft geen
  Docker (`docker: command not found`), dus de Testcontainers-Postgres-tests
  (`FactoryDashboardRepositoryAgentRunTest` incluis) en de e2e-suite kunnen hier niet draaien —
  zelfde bekende beperking als eerdere stories (zie agent-tips `maven-unavailable-offline`/
  `repo-test-needs-testcontainers-postgres`). CI draait de volledige `mvn verify` met Docker.
- Flutter-toolchain is in deze omgeving niet beschikbaar (`flutter`/`dart` niet geïnstalleerd);
  de nieuwe/aangepaste Dart-tests zijn geschreven volgens de bestaande `MockClient`-conventie
  (zie `settings_screen_test.dart`) maar niet lokaal gedraaid — CI draait `flutter test`.

### Niet gedaan / bewust buiten scope

- "Interactive sessions" (spec-only in `docs/factory/ux/screens/agents.md`) blijft ongebouwd,
  conform de story-scope.
- Geen SSE-gebaseerde live-log (polling gekozen, zoals de aannames toestaan); geen wijziging aan
  het bestaande `/api/v1/events`-kanaal.

## Review SF-1010 (reviewer)

Code-review van de volledige story-diff (`git diff main...HEAD`, 24 bestanden) t.o.v. de
refined story/acceptance-criteria.

**Functioneel/spec-consistentie:** in orde.
- `agents_screen.dart`: starttijd/looptijd correct via `formatTimestamp`/`formatDuration`
  (durationMs->seconden-conversie klopt), tile klikbaar, bestaande actief/recent/geschiedenis/
  refresh-gedrag ongewijzigd. `formatDuration` verwacht seconden — aanroep converteert
  `durationMs` correct (`/1000`).
- `agent_log_screen.dart`: polling (3s), stopt bij `ended=true`, auto-scroll, expliciete lege
  staat en foutbanner aanwezig — voldoet aan de acceptatiecriteria.
- Backend: `AgentLogApi`/`AgentLogService`-scheiding correct volgens de Modulith-grens (zelfde
  patroon als `SubtaskMaterializationApi`); `recentForAgentRun` haalt laatste N op met
  `ORDER BY id DESC LIMIT`, service keert daarna om naar chronologisch — correct (laatste N
  regels, oud->nieuw). `agent.log`-bridge-operatie, `requireLong`-helper, endpoint, soft-fail via
  `load()` — allemaal consistent met bestaande patronen.
- Docs (`docs/ontwerp-bridge-dashboard.md` §5, `docs/factory/ux/screens/agents.md`) bijgewerkt en
  consistent met de code.
- Testdekking qua scenario's (happy path, ontbrekende/ongeldige param, lege staat, stop-bij-ended,
  HTTP-fout) is passend en dekt de acceptatiecriteria.

**Blocker — testbewijs:**
- `FactoryDashboardRepositoryAgentRunTest` (nieuw in deze story) faalt in deze exacte checkout:
  `mvn test` levert `Tests run: 1, Errors: 1` — `IllegalStateException: Could not find a valid
  Docker environment` (zie `softwarefactory/target/surefire-reports/
  ...FactoryDashboardRepositoryAgentRunTest.txt`). Dit is geen pre-existing/onaangeraakt
  testgeval maar een door deze story toegevoegde test die nu rood staat.
- Er is geen volledige `mvn verify` (repository-maven-verify uit `.factory/verification.yaml`)
  gedraaid voor deze story; het worklog bevestigt dit expliciet ("mvn verify... niet volledig
  lokaal gedraaid"). Alleen losse `-Dtest=...`-subsets zijn gedraaid.
- `flutter analyze`/`flutter test`/`flutter pub get` zijn helemaal niet gedraaid (toolchain
  ontbreekt volgens worklog); de nieuwe/aangepaste Dart-widgettests
  (`agents_screen_test.dart`, `agent_log_screen_test.dart`) zijn dus ongeverifieerd proza.
- Er is geen agentworker-gemeten testbewijs voor exact deze HEAD/worktree-tree beschikbaar.
  Volgens de reviewer-regels is rood/ontbrekend volledig testbewijs een blocker, en telt een
  omgevingsbeperking (geen Docker/Flutter) niet als vrijstelling.

Verdict: review-rejected — code/scope/spec zijn in orde, maar volledig en groen testbewijs
(inclusief de nieuwe Testcontainers-test en de Flutter-testsuite) ontbreekt/staat rood en moet
alsnog geleverd worden voordat deze subtaak verder kan.
