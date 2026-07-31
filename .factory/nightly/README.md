# Audits (`.factory/nightly/`)

Elke submap hier is één **audit**: een read-only AI-agent-run die de Software Factory 's ochtends
oppakt. De starttijd is **per project instelbaar** (`audit_project_settings.start_time`, migratie
`V24__audit_project_settings.sql`); is er voor dat project geen rij, dan geldt de globale
`audit_settings.start_time` (default 08:00). Een audit past **nooit zelf code aan** — hij
onderzoekt, schrijft een rapport, en stelt hoogstens 1 kleine, afgebakende vervolg-story voor om het
belangrijkste gevonden probleem op te lossen. Die vervolg-story is een gewone (niet-silent) story:
vragen zijn toegestaan, goedkeuring is automatisch, en hij start in de wachtrij (`start-next`)
i.p.v. meteen — zie `StoryPhase.START_NEXT`/`OrchestratorService.promoteQueuedStories`.

Hoeveel audits een project per nacht krijgt is eveneens per project instelbaar (`audit_count`,
default 1). De scheduler (`AuditScheduler`) kiest per project de N enabled audits met de oudste
laatste-rapport-timestamp (nooit gedraaid = oudste), zodat alle geconfigureerde audits om beurten
aan bod komen. `audit_count = 0` betekent: dit project wordt niet geseed.

## Structuur

```
.factory/nightly/<audit-naam>/
  job.yaml        # metadata (titel, aan/uit, AI-instellingen)
  prompt.md        # de vaste audit-instructie die de agent uitvoert
```

## job.yaml

| veld        | verplicht | uitleg |
|-------------|-----------|--------|
| `title`     | ja        | titel van de audit (gebruikt in het dashboard/rapport) |
| `enabled`   | ja        | `false` = audit overslaan zonder hem te verwijderen |
| `aiSupplier`| nee       | bv. `claude`; anders de default van de factory |
| `aiModel`   | nee       | specifiek model |
| `priority`  | nee       | voor latere volgorde-bepaling (nu nog niet gebruikt) |

De **repo** wordt hier niet gezet: die volgt uit de repo waarin deze map staat.

## prompt.md

De vaste instructie voor de auditor-agent: wat te onderzoeken, en eventueel hoe een score te
bepalen (bv. de `quality`-audit draait `quality/run.sh` en neemt de uitkomst over). De agent krijgt
er automatisch bij: de laatste eerdere rapporten voor deze audit (historische context, incl.
score-trend) en zijn eigen memory-tips van vorige keren (via het bestaande
`knowledge`-domein/`agent-tips.md` — rol `auditor`, category = audit-naam).

De agent sluit af met een JSON-besluit in één van twee vormen:

- `{"phase":"audited"}`, optioneel aangevuld met `score`, `scoreLabel` en/of `proposedStory`
  (titel + beschrijving, hoogstens 1 per run). Het rapport zelf schrijft hij als markdown naar
  `/work/audit-report.md`.
- `{"phase":"audit-questions","questions":[...]}` als hij niet verder kan zonder menselijke
  beslissing. Zijn tussenstand (bevindingen tot dan toe) zet hij dan in `/work/audit-findings.md`.

Zie `AgentPromptContracts.RolePrompts.auditorPrompt()` (agentworker) voor het exacte contract.

## Regel voor álle audits

Functioneel niets veranderen — een audit **wijzigt geen code, maakt geen commits, geen PR**.

Kan de auditor niet verder zonder een menselijke beslissing, dan werkt het in **twee runs**. Run 1
eindigt met `{"phase":"audit-questions","questions":[...]}` en zet de bevindingen tot dan toe in
`/work/audit-findings.md`. Er komt dan géén rapport: de job gaat terminaal op `ASKED` (terminaal,
omdat een wachtende job anders alle audits zou stilleggen), en vraag + bevindingen worden bewaard in
`audit_question` (migratie `V26__audit_questions.sql`). De vraag is zichtbaar en beantwoordbaar op
de audit-kaart in het Audits-scherm en in Telegram. Zodra hij beantwoord is start run 2, en die
krijgt de vraag, het antwoord én de eerdere bevindingen terug — het onderzoek hoeft dus niet over.
Details staan in `docs/technical/scheduled-jobs.md`.

## Nu draaien

"Run now" in het dashboard (`audit.runNow` → `AuditScheduler.startManualAudit`) wordt sinds
`V25__audit_run_job_kind.sql` niet meer geweigerd als er al een run loopt: de job hangt als
`kind = manual` aan de lopende run en start zodra dat project geen andere audit meer heeft draaien.
Zo'n handmatige job telt niet als "dit project is geseed", dus de geplande ronde van dat project
gaat die dag gewoon door.

## Geschiedenis

Tot medio 2026 waren dit "nachtelijke jobs" die zelf code aanpasten (tot en met automerge/deploy),
via een `story.md`+optionele `subtasks.yaml`-config (SF-787). Dat bleek achteraf de verkeerde vorm:
eigenlijk was het een audit, geen ontwikkelwerk. Vervangen door bovenstaande opzet; de oude
`NightlyScheduler`/`NightlyJobsReader`-machinery in de factory-server is inmiddels verwijderd. De
mapnaam `.factory/nightly/` is uit die tijd blijven staan.
