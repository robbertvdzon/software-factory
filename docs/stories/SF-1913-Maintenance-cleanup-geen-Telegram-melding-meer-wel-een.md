# SF-1913 - Maintenance-cleanup: geen Telegram-melding meer, wel een log in het dashboard

## Story

Maintenance-cleanup: geen Telegram-melding meer, wel een log in het dashboard

<!-- refined-by-factory -->

## Samenvatting

De factory ruimt elke nacht oude releases en container-images op. Nu stuurt hij daar een
Telegram-bericht over; dat is ongewenst. Na deze story stopt die melding en wordt elke
opruimronde in plaats daarvan bewaard als historie. In de dashboard-app komt een nieuw
scherm "Maintenance" waar je per opruimronde ziet wanneer hij liep, voor welk project en
hoeveel er is opgeruimd. Tik je op een ronde, dan zie je precies wat er weg is. Ook rondes
waarbij niets is opgeruimd of die zijn misgegaan komen in de lijst, zodat je kunt zien dat
de opruimer echt gedraaid heeft. De historie wordt niet oneindig bewaard.

## Scope

### 1. Telegram-melding weg (softwarefactory)

- In `maintenance/services/MaintenanceCleanupScheduler.kt` (`cleanupProject`, r52-60) vervalt het
  volledige `telegram.sendMessage(...)`-blok inclusief `runCatching`-wikkel. De `logger.info` op
  r47-51 blijft ongewijzigd.
- `TelegramMessageGateway` verdwijnt uit de constructor (r25) en de import (r5). De gateway zelf
  blijft bestaan: `telegram/services/TelegramAuditQuestionService.kt:19` gebruikt 'm nog.
- `maintenance/package-info.java` wordt daarmee `allowedDependencies = {"config"}` (telegram is
  na deze wijziging geen dependency meer van de module).

### 2. Persistentie van cleanup-runs (softwarefactory)

- Nieuwe migratie `V30__maintenance_cleanup_runs.sql` (V29 is de laatste), tabel
  `${schema}.maintenance_cleanup_runs` met minimaal: `id` (PK), `project`, `started_at`,
  `finished_at`, `releases_deleted`, `releases_kept`, `packages_deleted`, `packages_kept`,
  `dry_run` (boolean), `error` (nullable tekst) en een detailveld (tekst/JSONB) met de verwijderde
  release-tags en package-versions. Index op `(started_at DESC)` t.b.v. de lijstquery.
- Nieuw subpackage `maintenance/repositories/` met eigen `package-info.java`
  (`@NamedInterface("repositories")`) en een `@Repository`-klasse + recordmodel, gemodelleerd naar
  `audit/repositories/AuditRepositories.kt` (`AuditReportRepository` r377-468): `JdbcTemplate` +
  `FactorySecrets`, `private val table get() = "${factorySecrets.factoryDatabaseSchema}.…"`,
  `INSERT … RETURNING id`, `get(id)`, `recent(project: String?, limit: Int)` (nieuwste eerst) en
  `deleteOlderThan(cutoff)`.
- `MaintenanceCleanupScheduler` schrijft per project per tick precies één rij:
  - ook als er 0 verwijderingen zijn (bewijs dat de scheduler gedraaid heeft);
  - ook bij dry-run (`dry_run = true`, met de *geplande* aantallen; er wordt niets verwijderd);
  - ook bij een mislukte projectrun: de bestaande `runCatching`-`onFailure` in `tick()` (r34-35)
    legt de fout vast in `error` en blijft de overige projecten gewoon afwerken;
  - een project zonder GitHub-slug (r41-44) wordt zoals nu overgeslagen en levert géén rij.
  - Falen van het wegschrijven zelf mag de cleanup niet laten omvallen: wikkel in
    `runCatching { … }.onFailure { logger.warn(...) }`, zelfde fail-soft-stijl als de rest.
- Om de verwijderde tags/versions te kunnen opslaan moeten de private helpers `cleanupReleases`
  (r63-75) en `cleanupPackages` (r77-97) meer teruggeven dan hun huidige `Pair<Int, Int>`; ze
  hebben maar één aanroeper, dus dat mag vrij worden hervormd.
- Retentie: aan het eind van `tick()` worden runs ouder dan de retentiegrens verwijderd,
  fail-soft en met een `logger.info` over het aantal verwijderde rijen. Precedent voor het
  retentiepatroon: `runtime/services/AgentEventRetentionPoller.kt` (`cleanupOnce`) en
  `runtime/repositories/AgentEventRepository.kt` r95-108 (batched delete).

### 3. Leespad naar het dashboard (softwarefactory + dashboard-backend)

Volg exact de audit-rapportketen; nieuwe stappen tussen haakjes:

- View-modellen bij de andere dashboard-views in `dashboard/models/FactoryDashboardModels.kt`
  (zie `AuditReportSummaryView`/`AuditReportListPageData`/`AuditReportDetailView` r543-566);
  let op `ModuleApiConventionTest`: `models` mag alleen immutable data classes bevatten.
- Twee methodes op `DashboardQueries` (`dashboard/DashboardApi.kt`, naast r18-19) met implementatie
  in `dashboard/services/DashboardQueryService.kt` (naast `auditReportsFor` r232-243 en
  `auditReportDetail` r246-259), inclusief het bestaande `load(errors, default) { … }`-soft-fail-patroon.
- `dashboard/package-info.java` krijgt `"maintenance"` + `"maintenance :: repositories"` in
  `allowedDependencies` (bridge mag de maintenance-module niet rechtstreeks importeren).
- Bridge-operaties `maintenance.cleanupsList` en `maintenance.cleanupDetail` in
  `bridge/services/BridgeRequestHandler.kt` → `dispatchOverviewRead` (bij r112-117), niet in een
  nieuw vijfde `when`-blok (LongMethod-ratchet).
- `dashboard-backend .../bridge/BridgeApiController.kt`: `GET /api/v1/maintenance/cleanups`
  (optionele `@RequestParam project`) en `GET /api/v1/maintenance/cleanups/{id}`, exact in de vorm
  van r416-433 (`authService.requireAuthorization(authorization)` eerst, daarna
  `respond(hub.dispatch(...))` met string-params).
- Onbekende id → `NotFoundException` (levert 404), niet `error(...)`.

### 4. Frontend (dashboard-frontend)

- Nieuw `lib/screens/maintenance_screen.dart`, gemodelleerd naar `audit_screen.dart`:
  `DataScreen`-wrapper met `_fetch` op `/api/v1/maintenance/cleanups`, per run een tegel/`ListTile`
  met datum/tijd (`formatTimestamp`), project, "X releases / Y package-versions opgeruimd", een
  dry-run-badge en een fout-badge. Lege lijst → `EmptyState`, fouten → `ErrorBanner`.
- Detail als volledige pagina (privé `StatefulWidget` met eigen `Scaffold`+`AppBar`, `FutureBuilder`
  op `/api/v1/maintenance/cleanups/{id}`, `ConstrainedBox(maxWidth: 860)`), zoals
  `_AuditReportDetailScreen` (audit_screen.dart r421-507): toont de verwijderde release-tags en
  package-versions, plus aantallen, dry-run en de eventuele foutmelding.
- Export toevoegen in `lib/screens/overview_screens.dart` en een `_NavEntry` in
  `lib/app_shell.dart` `_secondaryEntries` (r41-47); geen badge-teller.

### 5. Tests en docs

- Nieuw: `MaintenanceCleanupSchedulerTest` (bestaat nog niet) met fakes voor de GitHub-clients en
  een fake/in-memory repository: run met verwijderingen, run met 0 verwijderingen, dry-run-run,
  falende run (fout vastgelegd) en de assertie dat er geen Telegram-aanroep meer bestaat.
- Repository-test voor opslaan/uitlezen/retentie; Testcontainers-precedent staat in
  `softwarefactory/src/test/.../dashboard/repositories/FactoryDashboardRepository*Test.kt`.
- `BridgeRequestHandlerTest` (nieuwe operaties + `INVALID_PARAMS`-pad) en
  `BridgeApiControllerTest` (401 zonder token, operatie/params-capture via `StubHub`, 404-pad).
- `BridgeTestFixtures.buildFixture` (r105-167) moet de nieuwe repository-constructorparameter van
  `DashboardQueryService` meekrijgen, anders compileert de testset niet.
- `dashboard-frontend/test/screens/app_shell_test.dart` r45-54 assert de exacte navigatielabel-lijst
  en breekt op een nieuwe entry — bijwerken, plus een widgetttest voor het nieuwe scherm in de
  stijl van `test/screens/audit_memory_screen_test.dart`.
- Docs bijwerken: `docs/factory/ux/screen-map.md` (nav-opsomming r25 + routetabel), de
  operatie-catalogus in `docs/ontwerp-bridge-dashboard.md` (r230-235) en een korte alinea over de
  maintenance-cleanup + zijn historie in `docs/factory/technical-spec.md`.

### Buiten scope

- Het opruim-algoritme zelf (retentieregels voor releases/packages) verandert niet.
- Geen "Run now"-knop, geen filters/paginering in de UI, geen nieuwe Telegram-melding in een
  andere vorm.
- `docs/technical/scheduled-jobs.md` beschrijft deze scheduler nu niet; hem daar alsnog volledig
  documenteren is optioneel en geen faalcriterium.

## Acceptance criteria

1. Na een cleanup-run wordt er geen Telegram-bericht meer verstuurd; `TelegramMessageGateway` komt
   niet meer voor in `MaintenanceCleanupScheduler` en de bestaande `logger.info` blijft intact.
2. Elke uitgevoerde cleanup per project levert één rij in `maintenance_cleanup_runs`, óók bij 0
   verwijderingen, bij dry-run en bij een mislukte run (met de foutmelding in `error`).
3. `GET /api/v1/maintenance/cleanups` geeft de runs nieuwste-eerst terug (optioneel gefilterd op
   project) en `GET /api/v1/maintenance/cleanups/{id}` het detail; beide vereisen autorisatie en
   een onbekende id geeft 404.
4. In de dashboard-app staat onder "Meer" een nieuw scherm dat de runs toont met datum/tijd,
   project en aantallen, met een dry-run- en een fout-badge waar van toepassing.
5. Tikken op een run opent een volledige detailpagina met de verwijderde release-tags en
   package-versions (en de fout, als de run mislukte).
6. Runs ouder dan de retentiegrens worden automatisch opgeruimd; de historie groeit niet oneindig.
7. `mvn -q verify` (softwarefactory + dashboard-backend, inclusief `ModulithArchitectureTest` en
   `ModuleApiConventionTest`) en `flutter analyze` + `flutter test` zijn groen, met de onder
   "Tests en docs" genoemde nieuwe en aangepaste tests.

## Aannames

- **Nav-label "Maintenance"** (in plaats van "Opruimen"), omdat de overige entries in
  `_secondaryEntries` Engelstalig zijn (Projects, Builds, App-updates, Audits, Settings);
  schermtitel idem. Een andere labelkeuze is een eenregelige wijziging.
- **Retentie: 90 dagen**, instelbaar via `@Value("\${sf.maintenance.run-retention-days:90}")` —
  zelfde configuratiestijl als de bestaande `sf.maintenance.dry-run` en `sf.maintenance.cleanup-cron`
  in dezelfde klasse. Opruimen gebeurt aan het eind van de bestaande nachtelijke tick; geen aparte
  poller.
- **Aantallen = daadwerkelijk gelukte deletes.** Individuele deletes zijn nu fail-soft
  (`runCatching` per release/version, r70-71 en r89-90); een mislukte delete telt niet mee als
  verwijderd en zorgt niet voor `error` op de run — `error` is gereserveerd voor het falen van de
  hele projectrun.
- **Detailveld als JSON-tekst** met twee lijsten (verwijderde release-tags, verwijderde
  package-versions met hun tags), zodat het detailscherm er een leesbare opsomming van maakt.
- **Lijstlimiet 200 runs**, in dezelfde geest als `AUDIT_REPORT_LIST_LIMIT` in
  `DashboardQueryService`; geen paginering.
- Bestaande, al uitgerolde app-versies kennen het nieuwe scherm niet; er is geen migratie of
  backfill van historische cleanup-runs (de historie begint bij de eerste run na deploy).

## Eindsamenvatting

Ik heb .task.md, de worklog (SF-1914/1915/1916 + reviews) en de story-diff (31 bestanden, +1716/-44) gelezen. Hier is de eindsamenvatting.

---

# SF-1913 — Maintenance-cleanup: geen Telegram-melding meer, wel een log in het dashboard

## Wat is gebouwd

**1. Telegram-melding weg (softwarefactory)**
`MaintenanceCleanupScheduler` verstuurt geen Telegram-bericht meer: import, constructorparameter en het hele `sendMessage`-blok zijn verwijderd; de bestaande `logger.info` is intact gebleven. `maintenance/package-info.java` staat weer op `allowedDependencies = {"config"}`. De `TelegramMessageGateway` zelf blijft bestaan — `TelegramAuditQuestionService` gebruikt hem nog.

**2. Cleanup-runs persisteren**
Nieuwe Flyway-migratie `V30__maintenance_cleanup_runs.sql` (project, start-/eindtijd, verwijderd/bewaard per soort, `dry_run`, `error`, JSON-`details`, index op `started_at DESC`) plus nieuw subpackage `maintenance/repositories/` met `MaintenanceCleanupRunRepository` (`add` via `INSERT … RETURNING id`, `get`, `recent(project, limit)`, `deleteOlderThan`), gemodelleerd naar `AuditReportRepository`. Per project per tick precies één rij — ook bij 0 verwijderingen, bij dry-run en bij een gefaalde projectronde (fout in `error`, overige projecten lopen door). Een project zonder GitHub-slug levert bewust géén rij. Wegschrijven is fail-soft en kan de cleanup nooit laten omvallen. Retentie: aan het eind van `tick()` fail-soft opruimen ouder dan `sf.maintenance.run-retention-days` (default 90), geen aparte poller.

**3. Leespad naar het dashboard**
Drie immutable view-modellen, twee methodes op `DashboardQueries` met soft-fail-`load`-implementatie in `DashboardQueryService` (lijstlimiet 200), bridge-operaties `maintenance.cleanupsList`/`maintenance.cleanupDetail` in het bestaande `dispatchOverviewRead` (geen vijfde `when`-blok, i.v.m. de LongMethod-ratchet), en in dashboard-backend `GET /api/v1/maintenance/cleanups` (optionele `project`) en `/{id}`. Onbekende id → `NotFoundException` → HTTP 404; beide endpoints autoriseren eerst.

**4. Frontend**
Nieuw `maintenance_screen.dart`: lijst als `DataScreen` (offline-banner, pull-to-refresh, SSE) met per ronde datum/tijd, project, aantallen en de badges `dry-run` en `fout`; detail als volledige pagina met de verwijderde release-tags en package-versions, aantallen en eventuele foutmelding. Nav-entry "Maintenance" onder "Meer", tussen Audits en Settings, zonder badge-teller.

## Gemaakte keuzes (afwijkingen/aanvullingen op de refined story)

- **Extra `MaintenanceCleanupSettings`-bean en één `NewMaintenanceCleanupRun`-parameter** i.p.v. losse `@Value`s en elf repository-parameters: zonder deze twee ingrepen sloeg detekt's `LongParameterList` (threshold 7) aan en was `quality/run.sh` rood; een `@Suppress` kon niet, want de ratchet blokkeert nieuwe suppressions.
- **Fakes via `open`-klassen**: er is geen mock-framework in deze repo, dus de GitHub-cleanup-clients en de repository zijn `open` gemaakt voor handgeschreven subklasse-fakes. De reviewer merkte terecht op dat de allopen-plugin dit al regelt — de modifiers zijn functioneel overbodig (niet-blokkerend, blijven staan).
- **Dry-run-formulering** in de UI: "zou worden opgeruimd" i.p.v. "opgeruimd", omdat een dry-run alleen de geplande aantallen vastlegt.
- **Nav-label en schermtitel Engels** ("Maintenance"), conform de aanname in de refined story.

## Wat is getest

- `mvn -B verify` vanaf repo-root: **BUILD SUCCESS**, 935 tests, 0 failures/0 errors/0 skipped (factory-contracts 16, factory-common 55, softwarefactory 739 unit + 78 e2e, agentworker 61, dashboard-backend 57). Geen flakes.
- Nieuw: `MaintenanceCleanupSchedulerTest` (9 tests: met/zonder verwijderingen, dry-run, gefaalde run, geen slug, fail-soft repository, retentie-cutoff, mutatiebestendige Telegram-assertie) en `MaintenanceCleanupRunRepositoryTest` (6, Testcontainers). Uitgebreid: `BridgeRequestHandlerTest`, `BridgeApiControllerTest` (401/404/INVALID_PARAMS), `DashboardQueryServiceTest`. `ModulithArchitectureTest` en `ModuleApiConventionTest` groen.
- Flyway-bewijs uit de e2e-log: `Migrating schema "public" to version "30 - maintenance cleanup runs"`, 30 migraties schoon toegepast.
- Frontend: `flutter analyze` → geen issues; `flutter test` → 118/118 groen; `flutter build web --release` → exit 0.
- `tools/audit-documentation` → PASS.
- Alle 7 acceptatiecriteria zijn door de tester één voor één op de bron nagelopen en gedekt.

## Bewust niet gedaan / openstaande punten

- Het opruim-algoritme zelf is ongewijzigd; geen "Run now"-knop, geen filters of paginering, geen backfill van historische runs (historie begint bij de eerste run na deploy).
- `docs/technical/scheduled-jobs.md` beschrijft deze scheduler nog steeds niet (expliciet optioneel).
- Geen klikbare E2E/screenshots: in de tester-sandbox is geen browser of preview-URL; `flutter build web --release` is als zwaarste haalbare vervanging gedraaid.
- Niet-blokkerende reviewpunten, open voor een volgende ronde: een gefaalde ronde legt altijd 0 verwijderingen vast (ook als releases al weg waren voordat de packages-stap klapte); `kept` telt gepland terwijl `deleted` gelukt telt, dus de som kan lager uitvallen dan het totaal; een DB-storing in `maintenanceCleanupDetail` levert een 404 i.p.v. 5xx; `cleanupCountsLine`/`stringList` in het frontend-scherm zijn top-level en dus publiek.

<!-- deploy-summary:start -->
De nachtelijke opruimbeurt stuurt je geen Telegram-bericht meer. In plaats daarvan vind je onder "Meer" een nieuw scherm "Maintenance" met de laatste opruimrondes: wanneer ze liepen, voor welk project en hoeveel er is opgeruimd. Tik je op een ronde, dan zie je precies wat er weg is — ook rondes waarbij niets gebeurde of die misgingen staan erbij.
<!-- deploy-summary:end -->
