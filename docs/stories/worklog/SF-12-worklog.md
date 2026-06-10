# SF-12 - Worklog

## Story (eigen woorden)
Subtask van SF-10 (auto approve). Bouw de feitelijke auto-advance in
`OrchestratorService.kt` op het trigger/veld-fundament uit SF-11. Als auto-approve
aan staat, lopen de "waiting-for-approval"-statussen automatisch door naar het
bijbehorende `*-approved`, zodat de gebruiker niet meer handmatig hoeft te approven.
Vraag-zijtakken (`*-with-questions`) en manual (`AWAITING_HUMAN`) blijven op de
gebruiker wachten; er wordt nooit auto-gereject. Default uit = bestaand gedrag.

## Checklist
[x]: read issue en target docs (.task.md, SF-10/SF-11-worklog)
[x]: story-niveau auto-advance: REFINED -> refined-approved, PLANNED -> planning-approved
[x]: subtask-niveau auto-advance: DEVELOPED/REVIEWED/TESTED/SUMMARIZED -> *-approved
[x]: auto-approve van de PARENT-story lezen (centrale helper autoApproveActive)
[x]: grenzen bewaken: *-with-questions en AWAITING_HUMAN blijven waiting-for-user
[x]: tests toegevoegd (story + subtask + negatieve gevallen)
[ ]: tests gedraaid -- maven niet beschikbaar in deze omgeving

## Gedaan / rationale
- `OrchestratorService.kt`:
  - `autoAdvanceStory(issue, approved)`: leest `issue.fields.autoApprove`. Aan ->
    schrijf `STORY_PHASE = *-approved` en geef `Recovered` terug (volgende poll pakt
    de approved-tak op). Uit -> `Skipped(waiting-for-approval)` (ongewijzigd gedrag).
    Gebruikt in `processStoryRefinement` voor `REFINED` en `PLANNED`.
  - `autoAdvanceSubtask(subtask, approved)`: idem op `SUBTASK_PHASE`, maar de
    auto-approve-vlag staat op de PARENT-story. Gebruikt in de `DEVELOPED`/`REVIEWED`
    (developmentSubtask), `REVIEWED` (reviewSubtask), `TESTED` (testSubtask) en
    `SUMMARIZED` (summarySubtask) takken.
  - `autoApproveActive(subtask)`: centrale helper; checkt eerst de subtask zelf en
    daarna best-effort de parent via `parentStoryKey` + `getIssue` (runCatching ->
    default uit als parent ontbreekt/faalt). Consistente bron voor alle vier handlers.
  - Bij voorkeur het tracker-veld op `*-approved` gezet (i.p.v. direct doordispatchen),
    zodat de UI een consistente status toont en de bestaande approved-flow het oppakt.
- Grenzen ongewijzigd: `*-with-questions` en `AWAITING_HUMAN` blijven
  `Skipped(waiting-for-user)`; nooit auto-reject.
- Tests (`OrchestratorServiceTest.kt`): `autoApprove`-param toegevoegd aan de issue-helper;
  nieuwe tests voor REFINED->refined-approved, PLANNED->planning-approved, auto-approve
  uit blijft waiting, parent-auto-approve advancet DEVELOPED en SUMMARIZED, en
  developed-with-questions blijft waiting-for-user ook met auto-approve aan.

## Aandachtspunten
- Auto-approve wordt van de PARENT gelezen via een extra `getIssue`-call in de
  `*-ed`-tak; dit gebeurt alleen wanneer een subtask op een approval-moment staat
  (niet in de hot path van actieve fases).
- Maven/wrapper ontbreekt in deze omgeving; tests konden niet gedraaid worden.

## Review (reviewer, 2026-06-10)
- [info] Implementatie volgt het plan exact: `autoAdvanceStory`/`autoAdvanceSubtask`/`autoApproveActive` in `OrchestratorService.kt`, met `Recovered`-retour zodat de bestaande approved-tak op de volgende poll oppakt. `Recovered(storyKey, phase)`-signature en client-methoden (`parentStoryKey`, `getIssue`, `updateIssueFields`) kloppen.
- [info] Grenzen correct bewaakt: `*-with-questions` en `AWAITING_HUMAN` blijven `waiting-for-user`; nooit auto-reject. Default `autoApprove=false` → bestaand gedrag ongewijzigd (negatieve test aanwezig).
- [info] Veld-mapping (`YouTrackClient`/`ManualCommandService`) leest "on"/"true" tolerant en crasht niet als het custom field ontbreekt. Parser-pattern + idempotente `setAutoApprove` consistent met het supplier-patroon.
- [suggestie] Testdekking dekt twee van de vier subtask-takken (DEVELOPED, SUMMARIZED). REVIEWED en TESTED lopen via dezelfde helper, maar een expliciete test per tak zou regressies bij toekomstige refactors afvangen.
- [suggestie] Geen test voor de best-effort parent-lookup-fallback (`runCatching → false`) wanneer de parent ontbreekt/faalt; overweeg een korte negatieve test.
- [info] Tests konden hier niet gedraaid worden (geen mvn/wrapper, conform developer-notitie). Statisch zijn implementatie en tests consistent; aanbevolen `mvn -f softwarefactory/pom.xml test` in CI.

Conclusie: coherent, testbaar en binnen scope. Akkoord; de twee suggesties zijn niet-blokkerend.

## Review tweede pass (reviewer, 2026-06-10)
- [info] Onafhankelijk geverifieerd: alle gebruikte enum-trackerValues bestaan en matchen de test-asserts exact (`SubtaskPhase`: `development-approved`/`review-approved`/`test-approved`/`summary-approved`; `StoryPhase`: `refined-approved`/`planning-approved`).
- [info] Geverifieerd dat `YouTrackApi.parentStoryKey`/`getIssue`/`updateIssueFields` en `IssueProcessResult.Recovered(key, value)` bestaan en consistent gebruikt worden; `autoApproveActive` doet de parent-lookup best-effort via `runCatching`.
- [info] Veld in/uitlezen tolerant (`on`/`true`, case-insensitief) met default `false`; ontbrekend custom field crasht niet. Grenzen (`*-with-questions`, `AWAITING_HUMAN`) en "nooit auto-reject" bevestigd; default-uit = bestaand gedrag (negatieve test aanwezig).
- [info] Scope blijft beperkt tot de bedoelde modules; geen scope creep, geen secrets in output.
- [suggestie] Onveranderd t.o.v. eerste pass: expliciete tests voor REVIEWED/TESTED-takken en de parent-fallback (`runCatching → false`) zouden regressies afvangen — niet-blokkerend.
- [info] Tests niet gedraaid (geen mvn/wrapper in deze omgeving); aanbevolen `mvn -f softwarefactory/pom.xml test` in CI.

Eindoordeel: akkoord.
