# SF-1361 - Vervang de verouderde "Nightly scheduler (SF-350)"-secties in functional-spec.md en technical-spec.md door een correcte beschrijving van het Audit-systeem

## Story

Vervang de verouderde "Nightly scheduler (SF-350)"-secties in functional-spec.md en technical-spec.md door een correcte beschrijving van het Audit-systeem

<!-- refined-by-factory -->

## Scope

In `docs/factory/functional-spec.md` staat sectie "Nightly scheduler — nachtelijke jobs automatisch draaien (SF-350)" (incl. subsectie "Declaratief config-pad — subtaken uit `subtasks.yaml` (SF-787)") en in `docs/factory/technical-spec.md` sectie "Nightly scheduler (SF-350)" (incl. "Reconciliation-scheduler (SF-352)" en "Declaratief config-pad (SF-787)"). Beide beschrijven een systeem (`NightlyScheduler`/`NightlyPlanner`/`NightlyGateway`/`NightlyJobsReader`/`NightlyDigest`, `/nightly`-dashboardpagina, `/settings`-Nightly-instellingen) dat niet meer bestaat in de code.

Geverifieerd (2026-07-27):
- Geen enkele `Nightly*`-klasse meer in `softwarefactory/src/main/kotlin` (alleen nog in oude `V11`–`V14`-migratienamen en een testfixture-comment); vervangen door het `audit`-package: `AuditScheduler`, `AuditPlanner`, `AuditGateway`/`AuditGatewayAdapter`, `AuditJobsReader`, migraties `V21__audit_jobs.sql` t/m `V24__audit_project_settings.sql`.
- Frontend heeft geen `/nightly`-scherm meer; `app_shell.dart` heeft een navigatie-item "Audits" → `AuditScreen` (`dashboard-frontend/lib/screens/audit_screen.dart`). Geen "Nightly scheduler"-sectie meer op het settings-scherm.
- `.factory/nightly/README.md` bevat al een correcte, actuele beschrijving van het huidige audit-gedrag (per-project max 1 audit/nacht om 08:00, read-only, rapport + optioneel 1 vervolgstory-voorstel, `job.yaml` + `prompt.md` per audit).

Taak: vervang beide verouderde secties (functional-spec.md secties rond regel 265–322, technical-spec.md secties rond regel 285–388 — exacte regelnummers kunnen tijdens implementatie verschoven zijn, zoeken op de kopteksten) door een korte, correcte beschrijving van het huidige audit-systeem, en/of verwijs naar `.factory/nightly/README.md` als single source of truth voor het `.factory/nightly/<audit>/`-configuratieformaat. Puur documentatie, geen gedragswijziging — geen code, migraties, of frontend wijzigen.

## Acceptance criteria

- De sectie "Nightly scheduler — nachtelijke jobs automatisch draaien (SF-350)" in `functional-spec.md` (incl. de subsectie over `subtasks.yaml`/SF-787) is verwijderd of vervangen door een korte, feitelijk correcte beschrijving van het huidige audit-systeem (scheduler-cadans, 1 audit/project/nacht, read-only, rapport + optioneel vervolgstory-voorstel, `/`-navigatie-item "Audits").
- De sectie "Nightly scheduler (SF-350)" in `technical-spec.md` (incl. "Reconciliation-scheduler (SF-352)" en "Declaratief config-pad (SF-787)") is verwijderd of vervangen door een korte, feitelijk correcte technische beschrijving (`AuditScheduler`/`AuditPlanner`/`AuditGateway`/`AuditJobsReader`, migraties V21–V24, `.factory/nightly/<audit>/job.yaml`+`prompt.md`).
- Geen enkele resterende verwijzing in beide bestanden naar niet-bestaande klassen/tabellen/routes (`NightlyScheduler`, `NightlyPlanner`, `NightlyGateway`, `NightlyJobsReader`, `NightlyDigest`, `nightly_settings`, `nightly_run`, `nightly_run_job`, `/nightly`-pagina, `/settings`-Nightly-instellingen, `subtasks.yaml`-config-pad) tenzij expliciet als historie/migratie-context gemarkeerd.
- `.factory/nightly/README.md` mag als verwijzing/single source of truth worden gebruikt in plaats van het volledig herschrijven van alle details.
- Geen andere secties in beide bestanden worden inhoudelijk gewijzigd; geen code-, migratie- of frontendbestanden worden aangepast.
- Overige nightly-verwijzingen elders in `functional-spec.md` (bv. rond regel 83, over `createNightlyStory`) blijven buiten scope van deze story tenzij ze zelf ook feitelijk onjuist blijken te zijn over het huidige (audit-)systeem.

## Aannames

- "Vervang door een correcte beschrijving" mag zowel een volledige feitelijke sectie zijn als een korte verwijzing naar `.factory/nightly/README.md`, zolang de lezer daarna niet meer op het verouderde Nightly-beeld uitkomt.
- De historie-alinea in `.factory/nightly/README.md` ("de oude NightlyScheduler/NightlyJobsReader-machinery... is uitgezet, niet verwijderd") is voldoende contextuele nuance; de developer hoeft niet zelf verder in de code te bevestigen dat er geen actieve Nightly-scheduler-bean meer draait — dat is al bevestigd tijdens deze refine.
- Regelnummers van de te vervangen secties kunnen tijdens implementatie licht zijn opgeschoven t.o.v. deze refine (2026-07-27); de developer zoekt op de kopteksten ("Nightly scheduler", "Reconciliation-scheduler", "Declaratief config-pad") in plaats van blind op regelnummer.

## Eindsamenvatting

{"agent_tips_update":[]}
{"phase":"summarized"}
