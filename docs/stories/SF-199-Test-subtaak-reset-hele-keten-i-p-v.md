# SF-199 - Eindsamenvatting

## Story

Eindsamenvatting

## Eindsamenvatting

## Eindsamenvatting — SF-199: Test-subtaak test alleen; bevinding reset de hele subtaak-keten

**Wat is gebouwd**

De test-subtaak voert geen eigen gerichte fix meer uit. Voorheen dispatchte `SubtaskExecutionCoordinator.testSubtask` bij fase `TEST_REJECTED` een DEVELOPER-loopback. Nu reset een tester-bevinding de volledige subtaak-keten — exact zoals een handmatige reject via de manual-approve-poort — en wordt de testreden als feedback meegegeven aan de volgende ronde. Een nieuwe, configureerbare cap voorkomt oneindig herstarten.

**Belangrijkste onderdelen**
- **Geen developer-fix meer**: de `TEST_REJECTED`-tak roept de nieuwe `handleTestRejection(subtask)` aan i.p.v. een DEVELOPER-loopback te dispatchen. Overige fase-overgangen in `testSubtask` (testing/tested/recovery/questions) zijn ongewijzigd.
- **Keten-reset**: `handleTestRejection` hergebruikt de bestaande `resetStoryChainAfterRejection` (alle subtaken → lege fase + todo-lane, story → todo-lane, eerste subtaak op `start`, zelfde branch). Geen nieuwe reset-logica.
- **Feedback-blok**: `writeTestFeedbackToStory` schrijft de testreden van de laatste TESTER-run in een eigen, herhaalbaar te overschrijven gemarkeerd blok (`<!-- test-feedback:start/end -->`) in de parent-story-description — los van het handmatige-afkeur-blok. Vervangt i.p.v. stapelen; placeholder `(geen reden opgegeven)` bij een lege reden.
- **Cap**: nieuwe `OrchestratorSettings.maxTestChainResets` (env `SF_MAX_TEST_CHAIN_RESETS`, default 3). Geteld via `countForRole(storyRun.id, TESTER)` op de gedeelde story-run; bij `>= cap + 1` géén reset maar `Error` op de test-subtaak + `IssueProcessResult.Errored`. De top-level error-guard skipt de subtaak daarna elke poll → de keten stalt netjes en idempotent tot een mens ingrijpt.
- **Idempotentie**: na de reset is de test-subtaak fase-leeg, dus de eerstvolgende orchestrator-poll triggert geen nieuwe reset (zelfde garantie als de manual-approve-reset).

**Gemaakte keuzes**
- Aparte cap naast de developer-loopback-cap (conceptueel andere grens), via Kotlin-default toegevoegd zodat bestaande constructor-call-sites/tests ongewijzigd blijven.
- Bewust **geen** resume-increment voor de test-cap: dat zou een nieuw per-story YouTrack-veld vereisen, wat tegen de story-aannames ingaat. Na een reviewer-bevinding is daarom de triage-melding aangepast: ze belooft niet langer het niet-werkende "leeg `Error` om opnieuw te proberen", maar noemt de wél werkende escapes (`Paused = true` + parkeren, of `re-implement` → verse story-run, teller reset).
- Eigen test-feedback-markers zodat test-feedback en handmatige-afkeur-feedback elkaar niet overschrijven.

**Getest**
- `mvn -Dtest=OrchestratorServiceTest test`: **51/51 groen**. Dekt af: reset + feedback bij eerste bevinding (geen agent-dispatch, keten/story → todo-lane, eerste subtaak op `start`), blok-vervanging i.p.v. stapelen, placeholder bij lege reden, cap-overschrijding → `Errored` + Error op subtaak zonder reset (met assert op de juiste triage-melding), en idempotentie na reset (`Skipped("not-started")`).
- De verplaatste developer-cap-test loopt nu via `review-rejected` (de oude variant leunde op het verwijderde `test-rejected → developer`-pad). `PipelineFlowsE2eTest` herschreven naar het nieuwe reset-gedrag.
- Volledige suite: BUILD FAILURE met **0 echte failures** — afwijkingen zijn pre-existing/omgevings-gerelateerd (ModulithArchitectureTest-cycle, 11× Docker/Testcontainers-errors, forked-VM tail-crash), alle gereproduceerd op een schone `main`-worktree. Geen regressie.
- Specs bijgewerkt: `functional-spec.md` (sectie test-bevinding reset de keten) en `technical-spec.md` (`SF_MAX_TEST_CHAIN_RESETS`).

**Bewust niet gedaan**
- Geen wijziging aan `ManualCommandService.manualReject` of aan review-/development-loopback-gedrag (buiten scope); alleen de reset-/feedback-aanpak hergebruikt.
- Geen resume-increment / nieuw YouTrack-veld voor de test-cap (botst met de story-aanname); melding-fix gekozen.
- Geen nieuwe UI/Telegram-functionaliteit.

Alle 5 acceptatiecriteria zijn geïmplementeerd; review en test zijn akkoord, geen blockers.
