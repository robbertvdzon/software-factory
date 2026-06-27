# SF-351 — Persistentie & schrijfbare nightly-settings

## Story in eigen woorden

Eerste developer-stap van de nightly-scheduler-story (SF-350). Hier leg ik de
**persistentie-fundering** en de **schrijfbare settings** neer waar de
reconciliation-scheduler (SF-352) straks op draait:

- Nieuwe Flyway-migratie met drie tabellen: `nightly_settings` (master-switch +
  start/summary-tijd), `nightly_run` en `nightly_run_job` (DB-state voor runs).
- JdbcTemplate-repositories conform `RunRepositories.kt`:
  `NightlySettingsRepository` (single-row read/write met defaults) en
  `NightlyRunRepository` / `NightlyRunJobRepository` (CRUD + queries).
- Timezone-utility (`java.time`, `Europe/Amsterdam`, DST-correct, injecteerbaar
  via `Clock`) voor de NL→UTC-vergelijking die de scheduler nodig heeft.
- `/settings` uitgebreid met een schrijfbaar formulier (checkbox + twee
  HH:MM-velden + opslaan) en een nieuw POST-endpoint; bestaande read-only
  versie/config-info blijft staan.
- Unittests voor de timezone-conversie (DST, vaste klok) en de
  settings-repository round-trip.

De daadwerkelijke scheduler-loop, completion-detectie, digest en /nightly-status
horen bij SF-352 en vallen buiten deze stap.

## Checklist

- [x]: Flyway-migratie `V11__nightly_scheduler.sql` (nightly_settings, nightly_run, nightly_run_job)
- [x]: `NightlyTime` timezone-utility (Europe/Amsterdam, DST, Clock-injectie)
- [x]: `NightlySettings` record + `NightlySettingsRepository` (defaults enabled=false, 02:00/07:00)
- [x]: `NightlyRun`/`NightlyRunJob` records + repositories (CRUD + queries)
- [x]: `/settings` schrijfbaar formulier + POST-endpoint `/settings/nightly`
- [x]: Service + model uitgebreid (SettingsPageData met nightly-settings)
- [x]: Unittest timezone-conversie (DST, vaste klok)
- [x]: Unittest settings-repository round-trip (Testcontainers)
- [x]: Specs bijgewerkt (technical-spec, ux/settings)
- [x]: Build/tests gedraaid

## Wat en waarom

- **`V11__nightly_scheduler.sql`** maakt de drie tabellen aan volgens de bestaande
  `${schema}`-placeholder-conventie. `nightly_settings` is een single-row tabel
  (CHECK `id = 1`) en wordt door de migratie meteen met de defaults geseed, zodat
  `read()` altijd een rij vindt. `nightly_run` heeft een UNIQUE op `run_date` zodat
  de scheduler (SF-352) idempotent precies één run per dag kan aanmaken via
  `ON CONFLICT DO NOTHING`. `nightly_run_job` verwijst met `ON DELETE CASCADE` naar de run.
- **`NightlyTime`** kapselt alle NL↔UTC-logica in: `nlInstant`/`hasReached` (DST-correcte
  vergelijking) en `nlToday` (NL-kalenderdatum als `run_date`). De `Clock` is een
  constructor-param met default `Clock.systemUTC()` — exact het patroon van
  `NightlyJobsReader` — zodat Spring 'm als component opneemt maar tests een vaste klok
  kunnen injecteren. `parseHhMm`/`formatHhMm` vormen de brug naar de `HH:MM`-opslag.
- **`NightlyRepositories.kt`** bevat de records + drie `@Repository`-klassen conform
  `RunRepositories.kt` (JdbcTemplate, schema uit `factorySecrets`). Status-constanten
  staan in `NightlyRunStatus`/`NightlyJobStatus` met een `isTerminal`-helper die SF-352
  hergebruikt. De repos bieden de queries die de scheduler nodig heeft (lopende/laatste
  run per `run_date`, jobs per run/project, status-updates).
- **`/settings`**: `SettingsPageData` kreeg `nightly` + `nightlySaveResult`; de service
  leest de settings en valideert/persisteert ze (`saveNightlySettings`). De view rendert
  een extra sectie "Nightly scheduler" met checkbox + twee `type="time"`-velden + opslaan.
  Nieuw endpoint `POST /settings/nightly` schrijft weg en redirect met feedback-vlag; de
  bestaande versie-/config-secties en de proces-knoppen blijven ongewijzigd.
- **Tests**: `NightlyTimeTest` (6 tests, pure unit, draait lokaal groen) toetst winter/zomer
  DST-conversie met vaste klok, `hasReached`-randen en de NL-`run_date`. `NightlyRepositoriesTest`
  doet de settings-round-trip + run/job-lifecycle tegen Testcontainers-Postgres met de echte
  Flyway-migratie; die vereist Docker en draait in de pipeline (lokaal geen Docker).
- De twee bestaande service-constructies in tests (`FactoryDashboardServiceTest`,
  `TelegramNotificationServiceTest`) kregen de nieuwe `nightlySettingsRepository`-named-arg.

## Verificatie

- `mvn -f softwarefactory/pom.xml test-compile` → groen.
- `mvn -f softwarefactory/pom.xml test -Dtest='NightlyTimeTest,FactoryDashboardServiceTest,TelegramNotificationServiceTest'`
  → 54 tests, 0 failures.
- `NightlyRepositoriesTest` niet lokaal gedraaid (geen Docker in dev-omgeving); compileert en
  draait in de pipeline.

## Specs bijgewerkt

- `docs/factory/technical-spec.md`: sectie "Nightly scheduler (persistentie)" met de drie
  tabellen, repositories en `NightlyTime`.
- `docs/factory/ux/screens/settings.md`: nieuwe sectie + action voor het schrijfbare
  nightly-formulier.
</content>

## Review (reviewer, 2026-06-27)

Volledige story-diff `main...HEAD` beoordeeld. Akkoord.

- [info] Scope klopt: alleen persistentie-fundering + schrijfbare `/settings`. Reconciliation,
  completion-detectie, digest en `/nightly`-status zijn bewust naar SF-352 geschoven. Geen scope creep.
- [info] Migratie `V11__nightly_scheduler.sql` volgt de `${schema}`/`V<n>__desc.sql`-conventie (vgl. V10).
  Single-row `nightly_settings` (CHECK id=1, geseed met defaults), UNIQUE op `nightly_run.run_date`
  (idempotente 1-run-per-dag), FK + cascade + index op `nightly_run_job`.
- [info] Repos conform `RunRepositories.kt`-patroon; upsert via `ON CONFLICT`, status-constanten met
  `isTerminal`. `NightlyTime` DST-correct en `Clock`-injecteerbaar; de bestaande `factoryClock`-bean
  (OrchestratorConfiguration) voldoet aan de `@Component`-injectie → schone Spring-start.
- [info] Geen secrets in output; `redactedSummary()` blijft de bron voor read-only config.
- [info] Specs (technical-spec.md, ux/screens/settings.md) consistent met de diff bijgewerkt.
- [suggestie] `nightly_run.status` heeft SQL-default `'pending'` terwijl `create()` standaard `RUNNING`
  meegeeft; functioneel onschadelijk (create zet status altijd), kan in SF-352 worden gladgestreken.
- [info] Tests: `NightlyTimeTest` (pure unit, winter/zomer-DST + randen) en `NightlyRepositoriesTest`
  (Testcontainers + echte Flyway, settings/run/job round-trip). Testcontainers-test draait in CI
  (geen Docker lokaal). Twee bestaande test-fakes correct uitgebreid met de nieuwe dep.

Conclusie: coherent, testbaar en passend binnen de specs. Goedgekeurd.
