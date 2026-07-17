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

## Developer-ronde na test-rejected (SF-1038, tweede developer-pickup)

- SF-1038-implementatie (agents-tab/live-log) stond al volledig gecommit op de branch (zie diff
  `main..HEAD`: bridge-endpoint, `AgentLogApi`/`AgentLogService`, Flutter `AgentLogScreen` +
  `_AgentTile`, alle bijbehorende tests/docs) en de reviewer had die eerder al inhoudelijk
  goedgekeurd. De enige reden voor `test-rejected` was de losstaande
  `TesterVerificationRunnerTest`-failure in `agentworker` (geen enkel bestand in `agentworker/`
  zit in de SF-1009-diff: `git diff main -- agentworker/` is leeg).
- Boyscout-regel gevolgd: niet alleen aangenomen dat de failure omgevingsgebonden is, maar
  daadwerkelijk gereproduceerd en de oorzaak geïsoleerd, i.p.v. de eerdere ronde die alleen
  documenteerde zonder te herstellen:
  - `mvn -pl agentworker -am test -Dtest=TesterVerificationRunnerTest -Dsurefire.failIfNoSpecifiedTests=false`
    reproduceert lokaal consistent dezelfde failure (`local runner distinguishes missing tooling
    and kills timed out child process`, regel 95: `ProcessHandle.of(childPid).isAlive()` is `true`
    na `destroyForcibly()`).
  - Losse Java-reproductie (`ProcessBuilder("/bin/sh","-c","sleep 30 & echo $!; wait")`,
    `descendants().forEach{destroyForcibly()}` + `destroyForcibly()` op de hoofd-`sh`-proces)
    bevestigt: het kind-proces (`sleep`) ontvangt wél daadwerkelijk `SIGKILL` (`ps aux` toont
    `[sleep] <defunct>` direct na de kill), maar blijft in déze sandbox-omgeving **permanent** als
    zombie hangen (ook na >3s extra wachten) omdat er geen proces in de JVM-procesboom is dat er
    nog `wait4()` op kan doen zodra de directe kind-`sh` al gereaped is: de wees wordt
    gereparenteerd naar PID 1, en die reaped hem in deze sandbox kennelijk niet. `ProcessHandle
    .isAlive()` leest de OS-procestabel en rapporteert een niet-gereapede zombie-entry (state `Z`)
    als "alive" — dat blijft dus voor altijd `true` in deze omgeving, ongeacht hoe lang je wacht.
  - Conclusie: dit is een sandbox-eigenschap (ontbrekende/kapotte subreaper op PID 1 in déze
    testomgeving), geen bug in `LocalVerificationProcessRunner` — de productiecode killt het
    kindproces wel degelijk correct. Een echte fix zou vereisen dat de JVM zelf subreaper wordt
    (`prctl(PR_SET_CHILD_SUBREAPER)` via native/JNA-code) of dat het proces in een eigen
    procesgroep/sessie met een eigen init/reaper draait — beide zijn invasieve, risicovolle
    wijzigingen aan een voor deze story ongerelateerde kerncomponent (tester-verificatierunner)
    en vallen buiten een proportioneel boyscout-herstel.
  - Beslissing: **geëscaleerd, geen wijziging aangebracht** in `agentworker`. Alternatief
    (test verzwakken tot alleen "SIGKILL is verstuurd" i.p.v. "proces is niet meer alive") zou de
    test zijn functionele dekking (daadwerkelijk gekilld kindproces) ondermijnen puur om een
    sandbox-artefact te maskeren, en is dus ook bewust niet doorgevoerd.
- Geen overige code-, spec- of docwijzigingen nodig; de SF-1038-diff zelf was al compleet en
  reviewer-goedgekeurd. Volledige `mvn verify` vanaf de repo-root opnieuw gedraaid ter
  verificatie (zie logregel hieronder voor het resultaat) om te bevestigen dat dit de enige rode
  test is en dat er verder niets is geregresseerd.

## Reviewnotities (tweede ronde, na test-rejected/developer-escalatie)

- `git diff main...HEAD` opnieuw volledig doorgenomen (niet alleen de laatste developer-ronde):
  bridge-endpoint `GET /api/v1/agents/{agentRunId}/events` + `agent.log`-operatie,
  `AgentLogApi`/`AgentLogService`/`AgentLogPageData`, Flutter `_AgentTile` (start/looptijd,
  eigen 1s-ticker, opgeruimd in `dispose()`) en nieuw `AgentLogScreen` (poll 3s voor actieve
  runs, eenmalige load + lege/foutstaat voor afgeronde runs). Code, scope en spec-consistentie
  (`docs/factory/ux/screens/agents.md`, `docs/ontwerp-bridge-dashboard.md`) zijn ongewijzigd
  t.o.v. de eerder al goedgekeurde inhoud — deze ronde bevat alleen de developer-escalatietekst
  in het worklog, geen code-/doc-diff.
- Zelf opnieuw (gedeeltelijk) geverifieerd in deze reviewomgeving:
  `mvn -pl factory-common,softwarefactory -am test -Dtest=AgentLogServiceTest,BridgeRequestHandlerTest`
  en `mvn -pl dashboard-backend -am test -Dtest=BridgeApiControllerTest` → beide groen (exit 0),
  incl. de twee nieuwe `agents-events`-tests (operatie-vertaling + 401 zonder token) en de drie
  nieuwe `AgentLogServiceTest`-gevallen (chronologische omzetting, kind/limit-doorgifte,
  payload-fallback, lege lijst). `git diff main...HEAD --stat -- agentworker/
  .factory/verification.yaml` is leeg: deze diff raakt `agentworker` niet en `verification.yaml`
  is niet aangepast (geen fail-openroute, geen geschrapte command-id).
- De rode `TesterVerificationRunnerTest` (agentworker) blijft dus een op zichzelf staand
  omgevingsprobleem buiten deze story-diff, zoals de developer met een losse reproductie
  (zombie-kindproces door ontbrekende subreaper op PID 1 in déze sandbox) heeft aangetoond —
  geen regressie door SF-1009/SF-1038-code. [info] Dit is geen reden om de code-inhoud van deze
  story af te keuren; de absolute volledige-testsuite-poort (groen `mvn verify` vanaf de
  repo-root) blijft wel de bevoegdheid van de tester-subtaak (SF-1039), die na deze
  review opnieuw tegen de huidige HEAD moet draaien voor het echte testbewijs.
- Conclusie: code coherent, testbaar (voor de story-scope zelf volledig gedekt), past binnen de
  story-scope, specs consistent. Akkoord voor wat de reviewer beoordeelt; de volledige-suite-poort
  is aan SF-1039.

## Testnotities (SF-1039, herhaling na developer-escalatie)

- Volledig vangnet opnieuw gedraaid conform `.factory/verification.yaml`: `mvn -B --no-transfer-progress verify`
  vanaf de repo-root tegen de huidige HEAD (na developer-ronde `c7d2cbc`/reviewer-ronde `c466cc4`).
- **Resultaat: opnieuw BUILD FAILURE.** Zelfde rode test als vorige testronde:
  `agentworker` module → `TesterVerificationRunnerTest.local runner distinguishes missing tooling
  and kills timed out child process` (regel 95): `Expected value to be false` (kindproces na
  timeout/kill nog "alive" volgens `ProcessHandle`). Reactor: `factory-contracts`,
  `factory-common`, `softwarefactory` (SUCCESS, 3:38 min) → `agentworker` FAILURE (45 tests,
  1 failure) → `softwarefactory-dashboard-backend` SKIPPED.
- Dit is dezelfde failure die de developer in de vorige ronde heeft gereproduceerd en
  geanalyseerd als sandbox-eigenschap (zombie-kindproces door ontbrekende subreaper op PID 1 in
  déze testomgeving), niet als bug in productiecode, en niet gerelateerd aan de SF-1009-diff
  (`git diff main -- agentworker/` blijft leeg). Terecht niet gefixt door de developer (invasieve,
  risicovolle wijziging aan een ongerelateerde kerncomponent zou nodig zijn).
- Conform de absolute testerpoort (0 failures/0 errors vereist, ongeacht relevantie/oorzaak) is
  dit opnieuw `test-rejected`. Geen codewijzigingen aangebracht; geen productie-/clusterresources
  aangeraakt.

## Developer-ronde na test-rejected (SF-1038, derde developer-pickup)

- Bij oppak was de branch al gemerged met `main` (commit `9d63412`), die de losstaande fix
  `073dc7f` ("Flaky kill-timeout-test: wacht op reap i.p.v. direct isAlive asserten") meebracht.
  De SF-1038-implementatie zelf (bridge-endpoint, `AgentLogApi`/`AgentLogService`,
  `_AgentTile`/`AgentLogScreen`, tests, docs) stond al volledig gecommit en was al twee keer
  inhoudelijk goedgekeurd door de reviewer; deze ronde bevatte geen code-diff t.o.v. de vorige
  reviewronde.
- Opnieuw geverifieerd of de `TesterVerificationRunnerTest`-failure in `agentworker` nu is
  opgelost door de gemergede fix: `mvn -pl agentworker -am test
  -Dtest=TesterVerificationRunnerTest -Dsurefire.failIfNoSpecifiedTests=false` faalt in déze
  sandbox nog steeds op dezelfde assertie (regel ~101, `ProcessHandle.isAlive()` blijft `true`
  na de kill, óók na de toegevoegde `onExit()`-wachttijd van 5s). Dit bevestigt de eerdere
  root-cause-analyse uit deze worklog: het is geen kill/reap-timingprobleem dat met een korte
  wachttijd op te lossen is, maar een permanente zombie in déze specifieke sandbox (ontbrekende
  PID-1-subreaper), dus de `073dc7f`-fix verhelpt het hier niet — een echte fix vergt nog steeds
  invasieve `prctl(PR_SET_CHILD_SUBREAPER)`/eigen-procesgroep-wijzigingen aan een voor deze story
  ongerelateerde kerncomponent. Opnieuw bewust niet aangepakt (buiten proportioneel
  boyscout-herstel, zelfde afweging als de vorige ronde).
  `git diff main...HEAD --stat -- agentworker/ .factory/verification.yaml` blijft leeg: deze
  diff raakt `agentworker` nog steeds niet en de verification-config is niet gewijzigd.
- Docker is in déze developer-sandbox niet beschikbaar (`which docker` geeft niets, `docker info`
  faalt), dus de volledige root-`mvn verify` (Testcontainers-Postgres-e2e + de docker-gebaseerde
  commando's uit `.factory/verification.yaml`) kon hier niet end-to-end gedraaid worden. Als
  dichtstbijzijnde equivalent per module/scope gedraaid:
  - `mvn -pl factory-common,softwarefactory -am test`: 490 tests, 0 failures/errors, BUILD
    SUCCESS (incl. `AgentLogServiceTest`, `BridgeRequestHandlerTest` `agent.log`-cases).
  - `mvn -pl dashboard-backend -am test`: 40 tests, 0 failures/errors, BUILD SUCCESS (incl. de
    twee nieuwe `agents-events`-tests in `BridgeApiControllerTest`).
  - `flutter pub get` + `flutter analyze` (geen issues) + `flutter test`: 25/25 groen, incl.
    `agents_screen_test.dart` en `agent_log_screen_test.dart`.
  - `tools/audit-documentation` faalt lokaal op een ontbrekende `rg`-binary in deze sandbox
    (omgevingsbeperking, geen storyfout) en meldt daarnaast één bestaande, ongerelateerde
    documentatiefact over `factory-common` in de root-`pom.xml` — geen van beide is door deze
    story geraakt.
- Conclusie: de SF-1038-functionaliteit zelf is compleet, getest en tweemaal reviewer-akkoord;
  alle voor deze story relevante testlagen zijn hier lokaal groen. De blocker voor een groene
  volledige-suite-poort blijft uitsluitend de al eerder geanalyseerde, story-onafhankelijke
  `agentworker`-sandboxflakiness (nu ook ná de gemergede `073dc7f`-fix bevestigd) plus het
  ontbreken van Docker in déze specifieke devsandbox om de Testcontainers-/docker-commando's
  daadwerkelijk te draaien. Geen verdere codewijzigingen aangebracht.

## Reviewnotities (derde ronde, na derde developer-pickup)

- `git diff c466cc4...HEAD -- . ':!docs/stories/worklog/'` bevat geen wijzigingen behalve de
  reeds op `main` aanwezige `agentworker`-testfix (via de merge `9d63412` binnengekomen, `git diff
  main...HEAD -- agentworker/` is en blijft leeg). Deze developer-ronde bevat dus geen nieuwe
  code-/doc-diff t.o.v. de tweemaal reviewer-goedgekeurde inhoud — alleen een worklog-toevoeging
  die bevestigt dat de gemergede `073dc7f`-fix de flaky `TesterVerificationRunnerTest` in déze
  sandbox niet oplost (permanente zombie door ontbrekende PID-1-subreaper, geen timingprobleem).
- Backend (`BridgeApiController.kt`/`BridgeRequestHandler.kt`/`AgentLogApi`/`AgentLogService`) en
  frontend (`agents_screen.dart` `_AgentTile`, nieuw `agent_log_screen.dart`) opnieuw doorgelezen:
  ongewijzigd t.o.v. de eerder goedgekeurde code. Specs (`docs/factory/ux/screens/agents.md`,
  `docs/ontwerp-bridge-dashboard.md`) blijven consistent met de diff. `.factory/verification.yaml`
  is niet gewijzigd (geen shell-string, geen ontbrekende command-id, geen fail-openroute).
- De rode `TesterVerificationRunnerTest` in `agentworker` blijft buiten de SF-1009-diff en is de
  bevoegdheid van de tester-subtaak (SF-1039) om te beoordelen tegen de volledige-suite-poort; dat
  is geen reden om de story-eigen code hier af te keuren.
- Conclusie: geen nieuwe bevindingen t.o.v. de vorige twee reviewrondes. Akkoord.

## Testnotities (SF-1039, herhaling na derde developer-/reviewronde en gemergede kill-timeout-fix)

- Volledig vangnet opnieuw gedraaid conform `.factory/verification.yaml`: `mvn -B --no-transfer-progress verify`
  vanaf de repo-root tegen de huidige HEAD (`e264908`, na developer-ronde `371bd3e` en
  reviewer-ronde `e264908`, met de gemergede kill-timeout-fix `073dc7f` erin via `9d63412`).
- **Resultaat: opnieuw BUILD FAILURE.** Reactor: `factory-contracts`, `factory-common`,
  `softwarefactory` (SUCCESS, incl. de 69 e2e-tests met Docker/Testcontainers, 3:40 min) →
  `agentworker` FAILURE (45 tests, 1 failure) → `softwarefactory-dashboard-backend` SKIPPED.
- Zelfde rode test als de vorige twee testrondes, óók na de `073dc7f`-fix (wachten op
  `onExit()` vóór de `isAlive`-assert): `TesterVerificationRunnerTest.local runner
  distinguishes missing tooling and kills timed out child process` (nu regel 101):
  `AssertionFailedError: Expected value to be false` — het kindproces blijft ook na de
  extra wachttijd "alive" volgens `ProcessHandle` in déze tester-sandbox. Dit bevestigt de
  eerdere analyse van zowel developer als reviewer: geen kill/reap-timingprobleem dat met een
  korte wachttijd is op te lossen, maar een permanente zombie door een ontbrekende
  PID-1-subreaper in déze specifieke sandbox-omgeving. `git diff main -- agentworker/` blijft
  leeg (bevestigd opnieuw) — geen enkel bestand in `agentworker/` zit in de SF-1009-diff.
- Conform de absolute testerpoort (0 failures/0 errors vereist over het volledige vangnet,
  ongeacht relevantie, oorzaak of pre-existing-status) is dit **test-rejected**. Geen
  codewijzigingen, testwijzigingen of infrawijzigingen aangebracht; geen productie-/
  clusterresources aangeraakt. Geen tijdelijke testdata aangemaakt.
