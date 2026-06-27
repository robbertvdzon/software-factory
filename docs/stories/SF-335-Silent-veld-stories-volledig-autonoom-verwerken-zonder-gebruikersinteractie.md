# SF-335 - Silent-veld: stories volledig autonoom verwerken zonder gebruikersinteractie

## Story

Silent-veld: stories volledig autonoom verwerken zonder gebruikersinteractie

<!-- refined-by-factory -->

## Scope

Voeg een boolean custom field **"Silent"** (default `false`) toe op story-niveau in de software-factory. Wanneer `Silent=true` wordt de story volledig autonoom verwerkt: alle fases worden automatisch doorgezet, er is geen handmatige goedkeuring, onduidelijkheden leiden tot een error in plaats van wachten, en er gaat geen enkele Telegram-melding uit. Subtaken erven de waarde van de parent-story (geen eigen veld zetten nodig) — net zoals Auto-approve nu via parent-lookup wordt bepaald.

Doel: nachtelijke "improve"-stories (documentatie, test-coverage, code-kwaliteit, security) die functioneel niets aanpassen autonoom kunnen worden afgemaakt zolang alle tests slagen.

**In scope:**
1. Nieuw veld "Silent" (boolean) in `TrackerField` + `TrackerIssueFields` (core/TrackerModels.kt), gemodelleerd als enum-boolean analoog aan `Paused`.
2. Uitlezen/schrijven in `YouTrackClient.mapIssue()` en `fieldUpdate()` als `SingleEnumIssueCustomField` (analoog `Paused`), inclusief registratie in de schema-`FieldSpec`-lijst zodat het veld in YouTrack wordt aangemaakt/gegarandeerd.
3. Silent impliceert auto-approve: de bestaande auto-approve-logica (`SubtaskExecutionCoordinator.autoApproveActive`, incl. parent-lookup) triggert op `(autoApprove || silent)`.
4. Manual-approve-subtaak overslaan bij parent `silent=true`: in `AgentRunCompletionService.materializeSubtasksIfPlanned()` (de huidige naam van de in het issue genoemde `applyPlannerSubtasks()`) de `MANUAL_APPROVE`-spec niet aanmaken als de parent silent is. Merge- en deploy-subtaken blijven bestaan.
5. Elke `*_WITH_QUESTIONS`-uitkomst (refiner/planner → StoryPhase; developer/reviewer/tester/summary/documentation → SubtaskPhase) zet bij silent de story/subtaak in de **error-state** met de vragen als error-tekst, in plaats van de huidige "waiting-for-user"-skip.
6. Error-categorisatie als ontwerp-haakje: onderscheid een inhoudelijke **clarification**-error (uit `*_WITH_QUESTIONS`, niet retrybaar) van een **technische** error (flaky test/deploy/netwerk, wél retrybaar). Alleen het onderscheid markeren in het foutmodel; geen retry-/digest-/monitor-logica.
7. `TelegramNotificationService` slaat silent stories én hun subtaken (via parent-lookup) volledig over — inclusief de error-melding. Geen enkel bericht.

**Buiten scope (latere stories):** nachtelijke trigger die improve-stories op fase "start" zet; ochtend-digest met samenvatting + errors; monitor-agent die technische errors retryt/fixt.

## Acceptance criteria

1. **Veld bestaat & wordt gesynchroniseerd**
   - `TrackerField.SILENT` en `TrackerIssueFields.silent: Boolean = false` bestaan.
   - `YouTrackClient.mapIssue()` leest het veld als enum-boolean (analoog `Paused`: "true"/"on" → `true`, anders `false`).
   - `YouTrackClient.fieldUpdate()` schrijft het veld als `SingleEnumIssueCustomField`.
   - Het "Silent"-veld (enum on/off ofwel false/true) is opgenomen in de schema-bootstrap (`FieldSpec`-lijst), zodat het in YouTrack als custom field gegarandeerd bestaat.
   - Subtaken hebben geen eigen Silent-veld nodig; de waarde wordt uit de parent gelezen.

2. **Silent impliceert auto-approve**
   - Voor een story/subtaak met effectief `silent=true` (eigen veld of geërfd van parent) worden alle fases automatisch doorgezet zonder handmatige approve, identiek aan `autoApprove=true`.
   - De auto-approve-conditie evalueert als `(autoApprove || silent)`, met dezelfde parent-lookup voor subtaken als de bestaande `autoApproveActive`.

3. **Geen handmatige approve-subtaak bij silent**
   - Bij een silent parent-story wordt de `MANUAL_APPROVE`-subtaak niet aangemaakt in de subtaak-keten.
   - Merge- en deploy-subtaken worden onveranderd wél aangemaakt.
   - Bij een niet-silent story blijft het bestaande gedrag (manual-approve volgens `manualApproveFor`) ongewijzigd.

4. **Onduidelijkheden → error i.p.v. wachten**
   - Bij effectief silent leidt elke `*_WITH_QUESTIONS`-uitkomst (REFINED/PLANNED op story; DEVELOPED/REVIEWED/TESTED/SUMMARY/DOCUMENTATION op subtaak) tot het zetten van de error-state (`TrackerField.ERROR`) met de bijbehorende vragen als error-tekst.
   - De story/subtaak wacht in dat geval niet (geen "waiting-for-user"-skip).
   - Voor niet-silent stories blijft het bestaande wacht-gedrag ongewijzigd.

5. **Error-categorisatie gemarkeerd**
   - Een uit `*_WITH_QUESTIONS` voortkomende error is herkenbaar gemarkeerd als "clarification" (niet-retrybaar) en onderscheidbaar van technische errors. De markering is leesbaar terug te vinden in het opgeslagen foutmodel/-data; verdere afhandeling (retry/digest/monitor) valt buiten deze story.

6. **Nul Telegram bij silent**
   - `TelegramNotificationService` verstuurt geen enkel bericht voor een silent story of een subtaak waarvan de parent silent is (parent-lookup), inclusief de error-melding uit criterium 4.
   - Voor niet-silent stories/subtaken blijft de bestaande Telegram-notificatie ongewijzigd.

7. **Build & tests**
   - Het project compileert en de bestaande testsuite slaagt. Nieuw gedrag (silent-pad voor auto-approve, manual-approve-skip, error-i.p.v.-wachten, Telegram-suppressie) is gedekt door tests in de stijl van bestaande tests (bv. `MergeSubtaskHandlerTest`, coordinator-/notification-tests).

## Aannames

- "Silent" wordt gemodelleerd als enum-boolean met waarden `false`/`true` (analoog aan het bestaande `Paused`-veld), niet als een echte YouTrack-boolean. De story-tekst noemt beide ("boolean/enum on|off"); we volgen het `Paused`-patroon voor consistentie.
- De in het issue genoemde methode `AgentRunCompletionService.applyPlannerSubtasks()` verwijst naar de feitelijk bestaande `materializeSubtasksIfPlanned()`; daar wordt de manual-approve-skip ingebouwd.
- "Effectief silent" voor een subtaak = eigen veld `true` óf parent-story `silent=true`, bepaald via dezelfde best-effort parent-lookup als `autoApproveActive` (faalt parent-lookup, dan `false`).
- De error-state wordt gezet via het bestaande `TrackerField.ERROR`-mechanisme; de vragen-tekst wordt als error-tekst opgeslagen. Er wordt geen nieuw error-veld toegevoegd, alleen een categorie-markering (clarification vs technisch) binnen het bestaande foutmodel.
- De silent-suppressie in Telegram geldt voor alle `NotifyCategory`-uitkomsten (QUESTION/APPROVAL/MANUAL/DONE/ERROR/PROGRESS) van silent issues — letterlijk "geen enkel bericht".
- Bestaand gedrag voor niet-silent stories/subtaken blijft in alle paden volledig ongewijzigd (backwards compatible; default `silent=false`).
- Het aanmaken van het "Silent"-veld in de live YouTrack-instantie gebeurt via de bestaande schema-bootstrap; er is geen handmatige out-of-band YouTrack-configuratie nodig buiten wat de schema-registratie afdwingt.

## Eindsamenvatting

I have all the context I need. Here is the eindsamenvatting for the PO.

## Eindsamenvatting — SF-335: Silent-veld — stories volledig autonoom verwerken zonder gebruikersinteractie

**Wat is gebouwd**

Een nieuw enum-boolean veld **"Silent"** (default `false`, gemodelleerd analoog aan het bestaande `Paused`-veld) op story-niveau. Met `Silent=true` wordt een story volledig autonoom afgehandeld: alle fases lopen automatisch door, er is geen handmatige goedkeuring, onduidelijkheden worden een error in plaats van een wachtmoment, en er gaat geen enkel Telegram-bericht uit. Subtaken zetten zelf geen veld maar erven de waarde van de parent-story via parent-lookup (net als Auto-approve). Concreet:

1. **Veld & synchronisatie** — `TrackerField.SILENT` + `TrackerIssueFields.silent` in `core/TrackerModels.kt`. `YouTrackClient.mapIssue()` leest het veld als enum-boolean (`"true"`/`"on"` → `true`); `fieldUpdate()` schrijft het als `SingleEnumIssueCustomField`. Een `FieldSpec("Silent", …, values=["false","true"])` is in de schema-bootstrap geregistreerd zodat het veld in YouTrack gegarandeerd wordt aangemaakt.
2. **Gedeelde waarheid** — Eén helper `YouTrackApi.effectiveSilent(issue)` bepaalt "effectief silent" (eigen veld óf dat van de parent-story, best-effort parent-lookup) en wordt gebruikt door coördinatoren, notificaties en dashboard.
3. **Silent ⇒ auto-approve** — De auto-approve-conditie evalueert nu als `(autoApprove || silent)` in `SubtaskExecutionCoordinator`, `StoryRefinementCoordinator` en de `FactoryDashboardService`-mirror, met dezelfde parent-lookup.
4. **Geen handmatige-approve-subtaak** — In `AgentRunCompletionService.materializeSubtasksIfPlanned()` wordt de `MANUAL_APPROVE`-spec overgeslagen bij een silent parent; merge-, deploy- en documentation-subtaken blijven onveranderd.
5. **Vragen → error i.p.v. wachten** — Elke `*-with-questions`-uitkomst (story: refined/planned; subtaak: developed/reviewed/tested/summary/documentation) zet bij silent `TrackerField.ERROR` met de vragen als error-tekst, in plaats van de "waiting-for-user"-skip.
6. **Error-categorisatie** — Nieuw `ErrorCategory`-enum (`CLARIFICATION` vs `TECHNICAL`) als leesbare prefix (`[CLARIFICATION]`) in de bestaande error-tekst — geen nieuw YouTrack-veld. Alleen de markering; retry-/digest-/monitor-logica is bewust buiten scope.
7. **Nul Telegram** — `TelegramNotificationService.notifyPending()` slaat silent stories én hun subtaken volledig over (guard boven in de loop, vóór alle send-paden), inclusief de error-melding.

**Belangrijkste keuzes**
- Silent is gemodelleerd als enum-boolean (`false`/`true`) volgens het `Paused`-patroon voor consistentie, niet als echte YouTrack-boolean.
- De error-categorie leeft als prefix in de bestaande error-tekst; er is bewust géén nieuw error-veld toegevoegd.
- Niet-silent gedrag blijft in alle paden volledig backwards compatible (default `silent=false`).

**Wat is getest**
- Gerichte suites (`YouTrackClientTest`, `OrchestratorServiceTest`, `AgentRunCompletionServiceTest`, `TelegramNotificationServiceTest`): **97 tests, 0 failures** — alle 7 acceptatiecriteria afgedekt (veld lezen/schrijven + schema-seed, silent⇒auto-advance op story- en subtaakniveau, manual-approve-skip met behoud van merge/deploy/docs, clarification-error i.p.v. wachten, `[CLARIFICATION]`-markering, Telegram-suppressie).
- Volledige suite: **360 tests, 0 failures**, 13 errors — alle 13 zijn pre-existing/omgevingsgebonden (Docker-e2e + screenshots zonder docker-daemon, `ModulithArchitectureTest`-cycle) en identiek aan de baseline op schone `main`. Geen regressies.
- Reviewer en tester hebben beiden akkoord gegeven.

**Bewust niet gedaan (latere stories)**
- Nachtelijke trigger die improve-stories op fase "start" zet, de ochtend-digest met samenvatting + errors, en de monitor-agent die technische errors retryt — alle drie expliciet buiten scope.
- Eén kleine, niet-blokkerende inconsistentie gesignaleerd door de reviewer: `ManualCommandService` parseert Silent alleen op `"true"`, terwijl `mapIssue()` ook `"on"` accepteert. Functioneel niet kritiek (schemawaarden zijn `false`/`true`); bewust niet aangepast.

Alle 7 acceptatiecriteria zijn gehaald.
