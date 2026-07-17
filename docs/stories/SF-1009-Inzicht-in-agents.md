# SF-1009 - Inzicht in agents

## Story

Inzicht in agents

<!-- refined-by-factory -->

## Scope

De Agents-tab in de dashboard-frontend (`agents_screen.dart`) toont per agent-run alleen story/rol/status. Deze story voegt toe:

1. **Starttijd en looptijd tonen** — per agent-tile (actief én recent) de starttijd en de duur die de agent al draait (voor actieve runs: lopend vanaf `startedAt` tot nu; voor afgeronde runs: vaste duur tot `endedAt`).
2. **Klikbare agent-tile met live output** — een agent-tile is klikbaar en opent een detailweergave die de live output/log van die agent-run toont, bijwerkend terwijl de agent nog draait; voor afgeronde runs toont dezelfde weergave de volledige (niet meer bijwerkende) log.

Backend: de benodigde `startedAt`/`endedAt`/`durationMs`-velden zijn al aanwezig in `UiAgentRun` (`FactoryDashboardModels.kt`) en bereiken de frontend al via `GET /api/v1/agents` — punt 1 is een frontend-only wijziging die bestaande formatters (`formatTimestamp`, `formatDuration` in `api_client.dart`) hergebruikt.

Voor punt 2 bestaat nog geen voorziening om de al gelogde `agent_events` (kinds `docker-stdout`/`docker-stderr`, gevuld door `DockerLogFollower`) als logfeed richting de browser te ontsluiten. Dit vereist een nieuw backend-eindpunt (bijv. polling- of SSE-gebaseerd, aansluitend op het bestaande `GET /api/v1/events`-SSE-patroon in `BridgeApiController`) dat de events van een specifieke `agent_run_id` doorstuurt naar de frontend, plus een nieuw Flutter-detailscherm dat deze output live rendert en automatisch meescrollt.

Buiten scope: het "Interactive sessions"-onderdeel uit de UX-doc (`docs/factory/ux/screens/agents.md`) — dat is spec-only en nergens geïmplementeerd; deze story bouwt dat niet. Ook stuck/timeout-visuele states en session-caps blijven buiten scope.

## Acceptance criteria

- Op de Agents-tab toont elke agent-tile (actief en recent) de starttijd (leesbaar, lokale tijdnotatie) en de looptijd:
  - Voor actieve runs: doorlopend bijgewerkte duur sinds start (of minstens bij elke refresh/poll van het scherm).
  - Voor afgeronde runs: de vaste totale duur (`endedAt - startedAt`, gebaseerd op bestaande `durationMs`).
- Elke agent-tile is klikbaar en opent een detailweergave voor die specifieke agent-run.
- In de detailweergave van een nog actieve agent-run is de output-log zichtbaar en werkt deze automatisch bij zolang de agent draait (nieuwe regels verschijnen zonder handmatige herlaad-actie van de gebruiker).
- In de detailweergave van een afgeronde agent-run is de volledige historische output-log zichtbaar (geen live-updates meer nodig).
- Er is een expliciete lege/foutstaat wanneer er (nog) geen log-events beschikbaar zijn voor een run.
- Bestaande functionaliteit van de Agents-tab (actief/recent-lijst, geschiedenis tonen/verbergen, refresh) blijft ongewijzigd werken.
- Naamgeving blijft supplier-neutraal (niet "Claude" als paginanaam of labels), conform `docs/factory/ux/screens/agents.md`.

## Aannames

- "Live output" betekent de al gecapturede `docker-stdout`/`docker-stderr`-regels uit de `agent_events`-tabel, niet een nieuwe vorm van log-capture; deze story voegt alleen ontsluiting (backend-eindpunt + frontend-weergave) toe, geen nieuwe logbron.
- Bijwerken van de live output mag via polling (analoog aan bestaande refresh-patronen in de frontend) of via de bestaande SSE-infrastructuur (`/api/v1/events`); de exacte transportkeuze is een implementatiedetail voor de developer, geen functionele eis.
- "Hoe lang hij al draait" wordt afgeleid uit het reeds beschikbare `startedAt`/`endedAt`/`durationMs`; er is geen aparte backend-wijziging nodig voor dit deel van de story.
- Het "Interactive sessions"-gedeelte uit de UX-wireframe/spec valt buiten deze story en wordt niet meegenomen.
- Zeer lange logs mogen begrensd/afgekapt worden getoond (bijv. laatste N regels of laatste N KB) zolang dit duidelijk is voor de gebruiker; volledige ongelimiteerde historie is geen harde eis.

<!-- test-feedback:start -->
## Test-feedback
Het volledige vangnet (`mvn verify` conform `.factory/verification.yaml`) faalt: `agentworker` module, `TesterVerificationRunnerTest.local runner distinguishes missing tooling and kills timed out child process` (regel 101) — ook na de gemergede `073dc7f`-fix blijft `ProcessHandle.isAlive()` `true` in deze sandbox. Reactor: `factory-contracts`/`factory-common`/`softwarefactory` SUCCESS (incl. 69 e2e-tests), `agentworker` FAILURE (1/45 failures), `dashboard-backend` SKIPPED.

Dit is een bekende sandbox-eigenschap (ontbrekende PID-1-subreaper), niet gerelateerd aan de SF-1009-diff (`agentworker/` zit niet in `git diff main -- agentworker/`). Volgens de absolute testerpoort is elke rode test — ongeacht relevantie of oorzaak — een reden voor `test-rejected`. Ik heb geen code gewijzigd, alleen het worklog bijgewerkt.

{"agent_tips_update":[{"category":"testing","key":"agentworker-kill-timeout-fix-073dc7f-insufficient-in-sandbox","content":"De fix 073dc7f ('wacht op onExit() i.p.v. direct isAlive asserten', 5s timeout) lost de TesterVerificationRunnerTest-flakiness in deze testersandbox NIET op. Ook na deze fix faalt 'local runner distinguishes missing tooling and kills timed out child process' consistent op dezelfde assertie (nu regel 101): het kindproces blijft permanent als zombie 'alive' volgens ProcessHandle, ook na 5s extra wachten. Root cause blijft een ontbrekende PID-1-subreaper in deze specifieke sandbox (bevestigd door developer via losse reproductie), geen kill/reap-timingprobleem dat met een korte wachttijd op te lossen is. Een echte fix vergt prctl(PR_SET_CHILD_SUBREAPER) of een eigen procesgroep/init — invasief, buiten scope van een enkele story. Blijft desondanks een absolute test-rejected-trigger."}]}

{"phase":"test-rejected"}
<!-- test-feedback:end -->

## Eindsamenvatting

Nu heb ik voldoende context. Ik schrijf de eindsamenvatting.

**Eindsamenvatting SF-1009 — Inzicht in agents (starttijd/looptijd + klikbare live-logdetailweergave)**

**Wat is gebouwd**
- Op de Agents-tab tonen agent-tiles nu de starttijd en de looptijd, zowel voor actieve als afgeronde runs. Voor actieve runs telt de looptijd lokaal elke seconde door (eigen timer), voor afgeronde runs staat de vaste duur (gebaseerd op bestaande `durationMs`).
- Elke agent-tile is klikbaar en opent een nieuw detailscherm (`AgentLogScreen`) met de output-log van die specifieke run: stdout/stderr-regels, monospace, met kleurcodering. Voor actieve runs wordt elke 3 seconden automatisch bijgewerkt (auto-scroll); voor afgeronde runs wordt de volledige log eenmalig geladen.
- Expliciete lege staat (geen log-events) en foutstaat (mislukte fetch) zijn aanwezig.
- Backend: nieuw read-only eindpunt `GET /api/v1/agents/{agentRunId}/events`, met een nieuwe bridge-operatie `agent.log` volgens hetzelfde bestaande patroon (auth, dispatch) als de overige endpoints. Een nieuwe poort `AgentLogApi`/`AgentLogService` zet de al gelogde `docker-stdout`/`docker-stderr`-events chronologisch om, met fallback op de ruwe payloadtekst als parsen mislukt.
- Bestaande Agents-tab-functionaliteit (actief/recent-lijst, geschiedenis tonen/verbergen, refresh) is ongewijzigd gebleven.

**Gemaakte keuzes**
- Live-updates via polling (3s) i.p.v. SSE, als eenvoudigste implementatie die aansluit bij bestaande frontend-patronen.
- Nieuwe poort-laag i.p.v. rechtstreekse repository-toegang vanuit de dashboard-module, conform de bestaande Spring-Modulith-grens.
- Supplier-neutrale naamgeving aangehouden conform de UX-spec; "Interactive sessions" bleef bewust buiten scope.
- Specs (`docs/factory/ux/screens/agents.md`, `docs/ontwerp-bridge-dashboard.md`) zijn bijgewerkt.

**Getest**
- Backend: nieuwe/uitgebreide unit- en controllertests (`AgentLogServiceTest`, `BridgeRequestHandlerTest`, `BridgeApiControllerTest`) — allemaal groen.
- Flutter: nieuwe widgettests voor starttijd/looptijd, klikbaarheid/navigatie, live-update-gedrag, lege staat en foutstaat — 25/25 groen, inclusief `flutter analyze` zonder issues.
- Code is drie keer inhoudelijk door de reviewer goedgekeurd, zonder scope- of consistentie-issues.

**Bewust niet gedaan / bekende beperking**
- Het volledige testvangnet (`mvn verify`) bleef vijf testrondes op rij falen op één test in de ongerelateerde `agentworker`-module (`TesterVerificationRunnerTest`), veroorzaakt door een zombie-kindproces door een ontbrekende PID-1-subreaper in de sandbox-omgeving — geen regressie van deze story (`agentworker/` zit niet in de diff). De developer heeft dit grondig gereproduceerd en geanalyseerd; een echte fix zou invasieve wijzigingen aan een ongerelateerde kerncomponent vereisen en is bewust niet doorgevoerd binnen deze story.
- "Interactive sessions" uit de UX-wireframe en stuck/timeout-visuele states blijven buiten scope, zoals vooraf afgesproken.

```json
```
