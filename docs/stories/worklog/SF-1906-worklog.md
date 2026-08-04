# SF-1906 - Worklog

Story-context bij eerste pickup:
Tabellijsten aanvullen (overview.md, runbook.md), V25 in technical-spec.md en asked-triageregel in runbook.md

Alleen documentatie; geen code, migratie of gedragswijziging.

1) docs/technical/overview.md (§Dataopslag, :102-115): tel eerst de werkelijke tabellen in softwarefactory/src/main/resources/db/migration/ (create table-statements lopen over meerdere regels; een one-line grep telt fout - verwacht 28 over V1..V29). Vul de negen ontbrekende tabellen aan in de bestaande bulletstijl (korte functionele omschrijving per tabel of logische groep): issue_comments en issue_attachments (V15__tracker_issues.sql); project_key_sequences, agent_run_completions, agent_run_completion_steps, agent_run_completion_requeues, agent_run_usage_applications (V16__durable_agent_completion.sql); audit_project_settings (V24); audit_question (V26). Breid de audit-regel op :113 uit met audit_project_settings en audit_question, zodat die niet langer tegenspreekt wat :88-90 over per-project starttijd en de blokkerende audit-vraag zegt.

2) docs/factory/technical-spec.md: voeg in de bestaande migratie-opsomming rond :366-370 (V21-V24) een regel toe voor V25__audit_run_job_kind.sql: voegt audit_run_job.kind (TEXT NOT NULL DEFAULT 'scheduled') toe zodat "Run now" een handmatige audit achter een al lopende run kan zetten. Zelfde bulletvorm en detailniveau als de omliggende regels. Bron: docs/technical/scheduled-jobs.md §4 (r106-147).

3) runbook.md (§Database, :116-121): vul de opsomming 'Belangrijke tabellen' aan met minimaal telegram_threads, audit_project_settings, audit_question, issue_comments, issue_attachments, agent_knowledge, processed_comments en system_state. Het runbook blijft een operationele subset; geen volledig schemaoverzicht.

4) runbook.md (§Veelvoorkomende taken / troubleshooting, :133-164): voeg een bullet toe in de bestaande stijl '- **<situatie>:** <wat te doen>' voor auditstatus asked, die dekt: asked is een eindtoestand van de auditjob en geen vastloper (er komt bij die job geen rapport); bewust zo, want een niet-terminale job zou de run nooit laten sluiten en alle audits van alle projecten stilleggen (AuditPlanner); de vraag staat in audit_question en wordt beantwoord via het Audits-scherm in het dashboard (POST /api/v1/audits/questions/answer) of via een reply op de Telegram-melding; na het antwoord plant de factory automatisch een vervolgrun die de audit afmaakt (binnen de volgende scheduler-tick), handmatig herstarten is niet nodig.

Randvoorwaarden: Nederlands, bestaande bulletvorm en regellengte aanhouden; puur aanvullend (geen bestaande correcte tekst verwijderen of van betekenis veranderen, op de uitgebreide audit-regel :113 na); uitsluitend docs/technical/overview.md, docs/factory/technical-spec.md en runbook.md wijzigen. NIET meewijzigen: factory-common/src/main/resources/docs-skeleton/docs/factory/technical-spec.md (generiek bootstrap-sjabloon, geen duplicaat).

Sluit af met een eigen reviewstap: loop de acceptatiecriteria langs (grep-checks op V25 en asked, aanwezigheid van alle genoemde tabelnamen) en controleer met git diff --stat dat alleen de drie .md-bestanden gewijzigd zijn.

## In eigen woorden

Drie documenten beschrijven de database en de audit-triage onvolledig. De tabellijst in
`docs/technical/overview.md` noemde 19 van de 28 tabellen die de Flyway-migraties aanmaken, de
migratie-opsomming in `docs/factory/technical-spec.md` sloeg `V25` over, en `runbook.md` miste
zowel een aantal operationeel relevante tabellen als uitleg over een audit met status `asked`.
Alleen tekst: geen code, geen migratie, geen gedragswijziging.

Stappenplan:
[x]: read issue and target docs
[x]: werkelijke tabellen tellen in `db/migration/` (multiline `CREATE TABLE`)
[x]: overview.md aanvullen tot alle 28 tabellen + audit-regel uitbreiden
[x]: technical-spec.md aanvullen met V25
[x]: runbook.md tabellijst aanvullen + asked-triageregel toevoegen
[x]: acceptatiecriteria nalopen (grep-checks + `git diff --stat`)
[x]: volledig vangnet draaien
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- **Tabellen geteld met een python-regex** over `softwarefactory/src/main/resources/db/migration/`
  (`create table [if not exists] ${schema}.<naam>`), want de statements lopen over meerdere regels
  en `${schema}.` breekt een naïeve regex. Uitkomst: exact 28 unieke tabellen over `V1`–`V29`,
  precies de negen die de story noemt ontbraken. Afwijking t.o.v. de story: `project_key_sequences`
  komt uit `V15__tracker_issues.sql`, niet uit `V16`. Dat raakt de tekst niet (de bullets noemen
  geen migratienummers), dus alleen hier genoteerd.
- **`docs/technical/overview.md`**: drie bullets toegevoegd (`issue_comments`/`issue_attachments`,
  `project_key_sequences`, de vier `agent_run_completion*`/`agent_run_usage_applications`-tabellen)
  en de audit-regel gesplitst in drie bullets, zodat `audit_project_settings` en `audit_question`
  er expliciet in staan. Die regel sprak eerder tegen wat §Pipeline (:88-95) al over per-project
  starttijd en de blokkerende auditvraag zegt. Bestaande tekst is verder ongewijzigd.
- **`docs/factory/technical-spec.md`**: de bestaande `V21`–`V24`-opsomming doorgetrokken naar
  `V25__audit_run_job_kind.sql` (`kind` op `audit_run_job`, `TEXT NOT NULL DEFAULT 'scheduled'`)
  met het doel: "Run now" kan een handmatige audit (`kind = manual`) achter een al lopende run
  zetten. Geverifieerd tegen de migratie zelf en `docs/technical/scheduled-jobs.md` §4.
- **`runbook.md` §Database**: opsomming aangevuld met `issue_comments`, `issue_attachments`,
  `agent_knowledge`, `processed_comments`, `system_state`, `telegram_threads`,
  `audit_project_settings` en `audit_question`. Bewust nog steeds een operationele subset, geen
  volledig schemaoverzicht — dat staat in `docs/technical/overview.md`.
- **`runbook.md` §Troubleshooting**: bullet "Audit staat op `asked`" toegevoegd in de bestaande
  `- **<situatie>:** <wat te doen>`-stijl: eindtoestand en geen vastloper (geen rapport bij die
  job), waarom terminaal (anders sluit de run nooit en vallen alle audits stil, `AuditPlanner`),
  antwoorden via het Audits-scherm (`POST /api/v1/audits/questions/answer`, endpoint geverifieerd
  in `BridgeApiController.kt:468`) of een Telegram-reply, en de automatische vervolgrun binnen de
  volgende scheduler-tick (~30s).

## Verificatie

- Geen code gewijzigd, dus geen nieuwe tests: dit is documentatie zonder runtime-oppervlak.
- Scriptcheck: alle 28 tabelnamen uit de migraties komen als `` `naam` `` voor in
  `docs/technical/overview.md`; alle acht gevraagde namen komen voor in `runbook.md`.
- `grep -c 'V25' docs/factory/technical-spec.md` → 1; `grep -c -i 'asked' runbook.md` → 1.
- `git diff --stat`: uitsluitend `docs/factory/technical-spec.md`, `docs/technical/overview.md` en
  `runbook.md` (plus dit worklog); geen wijziging in `softwarefactory/`, `dashboard-*`,
  `agentworker/`, `factory-common/`, `db/migration/` of build-/configbestanden.
- `mvn -B --no-transfer-progress clean verify` vanaf de repo-root: **BUILD SUCCESS**, exitcode 0,
  0 failures / 0 errors over alle vijf modules (04:19 min, 04-08-2026).
- `tools/audit-documentation`: `documentation-audit/v1: PASS`, exitcode 0.
- Geen `docs/factory/`-spec aangepast bovenop de gevraagde wijziging: `technical-spec.md` is juist
  het doelwit van punt (2); `functional-spec.md` en de UX-docs raken deze story niet, want er
  verandert geen gedrag en geen scherm.

## Review (SF-1907, 04-08-2026)

Beoordeeld: volledige story-diff `git diff main...HEAD` (3 `.md`-bestanden + dit worklog).

- AC1/AC2 — eigen telling over `db/migration/*.sql` (python-regex, multiline + `${schema}.`):
  exact **28** unieke tabellen over `V1`–`V29`; machinale check dat elke naam als `` `naam` `` in
  `docs/technical/overview.md` voorkomt → 0 ontbrekend. De audit-regel noemt nu ook
  `audit_project_settings` en `audit_question` en spreekt §Pipeline (:88-95) niet meer tegen.
- AC3 — `V25__audit_run_job_kind.sql`-regel geverifieerd tegen de migratie zelf
  (`kind TEXT NOT NULL DEFAULT 'scheduled'`) en tegen `AuditRunKind.MANUAL = "manual"`
  (`AuditRepositories.kt:90-93`). Klopt.
- AC4 — triageregel inhoudelijk nagelopen tegen de code: `AuditJobStatus.ASKED` is terminaal
  (`AuditRepositories.kt:113`), rationale komt overeen met de KDoc/`V26`-commentaar,
  `POST /api/v1/audits/questions/answer` bestaat (`BridgeApiController.kt:468`, frontend
  `audit_screen.dart:708`), `AuditScheduler.answerQuestion:107-115` plant via `startManualAudit`
  de vervolgrun, en de tick-default is 30 s (`AuditScheduler.kt:51`, `sf.audit.tick-ms:30000`).
  De "~30s" in het runbook is dus correct.
- AC5 — alle acht gevraagde tabelnamen staan in `runbook.md`.
- AC6 — `git diff main...HEAD --stat`: alleen `docs/factory/technical-spec.md`,
  `docs/technical/overview.md`, `runbook.md` en dit worklog. Niets in `softwarefactory/`,
  `dashboard-*`, `agentworker/`, `factory-common/` (ook het docs-skeleton-sjabloon is ongemoeid)
  of build-/configbestanden.
- AC7 — diff is puur aanvullend; alleen de audit-bullet is gesplitst/uitgebreid, geen bestaande
  bewering verwijderd of van betekenis veranderd. Regellengte blijft ≤ 100 kolommen.
- Bewijs — developer-vangnet (`mvn clean verify` groen, 0 failures/0 errors) staat in de
  issue-comment; gerichte hercontrole door reviewer: `tools/audit-documentation` →
  `documentation-audit/v1: PASS`, exitcode 0. Dat is het enige verificatiecommando zonder
  `pathPrefixes` en dus het relevante gate voor deze docs-only diff.

Bevindingen: geen blockers. Eén [suggestie] (niet blokkerend): in `technical-spec.md` staat nu
tweemaal "en" in dezelfde opsomming (`… V23 …, en V24 … en V25 …`); een komma vóór `V24` leest
prettiger. Besluit: akkoord.

## Test (SF-1908, 04-08-2026)

Story-brede test op branch `ai/SF-1906`. Docs-only diff, geen runtime-oppervlak; verificatie is
documentair + een gerichte controle van elke feitelijke claim tegen de bron in de code/migraties.

- **AC1** — onafhankelijke telling met een eigen python-regex over
  `softwarefactory/src/main/resources/db/migration/*.sql` (multiline `CREATE TABLE`, `${schema}.`-
  prefix, `IF NOT EXISTS`): **28 statements, 28 unieke tabellen** over `V1`–`V29`. Machinale check
  dat elke naam als `` `naam` `` in `docs/technical/overview.md` staat → **0 ontbrekend**. De negen
  eerder ontbrekende tabellen staan er nu met een functionele omschrijving in de bestaande stijl.
- **AC2** — de audit-bullets noemen nu `audit_settings`, `audit_run`, `audit_run_job`,
  `audit_report`, `audit_project_settings` én `audit_question`. Omschrijvingen kloppen tegen de
  bron: `V24` heeft `start_time`/`audit_count DEFAULT 1` met terugval op `audit_settings`, `V26`
  heeft `question`/`findings`/`answer`/`answered_at`/`consumed_at`.
- **AC3** — `grep -c 'V25' docs/factory/technical-spec.md` → **1**. De regel klopt letterlijk tegen
  `V25__audit_run_job_kind.sql`: `ADD COLUMN IF NOT EXISTS kind TEXT NOT NULL DEFAULT 'scheduled'`,
  doel "Run now" achter een lopende run.
- **AC4** — `grep -c -i 'asked' runbook.md` → **1**. Alle vier claims van de triageregel
  geverifieerd tegen de code:
  - eindtoestand: `AuditJobStatus.isTerminal` (`AuditRepositories.kt:112-113`) bevat `asked`;
    `AuditPlanner.kt:105-106` zet de job direct op `MarkJobTerminal(..., ASKED, null, null)` → geen
    rapport bij die job.
  - rationale: `AuditPlanner.kt:120` sluit de run pas als `allTerminal`; `V26`-commentaar en
    `AuditRepositories.kt:106` beschrijven exact het stilvallen van alle audits.
  - antwoordroutes: `POST /api/v1/audits/questions/answer` bestaat
    (`BridgeApiController.kt:468`, frontend `audit_screen.dart:708`) en de Telegram-reply loopt via
    `TelegramReplyService.kt:102` → `AuditQuestionAnswering.answerAuditQuestion`.
  - vervolgrun: `AuditScheduler.answerQuestion:107-115` roept `startManualAudit` aan; tick-default
    `sf.audit.tick-ms:30000` (`AuditScheduler.kt:51`) → de "~30s" klopt.
- **AC5** — alle acht gevraagde tabelnamen komen voor in `runbook.md` (geteld met backticks).
- **AC6** — `git diff --stat main...HEAD`: alleen `docs/factory/technical-spec.md`,
  `docs/technical/overview.md`, `runbook.md` en dit worklog. Niets in `softwarefactory/`,
  `dashboard-backend/`, `dashboard-frontend/`, `agentworker/`, `factory-common/` (ook het
  docs-skeleton-sjabloon is ongemoeid), `db/migration/` of build-/configbestanden.
- **AC7** — diff volledig doorgelezen: puur aanvullend. De drie gewijzigde bestaande regels
  behouden hun oorspronkelijke tekst (audit-bullet in `overview.md` gesplitst/uitgebreid,
  runbook-tabellijst uitgebreid met behoud van alle eerder genoemde tabellen, `V24`-zin in
  `technical-spec.md` doorgetrokken naar `V25`). Geen correcte bewering verwijderd of van betekenis
  veranderd.

Vangnet: door de `pathPrefixes` in `.factory/verification.yaml` matcht bij deze docs-only diff
alleen `repository-documentation-audit`; `repository-maven-verify`, de drie `dashboard-flutter-*`-
commando's en `agent-mini-reactor-smoke` zijn out-of-scope, `agent-image-build-stage` is
`agentRunnable: false`. Gedraaid: `tools/audit-documentation` → `documentation-audit/v1: PASS`,
exitcode 0. Geen flakes waargenomen.

Screenshots: niet mogelijk — geen browser, geen `SF_PREVIEW_URL` en geen `/work/screenshots`-map in
de tester-sandbox; deze story heeft bovendien geen UI-oppervlak.

Besluit: **tested**. Eén niet-blokkerende schoonheidsfout blijft staan (de dubbele "en" in de
`V21`–`V25`-opsomming van `technical-spec.md`, ook al door de reviewer gesignaleerd).
