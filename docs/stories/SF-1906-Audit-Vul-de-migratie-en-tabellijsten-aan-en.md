# SF-1906 - [Audit] Vul de migratie- en tabellijsten aan en voeg een runbook-triageregel toe voor auditstatus `asked`

## Story

[Audit] Vul de migratie- en tabellijsten aan en voeg een runbook-triageregel toe voor auditstatus `asked`

<!-- refined-by-factory -->

## Samenvatting

Drie documenten beschrijven de database en de audit-triage onvolledig.

De tabellijst in de technische documentatie noemt 19 tabellen terwijl er 28 zijn, de technische spec slaat één migratie over, en het runbook mist zowel een aantal tabellen als uitleg over een audit die op een menselijk antwoord wacht.

Een operator die een audit met de status `asked` ziet, kan nu nergens teruglezen dat die run niet is vastgelopen maar bewust op een antwoord wacht, en waar dat antwoord gegeven wordt.

Deze story vult die tekst aan. Er verandert niets aan het gedrag van de software en er komt geen databasewijziging.

## Scope

Alleen documentatie. Geen code, geen migratie, geen configuratie, geen gedragswijziging.

**(1) `docs/technical/overview.md` — tabelopsomming (:102-115)**

Vul de negen ontbrekende tabellen aan, zodat de lijst de 28 tabellen dekt die de migraties aanmaken:

- `issue_comments`, `issue_attachments` (`V15__tracker_issues.sql`)
- `project_key_sequences`, `agent_run_completions`, `agent_run_completion_steps`, `agent_run_completion_requeues`, `agent_run_usage_applications` (`V16__durable_agent_completion.sql`)
- `audit_project_settings` (`V24__audit_project_settings.sql`)
- `audit_question` (`V26__audit_questions.sql`)

Neem `audit_project_settings` en `audit_question` expliciet op in de audit-regel (:113), die nu alleen `audit_settings`, `audit_run`, `audit_run_job` en `audit_report` noemt — terwijl :88-90 van hetzelfde bestand de features die op die twee tabellen draaien (per project instelbare starttijd, de blokkerende audit-vraag) al beschrijft. Houd de bestaande stijl aan: per tabel (of logische groep) één bullet met een korte functionele omschrijving.

**(2) `docs/factory/technical-spec.md` — ontbrekende migratie V25**

Voeg in de bestaande migratie-opsomming rond :368-372 (`V21`–`V24`) een regel toe voor `V25__audit_run_job_kind.sql`: die voegt `audit_run_job.kind` (`TEXT NOT NULL DEFAULT 'scheduled'`) toe, zodat "Run now" een handmatige audit achter een al lopende run kan zetten. Zelfde bulletvorm en detailniveau als de omliggende V21-V24-regels. Bron: `docs/technical/scheduled-jobs.md` §4.

**(3) `runbook.md` — tabelopsomming (:116-121)**

Vul de opsomming "Belangrijke tabellen" aan met minimaal: `telegram_threads`, `audit_project_settings`, `audit_question`, `issue_comments`, `issue_attachments`, `agent_knowledge`, `processed_comments` en `system_state`. Het runbook noemt bewust alleen de operationeel relevante tabellen; een volledige 28-tabelslijst is hier niet vereist.

**(4) `runbook.md` — triageregel voor auditstatus `asked`**

Voeg onder "Veelvoorkomende taken / troubleshooting" (:133-164) één bullet toe in de bestaande stijl (`- **<situatie>:** <wat te doen>`) die dekt:

- `asked` is een **eindtoestand** van de auditjob, geen vastloper: de auditor kon niet verder zonder menselijke beslissing en eindigde met een vraag in plaats van een rapport. Er komt bij die job géén rapport.
- Dat is bewust zo: bleef de job niet-terminaal, dan zou de run nooit sluiten en zouden alle audits van alle projecten stil komen te liggen (`AuditPlanner`).
- De vraag staat in `audit_question` en wordt beantwoord via het Audits-scherm in het dashboard (`POST /api/v1/audits/questions/answer`) of via een reply op de Telegram-melding.
- Na het antwoord plant de factory automatisch een vervolgrun in die de audit afmaakt (~binnen de volgende scheduler-tick); de operator hoeft niets handmatig te herstarten.

Bron voor (2) en (4): `docs/technical/scheduled-jobs.md` §4 (r106-147), dat actueel is.

## Acceptance criteria

1. `docs/technical/overview.md` somt alle 28 tabellen op die de Flyway-migraties `V1`–`V29` aanmaken; de negen bovengenoemde tabellen komen er nu in voor, elk met een korte functionele omschrijving in de bestaande stijl.
2. De audit-regel in `docs/technical/overview.md` noemt naast `audit_settings`, `audit_run`, `audit_run_job` en `audit_report` ook `audit_project_settings` en `audit_question`, zodat hij niet langer tegenspreekt wat :88-90 over die features zegt.
3. `grep -c 'V25' docs/factory/technical-spec.md` geeft ≥ 1, en de toegevoegde regel benoemt `audit_run_job.kind` met default `'scheduled'` en het doel (handmatige "Run now"-audit naast een lopende run).
4. `grep -c -i 'asked' runbook.md` geeft ≥ 1, en de toegevoegde triageregel vermeldt: eindtoestand (geen vastloper, geen rapport), antwoord via Audits-scherm of Telegram, en automatische vervolgrun na het antwoord.
5. De tabelopsomming in `runbook.md` bevat `telegram_threads`, `audit_project_settings`, `audit_question`, `issue_comments`, `issue_attachments`, `agent_knowledge`, `processed_comments` en `system_state`.
6. Er zijn uitsluitend `.md`-bestanden gewijzigd: `docs/technical/overview.md`, `docs/factory/technical-spec.md` en `runbook.md`. `git diff --stat` toont geen wijziging in `softwarefactory/`, `dashboard-backend/`, `dashboard-frontend/`, `agentworker/`, `factory-common/`, `db/migration/` of build-/configbestanden.
7. Geen enkele bestaande, correcte tekst is verwijderd of van betekenis veranderd; de wijziging is puur aanvullend (op de audit-regel van :113 na, die uitgebreid wordt).

## Aannames

- Alleen tekstaanvulling: er wordt geen migratie toegevoegd, geen code aangepast en geen gedrag gewijzigd. De laatste migratie op deze checkout blijft `V29__notify_mode_creation_default.sql`.
- De telfout "V1-V17" in `docs/technical/overview.md:99-100` is al gerepareerd (staat nu correct op "V1–V17 legde de basis … tot en met V29") en hoort niet meer bij deze story.
- Het runbook blijft een operationeel document: bij punt (3) wordt de opsomming aangevuld met de genoemde tabellen, niet omgebouwd tot een volledig schemaoverzicht. De volledige lijst hoort in `docs/technical/overview.md`.
- De sjabloonkopie `factory-common/src/main/resources/docs-skeleton/docs/factory/technical-spec.md` is een generiek 10-regelig bootstrap-sjabloon en géén duplicaat van het echte `docs/factory/technical-spec.md`; die wordt dus niet meegewijzigd.
- Taal en stijl volgen de repo-conventie: Nederlands, bestaande bulletvorm en regellengte van het omliggende document.
- Verificatie is documentair (grep-checks uit de acceptatiecriteria plus lezen); een build- of testrun is voor deze wijziging niet nodig, maar breekt er ook niet door.

## Eindsamenvatting

Alles gelezen: `.task.md`, het worklog (`docs/stories/worklog/SF-1906-worklog.md`) en de volledige story-diff. Hieronder de eindsamenvatting.

---

# SF-1906 — Migratie- en tabellijsten aangevuld + runbook-triage voor auditstatus `asked`

## Wat is gebouwd

Documentatie-only story: drie beschrijvende documenten liepen achter op de werkelijke database en op het audit-gedrag. Er is niets aan code, migraties, configuratie of gedrag veranderd.

1. **`docs/technical/overview.md` — tabellijst compleet.** De opsomming noemde 19 van de 28 tabellen die de Flyway-migraties `V1`–`V29` aanmaken. De negen ontbrekende zijn toegevoegd met een korte functionele omschrijving in de bestaande bulletstijl: `issue_comments`/`issue_attachments`, `project_key_sequences`, de vier `agent_run_completion*`/`agent_run_usage_applications`-tabellen, `audit_project_settings` en `audit_question`.
2. **`docs/technical/overview.md` — audit-regel rechtgetrokken.** De audit-bullet is gesplitst in drie bullets zodat `audit_project_settings` (per-project starttijd/aantal audits) en `audit_question` (de blokkerende auditvraag) er expliciet in staan. Die regel sprak eerder tegen wat de Pipeline-paragraaf van hetzelfde document al over die twee features zei.
3. **`docs/factory/technical-spec.md` — ontbrekende migratie V25.** De `V21`–`V24`-opsomming is doorgetrokken met `V25__audit_run_job_kind.sql` (`kind` op `audit_run_job`, `TEXT NOT NULL DEFAULT 'scheduled'`), inclusief het doel: "Run now" kan een handmatige audit achter een al lopende run zetten.
4. **`runbook.md` — tabellijst aangevuld.** De operationele opsomming bevat nu ook `telegram_threads`, `audit_project_settings`, `audit_question`, `issue_comments`, `issue_attachments`, `agent_knowledge`, `processed_comments` en `system_state`.
5. **`runbook.md` — triageregel voor auditstatus `asked`.** Nieuwe bullet die uitlegt dat `asked` een eindtoestand is en geen vastloper (bij die job komt geen rapport), waarom dat bewust zo is (een niet-terminale job zou de run nooit laten sluiten en alle audits van alle projecten stilleggen), hoe je antwoordt (Audits-scherm in het dashboard of een reply op de Telegram-melding) en dat de factory daarna zelf een vervolgrun inplant — handmatig herstarten is niet nodig.

## Gemaakte keuzes

- **Tabellen echt geteld, niet overgenomen.** De `CREATE TABLE`-statements lopen over meerdere regels en gebruiken een `${schema}.`-prefix, waardoor een simpele grep verkeerd telt. Met een regex over alle migratiebestanden kwam de telling op exact 28 unieke tabellen over `V1`–`V29` — precies de negen uit de story ontbraken.
- **Correctie op de story-tekst:** `project_key_sequences` komt uit `V15__tracker_issues.sql`, niet uit `V16` zoals de story stelde. Dat raakt de documentatietekst niet (de bullets noemen geen migratienummers) en is alleen genoteerd.
- **Runbook blijft operationeel.** Bewust géén volledig schemaoverzicht van 28 tabellen in het runbook; die volledige lijst hoort in `docs/technical/overview.md`. Het runbook krijgt alleen de operationeel relevante aanvulling.
- **Puur aanvullend.** Geen bestaande, correcte tekst verwijderd of van betekenis veranderd; alleen de audit-bullet is gesplitst en uitgebreid.
- **Sjabloon niet meegewijzigd:** `factory-common/.../docs-skeleton/docs/factory/technical-spec.md` is een generiek bootstrap-sjabloon en geen duplicaat van het echte document.

## Wat is getest

- **Alle feitelijke claims tegen de bron geverifieerd** (door developer, reviewer én tester onafhankelijk): de V25-migratie (`kind TEXT NOT NULL DEFAULT 'scheduled'`, `kind = manual`), `asked` als terminale status, het antwoord-endpoint en de Telegram-route, en de automatische vervolgrun binnen de volgende scheduler-tick (~30s, de geconfigureerde default). Alles klopt met de code.
- **Machinale check:** elke van de 28 tabelnamen komt voor in `docs/technical/overview.md` → 0 ontbrekend; alle acht gevraagde namen staan in `runbook.md`. Grep-checks op `V25` en `asked` gehaald.
- **Vangnet:** `mvn clean verify` over alle vijf modules → BUILD SUCCESS, 0 failures / 0 errors. `tools/audit-documentation` → `documentation-audit/v1: PASS`. Dat laatste is bij deze docs-only diff het enige relevante gate; de Flutter- en smoke-commando's vallen door hun padfilters buiten scope.
- **Scope-check:** `git diff --stat` toont uitsluitend de drie `.md`-bestanden plus het worklog — niets in `softwarefactory/`, `dashboard-*`, `agentworker/`, `factory-common/`, `db/migration/` of build-/configbestanden.
- Alle zeven acceptatiecriteria zijn afgevinkt. Review: akkoord, geen blockers. Test: goedgekeurd.

## Bewust niet gedaan

- Geen code, migratie, configuratie of gedragswijziging; de laatste migratie blijft `V29__notify_mode_creation_default.sql`.
- Geen nieuwe geautomatiseerde tests: er is geen runtime-oppervlak om te testen.
- Geen screenshots: deze story heeft geen UI-oppervlak (en de tester-sandbox had er ook geen faciliteit voor).
- **Eén openstaand schoonheidspuntje**, door reviewer en tester gesignaleerd en niet-blokkerend: in `docs/factory/technical-spec.md` staat nu tweemaal "en" in dezelfde opsomming (`… V23 …, en V24 … en V25 …`); een komma vóór `V24` zou prettiger lezen. Bewust niet gefixt om de diff strikt aanvullend te houden — kan mee met een volgende docs-aanpassing.

<!-- deploy-summary:start -->
De handleiding en de technische beschrijving van de factory zijn bijgewerkt, zodat ze weer kloppen met hoe het systeem er nu echt uitziet. Nieuw is een korte uitleg over wat je moet doen als een audit op een vraag staat te wachten: dat is geen storing, maar een audit die op jouw antwoord wacht, en na je antwoord gaat hij vanzelf verder. Aan de werking van de software zelf verandert niets.
<!-- deploy-summary:end -->
