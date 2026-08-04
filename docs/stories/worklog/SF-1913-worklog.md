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
