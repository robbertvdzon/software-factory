# SF-350 - Nightly scheduler: nachtelijke jobs automatisch draaien + status & samenvatting

## Story

Nightly scheduler: nachtelijke jobs automatisch draaien + status & samenvatting

<!-- refined-by-factory -->

## Scope

Bouw een restart-veilige nachtelijke scheduler die de per-project gedeclareerde nightly jobs (`.factory/nightly/<job>/job.yaml`, ingelezen via `NightlyJobsReader`) automatisch draait, instelbaar via de `/settings`-pagina, met een live status-overzicht op `/nightly` en één digest (Telegram + UI) na de summary-tijd.

In scope:
- **Persistente, schrijfbare settings**: nieuwe `nightly_settings`-store (Flyway-migratie + JdbcTemplate-repository, conform `RunRepositories.kt`-patroon) met `enabled` (master-switch), `start_time` (HH:MM) en `summary_time` (HH:MM). Tijden zijn lokale NL-tijd (Europe/Amsterdam) en worden intern naar UTC omgerekend (factory-klok draait UTC).
- **`/settings`-uitbreiding**: bij de bestaande read-only weergave een schrijfbaar formulier (checkbox + twee tijdvelden + opslaan) met een nieuw POST-endpoint dat de store wegschrijft.
- **DB-state voor runs** (Flyway, nieuwe migratie):
  - `nightly_run`: id, run_date, started_at, ended_at, status, summary_sent_at.
  - `nightly_run_job`: id, run_id, project, job_name, title, status (pending/running/done/failed), story_key, started_at, ended_at, error.
- **Reconciliation-scheduler** als `@Scheduled` tick (~30s), volledig op DB-state (géén in-memory run-status), zodat een rest-restart de lopende run weer oppikt:
  1. Als `enabled` én huidige UTC-tijd ≥ omgerekende `start_time` én er nog geen `nightly_run` voor vandaag (run_date) bestaat → maak een run aan met per project een queue van de enabled jobs (`enabled:true` in job.yaml én master-switch aan), status `pending`.
  2. Reconcile de lopende run: per project **parallel** (één queue per project), binnen een project de jobs **sequentieel**. Is de lopende job-story terminal → markeer `done`/`failed` en start de volgende `pending` job via `createNightlyStory(project, jobName)` (silent=true, start=true). Bij een fout (story-error gezet): markeer de job `failed` en ga door met de volgende jobs van dat project (één fout blokkeert de nacht niet).
  3. Na omgerekende `summary_time`: bouw de digest, stuur naar Telegram, sla op in DB, zet `summary_sent_at`.
  4. Alle jobs terminal → run-status `ended`.
- **Completion-detectie per nachtelijke story**: klaar = alle subtaken terminal (hergebruik `mergeReady`/`SubtaskPhase.isTerminal`); mislukt = error-veld gezet. Hierop hangt "start de volgende job" af.
- **`/nightly`-UI**: bovenaan de status van de huidige/laatste run, per project als afzonderlijk proces zichtbaar (welke jobs done, welke loopt, welke nog pending); daaronder de bestaande handmatige job-lijst en knop ongewijzigd.
- **Digest** (Telegram via `TelegramClient.sendMessage` + UI), gegroepeerd per project:
  - Per gedraaide job: project + naam, duur (`ended_at - started_at` van de story-run), kosten (`total_cost_usd_est` van de story-run), uitkomst (done/failed) en link naar de story.
  - Totale duur van de hele run (start tot laatste job klaar) en totale kosten.

Buiten scope:
- Geen wijziging aan de bestaande story-/subtask-flow: de scheduler maakt stories uitsluitend aan zoals de Nightly-knop dat nu doet (silent=true, start=true).
- Geen nieuwe credit/budget-logica: nachtelijke stories vallen onder dezelfde bestaande credit/budget-pauze als gewone stories.

## Acceptance criteria

1. Op `/settings` kan een ingelogde gebruiker de master-switch (enabled), start-tijd en summary-tijd instellen en opslaan; de waarden worden persistent bewaard (`nightly_settings`) en na herladen correct getoond. Bestaande read-only configuratie/versie-info blijft zichtbaar.
2. Als `enabled` aan staat en de huidige tijd voorbij de (naar UTC omgerekende) start-tijd is en er nog geen run voor vandaag bestaat, maakt de scheduler precies één `nightly_run` voor die dag aan met per project een queue van de jobs die zowel in job.yaml `enabled:true` hebben als onder de master-switch vallen. Een tweede tick op dezelfde dag maakt geen tweede run aan (idempotent op run_date).
3. Tijden ingevoerd als HH:MM worden als Europe/Amsterdam geïnterpreteerd en correct naar UTC vergeleken (DST-correct, getoetst met een vaste klok in tests).
4. Binnen een run draaien projecten parallel en jobs binnen één project strikt sequentieel: een volgende job van een project start pas nadat de voorgaande job van dat project terminal is.
5. Een nachtelijke job wordt als `done` gemarkeerd zodra zijn story terminal is (alle subtaken terminal, hergebruik `mergeReady`/`awaitsHuman`-logica) en als `failed` zodra het error-veld van de story is gezet; bij `failed` gaan de overige jobs van dat project gewoon door.
6. De run-status leeft volledig in de DB: na een (gesimuleerde) herstart midden in een run pikt de reconciliation-loop de bestaande run op zonder dubbele stories aan te maken en zonder verloren voortgang.
7. Na de (naar UTC omgerekende) summary-tijd wordt exact één digest verstuurd naar Telegram en opgeslagen/zichtbaar in de UI; herhaalde ticks versturen niet opnieuw (`summary_sent_at` borgt idempotentie).
8. De digest bevat, gegroepeerd per project: per job de naam (project + job), duur, kosten ($), uitkomst (done/failed) en story-link, plus de totale duur en totale kosten van de hele run.
9. `/nightly` toont bovenaan de status van de huidige/laatste run, per project gescheiden met done/lopend/pending jobs; de bestaande handmatige job-lijst en Nightly-knop blijven onveranderd werken.
10. Nieuwe DB-structuur wordt via nieuwe Flyway-migratie(s) aangemaakt (`nightly_settings`, `nightly_run`, `nightly_run_job`) volgens de bestaande `V<n>__<desc>.sql`-conventie; de applicatie start schoon op met de migraties.
11. De scheduler raakt de bestaande story-flow niet anders dan via `createNightlyStory` (silent=true, start=true); nachtelijke stories vallen onder de bestaande credit/budget-pauze.

## Aannames

- **Standaardwaarden settings**: bij ontbrekende/eerste-keer `nightly_settings` geldt `enabled=false` (scheduler doet niets tot bewust aangezet), met neutrale default-tijden (bv. start `02:00`, summary `07:00`); deze defaults zijn niet kritisch en mogen door de developer redelijk gekozen worden.
- **Eén run per kalenderdag** (run_date in NL-tijd). Mist de scheduler het start-tijdstip (bv. factory stond uit), dan wordt de run alsnog aangemaakt zodra de factory draait en de tijd ná start_time maar binnen dezelfde dag valt; na middernacht zonder run vervalt die dag (geen inhaalrun van gisteren).
- **Job-selectie**: de set jobs per project komt uit `NightlyJobsReader` (bron blijft GitHub-`.factory/nightly`), gefilterd op job-`enabled:true`. De master-switch is een globale aan/uit; er komt geen per-job override in settings in deze story.
- **Terminal/kosten-bron**: duur en kosten per job komen uit de laatste `story_runs`-record van de aangemaakte story (`started_at`/`ended_at`/`total_cost_usd_est`); is een story-run nog niet afgesloten op summary-moment, dan wordt de tot dan bekende waarde getoond.
- **Digest-formaat**: platte tekst-/HTML-bericht via `TelegramClient.sendMessage` (geen nieuw notificatie-kanaal); exacte opmaak/emoji is vrij zolang alle vereiste velden per criterium 8 aanwezig zijn. Bij een lege run (geen enabled jobs) wordt een korte "geen jobs"-digest verstuurd.
- **Story-link**: de link in de digest verwijst naar de story zoals elders in de factory gebruikelijk (issue-/dashboard-URL op basis van `story_key`).
- **Timezone**: er bestaat nog geen NL↔UTC-utility; de developer voegt een kleine conversie toe met `java.time` (`ZoneId.of("Europe/Amsterdam")`), DST-correct, en injecteerbaar via `Clock` voor tests.
- **Foutafhandeling jobs**: een fout in één job (story-error of fout bij `createNightlyStory`) markeert alleen die job `failed` en laat de rest van de nacht doorlopen; de run eindigt `ended` ook als sommige jobs `failed` zijn.

## Eindsamenvatting

## Eindsamenvatting — SF-350: Nightly scheduler

**Wat is gebouwd**

Een restart-veilige nachtelijke scheduler die per project gedeclareerde nightly jobs (`.factory/nightly/<job>/job.yaml`) automatisch draait, instelbaar via `/settings`, met live status op `/nightly` en één digest (Telegram + UI) na de summary-tijd. De story is in twee dev-stappen + een story-brede test gerealiseerd:

- **SF-351 — Persistentie & schrijfbare settings**
  - Flyway-migratie `V11__nightly_scheduler.sql` met drie tabellen: `nightly_settings` (single-row, master-switch + start/summary-tijd, geseed met defaults), `nightly_run` (UNIQUE op `run_date` → idempotent één run per dag) en `nightly_run_job` (FK + cascade).
  - JdbcTemplate-repositories conform het bestaande `RunRepositories.kt`-patroon.
  - `NightlyTime`-utility: NL↔UTC-conversie (Europe/Amsterdam, DST-correct, `Clock`-injecteerbaar voor tests).
  - `/settings` uitgebreid met schrijfbaar formulier (checkbox + twee HH:MM-velden + opslaan) en nieuw `POST /settings/nightly`; bestaande read-only versie/config-info blijft.

- **SF-352 — Reconciliation-scheduler, completion-detectie, digest & /nightly**
  - `@Scheduled`-tick (~30s) die volledig op DB-state draait (geen in-memory status) → restart-veilig.
  - Architectuur splitst beslislogica (puur `NightlyPlanner`, geeft acties terug) van uitvoering (`NightlyScheduler`-executor) en koppelt de buitenwereld los via een `NightlyGateway`-poort met `NightlyGatewayAdapter` in de web-laag.
  - Projecten draaien parallel (queue per project), jobs binnen een project sequentieel; één gefaalde job blokkeert de nacht niet.
  - Completion-detectie: `done` = alle subtaken terminaal (`SubtaskPhase.isTerminal`, zelfde bron als `mergeReady`); `failed` = error-veld op story óf subtaak.
  - Digest (`NightlyDigest`, puur): per project naam/duur/kosten($)/uitkomst/story-link + totale duur en kosten; lege run → korte "geen jobs"-digest. Idempotent geborgd via `summary_sent_at`; `V12__nightly_run_summary_text.sql` bewaart de digest-tekst voor de UI.
  - `/nightly` toont bovenaan de status van de huidige/laatste run per project; de handmatige job-lijst en Nightly-knop blijven ongewijzigd.

**Belangrijke keuzes**

- Run-status leeft volledig in de DB (geen in-memory state), zodat een herstart midden in een run de bestaande run oppikt zonder dubbele stories.
- Pure beslis-kern (`NightlyPlanner`) maakt alle acceptatiecriteria deterministisch testbaar.
- Tijden worden als NL-tijd ingevoerd en DST-correct naar UTC vergeleken.
- Scheduler raakt de bestaande story-flow uitsluitend via `createNightlyStory` (silent=true, start=true); nachtelijke stories vallen onder dezelfde credit/budget-pauze.

**Wat is getest**

- Doelgerichte run: **77 tests, 0 failures** (NightlyPlanner 14, Scheduler 6, Digest 3, NightlyTime 6, plus uitgebreide dashboard/telegram-fakes).
- Volledige suite: **390 tests, 0 failures**, 14 errors — allemaal omgevingsgebonden of pre-existing (11× Docker-e2e, 1× Testcontainers zonder Docker, 1× `ModulithArchitectureTest` en 1× screenshot-test die identiek falen op schone `main`). De nieuwe `nightly`-module introduceert geen nieuwe module-cycle.
- Alle 11 acceptatiecriteria zijn door tester herleidbaar afgevinkt. Geen code-bugs of regressies.

**Bewust niet gedaan / aandachtspunten**

- Geen preview-deploy voor deze factory-repo; verificatie lokaal via Maven (Testcontainers/Postgres-test draait alleen in CI met Docker).
- Geen per-job override in settings — alleen een globale master-switch (conform scope).
- Geen inhaalrun: mist de scheduler de dag, dan vervalt die dag na middernacht.
- Twee niet-blokkerende reviewbevindingen voor een volgende iteratie: (1) `activeRun()` kan een vastgelopen run van een vorige dag laten blijven hangen (bv. een nachtelijke story die op `awaiting-human`/`manual-approve` blijft staan en nooit terminaal wordt), waardoor `CreateRun` op latere dagen niet meer gepland wordt — overweeg `activeRun()` te beperken tot `run_date = nlToday`; (2) bevestig dat nachtelijke job-templates geen handmatige goedkeuringspoorten bevatten, of behandel `awaiting-human` als afgerond-voor-nightly. Daarnaast is `nightly_run.status='pending'` (DB-default) in de praktijk ongebruikt omdat `create()` direct `running` schrijft.
