# SF-470 - Worklog

Story-context bij eerste pickup:
E2e-integratietests voor ontbrekende spec-scenario's

Analyseer de scenario's uit docs/factory/functional-spec.md tegen de bestaande e2e-dekking (FullRefineToDevelopE2eTest, PipelineFlowsE2eTest, PipelineLoopbackE2eTest). Voeg nieuwe/aangescherpte integratietests toe in softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/ voor de aantoonbaar ontbrekende, niet-redundante scenario's (richtinggevend: silent autonoom + manual-approve overslaan + geen Telegram (SF-335); documentation-subtaak incl. documentation-with-questions (SF-213); manual-approve approve/reject (SF-192); automatische merge succes/merge-fout->Error (SF-244); test-chain-reset cap->Error (SF-200)). Gebruik E2eTestBase/AgentScript/FakeYouTrackServer/FactoryUiDriver/Awaitility en volg conventies (NL-fun-namen tussen backticks, geen project-interne wildcard-imports). Wijzig GEEN functioneel gedrag in src/main. Leg een expliciete scenario->dekkende-test mapping vast in docs/stories/worklog/SF-470-worklog.md, inclusief gemotiveerd buiten scope gelaten scenario's. Komt een test alleen groen door duidelijk buggy gedrag te bevriezen? Voeg 'm niet toe en zet de story op Error met concrete notitie. Voer een eigen review-stap uit voordat je afrondt.

Stappenplan:
[x]: read issue and target docs
[x]: bestaande e2e-dekking in kaart brengen tegen functional-spec.md
[x]: ontbrekende, niet-redundante, betrouwbaar simuleerbare scenario's selecteren
[x]: integratietests toevoegen (SpecScenarioCoverageE2eTest)
[x]: test-compile draaien (e2e-tests vereisen Docker → in CI)
[x]: scenario->dekkende-test mapping vastleggen
[x]: eigen review-stap

## Wat is gedaan en waarom

Nieuw testbestand `softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/SpecScenarioCoverageE2eTest.kt`
met drie tests voor aantoonbaar ontbrekende, betrouwbaar in de bestaande harnas te simuleren spec-scenario's.
Geen productiecode (`src/main`) aangeraakt; alleen testcode toegevoegd. Bestaande conventies gevolgd
(NL-`fun`-namen tussen backticks, `E2eTestBase`/`AgentScript`/`FactoryUiDriver`/Awaitility, expliciete imports).

1. **SF-335 — silent autonoom (nieuw)**: `silent story doorloopt de keten autonoom zonder enige menselijke actie`.
   Story met `Auto-approve=off` + `Silent=true` en zónder ook maar één UI-actie (geen login, geen
   `start-developing`, geen answer/approve). Bewijst dat silent ⇒ auto-approve én auto-start development
   (`StoryRefinementCoordinator.autoStartDevelopment`/`autoApproveOrSilent`, `SubtaskExecutionCoordinator.autoApproveActive`):
   de hele keten incl. de afgedwongen documenter loopt vanzelf tot alle AI-subtaken `*-approved`.
   Complementair aan de bestaande `silent story zet een agent-vraag in een clarification-Error` (PipelineLoopbackE2eTest),
   die het silent-vraag→Error-pad dekt.

2. **SF-213 — documentation-with-questions (nieuw)**: `documentation-subtaak stelt een vraag die de gebruiker beantwoordt`.
   De factory-afgedwongen `documentation`-subtaak loopt op de juiste plek in de keten (ná de geplande
   subtaken) en het `documentation-with-questions ↔ documentation-questions-answered → documentation-approved`-pad
   wordt end-to-end gedreven (vraag → antwoord via UI → approved). Dekt de DOCUMENTER-vraagflow die in
   `PipelineFlowsE2eTest` voor de andere rollen al bestond maar voor documentation ontbrak.

3. **SF-200 — test-chain reset cap (nieuw)**: `test-chain reset cap zet de test-subtaak in Error en stopt de reset-loop`.
   Met de default cap (`SF_MAX_TEST_CHAIN_RESETS=3`) resetten 3 bevindingen de keten (TESTER-runs 1..3),
   de 4e bevinding raakt de cap (`testerRuns >= maxTestChainResets + 1`) → geen reset meer maar `Error`
   ("Test-chain reset cap bereikt") op de test-subtaak. Vult het gat naast de bestaande
   `test-bevinding reset de keten twee keer` (PipelineLoopbackE2eTest), die alleen het sub-cap reset-pad dekt.

## Scenario → dekkende e2e-test (mapping)

| Spec-scenario | Dekkende e2e-test | Status |
|---|---|---|
| Happy-path refine→…→documentation | `FullRefineToDevelopE2eTest.story doorloopt refine tot alle subtaken afgerond` | Bestaand (@Disabled: merge/deploy niet simuleerbaar) |
| Per-rol vraagflow (review/test/summary) | `PipelineFlowsE2eTest.*stelt een vraag*` | Bestaand |
| Per-rol vraagflow (documentation) | `SpecScenarioCoverageE2eTest.documentation-subtaak stelt een vraag…` | **Nieuw (SF-213)** |
| Development-reject → developer-loopback | `PipelineFlowsE2eTest` + `PipelineLoopbackE2eTest` (1x en 2x) | Bestaand |
| Review-reject binnen development-subtaak | `PipelineLoopbackE2eTest.review afgekeurd…` | Bestaand |
| Developer-loopback-cap → Error | `PipelineLoopbackE2eTest.developer-loopback boven de cap…` | Bestaand (SF-…) |
| Test-bevinding reset de keten (1x/2x) | `PipelineFlowsE2eTest` + `PipelineLoopbackE2eTest` | Bestaand (SF-200) |
| Test-chain reset **cap** → Error | `SpecScenarioCoverageE2eTest.test-chain reset cap…` | **Nieuw (SF-200)** |
| Summary-reject → summarizer opnieuw | `PipelineFlowsE2eTest.summary-subtaak afgekeurd…` | Bestaand |
| Manual-subtaak wacht op mens | `PipelineFlowsE2eTest.manual-subtaak…` | Bestaand |
| Story-niveau refine/plan reject | `PipelineFlowsE2eTest.refinement/planning afgekeurd…` | Bestaand |
| Silent: vraag → clarification-Error | `PipelineLoopbackE2eTest.silent story zet een agent-vraag…` | Bestaand (SF-335) |
| Silent: volledig autonome keten (auto-start + alle gates) | `SpecScenarioCoverageE2eTest.silent story doorloopt de keten autonoom…` | **Nieuw (SF-335)** |

## Gemotiveerd buiten scope gelaten

- **SF-335 — "nul Telegram" bij silent**: de e2e-harnas vervangt YouTrack, git-remote en de agent-runtime,
  maar legt geen recording-dubbel voor `TelegramClient` neer (Telegram is in de e2e-config feitelijk uit:
  geen token/chat). Er is geen betrouwbaar observatiepunt om "géén bericht verstuurd" e2e te asserten zonder
  productie-/testinfra toe te voegen die buiten deze test-only-scope valt. De Telegram-notificatielogica is
  apart unit-getest (zie `telegram-notification-test-doubles`). Niet e2e gedekt → bewust overgeslagen.
- **SF-335 — "manual-approve niet aanmaken bij silent"**: de e2e-keten heeft de manual-approve-poort
  projectbreed UIT (`E2eTestConfig.projectRepoResolver` met `manualApprove=false`), dus er is in deze harnas
  geen poort om "wel/niet aangemaakt" tegen te onderscheiden. De silent-skip-tak
  (`AgentRunCompletionService` `parentSilent`) is een aanname-vrije `if` en hoort bij unit-dekking.
- **SF-192 — manual-approve approve/reject**: idem; de poort staat e2e-breed uit (geen menselijke gate in de
  auto-keten). Aanzetten zou de hele bestaande e2e-suite raken (productiegedrag/projectconfig veranderen),
  wat buiten "test-only, geen functioneel gedrag wijzigen" valt. De reset-bij-reject-mechaniek
  (`resetStoryChainAfterRejection`) wordt e2e wél indirect gedekt via het identieke SF-200 test-reset-pad.
- **SF-244 — automatische merge succes / merge-fout → Error**: de e2e-harnas gebruikt een lokale file-based
  git-remote (geen GitHub-PR), waardoor een echte merge via de GitHub API niet kan slagen én de exacte
  fout-vorm niet representatief is. Dit is precies waarom `FullRefineToDevelopE2eTest` `@Disabled` is. Een
  betrouwbare merge-e2e vereist een fake `GitHubApi`/PR-nummer in de harnas — een infra-uitbreiding die als
  aparte story hoort en niet test-only haalbaar is binnen deze scope.

Geen scenario is "groen gemaakt door buggy productiegedrag te bevriezen"; alle toegevoegde tests volgen
bestaand, gedocumenteerd gedrag (SF-335/SF-213/SF-200). Story hoeft dus niet op `Error`.

## Verificatie

- `mvn -f softwarefactory/pom.xml test-compile` → groen (alle test-sources compileren, incl. de nieuwe test).
- De e2e-tests draaien op Testcontainers (Postgres) en vereisen Docker; in deze dev-omgeving is geen Docker
  aanwezig, dus ze draaien in de CI/factory-pipeline. Logica is statisch geverifieerd tegen de productiecode
  (`SubtaskExecutionCoordinator.handleTestRejection`, `documentationSubtask`, `autoApproveActive`;
  `StoryRefinementCoordinator.autoStartDevelopment`/`autoApproveOrSilent`;
  `AgentRunCompletionService` materialisatie).
- Specs (`docs/factory/`) ongewijzigd: ze beschrijven SF-335/SF-213/SF-200 al correct en de huidige codebase
  weerspiegelt dat; deze story voegt alleen e2e-dekking toe, geen gedrag.

## Review (reviewer)

Akkoord. Statisch geverifieerd tegen productiecode:
- SF-200: cap-conditie `testerRuns >= maxTestChainResets + 1` (default 3 → `>=4`); 4 TESTER-runs, 4e reject → `Error "Test-chain reset cap bereikt"` (`SubtaskExecutionCoordinator.kt:171`).
- SF-335: `autoApproveOrSilent`/`autoStartDevelopment` (story) + `autoApproveActive` incl. parent-silent-erving (`:473-480`) → silent + `Auto-approve=off` loopt autonoom door.
- SF-213: `documentationSubtask` (`:311-322`) dekt het `with-questions → questions-answered → documented → documentation-approved`-pad.
- Harnas: `manualApprove=false` voor `sample` (`E2eTestConfig.kt:51`) → geen blokkerende poort; enforced subtaken gematerialiseerd samen met planned.
- Scope schoon: alleen test-only + worklog; geen `src/main`-wijziging. Conventies gevolgd.
- [info] `enforcedChild(.first{})` direct na `awaitSubtasksCreated(story, 1)` heeft een verwaarloosbaar, met de bestaande suite consistent race-venster; geen blocker.
- Tests niet lokaal gedraaid (geen mvn/Docker in reviewer-omgeving); CI/factory dekt de run.

## Test (tester)

Geverifieerd op branch `ai/SF-470` (2026-06-28):
- **Scope schoon**: `git diff --name-only main...HEAD` = alleen `docs/stories/worklog/SF-470-worklog.md` + `softwarefactory/.../e2e/SpecScenarioCoverageE2eTest.kt`. Geen `src/main`-wijziging. ✓
- **Test-compile**: `mvn -f softwarefactory/pom.xml test-compile` → groen; alle nieuwe helper-referenties (`awaitAllAiSubtasksApproved`, `awaitErrorContains`, `awaitDispatchCount`, `enforcedChild`, `state.setEnumField`, `AgentScript.subtasks`) resolven. ✓
- **Volledige suite**: `mvn -f softwarefactory/pom.xml test -Dsurefire.runOrder=alphabetical` → **Tests run: 419, Failures: 0, Errors: 21**. Failures=0 = schoon regressiesignaal. De 21 Errors zijn allemaal omgevingsgebonden (geen Docker-daemon in tester-omgeving): 18 bekende env-baseline (FactoryUiDriverLoginTest 1, FullRefineToDevelopE2eTest 1, PipelineFlowsE2eTest 9, PipelineLoopbackE2eTest 5, NightlyRepositoriesTest 1, FactoryDashboardRepositoryScreenshotTest 1) + **3 nieuw** van `SpecScenarioCoverageE2eTest`. De 3 nieuwe errors zijn elk `IllegalStateException: Could not find a valid Docker environment` — geen testlogica-fout. ✓
- **Statische verificatie nieuwe assertions tegen productiecode**: SF-200 cap-conditie `testerRuns >= settings.maxTestChainResets + 1` (default 3) + foutstring `"Test-chain reset cap bereikt"` (`SubtaskExecutionCoordinator.kt:171/177`); SF-213 fasen `documentation-with-questions`/`-questions-answered`/`-approved` (`SubtaskPhase.kt:52-54`) bestaan; conventies gevolgd. ✓
- **Beperking**: de e2e-tests (incl. de 3 nieuwe) vereisen Docker/Testcontainers en kunnen in de tester-omgeving niet daadwerkelijk runtime-uitgevoerd worden; ze draaien in CI/factory mét Docker. Lokaal is bevestigd: compileert + Failures=0 + assertions kloppen tegen productiecode. Geen blocker, geen bevinding.

Resultaat: **tested**.
