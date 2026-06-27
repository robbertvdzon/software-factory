# SF-350 - Worklog

Story-context bij eerste pickup:
Persistentie & schrijfbare nightly-settings

Flyway-migratie V<n>__nightly_scheduler.sql met tabellen nightly_settings (enabled, start_time, summary_time), nightly_run (id, run_date, started_at, ended_at, status, summary_sent_at) en nightly_run_job (id, run_id, project, job_name, title, status, story_key, started_at, ended_at, error), volgens bestaande V<n>__desc.sql-conventie. JdbcTemplate-repositories conform RunRepositories.kt: NightlySettingsRepository (single-row read/write, defaults enabled=false, start 02:00, summary 07:00) en NightlyRunRepository/NightlyRunJobRepository (CRUD + queries lopende/laatste run per run_date, jobs per run/project, statusupdates). Timezone-utility met java.time + ZoneId Europe/Amsterdam, DST-correct, injecteerbaar via Clock, voor NL->UTC vergelijking. /settings-scherm uitbreiden met schrijfbaar formulier (checkbox + twee HH:MM-velden + opslaan) en nieuw POST-endpoint dat de store wegschrijft; bestaande read-only secrets/versie-info blijft. Unittests voor timezone-conversie (DST, vaste klok) en settings-repository round-trip horen bij deze dev-stap. Sluit af met de ingebouwde review-stap.

Stappenplan:
[ ]: read issue and target docs
[ ]: implement requested changes
[ ]: run relevant tests
[ ]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
