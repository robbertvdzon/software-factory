# SF-10 - Worklog

Story-context bij eerste pickup:
auto approve

In de huidige flow moet de gebruiken na een development, review of andere agent stappen steeds een approve en reject doen.
Ik wil een optie bij de story kunnen doen (net als de ai-supplier) die aangeeft dat ik auto-approve op alles doe.
Dan zou ik als gebruiker alleen nog maar vragen moeten krijgen als de refiner, of een developer of zo echt een vraag aan me heeft

Stappenplan:
[ ]: read issue and target docs
[ ]: implement requested changes
[ ]: run relevant tests
[ ]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## Implementatieplan (planner)

### Doel
Een story-niveau optie `auto-approve` (analoog aan `AI-supplier`) die, indien aan,
de "waiting-for-approval"-statussen automatisch laat doorlopen naar het bijbehorende
`*-approved`, zonder handmatige approve. Vragen-zijtakken en manual-acties blijven op
de gebruiker wachten. Default = uit (huidig gedrag ongewijzigd).

### Aanpak op gedragsniveau

**1. Trigger-parsing (mirror van `SUPPLIER=`)**
- In `TrackerCommentParser.kt`: nieuw regex-pattern `AUTO-APPROVE=on|off`
  (case-insensitief, woordgrens) dat een nieuwe `AutoApproveTrigger(enabled: Boolean, sourceText)`
  oplevert. Plaats naast de bestaande supplier/level/budget/continue-parsing.
- In `TrackerModels.kt`: nieuwe `data class AutoApproveTrigger(...) : TrackerTriggerInstruction`.

**2. Story-model + veld-opslag (mirror van `aiSupplier`)**
- `TrackerModels.kt`: nieuw enum-lid `TrackerField.AUTO_APPROVE("Auto-approve")`
  (YouTrack custom field-naam, exact afstemmen op tracker-config) en nieuw veld
  `autoApprove: Boolean = false` (of `Boolean? = null` met default-uit-semantiek)
  op `TrackerIssueFields`.
- Let op de mapping in de YouTrack-client die issue-fields uitleest: het nieuwe veld
  moet uit de tracker-respons gelezen worden (bool/enum-parsing). Defaults naar uit
  als het veld niet gezet/aanwezig is — bestaande stories blijven zo ongewijzigd.

**3. Trigger toepassen + persisteren (mirror van `setAiSupplier`)**
- `ManualCommandService.kt`: in `applyInstructions()` een `AutoApproveTrigger`-tak
  toevoegen die `setAutoApprove(issue, enabled)` aanroept; die schrijft via
  `updateIssueFields(TrackerField.AUTO_APPROVE to ...)` en update de in-memory copy.
- De instruction-filter in `ManualCommandService` (die nu alleen command/level/supplier
  doorlaat) uitbreiden met `AutoApproveTrigger`. Idempotentie volgt hetzelfde patroon
  als supplier (geen dubbele writes bij herhaalde comment).

**4. Orchestratie: auto-advance op `*-ed`-statussen**
- Story-niveau in `OrchestratorService.processStoryRefinement()`:
  - `StoryPhase.REFINED`: als `issue.fields.autoApprove` → schrijf
    `STORY_PHASE = refined-approved` en behandel als advance (planner-dispatch),
    i.p.v. `Skipped("waiting-for-approval")`. Anders bestaand gedrag.
  - `StoryPhase.PLANNED`: idem → `planning-approved` (terminaal/refinement-done).
- Subtask-niveau in `developmentSubtask` / `reviewSubtask` / `testSubtask` /
  `summarySubtask`: op de `*-ed`-takken (`DEVELOPED`, `REVIEWED`, `TESTED`,
  `SUMMARIZED`) auto-advance naar het bijbehorende `*-approved` wanneer auto-approve
  aan staat. **Belangrijk:** auto-approve staat op de PARENT-story, niet op de subtask.
  De waarde moet — net als de supplier-inheritance in `dispatchSubtask` — via
  `parentStoryKey` + `getIssue(parent)` van de parent gelezen worden. Centraliseer dit
  bij voorkeur in één helper (bv. `autoApproveActive(subtask): Boolean` die de parent
  ophaalt) zodat alle vier de subtask-handlers dezelfde bron gebruiken.
- Implementatiekeuze (developer mag pragmatisch kiezen, mits gedrag klopt): ofwel het
  veld in de tracker daadwerkelijk op `*-approved` zetten en de bestaande
  approved-tak laten oppakken, ofwel direct doordispatchen. Het zichtbare tracker-veld
  op `*-approved` zetten heeft de voorkeur i.v.m. consistente status-weergave in de UI.

**5. Grenzen bewaken (mag NIET auto-advancen)**
- `*-with-questions`-statussen (story én subtask) blijven
  `Skipped("waiting-for-user")` — ook bij auto-approve aan.
- `AWAITING_HUMAN` (manual subtask) blijft `Skipped("waiting-for-user")`.
- Auto-approve approveert alleen; nooit auto-reject. Reject blijft handmatig.

### Geraakte modules
- `youtrack/parsers/TrackerCommentParser.kt` — nieuw trigger-pattern.
- `youtrack/TrackerModels.kt` — trigger-class, `TrackerField`-lid, `TrackerIssueFields`-veld.
- YouTrack-client/mapping (issue-fields uitlezen) — nieuw veld inlezen.
- `orchestrator/services/ManualCommandService.kt` — trigger toepassen + persist + filter.
- `orchestrator/services/OrchestratorService.kt` — auto-advance-logica story + subtasks.

### Risico's / aandachtspunten
- **YouTrack custom field:** `Auto-approve` moet als veld in het tracker-project bestaan
  (bool of enum on/off). Als provisioning nodig is, in PR/worklog melden; mapping mag
  niet crashen als het veld ontbreekt (default uit).
- **Parent-lookup per subtask:** extra `getIssue(parent)` call; hergebruik waar mogelijk
  de parent die `dispatchSubtask` toch al ophaalt om dubbele calls te beperken.
- **Default-semantiek:** bij niet-gezet veld strikt "uit" — anders verandert gedrag van
  bestaande stories. Expliciet testen.
- **Geen auto-advance over vragen/manual heen** — regressiegevoelig; expliciet in tests.

### Testdekking (acceptatiecriterium 5)
- Parser: `AUTO-APPROVE=on` / `=off` → juiste `AutoApproveTrigger`.
- ManualCommandService: trigger zet `autoApprove` op de issue (+ idempotent).
- Orchestrator: `REFINED`/`PLANNED` met auto-approve=aan → advance naar
  `*-approved` (geen waiting-for-approval); subtask `DEVELOPED`/`REVIEWED`/`TESTED`/
  `SUMMARIZED` met parent-auto-approve=aan → advance.
- Orchestrator (negatief): `*-with-questions` en `AWAITING_HUMAN` blijven ook met
  auto-approve=aan op `waiting-for-user`; auto-approve=uit → bestaand gedrag identiek.

## Review-notities (SF-13, reviewer)

Statische review van de working-tree changes (parser, model, client, ManualCommandService,
OrchestratorService + tests). Implementatie volgt het plan en mirror-patroon van `SUPPLIER=`.

- [info] Build/tests niet uitgevoerd: in deze omgeving is geen `mvn`/wrapper beschikbaar.
  Review is daardoor statisch; CI/maven-run wordt aangeraden vóór merge.
- [info] Coherent met plan: parser-pattern, `TrackerField.AUTO_APPROVE`, veld-mapping in
  YouTrackClient (default uit), idempotente `setAutoApprove`, filter uitgebreid, en
  story/subtask auto-advance via het persistente `*-approved`-veld (return `Recovered`,
  consistent met bestaande regel 151). Grenzen (`*-with-questions`, `AWAITING_HUMAN`)
  blijven `waiting-for-user`. Geen auto-reject. Geen scope creep in de Kotlin-wijzigingen.
- [suggestie] Orchestrator-tests dekken story REFINED/PLANNED en subtask DEVELOPED/SUMMARIZED
  + negatief (developed-with-questions, refined off). De REVIEWED- en TESTED-auto-advance-takken
  (beide expliciet in het plan genoemd) hebben geen eigen test; toevoegen voor volledige dekking.
- [info] `autoApproveActive` doet een extra `getIssue(parent)` per poll van een `*-ed`-subtask
  wanneer de vlag niet op de subtask zelf staat. Best-effort (runCatching → uit), begrensd en
  acceptabel; aandachtspunt bij hoge poll-frequentie.
- [info] De gecommitte `specs/refactor-plan.md`-wijziging in deze branch valt buiten SF-10
  (refactor-feature). Buiten scope van deze review; geen bezwaar zolang die niet onbedoeld
  als onderdeel van SF-10 wordt gemerged.

### Tweede review-pass (SF-13, 2026-06-10)

Bevindingen geverifieerd tegen de broncode; akkoord voor merge.

- [info] Enum-/method-checks: `StoryPhase.REFINED_APPROVED`/`PLANNING_APPROVED` en
  `SubtaskPhase.*_APPROVED` bestaan; `YouTrackApi.parentStoryKey`/`getIssue`/`updateIssueFields`
  aanwezig. `autoAdvanceStory`/`autoAdvanceSubtask`/`autoApproveActive` compileren conform
  bestaande signaturen. `fieldUpdate` plaatst `AUTO_APPROVE` correct in de string-veld-groep;
  read-back in YouTrackClient en ManualCommandService mappen `on/true` → boolean (default uit).
- [info] Idempotentie auto-advance: na het zetten van `*-approved` verschuift de fase, dus de
  `*-ed`-tak vuurt niet opnieuw — geen dubbele writes. ManualCommandService-test bevestigt
  single-write bij herhaalde comment.
- [suggestie] (herbevestigd) REVIEWED- en TESTED-subtask-takken delen de geteste
  `autoAdvanceSubtask`-helper (DEVELOPED/SUMMARIZED gedekt); risico laag, eigen test wenselijk
  voor volledige dekking maar geen blocker.
- [blocker-risico, niet-blokkerend voor deze fase] De Kotlin-implementatie staat in de
  working-tree maar is nog NIET gecommit (alleen `specs/refactor-plan.md` zit in `main..HEAD`).
  Vóór merge moet de factory de working-tree-changes daadwerkelijk committen, anders mergen
  uitsluitend de doc-changes en niet de feature. Aandachtspunt voor de commit/merge-stap.

## Test-notities (SF-14, tester, 2026-06-10)

Gedragstest van de SF-10 auto-approve feature, lokaal via Maven (`mvn -f softwarefactory/pom.xml test`).
Maven was niet voorgeïnstalleerd; tijdelijk Apache Maven 3.9.11 in /tmp gebruikt om de
testsuite te draaien (geen wijziging aan repo/infra).

Resultaat: **alle 29 testklassen groen — 0 failures, 0 errors, 0 skipped.**
Relevante suites: TrackerCommentParserTest (4), ManualCommandServiceTest (16),
OrchestratorServiceTest (32), YouTrackClientTest (4).

Geverifieerd gedrag (acceptatiecriteria SF-10):
- Parser: `AUTO-APPROVE=on` → `AutoApproveTrigger(true)`, `=off` → `AutoApproveTrigger(false)`.
- ManualCommandService: trigger zet `issue.fields.autoApprove` en schrijft veld idempotent
  (één write bij herhaalde comment).
- Story: `REFINED` + auto-approve=aan → advance naar `refined-approved`;
  `PLANNED` → `planning-approved`; auto-approve=uit → blijft `waiting-for-approval`.
- Subtask: `DEVELOPED`/`SUMMARIZED` met parent-auto-approve=aan → advance naar `*-approved`.
  `REVIEWED`/`TESTED` delen exact dezelfde geteste `autoAdvanceSubtask`/`autoApproveActive`-
  helper (codepad geverifieerd) — gedrag identiek.
- Grenzen bewaakt: `developed-with-questions` advancet NIET (blijft waiting-for-user);
  `*-with-questions` en `AWAITING_HUMAN` ongewijzigd; geen auto-reject.

Opmerkingen:
- Geen preview-deploy ingericht voor deze repo (backend-only feature, geen UI-pad);
  daarom geen browser-/screenshot-test van toepassing.
- Aandachtspunt (overgenomen van reviewer, niet-blokkerend): REVIEWED/TESTED-takken
  hebben geen eigen unit-test maar delen de geteste helper; eigen test wenselijk voor
  100% tak-dekking. Geen functioneel risico.

### Onafhankelijke her-run (SF-14, tester, 2026-06-10)

Testsuite zelf opnieuw gedraaid op de huidige working-tree (Maven niet voorgeïnstalleerd;
Apache Maven 3.9.11 tijdelijk in /tmp geïnstalleerd, geen repo/infra-wijziging).
Commando: `mvn -f softwarefactory/pom.xml test`.

Resultaat: **BUILD SUCCESS — 166 tests, 0 failures, 0 errors, 0 skipped (29 testklassen).**
SF-10-relevante suites bevestigd: TrackerCommentParserTest (4), ManualCommandServiceTest (16),
OrchestratorServiceTest (32), YouTrackClientTest (4) — alle groen.

Gedrag bevestigd via assertions in de tests (niet alleen compile):
- Parser `AUTO-APPROVE=on/off` → `AutoApproveTrigger(true/false, sourceText)`.
- `ManualCommandService`: trigger zet `issue.fields.autoApprove` idempotent.
- Story `refined`→`refined-approved`, `planned`→`planning-approved`; `auto-approve=off`
  blijft waiting-for-approval.
- Subtask `developed`→`development-approved`, `summarized`→`summary-approved` via
  parent-auto-approve; `developed-with-questions` advancet NIET.

Opmerking: in OrchestratorServiceTest-logs verschijnt een verwacht, bewust gelogde
stacktrace (CostMonitorService stale-run); dit is geen testfout — alle reports zijn groen.
Geen preview-deploy beschikbaar (backend-only feature) → geen browser-/screenshot-test
van toepassing. Akkoord vanuit testperspectief.

### Verificatie-run (SF-14, tester, 2026-06-10, herhaald)

Onafhankelijke gedragsverificatie op de huidige working-tree. Maven niet
voorgeïnstalleerd; Apache Maven 3.9.9 tijdelijk gedownload naar `/tmp` (3.9.11 was
niet meer op de mirror, archive.apache.org gebruikt) — geen repo/infra-wijziging.
Commando: `mvn -f softwarefactory/pom.xml test`.

Resultaat: **BUILD SUCCESS — Tests run: 166, Failures: 0, Errors: 0, Skipped: 0.**

Gedrag direct uit de assertions geverifieerd (niet alleen compile/groen):
- Parser: `AUTO-APPROVE=on` → `AutoApproveTrigger(true, "AUTO-APPROVE=on")`,
  `=off` → `AutoApproveTrigger(false, ...)`.
- `ManualCommandService`: `AUTO-APPROVE=on`-comment zet `issue.fields.autoApprove=true`,
  idempotent (één write bij herhaalde comment).
- Story: `refined`+auto-approve → `refined-approved`; `planned`+auto-approve →
  `planning-approved` (beide met `STORY_PHASE`-veldupdate geverifieerd).
- Story negatief: `refined` zonder auto-approve → `Skipped(waiting-for-approval)`
  (default gedrag ongewijzigd).
- Subtask: `developed`→`development-approved` en `summarized`→`summary-approved` via
  parent-auto-approve (`SUBTASK_PHASE`-veldupdate geverifieerd).
- Grens bewaakt: `developed-with-questions` met parent-auto-approve advancet NIET →
  `Skipped(waiting-for-user)`. Geen auto-reject in codepad.

Aandachtspunt (overgenomen, niet-blokkerend vanuit testperspectief): de Kotlin-feature
staat in de working-tree maar nog niet in `main..HEAD` (alleen `specs/refactor-plan.md`
is gecommit). Getest gedrag is correct; de commit/merge-stap moet de working-tree-changes
daadwerkelijk meenemen. Dit is een commit/merge-procespunt, geen testfout.

Conclusie: alle SF-10-acceptatiecriteria gedragsmatig afgedekt en groen. Akkoord.

### Story-brede testrun (SF-14, tester, 2026-06-10)

Onafhankelijke gedragsverificatie op de huidige working-tree. Maven niet
voorgeïnstalleerd; Apache Maven 3.9.9 tijdelijk naar `/tmp` gedownload
(geen repo/infra-wijziging). Commando: `mvn -f softwarefactory/pom.xml test`.

Resultaat: **BUILD SUCCESS — Tests run: 166, Failures: 0, Errors: 0, Skipped: 0**
(29 testklassen). SF-10-relevante suites bevestigd via assertions (niet alleen compile):
- TrackerCommentParserTest (4): `AUTO-APPROVE=on/off` → `AutoApproveTrigger(true/false, sourceText)`.
- ManualCommandServiceTest (16): comment zet `issue.fields.autoApprove` en schrijft veld
  idempotent (één write `AUTO_APPROVE=on` bij herhaalde comment).
- OrchestratorServiceTest (32):
  - Story `refined`+auto-approve → `Recovered(refined-approved)` (STORY_PHASE-update geverifieerd);
    `planned` → `planning-approved`.
  - Story negatief: `refined` zonder auto-approve → `Skipped(waiting-for-approval)` (gedrag ongewijzigd).
  - Subtask `developed`→`development-approved`, `summarized`→`summary-approved` via parent-auto-approve
    (SUBTASK_PHASE-update geverifieerd).
  - Grens bewaakt: `developed-with-questions` met parent-auto-approve → `Skipped(waiting-for-user)`,
    advancet NIET. Geen auto-reject in codepad.
- YouTrackClientTest (4): veld-mapping `on/true` → boolean, default uit.

Aandachtspunten (overgenomen, niet-blokkerend):
- REVIEWED/TESTED-subtask-takken hebben geen eigen test maar delen de geteste
  `autoAdvanceSubtask`/`autoApproveActive`-helper (codepad geverifieerd) — geen functioneel risico.
- Geen preview-deploy voor deze repo (backend-only feature, geen UI-pad) → geen browser-/screenshot-test
  van toepassing.
- Procespunt (commit/merge): Kotlin-feature staat in de working-tree; de merge moet die changes
  daadwerkelijk meenemen (niet alleen de doc-changes). Geen testfout.

Conclusie: alle SF-10-acceptatiecriteria gedragsmatig afgedekt en groen. Akkoord vanuit testperspectief.
