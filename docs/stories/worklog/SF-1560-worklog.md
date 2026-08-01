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

## Review (SF-1652) — 01-08-2026

Akkoord. Diff t.o.v. `main` volledig doorgelopen: alleen `SubtaskExecutionCoordinator.dispatchSubtask`,
`DeploySubtaskHandler.process`, test-support en tests; geen `factory-common`-, `deployTargetsFor`- of
`needsWatch`-wijziging en geen andere `runCatching{}.getOrNull()` aangeraakt (AC8). AC1-AC7 nagelopen
op de code: skip vóór dispatch/DEPLOY_APPROVED, één NL-warn met `{}`-placeholder en throwable als
laatste argument, `parentKey == null`-takken ongewijzigd, `budgetIssue = parent`.

Gerichte hercontrole in de reviewomgeving (naast het harness-geverifieerde developerbewijs):
`mvn -pl factory-common,softwarefactory -am test -Dtest=DeploySubtaskHandlerTest,OrchestratorPrAndLoopbackTest,OrchestratorSubtaskFlowTest,OrchestratorSubtaskRecoveryTest`
→ 56 tests, 0 failures/errors; plus `-Dtest=OrchestratorSubtaskChainTest,ManualCommandServiceTest,StoryPurgeServiceTest,FactoryDashboardServiceTest,AgentRunCompletionServiceTest`
→ 59 tests groen (de overige `FakeTrackerApi(parentKey = …)`-gebruikers die niet in de diff staan).

Opmerkingen (geen blockers):
- [info] Met het vervallen van `?: projectRepoResolver.resolve(subtask.fields.repo)` is `targetRepo`
  ook `null` als de parent wél leesbaar is maar een leeg/onbekend `Repo`-veld heeft; dat is een
  (bewuste, door de story voorgeschreven) gedragswijziging bij een geslaagde lees. In de praktijk is
  het subtask-`Repo`-veld in dat geval doorgaans óók leeg, dus geen praktisch verschil.
- [suggestie] De Logback-`ListAppender` in beide nieuwe tests wordt niet weer verwijderd
  (`detachAppender`); onschuldig zolang de suite serieel draait, maar een `@AfterEach`-opruiming
  voorkomt latere verrassingen als er ooit parallelle uitvoering aangezet wordt.

## Test (SF-1653) — 01-08-2026

Story-brede verificatie op branch `ai/SF-1560` (HEAD 1219ff9).

Uitgevoerd:
- `mvn -B --no-transfer-progress -pl softwarefactory -am test` → BUILD SUCCESS, exitcode 0,
  685 tests, 0 failures / 0 errors (factory-contracts + factory-common meegebouwd).
- `mvn -B --no-transfer-progress -pl softwarefactory -am verify` → BUILD SUCCESS, exitcode 0,
  685 unit + 74 failsafe/e2e (Testcontainers-Postgres), 0 failures / 0 errors. Geen flakes deze ronde
  (o.a. TesterVerificationEvidenceE2eTest en ChainCompositionE2eTest groen).
  De `PSQLException ... violates foreign key constraint`-regels in de e2e-log horen bij bewust
  negatieve scenario's binnen slagende tests, geen failures.

AC-controle tegen de diff (`git diff main...HEAD`):
- AC1/AC2: `SubtaskExecutionCoordinator.dispatchSubtask` gebruikt `getOrElse` met NL-warn
  (`{}`-placeholder + throwable als laatste argument) en `Skipped(key, "parent-unavailable")` vóór
  élke pauze-/fout-/supplier-check en vóór `dispatcher.dispatch`. Nieuwe test
  `OrchestratorPrAndLoopbackTest."unreadable parent story skips the subtask instead of dispatching an agent"`
  asserteert Skipped-reden, lege `runtime.dispatches` én exact één WARN met parent-key + throwable.
- AC3/AC4: `DeploySubtaskHandler.process` skipt met `"deploy-parent-unavailable"` vóór
  `matchedTargets`/`watchTargets`; nieuwe test dekt expliciet fase `START` (het pad dat vandaag ten
  onrechte approvde) en asserteert géén fase-update (dus ook geen `DEPLOY_APPROVED`), geen
  `advanceChain` en probes die gooien als ze aangeroepen zouden worden.
- AC5: geslaagde-lees-paden ongewijzigd; bestaande tests voor `parent-paused`, de
  `DEPLOY_APPROVED`-tak bij leeg `watchTargets` en de `parentKey == null`-takken zijn groen. De
  aangepaste bestaande tests kregen alleen een leesbare parent (`parentIssue = issue(...)`) mee,
  omdat ze eerder onbedoeld op de "onleesbare parent"-seam leunden.
- AC6: `budgetIssue = parent` (fallback `?: subtask` verwijderd).
- AC7: nieuwe tests dekken punt 1 en 3; volledige module-suite groen.
- AC8: diff raakt geen `factory-common`, geen `deployTargetsFor`/`needsWatch` en geen andere
  `runCatching { … }.getOrNull()`-aanroep.

Opmerking (geen blocker, al door de reviewer vastgelegd): met het vervallen van de
`?: resolve(subtask.fields.repo)`-fallback is `targetRepo` ook null als de parent wél leesbaar is maar
een leeg `Repo`-veld heeft. Dat is door de story expliciet zo voorgeschreven.

Preview/browser: voor deze factory-repo is geen preview-deploy of browsercontext ingericht
(`SF_PREVIEW_URL` leeg), dus geen screenshots; de e2e-suite is hier het gedragsbewijs.
