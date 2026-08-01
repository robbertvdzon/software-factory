# SF-1560 - Worklog

Story-context bij eerste pickup:
Mislukte parent-lees skippen in dispatch en deploy

Module softwarefactory.

1) pipeline/service/SubtaskExecutionCoordinator.kt (dispatchSubtask, rond r362): vervang `runCatching { issueTrackerClient.getIssue(parentKey) }.getOrNull()` door de getOrElse-variant naar huisstijl (zie StoryRefinementCoordinator.kt:145-148, SubtaskExecutionCoordinator.kt:213, ManualCommandService.kt:405): `logger.warn` in het Nederlands met SLF4J-placeholders, parentKey als placeholder-argument en de throwable als laatste argument, gevolgd door `return IssueProcessResult.Skipped(subtask.key, "parent-unavailable")`. De pauze- (parent.fields.paused) en foutpoort (parent.fields.error) worden daardoor altijd geevalueerd voor dispatch. De bestaande `parentKey == null` -> Errored-tak blijft exact ongewijzigd.

2) Ruim in dezelfde methode de dode nullability op, want getIssue heeft een non-null returntype en parent is na de early return altijd non-null: `parent?.fields?.aiSupplier` -> `parent.fields.aiSupplier`; de targetRepo-fallback `?: projectRepoResolver.resolve(subtask.fields.repo)` vervalt samen met de nu onjuiste comment 'valt terug op het eigen Repo-veld als de parent (nog) niet leesbaar is'; `budgetIssue = parent ?: subtask` -> `budgetIssue = parent`. Bij een geslaagde lees is het gedrag exact gelijk aan vandaag.

3) pipeline/service/DeploySubtaskHandler.kt (process, rond r134): zelfde patroon met de bestaande logger (r61) en reden "deploy-parent-unavailable". De bestaande `parentKey == null` -> Skipped(..., "deploy-no-parent")-tak blijft ongewijzigd. Hiermee kan projectName niet meer door een leesfout null worden en kan de START-tak niet meer ten onrechte DEPLOY_APPROVED schrijven.

4) Tests (horen bij deze subtaak): a) DeploySubtaskHandlerTest.kt uitbreiden - laat de parentIssue()-lambda (r101) gooien en verifieer voor fase START dat het resultaat Skipped(subtask.key, "deploy-parent-unavailable") is, dat er geen SUBTASK_PHASE-update naar DEPLOY_APPROVED wordt geschreven, advanceChain niet wordt aangeroepen en geen deploy-doel wordt getriggerd. b) Dispatch-test via testsupport/OrchestratorTestHarness.kt en orchestrator/OrchestratorPrAndLoopbackTest.kt - laat parentKey gezet zijn maar de parent weg uit de FakeTrackerApi-issues (FakeTrackerApi.getIssue doet issues.first { ... } en gooit dan); verifieer Skipped(subtask.key, "parent-unavailable") en dat dispatcher.dispatch(...) NIET is aangeroepen.

Buiten scope, niet aanraken: DeployConfig.Skip()-default in factory-common ProjectConfiguration.deployTargetsFor, needsWatch/matchedTargets/matchedDeployTargetsFor, en alle overige runCatching{...}.getOrNull()-aanroepen in src/main. Geen retry/backoff, geen nieuwe fase, geen TrackerField.ERROR-write, geen Telegram-melding.

Afronden met een eigen review-stap op de diff en `mvn -pl softwarefactory test` volledig groen, inclusief alle bestaande tests.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## SF-1652 (development) — 01-08-2026

Wat het probleem was: op twee plekken werd een mislukte parent-lees
(`runCatching { getIssue(parentKey) }.getOrNull()`) stil als "geen parent" behandeld. Bij dispatch
sloeg dat de pauze- en foutpoort van de story over (er startte alsnog een betaalde agent); bij deploy
werd de subtaak zonder één echte actie op `deploy-approved` gezet. De juiste reactie is skippen: de
orchestrator komt elke poll terug, dus een tijdelijke trackerstoring lost zichzelf op.

Gedaan:
- `pipeline/service/SubtaskExecutionCoordinator.kt` (`dispatchSubtask`): `getOrNull()` →
  `getOrElse { logger.warn("Dispatch: kon parent-story {} niet laden; subtaak overgeslagen.", parentKey, it); return Skipped(subtask.key, "parent-unavailable") }`,
  in de huisstijl van `StoryRefinementCoordinator.kt:145-148` / `SubtaskExecutionCoordinator.kt:213`.
  De `parentKey == null` → `Errored`-tak erboven is ongewijzigd.
- Dode nullability in dezelfde methode opgeruimd (`getIssue` heeft een non-null returntype, dus
  `parent` is na de early return altijd non-null): `parent?.fields?.aiSupplier` →
  `parent.fields.aiSupplier`, de `?: projectRepoResolver.resolve(subtask.fields.repo)`-fallback en de
  bijbehorende (nu onjuiste) comment verwijderd, `budgetIssue = parent ?: subtask` → `budgetIssue = parent`.
  Bij een geslaagde lees is het gedrag exact gelijk aan vandaag.
- `pipeline/service/DeploySubtaskHandler.kt` (`process`): zelfde patroon met de bestaande logger en
  reden `"deploy-parent-unavailable"`; `projectName` kan niet meer door een leesfout `null` worden,
  dus de `START`-tak kan niet meer ten onrechte `DEPLOY_APPROVED` schrijven. De
  `parentKey == null` → `"deploy-no-parent"`-tak is ongewijzigd.

Tests (zelf geschreven):
- `DeploySubtaskHandlerTest` — nieuwe seam `parentReadFails` op de tracker-fake plus test
  `unreadable parent on START skips instead of approving the deploy`: verwacht
  `Skipped(subtaskKey, "deploy-parent-unavailable")`, géén enkele veld-update (dus ook geen
  `DEPLOY_APPROVED`), `advanceChain` niet aangeroepen, probe/apkProbe gooien als ze toch worden
  bevraagd, en precies één WARN-regel met parent-key + throwable (Logback `ListAppender`).
- `OrchestratorPrAndLoopbackTest` — `unreadable parent story skips the subtask instead of dispatching an agent`:
  `parentKey` gezet maar de parent ontbreekt in de `FakeTrackerApi`-issues, dus `getIssue` gooit;
  verwacht `Skipped("SF-8", "parent-unavailable")`, `runtime.dispatches` leeg en één WARN-regel.
- Boyscout op de bestaande fakes: `FakeTrackerApi` heeft er een optionele `parentIssue`-parameter bij
  voor een parent die wél leesbaar is maar niet in de werk-lijst hoort. Tien bestaande tests in
  `OrchestratorSubtaskFlowTest`/`OrchestratorSubtaskRecoveryTest`/`OrchestratorPrAndLoopbackTest`
  leunden onbedoeld op de oude "onleesbare parent == geen parent"-coulance en zijn daarmee weer
  expliciet correct gemaakt.

Bewijs: `mvn -B --no-transfer-progress clean verify` vanaf de repo-root → BUILD SUCCESS, exitcode 0,
0 failures / 0 errors (softwarefactory unit 685 tests + e2e/Testcontainers, agentworker,
dashboard-backend 50 tests), 04:57 min.

Specs: geen wijzigingen in `docs/factory/` nodig — geen enkel spec-document beschrijft de oude
parent-lees-fallback (gecontroleerd met een grep op de betreffende formuleringen).
