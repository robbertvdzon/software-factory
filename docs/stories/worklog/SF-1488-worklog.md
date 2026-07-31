# SF-1488 - Worklog

Story in eigen woorden:
`.factory/nightly/README.md` is de single source of truth voor hoe audits werken en hoe je er een
configureert, maar de tekst liep achter op de code. Deze story werkt uitsluitend dat README bij:
audits kunnen tegenwoordig een vraag aan een mens stellen (twee-runs-model), starttijd en aantal
audits zijn per project instelbaar, "Run now" schuift achter een lopende run aan i.p.v. geweigerd te
worden, en de historie-alinea klopte niet meer. Geen code-, migratie- of gedragswijziging.

Stappenplan:
[x]: issue + docs/technical/scheduled-jobs.md §4 en het huidige README gelezen
[x]: de zes tekstwijzigingen doorgevoerd in `.factory/nightly/README.md`
[x]: alle genoemde bestands-/migratie-/symboolnamen tegen de repo geverifieerd
[x]: acceptatiecriteria 1-11 nagelopen en `git status` gecontroleerd
[x]: volledig vangnet (`mvn verify`) gedraaid

Done / rationale:
- Intro: "elke ochtend om 08:00" vervangen door de per-project instelbare starttijd
  (`audit_project_settings.start_time`, `V24__audit_project_settings.sql`) met de globale
  `audit_settings.start_time` (default 08:00) als terugval wanneer er geen projectrij is.
- Aantal per nacht: "hoogstens 1 audit per nacht" vervangen door het per project instelbare
  `audit_count` (default 1), inclusief de oudste-eerst-keuzeregel en de betekenis van `0`
  (project wordt niet geseed).
- JSON-contract: naast `{"phase":"audited"}` (+ optioneel `score`, `scoreLabel`, `proposedStory`)
  nu ook `{"phase":"audit-questions","questions":[...]}`, met beide vaste paden letterlijk benoemd:
  rapport → `/work/audit-report.md`, tussenstand bij een vraag → `/work/audit-findings.md`. De
  verwijzing naar `AgentPromptContracts.RolePrompts.auditorPrompt()` blijft staan.
- "Regel voor álle audits": "nooit interactief" is weg; in plaats daarvan het twee-runs-model
  (geen rapport, job terminaal op `ASKED`, vraag + bevindingen bewaard in `audit_question` /
  `V26__audit_questions.sql`, volgende run krijgt vraag, antwoord én bevindingen terug), plus één
  zin dat de vraag zichtbaar/beantwoordbaar is op de audit-kaart in het Audits-scherm en in
  Telegram. De regel "wijzigt geen code, maakt geen commits, geen PR" staat er nog steeds.
- Nieuwe alinea "Nu draaien": `audit.runNow` → `AuditScheduler.startManualAudit` wordt sinds
  `V25__audit_run_job_kind.sql` niet meer geweigerd bij een lopende run (`kind = manual`), en telt
  niet als "dit project is geseed".
- Geschiedenis: de oude `NightlyScheduler`/`NightlyJobsReader`-machinery is inmiddels daadwerkelijk
  verwijderd; de zinsnede over nog op te ruimen nightly-schermen/-bridge-operaties is vervallen. De
  rest van de alinea (`story.md`/`subtasks.yaml`, SF-787, waarom het een audit werd) blijft.
- De rest van het README (structuur, veldentabel, historische rapporten/agent-tips, voorgestelde
  vervolg-story) is inhoudelijk ongewijzigd.

Verificatie:
- Alle genoemde verwijzingen bestaan in de repo: `V24__audit_project_settings.sql` (kolommen
  `start_time`, `audit_count` met default 1 en `>= 0`-check), `V25__audit_run_job_kind.sql`,
  `V26__audit_questions.sql` (tabel `audit_question`), `AuditScheduler.startManualAudit`,
  bridge-operatie `audit.runNow`, `AgentPromptContracts.RolePrompts.auditorPrompt()`,
  `AuditOutcomeStatus.ASKED` en de paden `/work/audit-report.md` / `/work/audit-findings.md`
  (`AgentPaths` in `agentworker/.../agent/AiClient.kt`).
- Geen specs in `docs/factory/` aangepast: `functional-spec.md` en `technical-spec.md` beschrijven
  dit al correct en verwijzen naar dit README als single source of truth (expliciet buiten scope
  gehouden in de story).
- Vangnet `mvn verify` vanaf de repo-root: `BUILD SUCCESS`, exitcode 0, 0 failures / 0 errors over
  alle modules (incl. de Testcontainers-e2e's van `softwarefactory`). Docs-only wijziging, geen code
  geraakt.
- `git status`: alleen `.factory/nightly/README.md` gewijzigd, plus dit worklog (verplicht volgens
  `docs/factory/agents/developer.md`; het bestond al als untracked bestand vóór deze run).

Review (SF-1589):
- Volledige story-diff (`git diff main...HEAD`) beoordeeld: alleen `.factory/nightly/README.md` +
  dit worklog. Acceptatiecriteria 1-11 nagelopen en akkoord.
- Alle in het README genoemde verwijzingen zelf geverifieerd in de repo: `V24`/`V25`/`V26`
  bestaan (V24 met `start_time` + `audit_count SMALLINT DEFAULT 1 CHECK >= 0`, V26 met tabel
  `audit_question`), `AuditScheduler.startManualAudit` (hangt bij een lopende run een
  `AuditRunKind.MANUAL`-job aan die run), `AuditScheduler.seedProject` (N oudste enabled audits,
  slaat MANUAL-jobs over → handmatige run telt niet als geseed), `auditCountFor` (`> 0`-filter),
  `startTimeFor` (per-project met globale fallback, V21-default `08:00`), bridge-op `audit.runNow`,
  `AuditOutcomeStatus.ASKED` + `AuditJobStatus.isTerminal`, `AgentPaths.AUDIT_REPORT_FILE`/
  `AUDIT_FINDINGS_FILE` in `agentworker/.../agent/AiClient.kt`, `auditorPrompt(reportPath,
  findingsPath)` in `AgentPromptContracts.kt`, `StoryPhase.START_NEXT`/
  `OrchestratorService.promoteQueuedStories`, en de Audits-/Telegram-kanalen voor een openstaande
  vraag (`audit_screen.dart`, `TelegramAuditQuestionService`).
- Specs consistent: `docs/factory/functional-spec.md` (r277-278) en `technical-spec.md` (r302-304)
  verwijzen nog steeds correct naar dit README als single source of truth en zijn ongewijzigd.
- Oude `Nightly*`-machinery bestaat inderdaad niet meer als code (alleen nog historische
  KDoc-verwijzingen in comments) — de gecorrigeerde geschiedenis-alinea klopt.
- [info] `audit_project_settings.start_time` is nullable; `startTimeFor` valt ook terug op de
  globale starttijd bij een bestaande rij met lege `start_time`. Het README beschrijft alleen het
  "geen rij"-geval. Puur nuance, geen blocker.
- Geen testbewijs vereist naast het gedraaide vangnet: docs-only wijziging zonder runtime-gedrag.
