# SF-1380 - Worklog

Story-context bij eerste pickup:
Verwijder 3 ongebruikte constructor-dependencies uit DashboardQueryService

Verwijder de constructor-parameters orchestratorApi (OrchestratorApi), workspaceLauncher (WorkspaceDesktopLauncher) en subtaskPlanMaterializer (SubtaskMaterializationApi) uit DashboardQueryService.kt, inclusief de bijbehorende SF-787- en 'zelfde reden'-comments en de nu ongebruikte imports. Pas alle call-sites aan die DashboardQueryService(...) met named args bouwen: BridgeTestFixtures.kt (buildFixture, r122-146) en FactoryDashboardServiceTest.kt (createQueries ~r1006-1022 en createService ~r1051-1069) - schrap in elk de 3 named args orchestratorApi=, workspaceLauncher= en subtaskPlanMaterializer=. Verwijder per bestand alleen de lokale val's/imports die daardoor nergens anders meer gebruikt worden; behoud val's/imports die nog nodig zijn voor andere constructies. Controleer met een repo-brede grep naar 'DashboardQueryService(' dat geen call-site nog een van de 3 named args gebruikt.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `DashboardQueryService.kt`: constructor-parameters `orchestratorApi` (OrchestratorApi),
  `workspaceLauncher` (WorkspaceDesktopLauncher) en `subtaskPlanMaterializer`
  (SubtaskMaterializationApi) verwijderd, incl. de bijbehorende SF-787- en
  'zelfde reden'-comments en de nu ongebruikte imports (`OrchestratorApi`,
  `WorkspaceDesktopLauncher`, `SubtaskMaterializationApi`). Puur mechanische
  opschoning van dode code, geen gedragswijziging.
- `BridgeTestFixtures.kt` (`buildFixture`): de 3 named args (`orchestratorApi`,
  `workspaceLauncher`, `subtaskPlanMaterializer`) verwijderd uit de
  `DashboardQueryService(...)`-aanroep. De lokale `val orchestrator` en
  `val workspaceLauncher` blijven bestaan (nog nodig voor
  `FactoryOperationsService`/`DashboardCommandService`); geen ongebruikte
  imports/vals overgebleven.
- `FactoryDashboardServiceTest.kt` (`createQueries` en `createService`): idem, de 3
  named args verwijderd uit beide `DashboardQueryService(...)`-aanroepen. In
  `createQueries` waren `val workspaceLauncher` en `val materializer` daardoor
  nergens anders meer nodig en zijn verwijderd. In `createService` blijft
  `val workspaceLauncher` bestaan (nog gebruikt voor `DashboardCommandService`);
  `val materializer` is daar verwijderd (nergens anders meer gebruikt).
- Repo-brede grep naar `DashboardQueryService(` bevestigt: nog maar 3
  call-sites (de klasse zelf + de 2 hierboven), geen enkele gebruikt nog
  `orchestratorApi =`, `workspaceLauncher =` of `subtaskPlanMaterializer =`.
- Build/test-bewijs: eerst `mvn -pl factory-contracts,factory-common -am
  install -DskipTests` (kale `~/.m2`, zie bekende agent-tip
  `dashboard-backend-maven-deps`), daarna `mvn -f softwarefactory/pom.xml test`
  (groen, incl. `DashboardQueryService`-afhankelijke tests) en tot slot het
  volledige vangnet `mvn verify` vanaf de repo-root: **BUILD SUCCESS**, alle 5
  modules SUCCESS (`factory-contracts`, `factory-common`, `softwarefactory`,
  `agentworker`, `dashboard-backend`), 0 failures/0 errors op elke module.
- Geen documentatie in `docs/factory/` aangepast: dit is interne dode-code-
  opschoning binnen `DashboardQueryService`'s constructor zonder wijziging in
  gedrag, API-oppervlak of architectuur; er is geen functional-spec/technical-
  spec/UX-tekst die deze 3 constructor-parameters beschrijft.
