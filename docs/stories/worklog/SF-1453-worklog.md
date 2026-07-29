# SF-1453 - Worklog

Story-context bij eerste pickup:
REFINING-recovery naar `start` i.p.v. leeg, plus zichtbaarheid voor blokkerende openstaande story-runs

1) In StoryRefinementCoordinator.recoverActiveStoryPhase (StoryRefinementCoordinator.kt) de retryReset-berekening voor fase REFINING wijzigen van null naar StoryPhase.START.trackerValue, symmetrisch met de bestaande PLANNING -> REFINED_APPROVED-terugval; gedrag van de PLANNING-tak blijft ongewijzigd. 2) StoryRunRecord (core/contracts/RunRepositories.kt) uitbreiden met een startedAt: OffsetDateTime? = null veld; JdbcStoryRunRepository (orchestrator/repositories/RunRepositories.kt: storyRunSelect() + toStoryRunRecord()) selecteert en mapt de bestaande DB-kolom story_runs.started_at; InMemoryStoryRunRepository (testsupport) meenemen zodat startedAt ook in tests beschikbaar is. 3) Een nieuwe setting toevoegen aan OrchestratorSettings voor de waarschuwingsdrempel van een blokkerende openstaande story-run (ruime default, losstaand van hardTimeout). 4) In OrchestratorService.promoteQueuedStories: wanneer promotie van een start-next-story geblokkeerd wordt door een niet-lege activeRunForRepo(targetRepo) die ouder is dan deze drempel, een WARN-logregel loggen met storyKey, run-id, target-repo en de openstaande duur - geen automatische sluiting. 5) Unit tests schrijven: (a) een test die het scenario uit de story vastlegt - actieve fase refining, geen agent-run, activePhaseRecoveryDelay verstreken -> IssueProcessResult.Recovered met waarde start (niet <empty>); bestaande planning-recovery-tests moeten ongewijzigd blijven slagen; (b) een test voor de nieuwe WARN-zichtbaarheid bij een langdurig geblokkeerde start-next-promotie.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## Implementatie

1. `StoryRefinementCoordinator.recoverActiveStoryPhase`: `retryReset` is nu een niet-nullable
   `String`. Voor fase `REFINING` valt de retry-reset terug op `StoryPhase.START.trackerValue`
   (was `null`), symmetrisch met de bestaande `PLANNING -> REFINED_APPROVED`-terugval. Beide
   gebruikssites (`recoverActiveStoryPhase` zelf en `recoveredFromRetryableFailure`) gebruiken nu
   `retryReset` direct in plaats van `retryReset ?: "<empty>"`. Er is geen enkele recovery-route
   meer die `TrackerField.STORY_PHASE` op `null`/leeg zet.
2. `StoryRunRecord` (core/contracts/RunRepositories.kt) kreeg een nieuw veld
   `startedAt: OffsetDateTime? = null`. `JdbcStoryRunRepository.storyRunSelect()` selecteert nu ook
   de al bestaande kolom `story_runs.started_at` (V1-migratie had 'm al, DEFAULT now()) en
   `toStoryRunRecord()` mapt 'm. `InMemoryStoryRunRepository` (testsupport) kreeg een extra
   test-only overload `openOrCreate(storyKey, targetRepo, startedAt)` zodat tests een "oude" open
   run kunnen simuleren zonder de productie-interface te wijzigen.
3. `OrchestratorSettings` kreeg `blockedQueueWarnThreshold: Duration = Duration.ofHours(4)`, los van
   `hardTimeout` (die geldt per actieve fase, niet voor de levensduur van een hele run).
   `OrchestratorSettingsFactory.fromEnvironment` leest 'm via de nieuwe env-var
   `SF_BLOCKED_QUEUE_WARN_THRESHOLD_MINUTES` (default 240).
4. `OrchestratorService` kreeg `OrchestratorSettings` als extra constructor-dependency (was nog niet
   geïnjecteerd). `promoteQueuedStories` roept bij een niet-lege `activeRunForRepo(targetRepo)` nu
   `warnIfQueueBlockedTooLong(blockingRun, targetRepo)` aan: als `blockingRun.startedAt` ouder is dan
   de drempel, een WARN-logregel met storyKey, run-id, target-repo en de openstaande duur in
   minuten. Geen automatische sluiting — puur zichtbaarheid, zoals de story vraagt.
5. Tests:
   - `StoryPhaseRecoveryTest` (nieuw): legt exact het scenario uit de story vast — actieve fase
     `refining`, geen agent-run, `agentStartedAt` ouder dan `activePhaseRecoveryDelay` ->
     `IssueProcessResult.Recovered("<key>", "start")` en `Story Phase` op `start`. Tweede test in
     hetzelfde bestand dekt de ongewijzigde `planning -> refined-approved`-terugval, zodat een
     toekomstige regressie in de gedeelde recovery-methode meteen zichtbaar wordt.
   - `QueuedStoryBlockedWarningTest` (nieuw): gebruikt een Logback `ListAppender` op de
     `OrchestratorService`-logger om te bevestigen dat een blokkerende run van 10 uur oud een WARN
     met de story-key en target-repo logt, en dat een blokkerende run van 5 minuten oud dat niet
     doet (threshold-grens).
   - Bestaande tests (`QueuedStoryPromotionTest`, `OrchestratorRefinementFlowTest`,
     `OrchestratorSubtaskRecoveryTest`) blijven ongewijzigd slagen; `OrchestratorTestHarness` en
     `InMemoryStoryRunRepository` zijn bijgewerkt voor de nieuwe constructor-parameter/velden.

## Docs

- `docs/factory/technical-spec.md`: nieuwe env-var `SF_BLOCKED_QUEUE_WARN_THRESHOLD_MINUTES=240`
  toegevoegd aan de orchestrator-tuning-lijst, met korte uitleg van het doel (WARN bij een
  blokkerende start-next-wachtrij).

## Verificatie

- `mvn verify` vanaf de repo-root: **BUILD SUCCESS** (alle modules: factory-contracts,
  factory-common, softwarefactory, agentworker, softwarefactory-dashboard-backend). De
  softwarefactory-module inclusief Testcontainers-e2e-tests liep volledig mee (~3:53 min).
  `TesterVerificationEvidenceE2eTest` faalde in de eerste volledige run op een
  1-minuut-`ConditionTimeout` (tijdgevoelige e2e-test, subtask-niveau, los van deze wijziging);
  in isolatie (`mvn -o failsafe:integration-test -Dit.test=TesterVerificationEvidenceE2eTest`)
  en in de daaropvolgende volledige `mvn verify`-run slaagde 'm gewoon — bevestigde
  pre-existente flake, niet veroorzaakt door deze story.

## Review (SF-1460, reviewer)

- Diff (main...HEAD) nagelopen: `StoryRefinementCoordinator.recoverActiveStoryPhase` — `retryReset`
  is nu niet-nullable, REFINING -> `start`, PLANNING -> `refined-approved` ongewijzigd. Beide
  gebruikssites (`recoverActiveStoryPhase`, `recoveredFromRetryableFailure`) gebruiken `retryReset`
  direct; geen enkel pad zet `STORY_PHASE` nog op leeg. Klopt exact met de story-scope.
- `StoryRunRecord.startedAt` + Jdbc select/mapping geverifieerd tegen `story_runs.started_at`
  (bestaat sinds V1__initial_schema.sql, NOT NULL DEFAULT now()) — klopt.
- `OrchestratorSettings.blockedQueueWarnThreshold` (default 4u, env
  `SF_BLOCKED_QUEUE_WARN_THRESHOLD_MINUTES`) en `OrchestratorService.warnIfQueueBlockedTooLong`:
  alleen WARN-log, geen automatische sluiting — conform scope. `OrchestratorService` kreeg
  `OrchestratorSettings` als nieuwe constructor-param; Spring-DI lost dit vanzelf op (geen
  handmatige bean-config gevonden), `OrchestratorTestHarness` is bijgewerkt.
- `docs/factory/technical-spec.md` bijgewerkt met de nieuwe env-var — consistent met de code.
- Tests: `StoryPhaseRecoveryTest` dekt exact het scenario uit de AC (refining -> start,
  planning-regressie); `QueuedStoryBlockedWarningTest` dekt WARN wel/niet over de drempel.
  Surefire-reports in de werkomgeving tonen beide test-classes groen (2/2, 0 failures/errors);
  `mvn -pl factory-contracts,factory-common,softwarefactory -am test-compile -o` compileert
  main+test schoon zonder fouten.
- Geen scope creep, geen secrets, geen spec-inconsistenties gevonden.
- Oordeel: akkoord.

## Test (SF-1461, tester)

- Diff (main...HEAD) herbekeken: `StoryRefinementCoordinator` REFINING -> `start`, PLANNING
  ongewijzigd, geen enkel pad zet `STORY_PHASE` meer op leeg. `OrchestratorService` WARN-log
  (`warnIfQueueBlockedTooLong`) alleen zichtbaarheid, geen automatische sluiting. Klopt tegen scope
  en AC's.
- Volledig vangnet gedraaid: `mvn -B --no-transfer-progress clean verify` vanaf repo-root ->
  **BUILD SUCCESS**, 0 failures / 0 errors over alle modules (softwarefactory 659 unit + 70 e2e,
  agentworker 60, dashboard-backend 48). Geen flakes deze run (incl. `TesterVerificationEvidenceE2eTest`
  en `TesterVerificationRunnerTest`, die eerder in deze sandbox soms rood stonden).
- Nieuwe tests groen: `StoryPhaseRecoveryTest` (2/2, exact het scenario uit de AC: refining zonder
  agent-run na verstreken recovery-delay -> `Recovered(..., "start")`) en
  `QueuedStoryBlockedWarningTest` (2/2, WARN wel/niet over de drempel).
- Oordeel: `tested`.
