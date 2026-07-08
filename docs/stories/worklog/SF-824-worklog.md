# SF-824 / SF-825 - Worklog

## Story in eigen woorden

Fase F van de Flutter-migratie: het oude Kotlin HTML-dashboard volledig verwijderen. De Flutter-frontend is de enige UI geworden; de Kotlin-views, -controllers en -auth zijn dode code. Doel: codebase opruimen, compilatiebrekers oplossen, docs bijwerken.

## Checklist

[x]: Compilatiebreaker oplossen: StoryStatusPresenter + StatusBucket naar web/services/ verplaatsen
[x]: FactoryDashboardService importreferenties naar views verwijderen
[x]: web/views/ volledig verwijderd
[x]: web/config/DashboardAuthConfig.kt verwijderd
[x]: web/controllers/FactoryDashboardController.kt verwijderd
[x]: web/controllers/SafeRedirect.kt verwijderd
[x]: web/services/FactoryDashboardAuth.kt verwijderd
[x]: Bijbehorende tests verwijderd (DashboardAuthInterceptorTest, FactoryDashboardViewsTest, FactoryDashboardAuthTest, SafeRedirectTest)
[x]: FactoryUiDriver herschreven voor directe FakeYouTrackState-calls
[x]: FactoryUiDriverLoginTest verwijderd (testte verwijderde dashboard-login)
[x]: E2eTestBase.loginUi() bijgewerkt
[x]: ManualApproveGateE2eTest bijgewerkt
[x]: E2eTestConfig opgeschoond (TestRestTemplate-bean + dashboard-auth-config values)
[x]: Statische resources (static/stories.js, sf-ui.css, etc.) verwijderd
[x]: docs/technical/modules.md bijgewerkt
[x]: docs/technical/endpoints.md bijgewerkt
[x]: docs/onboarding-senior-developer.md bijgewerkt
[x]: runbook.md bijgewerkt
[x]: mvn test-compile: groen
[x]: Kritieke unit-tests groen: FactoryDashboardServiceTest, BridgeRequestHandlerTest, FactoryApiControllerTest

## Wat er gedaan is en waarom

### 1. Compilatiebreaker: StoryStatusPresenter + StatusBucket

`FactoryDashboardService` importeerde `StoryStatusPresenter` uit `web.views.shared` en
`StatusBucket` uit `FactoryDashboardViews`. Vóór het verwijderen van `web/views/` zijn:
- Een nieuw `web/services/StoryStatusPresenter.kt` gemaakt met `StatusBucket` enum + alle logica
- De imports in `FactoryDashboardService` bijgewerkt (views-imports verwijderd, StatusBucket nu lokaal)

### 2. Verwijderde bestanden

Alle bestanden uit de scope verwijderd. Statische resources (`static/stories.js`, `sf-ui.css`,
`new-story.js`, `dashboard.js`) ook verwijderd — ze zijn uitsluitend gebruikt door het
HTML-dashboard dat we verwijderen. Dit voorkomt dode bestanden in de repo.

### 3. e2e-tests bijgewerkt

De `FactoryUiDriver` maakte HTTP-calls naar `FactoryDashboardController`-endpoints. Na verwijdering
van de controller zijn de endpoints weg. Oplossing: `FactoryUiDriver` herschreven om direct
`FakeYouTrackState` te schrijven (fase-overgangs via `state.setEnumField`). Dit is semantisch
equivalent: de orchestrator pikt de gewijzigde velden op via zijn normale poll-cyclus.

`FactoryUiDriverLoginTest` verwijderd — dat testte uitsluitend de dashboard-auth-flow die niet meer bestaat.

### 4. Docs bijgewerkt

- `docs/technical/modules.md` — `softwarefactory: web` sectie herschreven
- `docs/technical/endpoints.md` — dashboard-endpoints tabel verwijderd, telling bijgewerkt (39→6),
  auth-sectie over `FactoryDashboardAuth`/`HandlerInterceptor` verwijderd
- `docs/onboarding-senior-developer.md` — dashboard-kookboekje verwijderd, `HumanActionPolicy`-tekst
  bijgewerkt, twee-dashboards-sectie herschreven, escaping-reviewregel verwijderd
- `runbook.md` — verwijzingen naar Kotlin-dashboard verwijderd

### 5. Testresultaten

- `mvn -f softwarefactory/pom.xml test-compile`: **groen**
- `FactoryDashboardServiceTest`, `BridgeRequestHandlerTest`, `FactoryApiControllerTest`: **groen**
- Enige failures zijn `NightlyRepositoriesTest` en `FactoryDashboardRepositoryScreenshotTest` —
  beide vereisen Docker (Testcontainers) dat in de dev-omgeving niet beschikbaar is; pre-existerende failures.

### 6. Keuze: statische resources verwijderd

De statische resources (`static/`) waren uitsluitend door het HTML-dashboard gebruikt. Ze zijn
verwijderd als onderdeel van de opruimactie. Geen functionele impact — de Flutter-frontend heeft
eigen assets.

## Tester-notities (SF-826)

### Testrun: 2026-07-08

**AC1 — mvn test**: `mvn -pl softwarefactory -am test`: 426 tests, Failures: 0, Errors: 1
(PostgresTrackerClientTest — pre-existing Docker/Testcontainers, geen regressie).
`ModulithArchitectureTest`: 1 test, groen (geen nieuwe module-cycle).
`mvn -pl dashboard-backend -am test`: 37 tests, Failures: 0, Errors: 0.

**AC2 — Verwijderde bestanden**: Alle 5 items bevestigd afwezig (web/views/, DashboardAuthConfig.kt,
FactoryDashboardController.kt, SafeRedirect.kt, FactoryDashboardAuth.kt). Static-directory leeg.

**AC3 — Behouden bestanden**: Alle 6 items bevestigd aanwezig.

**AC4 — Spring context**: ModulithArchitectureTest slaagt (Spring-context laadt probleemloos).

**AC5 — Docs**: Verwijzingen naar verwijderde items zijn uitsluitend in historische zin ("is
verwijderd in SF-825" / "voormalige HTML-dashboard") — geen actieve instructies of routes meer.

**AC6 — Statische resources**: Verwijderd (dashboard.js, new-story.js, sf-ui.css, stories.js).
`src/main/resources/static/` is leeg.

**Conclusie**: Alle acceptance criteria voldaan. Geen regressies gevonden.
