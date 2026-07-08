# SF-819 - Worklog (story-brede test)

Tester-verificatie van de wijzigingen op branch `ai/SF-817` t.o.v. `main`.

## Omgeving
- mvn 3.9.10 / JDK 21 voorgeïnstalleerd. GEEN Docker en GEEN Flutter in de tester-omgeving.
- Geen preview-deploy geconfigureerd (SF_PREVIEW_URL leeg) → geen browser/screenshot-test mogelijk.

## Uitgevoerde tests
- `mvn -pl softwarefactory -am test`: **472 tests, Failures: 0, Errors: 1**.
  - De enige Error is `PostgresTrackerClientTest` (Testcontainers/PostgreSQLContainer) → "Could not
    find a valid Docker environment". Dit is de bekende env-baseline (geen Docker), geen code-regressie.
- `mvn -pl dashboard-backend -am test`: **alle groen, Failures: 0, Errors: 0** (incl.
  BridgeApiControllerTest 12, BridgeRequestHandlerTest via reactor).
- `FactoryDashboardServiceTest` (30 tests) groen, incl. de nieuwe
  `createStory without projectKey falls back to the single configured project`.

## Statische verificatie (waar runtime niet kon door ontbrekende Docker/Flutter)
- AC1 sortering: `stories_screen.dart` sorteert `allIssues` aflopend op `_storyNumber(key)` vóór het
  toepassen van filters → hoogste storynummer altijd bovenaan.
- AC2 tijdstempels: backend exposet `created_at`/`updated_at`:
  - DB-kolommen bestaan (V15__tracker_issues.sql, TIMESTAMPTZ NOT NULL DEFAULT now()).
  - `PostgresTrackerClient` leest beide kolommen (ISSUE_COLUMNS + rs.getObject(...OffsetDateTime)).
  - `TrackerIssueFields` heeft nu `createdAt`/`updatedAt` (default null, niet in field-updates).
  - `updated_at = now()` bij summary/description/status-updates (o.a. transitionIssue) → afrondmoment.
  - Frontend toont per rij: afgeronde story → `updatedAt` (fallback `createdAt`), anders `createdAt`,
    via bestaande `formatTimestamp`.
- AC3/4/5 filters: repo-filter uit distinct `_repoOf` (fields.repo → run.targetRepo fallback) + "Alle
  repos"; case-insensitive zoekveld op summary; buckets + repo + zoek gecombineerd (AND); persistente
  prefs `stories_filter_repo`/`stories_filter_search` vervangen `stories_filter_project`.
- AC6 aanmaken: Project-dropdown verwijderd uit `_CreateStoryDialog`; frontend stuurt geen `projectKey`
  meer mee; `CreateStoryRequest.projectKey` optioneel; BridgeRequestHandler `params.optional`;
  `FactoryDashboardService.resolveProjectKey` valt terug op enige geconfigureerde project met leesbare
  fout bij geen project.
- AC7: bestaande tests groen; nieuwe backend-logica afgedekt (service-fallback runtime-geverifieerd;
  timestamp-exposure statisch geverifieerd + testmethode aanwezig maar vereist Docker).

## Conclusie
Failures: 0. De enige overgebleven Error is de Docker-afhankelijke Postgres-test (env-baseline). Alle
acceptatiecriteria kloppen tegen de code. Geen regressies gevonden.
