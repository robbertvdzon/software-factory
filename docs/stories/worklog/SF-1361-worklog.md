# SF-1361 - Worklog

Story-context bij eerste pickup:
Vervang verouderde Nightly-scheduler-secties door audit-beschrijving

Vervang in docs/factory/functional-spec.md de sectie 'Nightly scheduler - nachtelijke jobs automatisch draaien (SF-350)' (incl. subsectie 'Declaratief config-pad - subtaken uit subtasks.yaml (SF-787)') en in docs/factory/technical-spec.md de sectie 'Nightly scheduler (SF-350)' (incl. 'Reconciliation-scheduler (SF-352)' en de 'Declaratief config-pad (SF-787)'-alinea) door een korte, feitelijk correcte beschrijving van het huidige audit-systeem: per project max 1 audit/nacht (08:00), read-only, rapport + optioneel 1 vervolgstory-voorstel, 'Audits'-navigatie-item -> AuditScreen (functional-spec.md); AuditScheduler/AuditPlanner/AuditGateway/AuditGatewayAdapter/AuditJobsReader, migraties V21__audit_jobs.sql t/m V24__audit_project_settings.sql, .factory/nightly/<audit>/job.yaml+prompt.md (technical-spec.md). Verwijs naar .factory/nightly/README.md als single source of truth voor het configuratieformaat i.p.v. alles te herhalen. Zoek op de kopteksten (regelnummers kunnen verschoven zijn). Verwijder alle resterende referenties naar NightlyScheduler, NightlyPlanner, NightlyGateway, NightlyJobsReader, NightlyDigest, nightly_settings, nightly_run, nightly_run_job, de /nightly-pagina, de Nightly-sectie op /settings en het subtasks.yaml-config-pad, tenzij expliciet als historie/migratie-context gemarkeerd. Raak geen andere secties van beide bestanden inhoudelijk aan (incl. de createNightlyStory-verwijzing rond regel 83 van functional-spec.md, tenzij die zelf ook feitelijk onjuist blijkt) en wijzig geen code-, migratie- of frontendbestanden.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Geverifieerd dat er geen `Nightly*`-klassen meer in `softwarefactory/src/main/kotlin` bestaan
  (alleen nog migratienamen `V11`–`V14`); het `audit`-package (`AuditScheduler`, `AuditPlanner`,
  `AuditGateway`/`AuditGatewayAdapter`, `AuditJobsReader`, migraties `V21`–`V24`) is de huidige
  vervanging. Frontend heeft geen `/nightly`-pagina meer, wel navigatie-item "Audits" →
  `AuditScreen`.
- `docs/factory/functional-spec.md`: sectie "Nightly scheduler — nachtelijke jobs automatisch
  draaien (SF-350)" + subsectie "Declaratief config-pad — subtaken uit `subtasks.yaml` (SF-787)"
  vervangen door een korte sectie "Audits — nachtelijke read-only agent-runs" (scheduler-cadans,
  1 audit/project/nacht default, read-only, rapport + optioneel 1 vervolgstory-voorstel,
  "Audits"-navigatie-item), met verwijzing naar `.factory/nightly/README.md` als single source of
  truth voor het configuratieformaat. Ook de losstaande verwijzing rond regel 83
  (`DashboardCommandService.createNightlyStory`, silent=true) is aangepast: die klopte niet meer
  met het huidige audit-vervolgstory-gedrag (`AuditGatewayAdapter.proposeStoryIfAny`,
  `questionsAllowed = true`, `StoryPhase.START_NEXT`, dus juist géén silent story) — dit is een
  feitelijke correctie zoals expliciet toegestaan in de story-aannames, geen scope-uitbreiding.
- `docs/factory/technical-spec.md`: sectie "Nightly scheduler (SF-350)" (incl.
  "Reconciliation-scheduler (SF-352)" en "Declaratief config-pad (SF-787)") vervangen door een
  korte sectie "Audit-systeem" met `AuditScheduler`/`AuditPlanner`/`AuditGateway`/
  `AuditJobsReader`, migraties `V21__audit_jobs.sql` t/m `V24__audit_project_settings.sql` en het
  `.factory/nightly/<audit>/job.yaml`+`prompt.md`-configuratiepad (verwijzing naar
  `.factory/nightly/README.md`). Resterende `Nightly*`/`nightly_*`-vermeldingen in beide bestanden
  staan alleen nog in expliciete historie-/negatiecontext ("bestaat niet meer", "geen aparte
  `/nightly`-pagina meer").
- Geen andere secties van beide bestanden inhoudelijk gewijzigd; geen code-, migratie- of
  frontendbestanden aangepast (documentatie-only story).
- `mvn verify` vanaf de repo-root uitgevoerd (2026-07-27): BUILD SUCCESS, alle modules SUCCESS
  (factory-contracts, factory-common, softwarefactory, agentworker, softwarefactory-dashboard-backend),
  dashboard-backend-tests: 47 tests, 0 failures, 0 errors, 0 skipped. Totale tijd ~3:48 min.

## Test (SF-1363, 2026-07-27)

- `git diff main...HEAD --stat`: alleen `docs/factory/functional-spec.md`,
  `docs/factory/technical-spec.md` en dit worklog gewijzigd — geen code/migratie/frontendbestanden.
- Elke feitelijke claim in de nieuwe secties gegrept tegen de huidige code: `AuditScheduler`/
  `AuditPlanner`/`AuditGateway`/`AuditJobsReader` bestaan in `nl.vdzon.softwarefactory.audit`
  (services-subpackage), `AuditGatewayAdapter` in `dashboard/services`; `sf.audit.tick-ms` (default
  30000) klopt met `AuditScheduler.kt:48`; `proposeStoryIfAny` (`AuditGatewayAdapter.kt:227-238`)
  gebruikt inderdaad `questionsAllowed = true` + `StoryPhase.START_NEXT`; migraties
  `V21__audit_jobs.sql` t/m `V24__audit_project_settings.sql` bestaan; frontend-navigatie "Audits" →
  `AuditScreen` (`app_shell.dart:47`); geen "Nightly" meer in `settings_screen.dart`.
  Resterende "Nightly"-vermeldingen in `technical-spec.md` staan uitsluitend in expliciete
  historie-/negatiecontext ("bestaat niet meer", "geen aparte /nightly-pagina meer") — conform AC.
- `bash tools/audit-documentation` (repository-documentation-audit, geen pathPrefixes dus altijd
  in scope): `documentation-audit/v1: PASS`, exit 0.
- `repository-maven-verify` en `dashboard-flutter-*` zijn conform `.factory/verification.yaml`
  out-of-scope voor deze docs-only diff (geen overlap met hun `pathPrefixes`); de developer heeft
  `mvn verify` al bevestigd groen op deze branch (zie hierboven).
- Conclusie: alle acceptatiecriteria voldaan, geen code-/gedragswijziging, verwijzingen kloppen
  tegen de huidige code. Groen.
