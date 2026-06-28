# SF-621 - Worklog

## Story in eigen woorden

Breid de e2e-/pipeline-integratietestsuite (`softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/`)
uit zodat de functioneel beschreven scenario's uit `docs/factory/functional-spec.md` aantoonbaar door
integratietests gedekt zijn. Eerst een expliciete gap-analyse (spec-scenario ↔ bestaande test, met
conclusie per scenario), daarna de met de huidige harness bereikbare ontbrekende tests toevoegen.
Geen wijzigingen onder `src/main`; alles test-only. Geen test mag duidelijk buggy gedrag bevriezen.

## Stappenplan

[x]: read issue and target docs
[x]: gap-analyse opstellen (spec ↔ bestaande tests)
[x]: ontbrekende, bereikbare integratietests toevoegen
[x]: tests/build draaien (zie sectie "Build/test")
[x]: story-log bijwerken met resultaten

## Gap-analyse: functional-spec-scenario's ↔ integratietests

Bron: `docs/factory/functional-spec.md`. Bestaande tests: `FullRefineToDevelopE2eTest` (FR),
`PipelineFlowsE2eTest` (PF), `PipelineLoopbackE2eTest` (PL), `SpecScenarioCoverageE2eTest` (SC),
`ManualApproveGateE2eTest` (MA).

| # | Spec-scenario | Status vóór SF-622 | Conclusie |
|---|---------------|--------------------|-----------|
| 1 | Volledige ketenvolgorde refine→dev→review→test→summary→documentation→(manual-approve)→merge→deploy | FR (`@Disabled`), deels PF | Gedekt (mechaniek per stap in PF/PL/SC); FR blijft bewust `@Disabled` (zie onder) |
| 2 | Refiner-vraag (niet-silent) → wacht op mens → antwoord via UI | PF `refinement stelt een vraag…` | Gedekt |
| 3 | Planner-vraag (`planned-with-questions`) → antwoord | PF `planning stelt een vraag…` | Gedekt |
| 4 | Developer-vraag → antwoord | PF `development-subtaak stelt een vraag…` | Gedekt |
| 5 | Reviewer/Tester/Summarizer-vraag → antwoord | PF (3 tests) | Gedekt |
| 6 | Documenter-vraag (SF-213) → antwoord | SC `documentation-subtaak stelt een vraag…` | Gedekt |
| 7 | Story-rejects (refined/planning rejected) → opnieuw | PF (2 tests) | Gedekt |
| 8 | Development-reject → developer-loopback (1× en 2×) | PF + PL | Gedekt |
| 9 | Review-rejected binnen dev-subtaak → developer-loopback | PL | Gedekt |
| 10 | Developer-loopback-cap → `Error` | PL | Gedekt |
| 11 | Test-bevinding reset de keten (SF-200), 1× en 2× | PF + PL | Gedekt |
| 12 | Test-chain-reset-cap (SF-200) → `Error` | SC | Gedekt |
| 13 | Manual-subtaak (niet-AI) wacht op mens en rondt af | PF | Gedekt |
| 14 | Silent story doorloopt keten autonoom (SF-335) | SC | Gedekt |
| 15 | Silent + **subtaak**-vraag → `[CLARIFICATION]`-Error i.p.v. wachten | PL `silent story zet een agent-vraag…` | Gedekt |
| 16 | Manual-approve poort **wacht** ook bij auto-approve (SF-192) | MA | Gedekt |
| 17 | Manual-approve **reject** reset de keten (SF-192) | MA | Gedekt |
| 18 | **Manual-approve approve → keten zet door richting merge-subtaak** (SF-192/SF-244) | — | **Nieuw toegevoegd** (MA, zie onder) |
| 19 | Silent + **story**-vraag (refiner/planner) → `[CLARIFICATION]`-Error | — | **Nieuw toegevoegd** (SC, zie onder) |
| 20 | Merge slaagt → keten gaat door naar deploy (SF-244) | — | Bewust niet-gedekt (zie onder) |
| 21 | Merge fout (conflict/GitHub) → merge-subtaak `Error`, keten stopt (SF-244) | — | **Nieuw toegevoegd** (impliciet via #18: merge faalt op ontbrekende PR in de harness → `Error` → keten stalt) |
| 22 | Telegram-melding bij afgeronde test-subtaak (SF-206) | — | Bewust niet-gedekt (zie onder) |
| 23 | Telegram-assistent (conversationeel kanaal) | — | Buiten scope (geen orchestrator-pijplijn) |
| 24 | Nightly scheduler (SF-350) | — | Bewust niet-gedekt / buiten e2e-pijplijn-scope (zie onder) |

## Nieuw toegevoegde tests (deze story)

- **`ManualApproveGateE2eTest` — approve-pad (#18, #21).**
  Nieuwe test `manual-approve poort goedgekeurd zet de keten door naar de merge-subtaak`.
  Met de poort AAN (`ManualApproveE2eTestConfig`) en auto-approve aan loopt de keten autonoom tot
  `manual-approve-needed`. De test bevestigt eerst dat de merge-subtaak nog **niet** is opgepakt
  (poort houdt de keten tegen), keurt dan goed via `manually-approved`, en verifieert dat de
  keten doorzet naar de merge-subtaak: die wordt opgepakt en de automatische merge (SF-244) wordt
  geprobeerd. In de e2e-harness (lokale git-remote, geen GitHub-PR) faalt die merge op de ontbrekende
  PR/targetRepo → `Error` op de merge-subtaak ("…automatische merge…") → de keten stopt en de
  deploy-subtaak wordt nooit gestart. Dit dekt tegelijk het SF-244-foutpad (#21): merge-fout zet de
  merge-subtaak op `Error` en stopt de keten. Verifieert écht gedrag (gate-advance + merge-attempt),
  niet enkel een mock; bevriest geen buggy gedrag (de merge-foutmelding is de correcte reactie op de
  ontbrekende PR in de harness, geen productiebug).

- **`SpecScenarioCoverageE2eTest` — story-niveau silent clarification (#19).**
  Nieuwe test `silent story zet een refiner-vraag in een clarification-Error op story-niveau`.
  Een silent story met een refiner-vraag (`refined-with-questions`) wacht niet op een mens maar
  belandt in een `[CLARIFICATION]`-Error op de **story** (i.p.v. op een subtaak zoals in PL).
  Dekt de story-niveau-variant van SF-335 error-categorisatie die nog niet getest was.

## Bewust niet-gedekt (met reden)

- **#20 Merge slaagt → deploy (SF-244).** Vereist niet alleen een fake `GitHubApi`-bean, maar ook een
  PR-nummer op de `story_run` (anders faalt `MergeSubtaskHandler` al vóór de GitHub-call op "Geen
  PR-nummer gevonden") én een gesimuleerde deploy (`DeploySubtaskHandler` heeft kubectl/preview-config
  nodig). Dat is geen proportionele test-only uitbreiding binnen deze scope: het zou DB-seeding van de
  story-run en een tweede fake (deploy/kubectl) vergen die zelf nauwelijks echt gedrag toevoegt. Het
  *foutpad* (merge faalt → `Error` → keten stopt, #21) is wél gedekt via de approve-test.
- **#22 Telegram-melding bij afgeronde test (SF-206).** Vereist een fake Telegram-kanaal
  (`TelegramClient`) plus tester-rapport/preview-URL/attachment-state om de inhoud te asserten. De
  software-factory zelf heeft geen preview (`previewUrlTemplate` leeg), dus het meest karakteristieke
  deel (preview-link + screenshots) is in de e2e-harness niet representatief te maken. Telegram-gedrag
  is al unit-getest (zie `TelegramNotificationService`-tests). Niet proportioneel om in de e2e-pijplijn
  te herhalen.
- **#24 Nightly scheduler (SF-350).** Tijd-/klok-gestuurd (`@Scheduled`, NL→UTC-omrekening) en draait
  buiten de orchestrator-issue-pijplijn die de e2e-suite test. Heeft eigen pure + in-memory tests
  (`NightlyPlannerTest`, `NightlySchedulerTest`). Een e2e-dekking zou klok-sturing in de Spring-context
  vergen; buiten de scope/aanname van deze story (focus = orchestrator-pijplijn).
- **`FullRefineToDevelopE2eTest` (`@Disabled`).** Niet geforceerd aangezet: het happy-path wacht op de
  afgedwongen merge-subtaak die in de harness niet kan slagen (zelfde reden als #20). De
  pijplijn-mechaniek is volledig gedekt door PF + PL + SC. Re-enable zinvol zodra merge/deploy met
  een fake GitHubApi + PR-nummer wordt gesimuleerd — dezelfde uitbreiding als #20.

## Build/test

- Lokale dev-omgeving heeft **geen Docker**; de e2e-suite gebruikt Testcontainers (Postgres) en kan
  daarom lokaal niet draaien — dat is conform de bekende factory-constraint (e2e draait in CI).
- Geverifieerd met `mvn -f softwarefactory/pom.xml test-compile` (compileert de hele test-bron, incl.
  de nieuwe e2e-tests) plus de niet-Docker fixturetests waar mogelijk.
- Geen wijzigingen onder `softwarefactory/src/main`; alle wijzigingen zitten onder `src/test`.

## Aangepaste specs

Geen `docs/factory/`-specs aangepast: deze story voegt uitsluitend testdekking toe voor reeds
beschreven en geïmplementeerd gedrag; de functional-spec weerspiegelt de codebase al correct.

## Review-notities (reviewer, SF-622)

- [info] Scope geverifieerd: alleen `src/test` + dit worklog, geen wijziging onder `src/main`. ✔
- [info] Beide nieuwe tests geverifieerd tegen productiecode — verifiëren echt gedrag, geen bevroren bug:
  - approve-pad: `SubtaskExecutionCoordinator` (MANUALLY_APPROVED → advanceSubtaskChain) →
    `MergeSubtaskHandler.performAutomaticMerge` faalt op ontbrekende PR/targetRepo ("…automatische
    merge…") → error-guard in `StoryPipelineService` stopt de keten → deploy start niet. Correct.
  - story-niveau silent clarification: `StoryRefinementCoordinator.questionsOutcome` geeft bij silent
    een `[CLARIFICATION]`-Error i.p.v. wachten. Correct.
- [info] Gap-analyse volledig; bewust-niet-gedekt scenario's afdoende gemotiveerd.
- [bug] Aan het einde van dit worklog stond gelekte tool-call-markup (`</content>` / `</invoke>`),
  per ongeluk meegeschreven door de developer. Door de reviewer verwijderd zodat het deliverable schoon is.

## Test-notities (tester, SF-623)

- Scope geverifieerd: `git diff --name-only main...HEAD` = enkel `docs/stories/worklog/SF-621-worklog.md`
  + de twee e2e-testbestanden (`ManualApproveGateE2eTest.kt`, `SpecScenarioCoverageE2eTest.kt`). Geen
  wijziging onder `softwarefactory/src/main`. ✔
- `mvn -f softwarefactory/pom.xml test-compile`: groen (de nieuwe e2e-tests compileren).
- Volledige suite `mvn -f softwarefactory/pom.xml test -Dsurefire.runOrder=alphabetical`:
  **428 tests, Failures: 0**, 27 Errors. Alle 27 errors zijn omgevingsgebonden (geen Docker-daemon in
  de tester-omgeving → Testcontainers): 25× e2e-package (incl. de 2 nieuwe tests — ManualApproveGate 2,
  SpecScenarioCoverage 4), 1× NightlyRepositoriesTest, 1× FactoryDashboardRepositoryScreenshotTest.
  Geen enkele *Failure*; dit is het bekende no-Docker baseline-signaal.
- Assertie-strings/gedrag van de nieuwe tests geverifieerd tegen productiecode: "automatische merge"
  (`MergeSubtaskHandler.kt`), `[CLARIFICATION]` (`TrackerModels.kt`), `MANUALLY_APPROVED ->
  advanceSubtaskChain` (`SubtaskExecutionCoordinator.kt:113`), `manual-approve-needed` (`SubtaskPhase.kt`).
  De tests verifiëren echt productiegedrag en bevriezen geen buggy gedrag.
- Geen preview-/browser-omgeving ingericht (`SF_PREVIEW_URL` leeg) — conform factory-docs; geen
  screenshots van toepassing.

