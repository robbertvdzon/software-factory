# SF-882 - Worklog

Story-context bij eerste pickup:
Builds-info integreren bij Projects-scherm met kolomtitels en sync-status

Backend (FactoryDashboardModels.kt, FactoryDashboardService.kt, GitHubActionsClient): WorkflowRunInfo uitbreiden met headSha (en evt. runStartedAt) door deze velden uit de GitHub Actions API te parsen. ProjectOverviewItem/projectsOverview() uitbreiden met: laatste afgeronde main-build (timestamp), actieve-build-indicatoren voor main en PR (afgeleid uit status queued/in_progress + event), en een in-sync/out-of-sync-vergelijking tussen prdVersion en de laatste main-build-sha (prefix-matching, zoals bestaande deploy-sync-logica). Projecten zonder versionUrl/deploy-config tonen expliciet 'geen productieversie beschikbaar'. Geen nieuwe REST-endpoints nodig; bestaande /api/v1/projects en /api/v1/builds worden uitgebreid.

Frontend (lib/screens/builds_screen.dart, lib/screens/overview_screens.dart): header-rij met Nederlandstalige kolomtitels (Workflow / Resultaat / Branch / Event / Duur) toevoegen boven de builds-tabel, conform wireframe docs/factory/ux/wireframes2/builds.html. ProjectsScreen per project-panel uitbreiden met builds-blok: laatste main-build-timestamp, actieve-build-badges (main/PR, of 'geen actieve build'), en een visueel onderscheiden in-sync/out-of-sync-badge naast de bestaande prdVersion-weergave. Bestaande functionaliteit (force-deploy-knop, story-counters, kosten-chip, Builds-scherm filteren/doorlinken/refresh) blijft ongewijzigd werken.

Inclusief: unit tests voor de sync-bepaling en main/PR-status-afleiding in de backend-service, en widget-tests voor de nieuwe header-rij en badges in de frontend.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Backend (`FactoryDashboardModels.kt`): `WorkflowRunInfo` uitgebreid met `headSha`
  en `runStartedAt` (defaults zodat bestaande call-sites blijven compileren). Nieuwe
  `BuildSyncStatus`-enum (`IN_SYNC`/`OUT_OF_SYNC`/`UNAVAILABLE`) en
  `ProjectBuildStatus`-data class (`lastMainBuildAt`, `mainBuildActive`, `prBuildActive`,
  `syncStatus`); `ProjectOverviewItem` kreeg een verplicht `buildStatus`-veld.
- `GitHubActionsClient.toWorkflowRunInfo` parset nu ook `head_sha` en `run_started_at`
  uit de GitHub Actions API-response.
- `FactoryDashboardService.projectsOverview()` haalt per project (parallel, zelfde
  patroon als de bestaande `prdVersion`-futures) de laatste runs per workflow en de
  default branch op en berekent daarmee `buildStatus` via de nieuwe pure/testbare
  companion-functie `buildStatusFor(runs, defaultBranch, hasDeployConfig, prdVersion)`:
  main-build = `event == push` op de default branch, PR-build = `event ==
  pull_request`; actief = status `queued`/`in_progress`; sync-vergelijking gebruikt
  een nieuwe `shaPrefixMatch`-helper (zelfde prefix-tolerante recept als
  `DeploySubtaskHandler.shaPrefixMatch`, maar niet hergebruikt — geen precedent in
  deze repo om een `internal`-helper cross-module te delen voor zo'n kleine pure
  functie). Geen nieuwe REST-endpoints/bridge-ops: `/api/v1/projects` en
  `/api/v1/builds` geven de uitgebreide velden automatisch door (bridge is een pure
  JSON pass-through, zie `docs/ontwerp-bridge-dashboard.md`).
- Frontend `lib/screens/builds_screen.dart`: nieuwe `_BuildsTableHeader`-widget
  (Workflow / Resultaat / Branch / Event / Duur) boven de workflow-runs-rijen, alleen
  getoond als een repo runs heeft (niet bij de lege-staat-tekst).
- Frontend `lib/screens/overview_screens.dart`: `ProjectsScreen`-project-panel kreeg
  een `_ProjectBuildStatusRow` (laatste main-build-timestamp via `formatTimestamp`,
  actieve-build-badges of "Geen actieve build", en een `_SyncStatusBadge` met de drie
  sync-statussen als Nederlandstalige badge-teksten). Bestaande widgets (force-deploy,
  story-chips, prd-versie-tekst) ongewijzigd.
- Tests: `GitHubActionsClientTest` uitgebreid met `head_sha`/`run_started_at`-assertions;
  `FactoryDashboardServiceTest` kreeg 7 nieuwe tests voor `shaPrefixMatch` en
  `buildStatusFor` (geen deploy-config, in-sync, out-of-sync, geen bekende main-sha,
  actieve main+PR-build, geen actieve build). Frontend: `builds_screen_test.dart`
  uitgebreid met assertions voor de vijf kolomtitels; nieuwe
  `test/screens/projects_screen_test.dart` met 3 widget-tests (in-sync + actieve
  main-build, out-of-sync + geen actieve build, geen deploy-config →
  "Geen productieversie beschikbaar").
- Backend geverifieerd: `mvn -pl factory-common -am install -DskipTests` daarna
  `mvn -f softwarefactory/pom.xml test -Dtest='GitHubActionsClientTest,FactoryDashboardServiceTest'`
  groen, en de volledige suite (`-Dtest='!ModulithArchitectureTest,!AgentResultFileCompletionPollerTest'`)
  geeft 0 failures / 32 errors — alle 32 zijn pre-existing Docker/Testcontainers-e2e-
  en repo-tests die in deze devomgeving (geen Docker) altijd falen, niet gerelateerd
  aan deze wijziging (o.a. `PipelineFlowsE2eTest`, `ChainCompositionE2eTest`,
  `PostgresTrackerClientTest`).
- Frontend niet lokaal te draaien (geen `flutter`/`dart`-CLI in deze omgeving, zie
  agent-tips `build / flutter-unavailable-offline`); geverifieerd via statische
  review + de nieuwe/aangepaste widget-tests, CI draait `flutter test`.
- Specs bijgewerkt: `docs/factory/ux/screens/builds.md` (kolomtitel-rij + verwijzing
  naar het nieuwe builds-blok op Projects), nieuw `docs/factory/ux/screens/projects.md`
  (er bestond nog geen los screen-doc voor het Projects-scherm) en
  `docs/factory/ux/screen-map.md` (ontbrekende `/projects`-route toegevoegd aan de
  routetabel; overige ontbrekende routes zoals `/my-actions` zijn een pre-existing gap,
  buiten scope van deze story).

## Review (SF-890)

- Volledige story-diff (`git diff main...HEAD`) bekeken, niet alleen de laatste commit.
- Backend herbouwd en getest: `mvn -pl softwarefactory -am test -Dtest='GitHubActionsClientTest,FactoryDashboardServiceTest'`
  → groen (4 + 40 tests, 0 failures/errors). `test-compile` van de volledige module
  compileert zonder warnings die op dit werk wijzen.
- `buildStatusFor`/`shaPrefixMatch`-logica, `ProjectBuildStatus`/`BuildSyncStatus`-modellen,
  frontend-widgets (`_BuildsTableHeader`, `_ProjectBuildStatusRow`, `_SyncStatusBadge`) en
  de bijbehorende specs (`ux/screens/projects.md`, `ux/screens/builds.md`, `screen-map.md`)
  zijn onderling consistent en dekken alle acceptance criteria uit `.task.md`.
- [info] `shaPrefixMatch` is bewust gedupliceerd i.p.v. hergebruikt vanuit
  `DeploySubtaskHandler` (worklog motiveert dit expliciet: geen precedent voor
  cross-module hergebruik van een kleine `internal`-helper). Geen blocker, wel iets om
  in een volgende cross-cutting refactor-story te overwegen als er een derde
  toepassing bijkomt.
- [info] `UNAVAILABLE`-sync-badge ("Geen productieversie beschikbaar") wordt zowel
  getoond bij projecten zonder deploy-config als bij projecten mét deploy-config maar
  (nog) geen vergelijkbare main-build-sha. Dat is inhoudelijk correct en expliciet
  gedocumenteerd in `ux/screens/projects.md`, dus geen misleidende lege staat — voldoet
  aan de acceptance criteria.
- Geen regressies gevonden in force-deploy-knop, story-counters, kosten-chip of
  bestaande Builds-scherm-functionaliteit (filteren/doorlinken/refresh) — diff raakt
  die code-paden niet.
- Frontend-widget-tests kon ik hier niet lokaal draaien (geen flutter/dart-CLI in deze
  omgeving); statische review van `projects_screen_test.dart` en de uitbreiding van
  `builds_screen_test.dart` laat zien dat de nieuwe UI-elementen gedekt zijn. CI draait
  `flutter test`.
- Conclusie: akkoord, geen blockers.

## Test (SF-891)

- Story-brede diff (`git diff main...HEAD`) bekeken: backend
  (`FactoryDashboardModels.kt`, `FactoryDashboardService.kt`,
  `GitHubActionsClient.kt` + tests), frontend (`builds_screen.dart`,
  `overview_screens.dart` + widget-tests), docs (`ux/screens/builds.md`,
  nieuw `ux/screens/projects.md`, `screen-map.md`) en worklog.
- Backend gebouwd/getest: `mvn -pl factory-common -am install -DskipTests`
  daarna `mvn -pl softwarefactory -am -Dsurefire.failIfNoSpecifiedTests=false
  test -Dtest='GitHubActionsClientTest,FactoryDashboardServiceTest'` → groen
  (4 + 40 tests, 0 failures/errors).
- Volledige no-Docker suite: `mvn -pl softwarefactory -am
  -Dsurefire.failIfNoSpecifiedTests=false test
  -Dtest='!ModulithArchitectureTest,!AgentResultFileCompletionPollerTest'`
  → 465 tests, Failures 0, Errors 32. Alle 32 errors geïdentificeerd als
  pre-existing Docker/Testcontainers-omgevingsfouten (e2e-package 29:
  ChainCompositionE2eTest 2, FullRefineToDevelopE2eTest 1,
  ManualApproveGateE2eTest 2, OrchestratorGateE2eTest 3, PipelineFlowsE2eTest
  12, PipelineLoopbackE2eTest 5, SpecScenarioCoverageE2eTest 4 +
  NightlyRepositoriesTest 1 + PostgresTrackerClientTest 1 +
  FactoryDashboardRepositoryScreenshotTest 1) — matcht exact de bekende
  baseline uit eerdere stories (agent-tips `sf-853-work-cleanup-baseline`).
  Geen enkele failure, geen nieuwe/onbekende error-klasse.
- `buildStatusFor`/`shaPrefixMatch`-logica in `FactoryDashboardService.kt`
  statisch geverifieerd tegen de acceptance criteria: main-build = `event ==
  push` op default branch, PR-build = `event == pull_request`, actief =
  status `queued`/`in_progress`, sync-vergelijking prefix-tolerant tussen
  `prdVersion.commitShort` en de laatst afgeronde main-build-sha, en
  `UNAVAILABLE` zowel zonder deploy-config als zonder vergelijkbare data.
- Frontend niet lokaal draaibaar (geen flutter/dart-CLI op deze aarch64-
  tester-host, dart-sdk-binary is x86_64 — zie agent-tips
  `environment/flutter-arm64-unavailable`; CI draait ook geen `flutter test`
  voor dashboard-frontend, zie `testing/dashboard-frontend-ci-no-tests`).
  Statische review uitgevoerd:
  - `_BuildsTableHeader` (builds_screen.dart) toont de vijf Nederlandse
    kolomtitels (Workflow/Resultaat/Branch/Event/Duur) boven de runs-rijen,
    alleen wanneer een repo runs heeft — niet bij de lege-staat-tekst.
    Bijbehorende assertions in `builds_screen_test.dart` kloppen met de
    widget.
  - `_ProjectBuildStatusRow`/`_SyncStatusBadge` (overview_screens.dart)
    gebruiken bestaande helpers (`boolValue`, `text`, `formatTimestamp`,
    `StatusBadge`/`BadgeTone` uit `widgets/common.dart`/`api_client.dart`) —
    geen ontbrekende symbolen. JSON-keys (`lastMainBuildAt`,
    `mainBuildActive`, `prBuildActive`, `syncStatus`) matchen de
    Jackson-camelCase-serialisatie van `ProjectBuildStatus`.
  - Nieuwe `buildStatus`-blok is additief in `ProjectsScreen`
    (`if (project['buildStatus'] != null) ...`), na de bestaande
    story-chips/prdVersion-widgets; force-deploy-knop, story-counters en
    kosten-chip zijn ongewijzigd — geen regressierisico in die code-paden.
  - `projects_screen_test.dart` (3 nieuwe widget-tests: in-sync + actieve
    main-build, out-of-sync + geen actieve build, geen deploy-config) dekt
    de drie sync-statussen en de "Geen actieve build"-tekst, consistent met
    de widget-implementatie.
  - Docs (`ux/screens/projects.md`, bijgewerkte `ux/screens/builds.md`,
    `screen-map.md`) komen inhoudelijk overeen met de code en met de
    acceptance criteria uit `.task.md`.
- Geen bugs of afwijkingen van de story gevonden. `git status` blijft schoon
  (geen wijzigingen buiten dit worklog aangebracht tijdens testen).
- Conclusie: story voldoet aan alle acceptance criteria, geverifieerd voor
  zover mogelijk in deze omgeving (backend volledig, frontend statisch).
  Geen blockers → tested.
