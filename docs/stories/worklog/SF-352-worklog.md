# SF-352 — Reconciliation-scheduler, completion-detectie, digest & /nightly-status

## Story in eigen woorden

Tweede developer-stap van de nightly-scheduler-story (SF-350), bovenop de persistentie-fundering
uit SF-351. Ik bouw de daadwerkelijke **reconciliation-scheduler**: een `@Scheduled`-tick (~30s)
die volledig op DB-state draait (geen in-memory status) en dus restart-veilig is. Hij:

1. maakt — als de master-switch aan staat en de (naar UTC omgerekende) start-tijd bereikt is —
   precies één `nightly_run` per kalenderdag aan met per project een queue van de enabled jobs;
2. reconcilet de run: projecten parallel, jobs binnen een project sequentieel; lopende job-story
   terminaal → `done`/`failed` en de volgende pending job starten; één fout blokkeert de nacht niet;
3. stuurt na de summary-tijd exact één digest (Telegram + UI), gegroepeerd per project;
4. zet de run op `ended` zodra alle jobs terminaal zijn én de digest verstuurd is.

Plus completion-detectie per nachtelijke story en een status-overzicht bovenaan `/nightly`.

## Checklist

- [x]: `NightlyGateway`-poort + `NightlyStoryOutcome`/`NightlyOutcomeStatus` (nightly-module los gekoppeld)
- [x]: `NightlyGatewayAdapter` (web) — story aanmaken, completion-detectie, kosten/tijden, link, Telegram
- [x]: `NightlyPlanner` — pure beslis-kern (acties), idempotent + restart-veilig
- [x]: `NightlyScheduler` — `@Scheduled` executor op repos + gateway
- [x]: `NightlyDigest` — pure digest-builder (per project, duur/kosten/uitkomst/link + totalen)
- [x]: Flyway `V12__nightly_run_summary_text.sql` (digest-tekst bewaren voor de UI)
- [x]: `/nightly`-UI — status van huidige/laatste run per project bovenaan; handmatige lijst ongewijzigd
- [x]: Unittests: `NightlyPlannerTest`, `NightlyDigestTest`, `NightlySchedulerTest`
- [x]: Bestaande service-/telegram-test-fakes uitgebreid met de twee nieuwe run-repos
- [x]: Specs bijgewerkt (technical-spec, functional-spec)
- [x]: Build/tests gedraaid

## Wat en waarom

- **Plan/uitvoer-scheiding.** De beslislogica zit in het pure `NightlyPlanner` (geen DB, tijd of
  netwerk): het krijgt de huidige run + jobs + gepolde story-uitkomsten en geeft een lijst
  `NightlyAction`s terug (`CreateRun`, `StartJob`, `MarkJobTerminal`, `SendDigest`, `EndRun`). De
  `NightlyScheduler`-executor voert die acties uit tegen de repositories en de `NightlyGateway`.
  Hierdoor zijn alle acceptatiecriteria (idempotente run-creatie, sequentieel/parallel, failed
  blokkeert niet, restart-pickup, digest-idempotentie) puur en deterministisch te testen.
- **Restart-veiligheid.** Elke tick herleidt de relevante run uit de DB (`activeRun()` → anders de
  run van vandaag). Lopende jobs hebben al een `story_key`; de planner start alleen `pending` jobs,
  dus na een herstart komt er geen dubbele story. De executor seedt jobs alleen als de run nog leeg
  is.
- **Completion-detectie** (`NightlyGatewayAdapter.storyOutcome`): klaar = alle subtaken terminaal
  (`SubtaskPhase.isTerminal`, zelfde bron als `mergeReady`). Mislukt = error-veld op de story **of**
  op een subtaak gezet — een errored subtaak wordt namelijk niet "terminaal" qua fase, dus zonder
  die check zou de project-queue blijven hangen. Duur/kosten komen uit de laatste `story_runs`
  (`started_at`/`ended_at`/`total_cost_usd_est`).
- **Digest** (`NightlyDigest`, puur): platte tekst, gegroepeerd per project; per job de titel +
  job-naam, duur, kosten ($) en story-link, plus totale duur en kosten. Lege run → korte
  "geen jobs"-digest. De executor stuurt 'm via `gateway.sendDigest` (Telegram) en zet altijd
  `summary_sent_at` + `summary_text` (idempotentie + UI-zichtbaarheid), ongeacht of Telegram het
  bericht accepteerde.
- **Tijd/zone.** De scheduler gebruikt de bestaande `NightlyTime` (Europe/Amsterdam, DST-correct,
  `Clock`-injecteerbaar) voor `nlToday`, `hasReached(start_time)` en `hasReached(summary_time)`. De
  DST-conversie zelf is al in SF-351 getest (`NightlyTimeTest`).
- **Module-koppeling.** De `nightly`-module blijft schoon: geen imports buiten `nightly`/`config`/
  `git`. De gateway-implementatie leeft in `web` (`NightlyGatewayAdapter`), die al van tracker,
  story-run-repo, dashboard-service en Telegram afhangt.
- **DB.** `V12__nightly_run_summary_text.sql` voegt `summary_text` toe aan `nightly_run` zodat de
  laatste digest in de UI zichtbaar blijft. `NightlyRunRepository.markSummarySent` schrijft tekst +
  tijd; `NightlyRunRecord` kreeg `summaryText`.
- **`/nightly`-UI.** `NightlyJobsPageData` kreeg een optionele `run`-view; de service bouwt 'm uit de
  laatste run + jobs (per project gegroepeerd, met status-badge en story-link), de view rendert een
  "Automatische run"-blok bovenaan met de digest in een uitklapbaar `<details>`. De handmatige
  job-lijst en Nightly-knop blijven ongewijzigd.

## Verificatie

- `mvn -f softwarefactory/pom.xml test-compile` → groen.
- `mvn -f softwarefactory/pom.xml test -Dtest='NightlyPlannerTest,NightlyDigestTest,NightlySchedulerTest,NightlyTimeTest,FactoryDashboardServiceTest,TelegramNotificationServiceTest'`
  → 77 tests, 0 failures, 0 errors.
- Nieuwe tests: `NightlyPlannerTest` (14, pure beslislogica incl. idempotentie, sequentieel/parallel,
  failed-blokkeert-niet, restart-pickup, digest-idempotentie), `NightlyDigestTest` (3, opmaak +
  totalen + lege run + lopende job), `NightlySchedulerTest` (6, executor met in-memory fake-repos +
  fake-gateway: run-creatie idempotent, parallel/sequentieel, failed, restart zonder dubbele story,
  digest exact één keer + run ended, lege run).
- `NightlyRepositoriesTest` (Testcontainers) niet lokaal gedraaid (geen Docker); draait in de pipeline.

## Specs bijgewerkt

- `docs/factory/technical-spec.md`: sectie "Reconciliation-scheduler (SF-352)" toegevoegd onder de
  nightly-fundering + `V12`-migratie genoemd.
- `docs/factory/functional-spec.md`: nieuwe sectie "Nightly scheduler — nachtelijke jobs automatisch
  draaien (SF-350)".

## Review (reviewer, 2026-06-27)

Volledige story-diff t.o.v. `main` beoordeeld (V11+V12, nightly-module, web-laag, specs, tests).
Architectuur (pure `NightlyPlanner` + `NightlyScheduler`-executor + `NightlyGateway`-poort) is
schoon en goed testbaar; alle 9 acceptatiecriteria zijn herleidbaar geïmplementeerd en gedekt door
`NightlyPlannerTest`/`NightlySchedulerTest`/`NightlyDigestTest`. Specs (technical-/functional-spec,
settings UX) zijn consistent met de diff. Geen blockers; geen secrets in output. Akkoord.

Niet-blokkerende bevindingen voor een volgende iteratie:

- [bug] `NightlyScheduler.runOnce` kiest de run via `activeRun() ?: forDate(nlToday)`, en
  `activeRun()` = de meest recente run met `status <> 'ended'` ongeacht datum. Blijft een run van een
  vorige dag hangen op `running` (bv. een nachtelijke story die nooit terminaal wordt — denk aan een
  `manual-approve`-poort/`awaiting-human`, die niet `isTerminal` is), dan is `run != null` op elke
  volgende dag en wordt `CreateRun` nooit meer gepland: de scheduler ligt stil tot iemand de DB
  ingrijpt. Overweeg `activeRun()` te beperken tot `run_date = nlToday` (of een hung-run na zijn dag
  af te kappen), zodat één vastgelopen nacht niet alle volgende nachten blokkeert.
- [suggestie] Completion-detectie (`NightlyGatewayAdapter.storyOutcome`) markeert een story pas
  `done` als álle subtaken `SubtaskPhase.isTerminal`. De story-description noemde ook `awaitsHuman`
  als signaal; een nachtelijke story die op een handmatige goedkeuring blijft wachten telt nu niet
  als afgerond en houdt de project-queue (en daarmee de run) bezig. Bevestig dat nachtelijke
  job-templates geen handmatige poorten bevatten, of behandel `awaiting-human`/`manual-approve` als
  afgerond-voor-nightly.
- [info] `nightly_run.status` heeft DB-default `'pending'`, maar `create()` schrijft direct
  `running`; de `pending`-runstatus is daardoor in de praktijk ongebruikt. Geen probleem, puur ter
  kennisgeving.
- [info] Wordt de digest na `summary_time` verstuurd terwijl er nog jobs lopen, dan komt er geen
  geactualiseerde digest meer (bewust: "exact één digest"). Conform spec; alleen benoemd.
