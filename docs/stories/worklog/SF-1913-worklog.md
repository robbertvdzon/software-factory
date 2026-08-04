# SF-1913 - Worklog

Story-context bij eerste pickup:
Backend: Telegram-melding weg, cleanup-runs persisteren en ontsluiten

softwarefactory + dashboard-backend.

1) Telegram eruit: verwijder in maintenance/services/MaintenanceCleanupScheduler.kt het volledige telegram.sendMessage(...)-blok inclusief runCatching-wikkel in cleanupProject; laat de bestaande logger.info ongewijzigd. Haal TelegramMessageGateway uit de constructor en de import. Zet maintenance/package-info.java op allowedDependencies={"config"}. De gateway zelf blijft bestaan (nog gebruikt door telegram/services/TelegramAuditQuestionService).

2) Persistentie: nieuwe Flyway-migratie V30__maintenance_cleanup_runs.sql (V29 is de laatste) met tabel ${schema}.maintenance_cleanup_runs: id (PK), project, started_at, finished_at, releases_deleted, releases_kept, packages_deleted, packages_kept, dry_run (boolean), error (nullable tekst) en een detailveld (tekst/JSON) met de verwijderde release-tags en package-versions; index op (started_at DESC). Nieuw subpackage maintenance/repositories/ met eigen package-info.java (@NamedInterface("repositories")) en een @Repository + recordmodel, gemodelleerd naar audit/repositories/AuditRepositories.kt: JdbcTemplate + FactorySecrets, table via factorySecrets.factoryDatabaseSchema, INSERT ... RETURNING id, get(id), recent(project: String?, limit: Int) nieuwste-eerst, deleteOlderThan(cutoff).

3) Scheduler schrijft per project per tick precies één rij: ook bij 0 verwijderingen, ook bij dry-run (dry_run=true met de geplande aantallen, er wordt niets verwijderd) en ook bij een mislukte projectrun (fout uit de bestaande runCatching/onFailure in tick() in het error-veld; overige projecten worden gewoon afgewerkt). Een project zonder GitHub-slug wordt zoals nu overgeslagen en levert geen rij. Het wegschrijven zelf is fail-soft (runCatching + logger.warn) en mag de cleanup nooit laten omvallen. Hervorm de private helpers cleanupReleases/cleanupPackages zodat ze naast de aantallen ook de verwijderde tags/versions teruggeven (ze hebben maar één aanroeper). Aantallen = daadwerkelijk gelukte deletes; een individuele mislukte delete telt niet mee en zet geen error op de run. Het opruim-algoritme zelf verandert niet.

4) Retentie: aan het eind van tick() runs ouder dan de grens verwijderen, fail-soft, met logger.info over het aantal verwijderde rijen. Grens 90 dagen via @Value("${sf.maintenance.run-retention-days:90}"), zelfde stijl als sf.maintenance.dry-run. Geen aparte poller. Precedent: runtime/services/AgentEventRetentionPoller.kt + AgentEventRepository.deleteOlderThan (batched delete).

5) Leespad: immutable view-modellen in dashboard/models/FactoryDashboardModels.kt naast AuditReportSummaryView/AuditReportListPageData/AuditReportDetailView (ModuleApiConventionTest: models mag alleen immutable data classes bevatten). Twee methodes op DashboardQueries (dashboard/DashboardApi.kt) met implementatie in dashboard/services/DashboardQueryService.kt naast auditReportsFor/auditReportDetail, inclusief het load(errors, default){...}-soft-fail-patroon; lijstlimiet 200 in de geest van AUDIT_REPORT_LIST_LIMIT, geen paginering. dashboard/package-info.java krijgt "maintenance" en "maintenance :: repositories" in allowedDependencies (de bridge mag maintenance niet rechtstreeks importeren).

6) Bridge: operaties maintenance.cleanupsList en maintenance.cleanupDetail toevoegen in bridge/services/BridgeRequestHandler.kt in dispatchOverviewRead - NIET in een nieuw vijfde when-blok (LongMethod-ratchet). In dashboard-backend .../bridge/BridgeApiController.kt: GET /api/v1/maintenance/cleanups (optionele @RequestParam project) en GET /api/v1/maintenance/cleanups/{id}, exact in de vorm van de audits/reports-endpoints (eerst authService.requireAuthorization(authorization), dan respond(hub.dispatch(...)) met string-params). Onbekende id -> NotFoundException (404), geen error(...).

7) Tests (hoort bij deze subtaak): nieuw MaintenanceCleanupSchedulerTest met fakes voor de GitHub-clients en een in-memory repository: run met verwijderingen, run met 0 verwijderingen, dry-run, falende run met vastgelegde fout, en assertie dat er geen Telegram-aanroep meer plaatsvindt. Repository-test voor opslaan/uitlezen/retentie volgens het Testcontainers-precedent in dashboard/repositories/FactoryDashboardRepository*Test.kt. BridgeRequestHandlerTest (nieuwe operaties + INVALID_PARAMS-pad) en BridgeApiControllerTest (401 zonder token, operatie/params-capture via StubHub, 404-pad). BridgeTestFixtures.buildFixture moet de nieuwe DashboardQueryService-constructorparameter meekrijgen, anders compileert de testset niet.

8) Docs: operatie-catalogus in docs/ontwerp-bridge-dashboard.md en een korte alinea over de maintenance-cleanup + historie in docs/factory/technical-spec.md.

Afronden met mvn verify (softwarefactory + dashboard-backend, inclusief ModulithArchitectureTest en ModuleApiConventionTest) groen, en een review-stap op de eigen wijziging.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

## Subtaak SF-1914 (developer) — backend

Story in eigen woorden: de nachtelijke opruimer moet ophouden met Telegrammen en in plaats daarvan
per project per ronde een regel in de database achterlaten, zodat het dashboard straks kan laten
zien dát hij gedraaid heeft en wat er weg is. Die historie moet ook een lege of mislukte ronde
vastleggen, mag niet oneindig groeien, en moet via bridge + dashboard-backend leesbaar zijn.

Gedaan / waarom:

- **Telegram eruit.** `MaintenanceCleanupScheduler` heeft geen `TelegramMessageGateway` meer (import,
  constructorparameter en het hele `sendMessage`-blok weg); de bestaande `logger.info` staat
  ongewijzigd. `maintenance/package-info.java` is daardoor terug naar
  `allowedDependencies = {"config"}`. De gateway zelf blijft bestaan voor
  `TelegramAuditQuestionService`.
- **Persistentie.** Nieuwe migratie `V30__maintenance_cleanup_runs.sql` (tabel met project, start/eind,
  verwijderd/bewaard per soort, `dry_run`, `error`, JSON-`details` en een index op `started_at DESC`)
  plus het nieuwe subpackage `maintenance/repositories/` met `@NamedInterface("repositories")` en
  `MaintenanceCleanupRunRepository` (`add` met `INSERT … RETURNING id`, `get`, `recent(project, limit)`,
  `deleteOlderThan(cutoff)`), gemodelleerd naar `AuditReportRepository`.
- **Eén rij per project per tick.** `tick()` schrijft de rij nu vanuit `onSuccess`/`onFailure` van de
  bestaande `runCatching`, zodat een gefaalde projectronde zijn foutmelding in `error` krijgt en de
  overige projecten gewoon doorlopen. Een project zonder GitHub-slug levert bewust géén rij (helper
  geeft `null` terug). Het wegschrijven is zelf fail-soft. `cleanupReleases`/`cleanupPackages` geven
  nu een `CleanupStep` (verwijderde namen + aantal bewaard) i.p.v. `Pair<Int, Int>`, zodat de
  verwijderde tags/versions vastgelegd kunnen worden; aantallen zijn de daadwerkelijk gelukte deletes
  (bij dry-run de geplande).
- **Retentie.** Aan het eind van `tick()` een fail-soft `deleteOlderThan(now - sf.maintenance.run-retention-days)`
  (default 90) met `logger.info` over het aantal verwijderde rijen. Geen aparte poller, zoals gevraagd.
- **Parametergrens quality-ratchet.** `sf.maintenance.dry-run` + `sf.maintenance.run-retention-days`
  zitten in een eigen `MaintenanceCleanupSettings`-bean (zelfde `@Value`-stijl, maar één
  constructorparameter) en `repository.add(...)` neemt één `NewMaintenanceCleanupRun` i.p.v. elf
  losse parameters. Zonder die twee ingrepen sloeg detekt' `LongParameterList` aan (threshold 7) en
  was `./quality/run.sh` rood; een `@Suppress` is geen optie want de ratchet blokkeert ook op nieuwe
  suppressions.
- **Leespad.** Drie immutable view-modellen in `FactoryDashboardModels.kt`, twee methodes op
  `DashboardQueries` met implementatie in `DashboardQueryService` (soft-fail `load`-patroon,
  lijstlimiet 200), `dashboard/package-info.java` uitgebreid met `maintenance` +
  `maintenance :: repositories`, en de operaties `maintenance.cleanupsList`/`maintenance.cleanupDetail`
  in `dispatchOverviewRead` (geen vijfde `when`-blok). Een onbekende run geeft `NotFoundException` →
  `NOT_FOUND` → HTTP 404. In dashboard-backend zijn `GET /api/v1/maintenance/cleanups` (optionele
  `project`-param) en `GET /api/v1/maintenance/cleanups/{id}` toegevoegd in de vorm van de
  audits/reports-endpoints.
- **Testbaarheid.** De drie GitHub-cleanup-clients en de nieuwe repository zijn `open` gemaakt: er is
  geen mock-framework in deze repo, dus de scheduler-test gebruikt handgeschreven subklasse-fakes.
  Dat is de kleinst mogelijke ingreep t.o.v. het introduceren van extra poorten of een
  `HttpClient`-dubbel.
- **Tests.** Nieuw `MaintenanceCleanupSchedulerTest` (run met verwijderingen, run met 0
  verwijderingen, dry-run zonder echte deletes, gefaalde projectrun met vastgelegde fout, project
  zonder slug, fail-soft repository, retentie-cutoff en een mutatiebestendige assertie dat er geen
  telegram-type meer in de constructor zit) en `MaintenanceCleanupRunRepositoryTest` (Testcontainers:
  opslaan incl. JSON-details, nieuwste-eerst + projectfilter, limiet, onbekende id, retentie-delete).
  `BridgeRequestHandlerTest` en `BridgeApiControllerTest` zijn uitgebreid met de nieuwe operaties,
  het `INVALID_PARAMS`- en het 404-pad; `BridgeTestFixtures` en `FactoryDashboardServiceTest` kregen
  de nieuwe `DashboardQueryService`-constructorparameter.
- **Docs.** `docs/ontwerp-bridge-dashboard.md` (operatie-catalogus) en `docs/factory/technical-spec.md`
  (nieuwe paragraaf "Maintenance-cleanup") bijgewerkt; `docs/technical/module-dependencies.md` is
  hergenereerd omdat `dashboard`'s `allowedDependencies` wijzigde.

Niet gedaan (bewust, andere subtaak): het Maintenance-scherm in `dashboard-frontend` en de
bijbehorende `docs/factory/ux/screen-map.md`-aanpassing horen bij SF-1915.

## Review SF-1914 (reviewer) — akkoord

Volledige story-diff t.o.v. `main` beoordeeld (25 bestanden). AC 1, 2, 3, 6 en de backend-kant van
AC 7 zijn gedekt; AC 4/5 (frontend) horen bij SF-1915. Gerichte hercontrole in de reviewsandbox:
`mvn -pl factory-common,softwarefactory -am test -Dtest=MaintenanceCleanupSchedulerTest,BridgeRequestHandlerTest,DashboardQueryServiceTest`
→ BUILD SUCCESS, 121 tests, 0 failures/0 errors; `tools/check-composition-roots` → PASS (27 paden).
Het volledige vangnet is niet herhaald (harness-geverifieerd developerbewijs).

Niet-blokkerende punten voor een volgende ronde:

- **`open` is overbodig.** `softwarefactory/pom.xml` r175-185 activeert de kotlin-`spring`
  (allopen)-compilerplugin, dus `@Component`/`@Repository`-klassen én hun members zijn al open. De
  toegevoegde `open`-modifiers op `GitHubReleaseCleanupClient`, `GitHubPackageCleanupClient`,
  `GitHubProtectedShaSource` en `MaintenanceCleanupRunRepository` raken productiebestanden zonder
  functioneel effect; de subklasse-fakes binden ook zonder.
- **Gefaalde ronde legt altijd 0 verwijderingen vast.** `MaintenanceCleanupScheduler.tick()` schrijft
  bij een `onFailure` `CleanupOutcome.EMPTY` weg, ook als `cleanupReleases` al releases had verwijderd
  voordat `cleanupPackages` klapte. De rij toont dan `releases_deleted = 0` terwijl er wél iets weg is.
- **`kept` telt gepland, `deleted` telt gelukt.** `releases.size - toDelete.size` respectievelijk
  `versions.size - toDelete.size`: een release/version waarvan de delete faalde telt noch als
  verwijderd noch als bewaard, dus `deleted + kept` kan lager uitvallen dan het totaal.
- **`maintenanceCleanupDetail` maakt van een DB-storing een 404.** Bewust en gedocumenteerd
  (`DashboardQueryService` r285-288), maar een onbereikbare database is geen "bestaat niet"; overweeg
  daar later een 5xx van te maken.

## Subtaak SF-1915 (developer) — frontend

Story in eigen woorden: de opruimhistorie die SF-1914 in de database en achter de bridge heeft gezet
moet nu zichtbaar worden in de app. Onder "Meer" komt een scherm "Maintenance" met per opruimronde
een tegel (wanneer, welk project, hoeveel opgeruimd, plus een dry-run- en een fout-badge); tikken op
een ronde opent een volledige detailpagina met precies welke release-tags en package-versions weg
zijn, de aantallen en de eventuele foutmelding. Geen filters, geen paginering, geen "Run now".

Stappenplan SF-1915:
[x]: lijstscherm `MaintenanceScreen` op `/api/v1/maintenance/cleanups`
[x]: detailpagina op `/api/v1/maintenance/cleanups/{id}`
[x]: navigatie (export + `_NavEntry` "Maintenance", geen badge-teller)
[x]: widgettests nieuw scherm + `app_shell_test` bijwerken
[x]: `docs/factory/ux/screen-map.md` bijwerken
[x]: `flutter analyze` + `flutter test` + volledig vangnet groen

Gedaan / waarom:

- **`lib/screens/maintenance_screen.dart`** (nieuw), gemodelleerd naar `audit_screen.dart`. Het
  lijstscherm is een `DataScreen`-wrapper (dus automatisch offline-banner, pull-to-refresh en
  SSE-verversing) met `_fetch` op `/api/v1/maintenance/cleanups`. Per run een `Panel` met een
  `ListTile`: `formatTimestamp(startedAt)` als titel, "project — X releases / Y package-versions
  opgeruimd" als subtitel, en de badges `dry-run` (`BadgeTone.warn`) en `fout` (`BadgeTone.bad`) uit
  de bestaande `StatusBadge`. De backend levert `failed` als apart booleaanveld in de lijst-view, dus
  de fout-badge hoeft het detail niet op te halen. Lege lijst → `EmptyState`, `errors` → `ErrorBanner`
  (net als in `audit_screen.dart` zijn dat losse strings, geen maps — dus niet via `asList`).
- **Dry-run-formulering.** Bij `dryRun = true` staat er "zou worden opgeruimd" i.p.v. "opgeruimd":
  de backend legt bij een dry-run de *geplande* aantallen vast en verwijdert niets, dus "opgeruimd"
  zou daar een onwaarheid zijn.
- **Detail als volledige pagina** (`_MaintenanceRunDetailScreen`, privé `StatefulWidget` met eigen
  `Scaffold` + `AppBar` "Opruimronde", `FutureBuilder`, `ConstrainedBox(maxWidth: 860)`), exact het
  patroon van `_AuditReportDetailScreen`. Toont datum/tijd + dry-run-badge, project, eindtijd, de
  opgeruimd/bewaard-aantallen voor releases én package-versions, de foutmelding in een `ErrorBanner`
  en daaronder twee opsommingen. De detailvelden `deletedReleaseTags`/`deletedPackageVersions` zijn
  JSON-lijsten van *strings*, niet van maps, dus daar past `asList` niet; een kleine `stringList`-helper
  doet het werk (met comment, omdat dat afwijkt van de rest van het scherm).
- **Navigatie:** export in `lib/screens/overview_screens.dart` en een `_NavEntry('Maintenance',
  Icons.cleaning_services_outlined, …)` in `_secondaryEntries` van `lib/app_shell.dart`, tussen
  Audits en Settings. Geen badge-teller: `_navIcon` telt alleen op de labels 'My actions' en 'Audits',
  dus daar was geen wijziging nodig. Label en schermtitel zijn Engels ("Maintenance"), conform de
  aanname in de refined story en de overige secundaire entries.
- **Tests:** nieuw `test/screens/maintenance_screen_test.dart` in de stijl van
  `audit_memory_screen_test.dart` (MockClient + `http.runWithClient` met body-callback, zodat de
  detail-call ná de tap binnen dezelfde zone valt): lijst met aantallen inclusief een ronde met 0
  verwijderingen, de dry-run- en fout-badge plus de afwijkende dry-run-tekst, de lege staat, en de
  drilldown die aantoonbaar `/api/v1/maintenance/cleanups/7` ophaalt en de verwijderde tags/versions,
  de aantallen en de foutmelding toont. `test/screens/app_shell_test.dart` r46-55 asserteert de exacte
  rail-labellijst en is met 'Maintenance' uitgebreid.
- **Docs:** `docs/factory/ux/screen-map.md` — de nav-opsomming van "Meer" en een routerij
  `/maintenance` met het gedrag van lijst + detailpagina. `technical-spec.md` beschrijft de
  maintenance-cleanup al sinds SF-1914; daar was geen aanvulling nodig.

Bewijs (in deze agent-container gedraaid):

- `flutter analyze` → `No issues found!`
- `flutter test` → `All tests passed!` (118 geslaagde tests, inclusief de 4 nieuwe
  maintenance-widgettests).
- `mvn -B --no-transfer-progress clean verify` vanaf repo-root → BUILD SUCCESS, exitcode 0, 04:34 min;
  0 failures / 0 errors over alle modules (16 + 55 + 739 + 78 + 61 + 57 tests). De `ERROR`-regels in
  het log zijn testlogging (bewust gesimuleerde merge-/deploy-/configfouten), geen buildfouten.
- `tools/audit-documentation` → `documentation-audit/v1: PASS`.
- Mutatiebewijs: de dry-run-tekst hardcoderen op "opgeruimd" maakt
  `maintenance_screen_test.dart` rood ("Found 0 widgets with text containing zou worden opgeruimd");
  daarna teruggedraaid en opnieuw groen gedraaid.
