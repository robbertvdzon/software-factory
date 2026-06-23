# SF-168 / SF-175 - Worklog

Story: Voeg /projects-pagina toe aan het Kotlin-dashboard

## Checklist

[x]: FactoryDashboardModels.kt — PrdVersionInfo, ProjectOverviewItem, ProjectsPageData toegevoegd
[x]: FactoryDashboardRepository.kt — totalCostByTargetRepo() en activeAgentCountByTargetRepo() toegevoegd
[x]: FactoryDashboardService.kt — storyStatusBucket, repoMatchesProject, parsePrdVersionJson, fetchPrdVersion, projectsOverview(), forceProjectDeploy() toegevoegd; HttpClient als constructor-parameter
[x]: FactoryDashboardViews.kt — nav-item 'Projects' na Dashboard vóór Stories; projects()-methode toegevoegd
[x]: FactoryDashboardController.kt — GET /projects en POST /projects/{projectName}/force-deploy toegevoegd
[x]: Unit-tests voor storyStatusBucket, repoMatchesProject, parsePrdVersionJson

## Gedaan

- **Models**: Drie nieuwe data-classes in FactoryDashboardModels.kt: `PrdVersionInfo`, `ProjectOverviewItem`, `ProjectsPageData`.
- **Repository**: Twee nieuwe SQL-queries: `totalCostByTargetRepo()` (GROUP BY / SUM) en `activeAgentCountByTargetRepo()` (JOIN met WHERE ended_at IS NULL).
- **Service**: 
  - `HttpClient` als optionele constructor-parameter (default `HttpClient.newHttpClient()`) voor testbaarheid.
  - `storyStatusBucket()`: zelfde logica als `classifyStatus()` in Views maar geeft strings terug.
  - `repoMatchesProject()`: case-insensitive contains/equals check.
  - `parsePrdVersionJson()`: regex-parsing van commitHash/commitDate/branch.
  - `fetchPrdVersion()`: GET via httpClient, delegeert naar parsePrdVersionJson.
  - `projectsOverview()`: itereert projectNames(), matcht stories op fields.repo, aggregeert kosten + agents, haalt versie op.
  - `forceProjectDeploy()`: POST naar restartUrl met Bearer-token; exception bij ontbrekend token of HTTP-fout.
- **Views**: Nav-item 'Projects' ingevoegd na Dashboard vóór Stories. `projects()` toont per project een section met key-value (repo, prdversie) en metric-grid (todo/in-progress/done/kosten/agents). Force-deploy knop als hasDeployConfig=true.
- **Controller**: GET /projects (authenticated) en POST /projects/{projectName}/force-deploy (redirect bij succes/fout).
- **Tests**: storyStatusBucket (done/in-progress/todo/null/onbekend), repoMatchesProject (exact/contains/geen match/leeg), parsePrdVersionJson (geldig/geen commitHash/alleen hash).

## Tester-verificatie (SF-176)

### Tests gerund (2026-06-23)
- **FactoryDashboardServiceTest**: 29 tests ✓ (BUILD SUCCESS)
  - 8 nieuwe unit-tests: storyStatusBucket (3), repoMatchesProject (4), parsePrdVersionJson (3)
  - Alle tests slagen; logica correct geverifieerd
- **FactoryDashboardViewsTest**: 35 tests ✓ (BUILD SUCCESS)
- **FactoryDashboardAuthTest**: 4 tests ✓ (BUILD SUCCESS)
- **mvn test (full)**: 319 tests run, 0 failures, 12 E2E errors (omgeving: geen Docker, verwacht per agent-tips)

### Code review
- Models (PrdVersionInfo, ProjectOverviewItem, ProjectsPageData): ✓ correct
- Repository (totalCostByTargetRepo, activeAgentCountByTargetRepo): ✓ SQL correct
- Service (projectsOverview, forceProjectDeploy, helpers): ✓ logica correct
- Views (projects-methode): ✓ escaping (.e()) op commitShort/branch/commitDate (fix van reviewer)
- Controller (GET /projects, POST /force-deploy): ✓ auth + redirects correct
- Navigation: ✓ 'Projects' tussen Dashboard en Stories

## Niet gedaan / afwijkingen

- E2E tests falen door omgeving (Docker), maar unit-tests slagen allemaal.

## Review-bevindingen (SF-175 reviewing)

- **[bug] OPGELOST** FactoryDashboardViews.kt:521 — commitShort was niet geescaped; nu `.e()` toegevoegd zodat het consistent is met regel 578 en branch/commitDate-escaping. Admin merkte dit op in issue-comment 7-1292.
- [info] fetchPrdVersion() haalt versies op met correcte error-handling (4xx/5xx → null, exception → null).
- [info] forceProjectDeploy() werpt correct exception als DeployConfig niet RestRestart is (requirement-check).
- [info] storyStatusBucket() case-insensitive klassering conform bestaande Views-logica.
- [info] Alle inputs (projectName in path, branch, commitDate in HTML) zijn correct geescaped; token in Bearer-header is veilig ingebedded.
