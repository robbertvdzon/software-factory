# SF-1921 - Worklog

## Story in eigen woorden

De factory ruimt op vijf plekken oude spullen op, maar je zag daar bijna niets van terug: alleen de
nachtelijke GitHub-release/package-cleanup had een scherm, de rest verdween in de logs. En één tabel
— `agent_runs` — werd helemaal nooit opgeruimd, waardoor het agent-log-scherm runs van maanden
geleden liet zien waarvan de inhoud (`agent_events`) allang weg was.

Deze story doet twee dingen:

1. Automatische retentie op `agent_runs`, met instelbare bewaartermijn en de garantie dat lopende en
   onafgeronde runs blijven staan.
2. `maintenance_cleanup_runs` wordt de gedeelde opruim-log van álle vijf mechanismen, en het
   Maintenance-scherm wordt een algemeen "Opruimen"-overzicht met een filter op soort.

## Checklist

[x]: story, `docs/factory/development.md` en de bestaande code gelezen
[x]: Flyway `V31` — `kind`, nullable `project`, `items_deleted`/`items_kept`, index, datamigratie
[x]: `MaintenanceCleanupRunRepository` + record-/insert-types gegeneraliseerd (`CleanupKinds`, `CleanupDetails`)
[x]: `MaintenanceCleanupScheduler` schrijft `kind = github-releases` + uitsplitsing in `details`
[x]: `AgentRunRepository.deleteOlderThan(cutoff, batchSize)` + JDBC-implementatie met de twee veiligheidsregels
[x]: `AgentRunRetentionSettings` + `AgentRunRetentionPoller` (SF_AGENT_RUN_RETENTION_*)
[x]: `CleanupLogWriter` + wegschrijven door de vier factory-brede opruimers (schrijfregel + fail-soft)
[x]: modulith `runtime` → `maintenance :: repositories`, module-dependencies hergenereerd
[x]: `kind`-queryparameter door bridge → dashboard-queries → repository
[x]: `maintenance_screen.dart` met soort-filter, kind-badge en leeg project; navigatielabel `Opruimen`
[x]: tests: retentie (unit + Postgres), opruim-log per soort, schrijfregel, V31-datamigratie, frontend
[x]: documentatie: technical-spec, scheduled-jobs, runbook, screen-map, ontwerp-bridge-dashboard, properties.default.env
[x]: volledig vangnet gedraaid (`mvn verify`, `flutter analyze && flutter test`)

## Wat er gedaan is en waarom

### 1. Retentie op `agent_runs`

- `core/contracts/RunRepositories.kt`: `AgentRunRepository.deleteOlderThan(olderThan, batchSize)`
  toegevoegd met default `0`, zodat bestaande fakes/implementaties in tests niets hoeven te weten.
- `orchestrator/repositories/RunRepositories.kt`: de JDBC-implementatie. `WHERE started_at < ? AND
  ended_at IS NOT NULL AND NOT EXISTS (agent_run_completions met status PENDING/IN_PROGRESS/
  FAILED_RETRYABLE)`, `ORDER BY id LIMIT ?`. Er wordt bewust alleen op `agent_runs` gedelete —
  `agent_events`, `agent_run_completions` en `agent_run_completion_steps` volgen via de bestaande
  `ON DELETE CASCADE`, dus geen extra delete-statements en geen wezen.
- `runtime/services/AgentRunRetentionSettings.kt` + `AgentRunRetentionPoller.kt`, één-op-één
  gemodelleerd op de event-variant: `@Scheduled` met eigen `fixedDelayString`/`initialDelayString`,
  `enabled`-vlag, batching met `maxBatchesPerRun`, `runCatching` in `poll()`, publieke `cleanupOnce()`
  als test-seam en dezelfde `coerceIn`-clamping. Bewust een aparte poller: andere default-termijn
  (90 vs. 30 dagen, zodat de kostenhistorie langer meegaat dan de logregels), eigen schakelaar en
  eigen veiligheidsregels.

### 2. Gegeneraliseerde opruim-log

- `V31__maintenance_cleanup_kinds.sql` voegt `kind`, `items_deleted` en `items_kept` toe, maakt
  `project` nullable, migreert bestaande rijen naar `kind = 'github-releases'` met opgetelde tellers
  en zet de release/package-uitsplitsing via `jsonb_build_object` in `details` — zodat het
  detailscherm van een oude ronde niets verliest. Daarna vervallen de vier `releases_*`/`packages_*`-
  kolommen en komt er een index op `(kind, started_at DESC)`.
- `MaintenanceCleanupRunRepository`: `CleanupKinds` (de vijf afgesproken waarden), `CleanupDetails`
  (de JSON-vorm van `details`, inclusief de github-specifieke uitsplitsing) en `recent(project, kind,
  limit)` met dynamisch samengestelde `WHERE`. `deleteOlderThan(cutoff)` blijft ongewijzigd en dekt
  daarmee automatisch alle nieuwe soorten.
- `runtime/services/CleanupLogWriter`: één plek voor de schrijfregel ("alleen bij `items_deleted > 0`
  of bij een fout") en het fail-soft wegschrijven. Bewust een gewone component en geen poort-interface
  (zoals de story aangeeft); `open` puur voor de test-fakes. De vier schrijvers zijn
  `AgentEventRetentionPoller`, `AgentRunRetentionPoller`, `WorkCleanupPoller` en de payload-purge in
  `AgentRunCompletionService.reconcileDurableCompletions()`. De nachtelijke GitHub-cleanup houdt zijn
  SF-1913-gedrag (élke ronde een rij, ook bij 0) en schrijft nu `kind` + `details`.
- De `CleanupLogWriter` wordt overal als optionele constructorparameter (`= null`) meegegeven, zodat
  de bestaande unit-tests van die vier klassen ongewijzigd blijven werken en Spring 'm in productie
  gewoon injecteert.
- `runtime/package-info.java` kreeg `"maintenance :: repositories"` in `allowedDependencies`; er
  ontstaat geen cyclus omdat `maintenance` alleen van `config` afhangt.
  `docs/technical/module-dependencies.md` is met `tools/generate-module-dependencies` hergenereerd.

### 3. Bridge + scherm

- `kind` is doorgezet via `BridgeApiController` (`@RequestParam kind`) → `maintenance.cleanupsList`
  (bestaand `when`-blok, geen nieuw blok) → `DashboardQueries.maintenanceCleanups(project, kind)` →
  repository.
- De view-modellen zijn generiek geworden (`kind`, nullable `project`, `itemsDeleted`/`itemsKept`);
  het detail houdt de release/package-uitsplitsing als extra velden, gelezen uit `details`.
- `maintenance_screen.dart` is stateful geworden voor het soort-filter: de keuze gaat als
  `?kind=`-queryparam mee en een `ValueKey` op de `DataScreen` forceert een herlaadbeurt. Per rij een
  `kind`-badge; het project verdwijnt uit de regel als het leeg is. Het detailscherm toont de
  release/package-uitsplitsing en de opsommingen alleen voor `github-releases` — de andere soorten
  hebben niets uit te splitsen.
- Navigatielabel is `Opruimen` (`app_shell.dart` + de exacte labellijst in `app_shell_test.dart`).

### 4. Tests (zelf geschreven, onderdeel van dit ontwikkelwerk)

- `AgentRunRetentionPollerTest` (9): cutoff, batching tot de eerste deel-batch, begrenzing op
  `maxBatchesPerRun`, `enabled=false` doet niets, fout ⇒ poller valt niet om én de fout landt in de
  opruim-log, geslaagde ronde met verwijderingen landt in de log, env-defaults en clamping.
- `AgentRunRetentionRepositoryTest` (5, Testcontainers): de SQL-veiligheidsregels — verlopen
  afgeronde run weg, verse run blijft, lopende run blijft ongeacht leeftijd, run met
  PENDING/IN_PROGRESS/FAILED_RETRYABLE-completion blijft (COMPLETED/FAILED_PERMANENT niet),
  batchgrootte begrenst, en events/completions gaan mee via de cascade.
- `CleanupLogWriterTest` (5): de schrijfregel (0 zonder fout ⇒ geen rij; verwijderingen ⇒ rij; fout
  zonder verwijderingen ⇒ rij) en het fail-soft gedrag van een klappende insert.
- `MaintenanceCleanupRunRepositoryTest` uitgebreid naar het gegeneraliseerde model: alle vijf soorten
  incl. NULL-project, `error`, `details`, filteren op `kind` en op `kind`+`project` samen.
- `MaintenanceCleanupRunMigrationTest` (3, Testcontainers): migreert bewust eerst tot V30, schrijft
  een rij in de oude vorm en draait daarna de rest — bewijs voor AC5 dat een bestaande rij als
  `github-releases` terugkomt mét zijn tags/package-versies en uitsplitsing.
- `MaintenanceCleanupSchedulerTest` groen gehouden op het nieuwe model.
- Frontend: `maintenance_screen_test.dart` uitgebreid met de kind-badge, de generieke aantallen, het
  filter (default "alle soorten" zonder `kind=`-param; kiezen ⇒ `?kind=agent-runs` én een andere
  lijst) en een factory-brede ronde zonder project/uitsplitsing. `app_shell_test.dart` bijgewerkt.

### 5. Documentatie

- `docs/factory/technical-spec.md`: de vier `SF_AGENT_RUN_RETENTION_*`-vars én de tot nu toe
  ontbrekende `SF_AGENT_EVENT_RETENTION_*` in de config-sectie, plus de maintenance-sectie herschreven
  naar de gedeelde opruim-log (kinds, schrijfregel, retentie, leespad met `kind`-param).
- `docs/technical/scheduled-jobs.md`: nieuwe sectie 8 over de twee agent-retentie-pollers en het
  wegschrijven in de opruim-log; sectie 5 en 7 bijgewerkt.
- `runbook.md`: triage-alinea's voor de agent-run-retentie en voor de gedeelde opruim-log.
- `docs/factory/ux/screen-map.md`: nav-opsomming en de routetabelregel voor `Opruimen`.
- `docs/ontwerp-bridge-dashboard.md`: `maintenance.cleanupsList` met de `kind`-parameter.
- `properties.default.env`: de vier nieuwe defaults.

## Bewijs

- `mvn -B --no-transfer-progress clean verify` vanaf de repo-root (04-08-2026, ~4m42):
  **BUILD SUCCESS**, exitcode 0. `softwarefactory` 763 unit-tests + 78 e2e/failsafe-tests,
  `agentworker` 61, `dashboard-backend` 57 — overal 0 failures en 0 errors.
- `flutter analyze`: `No issues found!` (~7s); `flutter test`: **121 tests, all tests passed**.
- `tools/generate-module-dependencies --check`: "Moduledependency-metadata en documentatie zijn
  actueel" (na hergenereren vanwege de nieuwe `runtime → maintenance`-richting).

### Openstaand punt: quality-ratchet-fingerprints

`./quality/run.sh` geeft `ok:false` met precies twee "nieuwe" bevindingen — en tegelijk
`resolved: 2` op dezelfde twee bestanden en dezelfde regel:

- `TooManyFunctions` op `core/contracts/RunRepositories.kt`
- `TooManyFunctions` op `orchestrator/repositories/RunRepositories.kt`

Beide klassen stonden al ver over de drempel; door de ene nieuwe methode `deleteOlderThan` verandert
alleen het aantal in de Detekt-boodschap, en daarmee de fingerprint. Er is dus geen nieuwe
overtreding, alleen een verschoven vingerafdruk van een bestaande. De baseline is bewust **niet**
opgerekt (zie `docs/verbeterplan-soepele-stories-2026-07.md`: "écht opgelost i.p.v. baseline
opgerekt"), en de ratchet zit sinds 2026-07-24 bewust niet meer in de CI-mergegate
(`.github/workflows/verify.yml`) en ook niet in `.factory/verification.yaml`. Het opsplitsen van
`AgentRunRepository` valt buiten deze story; de story schrijft `deleteOlderThan` expliciet op de
agent-run-repository voor.
