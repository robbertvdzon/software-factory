# SF-1488 - [Audit] Werk .factory/nightly/README.md bij naar het huidige auditgedrag (blokkerende vraag, per-project starttijd/aantal, Run now)

## Story

[Audit] Werk .factory/nightly/README.md bij naar het huidige auditgedrag (blokkerende vraag, per-project starttijd/aantal, Run now)

<!-- refined-by-factory -->

## Samenvatting

Het bestand `.factory/nightly/README.md` legt uit hoe de nachtelijke audits werken en geldt als het
officiële naslagwerk daarvoor. De tekst loopt achter op de werkelijkheid: audits kunnen inmiddels
een vraag stellen aan een mens, de starttijd en het aantal audits zijn per project instelbaar, en
je kunt een audit ook handmatig starten terwijl er al een ronde loopt. Ook een stukje geschiedenis
klopt niet meer.

Deze story werkt die tekst bij zodat hij weer klopt met wat het systeem echt doet. Er verandert
niets aan de werking van de factory en er wordt geen code aangepast.

## Scope

In scope — uitsluitend `.factory/nightly/README.md`:

1. **Vragen stellen (r46-50).** Vervang "nooit interactief" door een beschrijving van het
   twee-runs-model: een auditor die niet verder kan zonder menselijke beslissing eindigt met
   `{"phase":"audit-questions","questions":[...]}` en zet zijn tussenstand in
   `/work/audit-findings.md`. De job gaat dan terminaal op `ASKED` (geen rapport), vraag +
   bevindingen worden bewaard (`audit_question`, `V26`), en een volgende run krijgt vraag, antwoord
   en de eerdere bevindingen terug zodat het onderzoek niet over hoeft. Blijf benoemen dat een audit
   nog steeds geen code/commits/PR's maakt.
2. **JSON-contract (r42-44).** Noem naast `{"phase":"audited"}` (+ optioneel `score`, `scoreLabel`,
   `proposedStory`) ook de tweede eindvorm `{"phase":"audit-questions","questions":[...]}`, en
   benoem beide bestandspaden: rapport → `/work/audit-report.md`, tussenstand bij een vraag →
   `/work/audit-findings.md`. Verwijzing naar
   `AgentPromptContracts.RolePrompts.auditorPrompt()` blijft staan.
3. **Starttijd (r3-4).** Vervang "elke ochtend om 08:00" door: starttijd is per project instelbaar
   (`audit_project_settings.start_time`, `V24`), met de globale `audit_settings.start_time`
   (default 08:00) als terugval wanneer er geen projectrij is.
4. **Aantal per nacht (r10-12).** Vervang "hoogstens 1 audit per nacht" door: een per project
   instelbaar aantal (`audit_count`, default 1). De scheduler kiest per project de N enabled audits
   met de oudste laatste-rapport-timestamp (nooit gedraaid = oudste), zodat alle audits om beurten
   aan bod komen. `audit_count = 0` betekent: dit project wordt niet geseed.
5. **Geschiedenis (r52-59).** Corrigeer "uitgezet (niet meer verwijderd)": de oude
   `Nightly*`-machinery is inmiddels daadwerkelijk verwijderd. De rest van de historische alinea
   (oorspronkelijke opzet met `story.md`/`subtasks.yaml`, SF-787, en waarom het een audit werd)
   blijft.
6. **Nieuwe korte alinea "Nu draaien".** "Run now" in het dashboard (`audit.runNow` →
   `AuditScheduler.startManualAudit`) wordt sinds `V25__audit_run_job_kind.sql` niet meer geweigerd
   als er al een run loopt: de job hangt als `kind = manual` aan de lopende run en start zodra dat
   project geen andere audit meer heeft draaien. Zo'n handmatige job telt niet als "dit project is
   geseed", dus de geplande ronde van dat project gaat die dag gewoon door.

Buiten scope:

- Elke gedragswijziging of codewijziging (agentworker, softwarefactory, dashboard-backend,
  dashboard-frontend, migraties, tests).
- Andere documentatie: `docs/technical/scheduled-jobs.md`, `docs/factory/functional-spec.md` en
  `docs/factory/technical-spec.md` beschrijven dit al correct en blijven ongewijzigd.
- Hernoemen of verplaatsen van de map `.factory/nightly/` en van het README-bestand zelf; de
  historische mapnaam blijft.
- Inhoudelijke wijzigingen aan de audit-submappen (`job.yaml`/`prompt.md` van adr, consistency,
  documentation, integration-tests, quality, security).

## Acceptance criteria

1. Alleen `.factory/nightly/README.md` is gewijzigd; `git status` toont geen andere gewijzigde
   bestanden.
2. Het README beschrijft de tweede eindvorm `{"phase":"audit-questions","questions":[...]}`, inclusief:
   geen rapport, job terminaal (`ASKED`), tussenstand in `/work/audit-findings.md`, en dat een
   volgende run vraag, antwoord én eerdere bevindingen terugkrijgt.
3. Het README noemt beide vaste bestandspaden letterlijk: `/work/audit-report.md` (rapport) en
   `/work/audit-findings.md` (tussenstand bij een vraag), met vermelding waarvoor elk dient.
4. De tekst "nooit interactief" (of een gelijkwaardige bewering dat een auditor nooit een vraag kan
   stellen) komt niet meer voor; de regel dat een audit geen code wijzigt en geen commits/PR's maakt
   staat er nog steeds.
5. De bewering "elke ochtend om 08:00" is vervangen door de per-project instelbare starttijd met de
   globale `audit_settings.start_time` als terugval; `V24__audit_project_settings.sql` wordt genoemd.
6. De bewering "hoogstens 1 audit per nacht" is vervangen door het instelbare `audit_count`
   (default 1), inclusief de oudste-eerst-keuzeregel en de betekenis van `0`.
7. De historie-alinea zegt dat de oude `Nightly*`-machinery verwijderd is (niet "uitgezet, niet meer
   verwijderd"); de tekst spreekt niet meer over nog op te ruimen nightly-schermen of
   -bridge-operaties.
8. Er staat een korte alinea "Nu draaien" die beschrijft dat een handmatige run achter een lopende
   run aanschuift (`kind = manual`, `V25__audit_run_job_kind.sql`) in plaats van geweigerd te worden,
   en dat hij de geplande ronde van dat project niet verdringt.
9. Alle in het README genoemde codeверwijzingen (bestandsnamen, migratienamen, klasse-/functienamen)
   bestaan daadwerkelijk in de repo.
10. De rest van het README (structuur `job.yaml`/`prompt.md`, de veldentabel, de uitleg over
    historische rapporten/agent-tips, de regel over de voorgestelde vervolg-story) blijft inhoudelijk
    intact; wijzigingen beperken zich tot de zes punten hierboven.
11. `docs/factory/functional-spec.md` en `docs/factory/technical-spec.md` blijven ongewijzigd en hun
    verwijzing naar dit README als single source of truth blijft kloppen.

## Aannames

- De doelgroep en toon blijven gelijk: Nederlands, beknopt, gericht op iemand die een audit
  configureert of een auditor-prompt schrijft. Diepe implementatiedetails blijven in
  `docs/technical/scheduled-jobs.md`; het README verwijst daar waar nuttig naar.
- `docs/technical/scheduled-jobs.md` §4 wordt als bron gebruikt, maar de tekst wordt herschreven
  naar README-niveau — niet letterlijk gekopieerd.
- Regelnummers in de story zijn indicatief; leidend is de inhoud van de betreffende passage.
- De aanwezigheid van een openstaande audit-vraag in dashboard (Audits-scherm) en Telegram hoeft in
  dit README hooguit in één zin genoemd te worden; de volledige UI/notificatiebeschrijving blijft in
  `docs/technical/scheduled-jobs.md`.
- Geen build of testrun nodig: er is geen code gewijzigd. Verificatie is een leesronde plus controle
  dat de genoemde bestanden/migraties/symbolen bestaan.

## Eindsamenvatting

Ik heb de story-context, het worklog en de volledige diff gelezen. Hier de eindsamenvatting.

---

# SF-1488 — Eindsamenvatting

## Wat is gebouwd
Uitsluitend een documentatie-update van `.factory/nightly/README.md` (+59/-18 regels), het naslagwerk voor hoe audits werken. De tekst liep achter op de code en is op zes punten bijgewerkt:

1. **Starttijd** — "elke ochtend om 08:00" vervangen door de per-project instelbare starttijd (`audit_project_settings.start_time`, `V24__audit_project_settings.sql`), met de globale `audit_settings.start_time` (default 08:00) als terugval.
2. **Aantal per nacht** — "hoogstens 1 audit per nacht" vervangen door het per project instelbare `audit_count` (default 1), inclusief de oudste-eerst-keuzeregel en de betekenis van `0` (project wordt niet geseed).
3. **JSON-contract** — naast `{"phase":"audited"}` nu ook de tweede eindvorm `{"phase":"audit-questions","questions":[...]}`, met beide vaste paden letterlijk benoemd: rapport → `/work/audit-report.md`, tussenstand → `/work/audit-findings.md`.
4. **Vragen stellen** — "nooit interactief" is weg; daarvoor in de plaats het twee-runs-model: geen rapport, job terminaal op `ASKED`, vraag + bevindingen bewaard in `audit_question` (`V26`), en run 2 krijgt vraag, antwoord én eerdere bevindingen terug. De regel "wijzigt geen code, maakt geen commits, geen PR" staat er nog steeds.
5. **Nieuwe alinea "Nu draaien"** — een handmatige run (`audit.runNow` → `AuditScheduler.startManualAudit`) schuift sinds `V25__audit_run_job_kind.sql` als `kind = manual` achter een lopende run aan in plaats van geweigerd te worden, en verdringt de geplande ronde niet.
6. **Geschiedenis** — gecorrigeerd: de oude `Nightly*`-machinery is inmiddels daadwerkelijk verwijderd (niet "uitgezet, niet meer verwijderd"); de mapnaam `.factory/nightly/` blijft als historisch relict.

## Gemaakte keuzes
- Toon en doelgroep bewust gelijk gehouden: Nederlands, beknopt, gericht op wie een audit configureert. Diepe implementatiedetails blijven in `docs/technical/scheduled-jobs.md`; het README verwijst daarheen.
- De UI-/notificatiekant van een openstaande audit-vraag is tot één zin beperkt (Audits-scherm + Telegram); de volledige beschrijving blijft in de technische doc.
- De rest van het README (structuur `job.yaml`/`prompt.md`, veldentabel, historische rapporten/agent-tips, regel over de voorgestelde vervolg-story) is inhoudelijk ongemoeid gelaten.

## Wat is getest
- **Feitencontrole** (de eigenlijke test bij een docs-only story): elke bewering in het README is door zowel reviewer als tester tegen de code gecontroleerd. Alle genoemde migraties, symbolen en paden bestaan: `V24`/`V25`/`V26`, tabel `audit_question`, `AuditScheduler.startManualAudit`/`seedProject`/`auditCountFor`/`startTimeFor`, `AuditOutcomeStatus.ASKED`, bridge-operatie `audit.runNow`, `AgentPromptContracts.RolePrompts.auditorPrompt()`, `StoryPhase.START_NEXT`/`OrchestratorService.promoteQueuedStories`, en `/work/audit-report.md` / `/work/audit-findings.md` (`AgentPaths`).
- **Vangnet**: `mvn verify` vanaf de repo-root draaide groen (BUILD SUCCESS, 0 failures / 0 errors, incl. de Testcontainers-e2e's).
- **Scope-check**: `git diff main...HEAD` raakt alleen `.factory/nightly/README.md` plus het worklog. Acceptatiecriteria 1 t/m 11 zijn nagelopen en akkoord; geen bevindingen.

## Bewust niet gedaan
- Geen enkele code-, migratie-, test- of gedragswijziging — de story is puur tekstueel.
- `docs/factory/functional-spec.md` en `docs/factory/technical-spec.md` bleven ongewijzigd: die beschrijven het al correct en verwijzen naar dit README als single source of truth.
- De map `.factory/nightly/` en het README-bestand zijn niet hernoemd of verplaatst; de audit-submappen (`job.yaml`/`prompt.md`) zijn niet inhoudelijk aangepast.

## Aandachtspunt voor de PO (niet blokkerend)
Reviewer en tester signaleerden dezelfde nuance: `audit_project_settings.start_time` is nullable, dus `startTimeFor` valt óók terug op de globale starttijd bij een bestaande projectrij met een lege `start_time`. Het README beschrijft alleen het "geen projectrij"-geval. Puur een detail; eventueel mee te nemen in een latere tekstronde.

```json
```
