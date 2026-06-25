# SF-199 - Worklog

Story-context bij eerste pickup:
Test-subtaak reset hele keten i.p.v. developer-loopback, met feedback-blok en reset-cap

Wijzig SubtaskExecutionCoordinator.testSubtask zo dat de TEST_REJECTED-tak niet langer een DEVELOPER-loopback dispatcht. In plaats daarvan: (1) schrijf de testreden van de laatste TESTER-run naar de parent-story-description in een eigen, herhaalbaar te overschrijven test-feedback marker-blok (hergebruik de marker-blok-techniek uit ManualCommandService.writeRejectionReasonToStory; vervangen, niet stapelen; placeholder bij lege reden); (2) roep resetStoryChainAfterRejection aan (alle subtaken -> lege fase + todo-lane, story -> todo-lane, eerste subtaak op start, zelfde branch). Voeg een cap toe analoog aan de developer-loopback-cap: nieuwe configureerbare limiet (SF_MAX_TEST_CHAIN_RESETS in OrchestratorSettings, veilige default ~3), tel resets via countForRole(storyRun.id, TESTER); bij overschrijden geen reset maar TrackerField.ERROR zetten met een begrijpelijke triage-melding en IssueProcessResult.Errored teruggeven. Borg idempotentie t.o.v. de orchestrator-poll. Overige fase-overgangen in testSubtask blijven ongewijzigd. Schrijf unit tests die het nieuwe gedrag afdekken: reset+feedback bij eerste bevinding, stoppen-met-error bij overschrijden cap, en idempotentie na reset. Sluit af met een zelf-review.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

## Gedaan / rationale

### Code
- `OrchestratorSettings`: nieuwe configureerbare cap `maxTestChainResets`
  (env `SF_MAX_TEST_CHAIN_RESETS`, default `DEFAULT_MAX_TEST_CHAIN_RESETS = 3`). Bewust een aparte
  grens naast de developer-loopback-cap; toegevoegd met een Kotlin-default zodat bestaande
  constructor-call-sites (incl. tests) ongewijzigd blijven.
- `SubtaskExecutionCoordinator.testSubtask`: de `TEST_REJECTED`-tak dispatcht geen DEVELOPER-loopback
  meer maar roept de nieuwe `handleTestRejection(subtask)` aan. Overige fase-overgangen in
  `testSubtask` zijn ongewijzigd.
- `handleTestRejection`:
  1. resolve parent + open story-run;
  2. cap-check: `countForRole(storyRun.id, TESTER)`; bij `>= maxTestChainResets + 1` (zelfde
     +1-patroon als de developer-cap) wordt de test-subtaak zelf op `Error` gezet met een
     triage-melding en `IssueProcessResult.Errored` teruggegeven — géén reset. Error op de subtaak
     (net als de developer-loopback-cap) zodat de top-level error-guard in `StoryPipelineService` 'm
     daarna elke poll skipt → de keten stalt netjes en idempotent tot een mens ingrijpt; de fout
     surfacet op het storyscherm als subtaak-fout;
  3. anders: testreden uit `latestForRole(storyRun.id, TESTER).summaryText` (placeholder bij leeg)
     naar de story-description schrijven en `resetStoryChainAfterRejection(subtask)` aanroepen
     (bestaande reset-logica, niet gedupliceerd).
- `writeTestFeedbackToStory`: zet de testreden in een eigen, herhaalbaar te overschrijven gemarkeerd
  blok (`<!-- test-feedback:start -->` / `...:end -->`) in de parent-story-description. Hergebruik van
  de marker-blok-techniek uit `ManualCommandService.writeRejectionReasonToStory`; eigen markers zodat
  test-feedback los staat van handmatige-afkeur-feedback. Vervangt het blok bij een volgende bevinding
  (stapelt niet).
- Idempotentie: na de reset is de test-subtaak fase-leeg, dus de eerstvolgende poll triggert geen
  nieuwe reset (zelfde garantie als bij de manual-approve-reset).

### Tests
- `OrchestratorServiceTest` (nieuw, SF-200-blok):
  - reset+feedback bij eerste bevinding (geen agent-dispatch, keten gereset, testreden in blok);
  - herhaalde bevinding vervangt het marker-blok i.p.v. stapelen;
  - placeholder bij ontbrekende tester-reden;
  - cap-overschrijding -> `Errored` + story-`Error`, géén reset;
  - idempotentie: fase-leeg -> `Skipped("not-started")`, geen reset/description-update.
- `OrchestratorServiceTest`: bestaande test `developer loopback cap is counted per subtask`
  omgezet naar een development-subtaak in `review-rejected` (de oude variant leunde op het verdwenen
  `test-rejected -> developer`-gedrag; de per-subtaak-developer-cap zelf is ongewijzigd).
- `PipelineFlowsE2eTest`: `test-subtaak afgekeurd ...` herschreven naar het nieuwe reset-gedrag
  (0 developer-dispatches, tester draait opnieuw na de reset).

### Specs
- `docs/factory/functional-spec.md`: sectie "Test-bevinding reset de keten (SF-200)" toegevoegd.
- `docs/factory/technical-spec.md`: `SF_MAX_TEST_CHAIN_RESETS=3` toegevoegd aan de env-lijst.

### Build/test
- `mvn test -Dtest=OrchestratorServiceTest`: 51/51 groen.
- `mvn test` over OrchestratorServiceTest + ManualCommandServiceTest +
  StoryRefinementCoordinatorAutoStartTest + CreditsPauseServiceTest + FakeYouTrackServerTest: 81/81 groen.
- `PipelineFlowsE2eTest` compileert; draait lokaal niet vanwege ontbrekende Docker
  (Testcontainers) — CI draait deze.

## Niet gedaan / bewuste keuzes
- Geen wijziging aan `ManualCommandService.manualReject` of aan review-/development-loopback-gedrag
  (buiten scope); alleen de reset-/feedback-aanpak hergebruikt.
- Geen resume-increment voor de test-chain-cap (zoals bij de developer-cap): bij cap-overschrijding
  volstaat handmatige triage (feedback + `Error` legen of pauzeren), conform de story-aannames.

## Review-notities (reviewer, 2026-06-25)

Volledige story-diff (`git diff main...HEAD`) beoordeeld. Implementatie dekt de ACs
(reset i.p.v. developer-loopback, test-feedback-blok met vervang-gedrag + placeholder,
cap met error-stop, idempotentie) en is goed getest. Specs zijn consistent bijgewerkt.

Eén bevinding blokkeert merge:

- [bug] `SubtaskExecutionCoordinator.handleTestRejection` zet bij cap-overschrijding een
  triage-melding die letterlijk uit de developer-loopback-cap is overgenomen: "Geef feedback
  en leeg `Error` om opnieuw te proberen". Voor de developer-cap werkt dat omdat
  `ManualCommandService.resume` bij `isDeveloperLoopbackCapError` de limiet ophoogt
  (`LOOPBACK_RESUME_INCREMENT`). Voor de test-chain-cap is er bewust géén resume-increment, en
  de teller is `countForRole(storyRun.id, TESTER)` op de persistente story-run. Gevolg: als een
  mens enkel `Error` leegt terwijl de test-subtaak nog fase `test-rejected` heeft en de
  TESTER-teller nog ≥ cap+1 is, draait de eerstvolgende poll direct opnieuw door
  `handleTestRejection` en zet exact dezelfde error terug → directe re-error-loop. De melding
  belooft dus een herstel-pad dat niet werkt. Werkende escapes zijn alleen `Paused=true` of een
  handmatige keten-reset. Fix: pas de melding aan naar het daadwerkelijk werkende triage-pad
  (pauzeren of de keten handmatig resetten), óf voeg een test-cap resume-mechaniek toe analoog
  aan de developer-cap.
