# SF-875 - Worklog

Story-context bij eerste pickup:
GitHub Actions build-status: backend-client, bridge-endpoints en Builds-scherm in dashboard-frontend

Bouw de volledige verticale slice voor build-zichtbaarheid: (1) nieuwe GitHub Actions REST-client (naar het patroon van GitHubReleaseClient/GitHubCliClient, bearer-token via FactorySecrets.githubToken/SF_GITHUB_TOKEN) die per beheerd repo (projects.yaml/ProjectRepoResolver) de laatste run per workflow ophaalt (naam, conclusion, branch, event, duration, timestamp, html_url), met TTL-cache (@Volatile Pair<Long,T>, ~30-60s) naar het patroon van NightlyJobsReader; (2) FactoryDashboardService.builds() (of vergelijkbaar) naar het patroon van downloads(), met per-repo foutafvang in een errors-lijst en model in FactoryDashboardModels.kt; (3) bridge-dispatch-operatie(s) analoog aan downloads.list, en dashboard-backend BridgeApiController-endpoints GET /api/v1/repositories/{owner}/{repo}/workflows en /runs die via hub.dispatch(...) doorzetten (geen eigen GitHub-call in dashboard-backend); (4) uitbreiding van het bestaande dashboard-overzicht met een attention/waarschuwingen-sectie voor repo's waarvan de laatste run op de default branch conclusion==failure heeft (repo-naam, workflow-naam, link naar de failing run); (5) nieuw 'Builds'-scherm in dashboard-frontend (naar het patroon van downloads_screen.dart en wireframe wireframes2/builds.html): tabel Workflow/Last result/Branch/Event/Duration, projectfilter-pills, lege-staat 'No GitHub Actions workflows found...', en een nieuwe nav-entry in app_shell.dart; (6) bijbehorende unit-/widget-tests voor client, service en het nieuwe scherm. Geen auto-retry/Telegram-notificaties, geen repository-detailscherm met tabs, geen Cucumber/e2e-uitbreiding.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- **GitHubActionsClient** (`softwarefactory/.../web/services/GitHubActionsClient.kt`, nieuw):
  zelfde recept als `GitHubReleaseClient` (raw `java.net.http.HttpClient`, bearer-token via
  `FactorySecrets.githubToken`). `latestRunsPerWorkflow(slug, projectKey)` haalt
  `GET /repos/{slug}/actions/runs?per_page=30` op, groepeert per workflow-naam en houdt de
  meest recente run (`run_started_at`) over; `defaultBranch(slug)` haalt `GET /repos/{slug}`
  en cachet `default_branch` apart (langere TTL, verandert zelden). Beide met een
  in-memory TTL-cache per repo-slug (`@Volatile Map<String, Pair<Long, T>>`, 30s resp. 5min) —
  zelfde patroon als `NightlyJobsReader`, maar per-key i.p.v. één cache-waarde omdat zowel het
  geaggregeerde als het per-repo endpoint dezelfde data delen. De pure parse-stap
  (`parseLatestRunsPerWorkflow`, `internal` op de companion) is los getest zonder HTTP
  (`GitHubActionsClientTest`) — er was geen precedent in deze repo om `java.net.http.HttpClient`
  te mocken.
- **Modellen** (`FactoryDashboardModels.kt`): `WorkflowRunInfo`, `RepoBuildsView`,
  `BuildsPageData` toegevoegd (patroon `DownloadInfo`/`DownloadsPageData`); `DashboardPageData`
  kreeg een `attentionBuilds: List<WorkflowRunInfo>`-veld (default `emptyList()`, dus geen
  bestaande call-sites gebroken).
- **FactoryDashboardService**: `builds()` (patroon `downloads()`, per-repo foutafvang via de
  bestaande `load(errors, default) { ... }`-helper), `buildsFor(owner, repo)` (voor de
  per-repo endpoints; resolvet de projectKey via `ProjectRepoResolver` op basis van de
  GitHub-slug, valt terug op de slug zelf), en een private `failingDefaultBranchBuilds()` die
  `builds()` hergebruikt en filtert op `branch == defaultBranch && conclusion == "failure"` —
  aangeroepen vanuit `dashboard()` voor de attention-sectie. Nieuwe constructor-dependency
  `gitHubActionsClient: GitHubActionsClient`; bijgewerkt in beide test-fixtures
  (`FactoryDashboardServiceTest.createService`, `BridgeTestFixtures.buildFixture`).
- **Bridge**: `BridgeRequestHandler` kreeg `builds.list -> dashboardService.builds()` en
  `builds.runs -> BuildsRunsBody(dashboardService.buildsFor(owner, repo))` (analoog aan
  `downloads.list`, params via de bestaande `require`/`optional`-helpers → ontbrekende
  owner/repo geeft `INVALID_PARAMS`, geen netwerkcall).
- **dashboard-backend BridgeApiController**: `GET /api/v1/builds` (→ `builds.list`),
  `GET /api/v1/repositories/{owner}/{repo}/workflows` én `/runs` (beide → `builds.runs`, zelfde
  operatie levert al "laatste run per workflow" dus voldoet aan de AC "workflows en/of runs").
  Geen eigen GitHub-call in dashboard-backend, alleen `hub.dispatch(...)`.
- **dashboard-frontend**: nieuw `screens/builds_screen.dart` (`BuildsScreen`) — projectfilter-pills
  (`ChoiceChip`, "Alle" + per repo) boven per-repo panelen met Workflow/Last result (kleur-gecodeerde
  `StatusBadge` op `conclusion`)/Branch/Event/Duration + "Open"-link naar GitHub; lege staat op
  scherm-niveau ("No GitHub Actions workflows found for the configured repositories.") en per repo
  zonder runs (tekst uit de wireframe). Nav-entry `Builds` toegevoegd in `app_shell.dart`
  (`_secondaryEntries`, tussen Nightly en Downloads). `DashboardOverviewScreen` toont een
  "Aandacht nodig"-sectie met `data['attentionBuilds']` (alleen zichtbaar als niet leeg) — repo,
  workflow, branch en een link naar de falende run.
- **Tests**: `GitHubActionsClientTest` (pure parse-logica: laatste run per workflow, lege
  workflow-naam genegeerd, ontbrekende `workflow_runs`, ongeldige timestamp → geen crash);
  `BridgeRequestHandlerTest` uitgebreid met `builds.list` (leeg zonder geconfigureerde repo's,
  geen netwerkcall) en `builds.runs` zonder owner/repo → `INVALID_PARAMS`; Flutter
  `test/screens/builds_screen_test.dart` (repo-groepering, filter-pill, lege staten, via
  `http.testing.MockClient`) en `test/screens/dashboard_overview_screen_test.dart`
  (attention-sectie zichtbaar/verborgen).
- **Niet gedaan (bewust, per de story-aannames)**: geen repository-detailscherm met tabs (dat
  bestaat nog niet — een los Builds-scherm volstaat volgens de story-aannames), geen
  auto-retry/Telegram-melding bij een gefaalde build, geen Cucumber/e2e-uitbreiding.
- **Docs bijgewerkt**: `docs/ontwerp-bridge-dashboard.md` §5 (operatie-catalogus, `builds.list`/
  `builds.runs`); `docs/factory/ux/dashboard-v2.md` (SF-876-statusnotitie bij Screen 4
  Buildstraat: geïmplementeerd als los Builds-scherm, niet als tab); `docs/factory/ux/screen-map.md`
  (route `/builds` toegevoegd); nieuwe `docs/factory/ux/screens/builds.md` +
  vermelding in `docs/factory/ux/README.md`.
- **Build/test-verificatie**: `mvn -pl factory-common -am install -DskipTests` (nodig voor
  `factory-common`-artifact), daarna `mvn -f softwarefactory/pom.xml test-compile` en gerichte
  `mvn -f softwarefactory/pom.xml test -Dtest='GitHubActionsClientTest,BridgeRequestHandlerTest,FactoryDashboardServiceTest'`
  groen; volledige `mvn -f softwarefactory/pom.xml test -Dtest='!ModulithArchitectureTest,!AgentResultFileCompletionPollerTest'`
  draait met 32 pre-existing errors, allemaal in Docker/Testcontainers-afhankelijke e2e-/repo-tests
  (ChainCompositionE2eTest, FullRefineToDevelopE2eTest, PostgresTrackerClientTest, etc. — geen van
  deze raakt build/downloads/dashboard-code); `mvn -pl dashboard-backend -am test` volledig groen.
  Flutter/Dart-tests kunnen niet lokaal draaien (geen flutter/dart-binary in deze omgeving,
  bekende tip); nieuwe widget-tests zijn statisch nagelopen tegen bestaande patronen
  (`settings_screen_test.dart`) en laten CI ze draaien.

## Review (reviewer, 2026-07-09)

- [info] Volledige story-diff (`origin/main...HEAD`, 20 bestanden) bekeken, niet alleen de
  laatste commit.
- [info] `mvn -pl factory-common,softwarefactory,dashboard-backend -am test-compile` schoon;
  gerichte tests (`GitHubActionsClientTest`, `BridgeRequestHandlerTest`,
  `FactoryDashboardServiceTest`) en de volledige `dashboard-backend`-testsuite groen. Flutter-
  tests niet lokaal gedraaid (geen toolchain in deze omgeving, zoals ook bij de developer) —
  CI-only, code statisch nagelopen tegen bestaande patronen (`downloads_screen.dart`,
  `data_screen.dart`, `widgets/common.dart`) en consistent bevonden.
- [info] Correctheid gecontroleerd: `GitHubActionsClient` hergebruikt `FactorySecrets.githubToken`
  (geen nieuw secret-pad), cache-patroon (`@Volatile Map<String, Pair<Long,T>>` per repo-slug)
  is een verdedigbare variant op `NightlyJobsReader` omdat `builds.list`/`builds.runs` dezelfde
  onderliggende data delen. `ProjectRepoResolver.projectNames()`/`repoFor()` bestaan en worden
  correct aangeroepen. `DashboardPageData.attentionBuilds` heeft een default (`emptyList()`),
  dus geen bestaande call-sites gebroken.
  Beide dashboard-backend-routes (`/workflows` en `/runs`) wijzen bewust naar dezelfde
  `builds.runs`-operatie — expliciet toegelicht in AC ("workflows en/of runs") en in het
  worklog; geen scope creep.
- [info] Empty states (geen repo's, repo zonder workflows) en de "Aandacht nodig"-sectie
  (verborgen bij lege lijst) matchen de AC. Docs (`ontwerp-bridge-dashboard.md`,
  `ux/dashboard-v2.md`, `ux/screen-map.md`, nieuwe `ux/screens/builds.md`, `ux/README.md`)
  zijn consistent met de code-diff.
- [info] Geen scope creep: geen repository-detailscherm, geen auto-retry/Telegram, geen
  Cucumber/e2e — conform de story-aannames.
- Oordeel: akkoord, geen blockers/bugs gevonden.
