# Technical Spec

## Stack

- Kotlin
- JDK 21
- Spring Boot
- Maven
- Flyway voor DB-migraties
- Postgres, remote via Neon of lokaal via Docker
- Docker Engine voor agent-containers

## Config

Alle eigen environment variables beginnen met `SF_`. De loader leest eerst
`./secrets.env` en valt per key terug op system environment variables.

Verplichte keys:

- `SF_YOUTRACK_BASE_URL`
- `SF_YOUTRACK_TOKEN`
- `SF_GITHUB_TOKEN`
- `SF_DATABASE_URL`
- `SF_DATABASE_SCHEMA`

`SF_DATABASE_URL` bepaalt welke Postgres gebruikt wordt. Thuis kan dit Neon
zijn; op werk kan dit de lokale Docker Postgres uit `docker-compose.yml` zijn.

`SF_DATABASE_SCHEMA` moet een geldige Postgres identifier zijn. Gebruik nooit
het schema `factory`; dat schema bestaat al in de gedeelde Neon database en
hoort bij een ander systeem. Gebruik voor branches/stories een eigen schema,
bijvoorbeeld `software_factory_dev` of `software_factory_sf_020`.

Lokale Postgres starten:

```bash
./factory local-db
```

Standaard URL voor `secrets.env`:

```env
SF_DATABASE_URL=postgresql://software_factory:software_factory@localhost:5432/software_factory
SF_DATABASE_SCHEMA=software_factory_dev
```

Orchestrator tuning gebruikt ook `SF_` env-vars. Defaults:

- Polling staat altijd aan zodra de applicatie draait.
- `SF_POLL_INTERVAL_MS=15000`
- `SF_MAX_PARALLEL_REFINER=1`
- `SF_MAX_PARALLEL_DEVELOPER=2`
- `SF_MAX_PARALLEL_REVIEWER=2`
- `SF_MAX_PARALLEL_TESTER=1`
- `SF_MAX_PARALLEL_TOTAL=4`
- `SF_MAX_DEVELOPER_LOOPBACKS=5`
- `SF_MAX_TEST_CHAIN_RESETS=3`
- `SF_MAX_TRANSIENT_RETRIES=2`
- `SF_AGENT_HARD_TIMEOUT_MINUTES=60`

## Per-project config (`projects.yaml`)

Naast `repo` en `deploy` kent een project de optionele vlag `manualApprove`
(boolean, default `true`). Die schakelt de handmatige goedkeur-poort (een vaste
`manual-approve`-subtaak vlak vóór de merge) per project aan/uit; alleen een expliciete
`manualApprove: false` zet 'm uit. Gelezen via `ProjectRepoResolver.manualApproveFor(...)`.

De MERGE-subtaak is niet meer configureerbaar: hij merget bij fase START altijd automatisch
de PR via de GitHub API (`MergeSubtaskHandler.performAutomaticMerge`). Er is geen `merge.mode`
of handmatige merge-poort meer; een merge-conflict of GitHub-fout zet de subtaak op Error en
stopt de keten (geen `AWAITING_HUMAN`). De handmatige goedkeuring zit volledig in de
voorafgaande `manual-approve`-subtaak.

De documentatie-stap (`documentation`-subtaak, rol DOCUMENTER, SF-213) is daarentegen altijd aan
en niet per project uit te zetten. Die wordt afgedwongen ná de planner-subtaken en vóór de
manual-approve-poort; volledige ketenvolgorde:
`development → review → test → summary → documentation → manual-approve → merge → deploy`.

## YouTrack custom fields

De factory garandeert haar custom fields via schema-bootstrap (`YouTrackClient.factoryFieldSpecs`).
Enum-booleans worden gemodelleerd als een `enum[1]`-veld met waarden `false`/`true` en uitgelezen als
`SingleEnumIssueCustomField`. Voorbeelden: `Paused` en — sinds SF-335 — `Silent` (default `false`).
`Silent` staat op story-niveau; subtaken lezen de waarde van hun parent-story (best-effort
parent-lookup), net als `Auto-approve`. De gedeelde helper `YouTrackApi.effectiveSilent(issue)` bepaalt
"effectief silent" (eigen veld óf parent) zodat coördinatoren, notificaties en dashboard dezelfde
beslissing nemen. Clarification-errors (uit `*-with-questions` bij silent) worden in de error-tekst
gemarkeerd met `ErrorCategory.CLARIFICATION` (`[CLARIFICATION]`), onderscheidbaar van technische errors.

## Nightly scheduler (persistentie, SF-350/SF-351)

De nachtelijke scheduler bouwt voort op drie tabellen (Flyway-migratie
`V11__nightly_scheduler.sql`):

- `nightly_settings` — enkele rij (`id = 1`) met de master-switch `enabled` en de
  `start_time`/`summary_time` als `HH:MM` in lokale NL-tijd. Defaults: `enabled = false`,
  start `02:00`, summary `07:00`. Beheerd via `NightlySettingsRepository`.
- `nightly_run` — één run per kalenderdag (`run_date` in NL-tijd, uniek) met
  `status` (`pending`/`running`/`ended`), `started_at`/`ended_at` en `summary_sent_at`
  (idempotentie-borg voor de digest). Beheerd via `NightlyRunRepository`.
- `nightly_run_job` — per run en project de job-queue met `status`
  (`pending`/`running`/`done`/`failed`), `story_key`, tijden en `error`. Beheerd via
  `NightlyRunJobRepository`.

Tijden staan in lokale NL-tijd; `NightlyTime` (`ZoneId.of("Europe/Amsterdam")`,
DST-correct, injecteerbaar via `Clock`) rekent ze DST-correct naar UTC voor vergelijking
met de UTC-factory-klok en leidt de NL-`run_date` af. De reconciliation-loop, completion-
detectie en digest die op deze fundering draaien horen bij SF-352.

## Ontwerpregels

- Orchestrator-state blijft idempotent en herstelbaar.
- YouTrack blijft de zichtbare workflow-bron voor gebruiker en agents.
- Postgres is de bron voor run history, event logging en agent knowledge.
- Agents werken in tijdelijke clones en schrijven alleen via hun toegestane rol.
- Gebruik kleine, gerichte tests rond state-machine, config en adapters.
