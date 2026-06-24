# SF-180 / SF-181 - Worklog

## Story in eigen woorden
Bij **actieve auto-approve** moet de Software Factory via Telegram extra voortgangsmeldingen
sturen, zodat je de keten kunt volgen zonder zelf goed te keuren. Concreet in
`TelegramNotificationService.kt`:
- nieuwe categorie `PROGRESS` (informatief, niet replyable);
- "Refining klaar" bij story-fase `PLANNING` (gepromote description als context, max 1200 tekens);
- "Planning klaar" bij story-fase `PLANNING_APPROVED` (subtaak-overzicht i.p.v. de oude DONE-melding);
- "Klaar"-melding per afgeronde subtaak met story-overzicht en — als de hele story af is en er
  een PR ligt — een merge-actie in hetzelfde bericht.

Bij auto-approve UIT blijft alles ongewijzigd (regressie).

## Checklist
- [x]: read issue and target docs
- [x]: PROGRESS toevoegen aan NotifyCategory (niet-replyable)
- [x]: classifyStory PLANNING + auto-approve -> PROGRESS "Refining klaar"
- [x]: classifyStory PLANNING_APPROVED + auto-approve -> PROGRESS "Planning klaar" met overzicht
- [x]: contextbepaling in notifyPending uitgebreid (PROGRESS) met bestaande idempotentie-signature
- [x]: data class SubtaskDoneInfo + helper buildSubtaskDoneInfo
- [x]: terminale subtaak -> tryNotifyMergeReady alleen bij auto-approve UIT, anders nieuwe tak
- [x]: buildMessage uitgebreid (PROGRESS-header/context, subtaak-DONE-context, merge-regel)
- [x]: unit-tests voor AC1-AC6 incl. regressie auto-approve UIT en idempotentie
- [x]: update story-log met resultaten

## Wat is er gedaan en waarom
Alle productie-wijzigingen zitten in
`softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/telegram/TelegramNotificationService.kt`:

1. `NotifyCategory` kreeg `PROGRESS`; `replyable` blijft ongewijzigd (alleen QUESTION/APPROVAL/MANUAL),
   dus PROGRESS/DONE/ERROR zijn informatief.
2. `NotifyEvent` heeft een optioneel `header`-veld zodat de twee PROGRESS-mijlpalen elk hun eigen
   header dragen ("ℹ️ Refining klaar, begint met plannen" / "ℹ️ Planning klaar, begint met uitvoeren").
3. `classifyStory`: `PLANNING + autoApprove -> PROGRESS`, `PLANNING_APPROVED + autoApprove -> PROGRESS`
   (zonder auto-approve nog steeds DONE).
4. `notifyPending`: context wordt nu ook voor PROGRESS bepaald (`progressContext`) met dezelfde
   idempotentie-signature `context?.let { sig + ":" + it.hashCode() } ?: sig`. De subtaak-DONE-tak
   splitst: auto-approve UIT -> bestaande `tryNotifyMergeReady`; auto-approve AAN -> `notifySubtaskDone`.
5. `progressContext`/`planningOverview` bouwen de PROGRESS-context (description resp. subtaak-overzicht
   met `[ ]`/`[X]` via `SubtaskPhase.isTerminal`). Tracker-calls staan in `runCatching` -> nette degradatie.
6. `SubtaskDoneInfo` + `buildSubtaskDoneInfo`: parent via `parentStoryKey`, subtaken via `subtasksOf`,
   `[X]`/`[ ]`-markering, bij alle terminaal "Story helemaal afgerond! 🎉" en bij een open PR
   (`mergeReady`) de `mergeInfo`. Bij `mergeInfo != null` komt de merge-reply-regel in het bericht en
   wordt `store.savePending(..., MERGE_READY_PHASE)` opgeslagen; er gaat geen apart merge-ready bericht.
7. `buildMessage`: gebruikt `event.header` indien gezet, rendert PROGRESS- en subtaak-DONE-context
   (afgekapt op 1200 tekens) en voegt voor DONE met `mergeOffer` de merge-regel toe.

Tests: nieuw bestand
`softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/telegram/TelegramNotificationServiceTest.kt`
met testdoubles (FakeTracker/FakeStore/RecordingTelegramClient + FakeDashboard die alleen `mergeReady`
overschrijft; auto-approve leunt op de echte logica). Dekt AC1 (incl. 1200-afkapping en "geen melding
zonder auto-approve"), AC2 (+regressie DONE zonder auto-approve), AC3 (overzicht + afrond-regel), AC4
(merge-actie + savePending), AC5 (subtaak zonder auto-approve via bestaande tak) en AC6 (idempotentie).

## Specs
`docs/factory/` bevat geen documentatie over de Telegram-meldingen (grep op "Telegram" leeg), dus er
zijn geen functionele/technische specs of UX-docs die bijgewerkt hoeven te worden.

## Build/test
De factory-agent-omgeving heeft geen gevulde `~/.m2` en draait offline, dus
`mvn -f softwarefactory/pom.xml test` kan hier niet draaien (parent-POM niet resolvebaar). Correctheid
is statisch geverifieerd; de factory-pipeline/CI draait de volledige suite.
