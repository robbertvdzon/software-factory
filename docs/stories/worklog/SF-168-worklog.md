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

## Niet gedaan / afwijkingen

- Tests kunnen niet lokaal gedraaid worden (geen Maven in de agent-omgeving); correctheid is via statische review geverifieerd.
