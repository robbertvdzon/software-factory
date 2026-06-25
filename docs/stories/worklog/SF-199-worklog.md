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

## Developer-loopback (review-rejected, 2026-06-25)

Bevinding van de reviewer opgelost. Gekozen voor de melding-fix i.p.v. een test-cap
resume-mechaniek: de story-aannames vermijden bewust een nieuw per-story YouTrack-veld
(de cap telt TESTER-runs op de gedeelde story-run). Een resume-increment analoog aan de
developer-cap zou juist zo'n per-story teller/veld vereisen en dus tegen de story-opzet ingaan.

### Code
- `SubtaskExecutionCoordinator.handleTestRejection`: de triage-melding bij cap-overschrijding
  belooft niet langer "leeg `Error` om opnieuw te proberen". Ze legt nu expliciet uit dat de
  TESTER-teller op de gedeelde story-run staat en dat de test-cap géén resume-increment kent —
  enkel `Error` legen herstart niets (re-error-loop op de volgende poll). De melding noemt de wél
  werkende herstelpaden: `Paused = true` + parkeren, of `re-implement` op de story (verse story-run
  → teller reset). Inline-comment toegevoegd die het verschil met de developer-loopback-cap uitlegt.

### Tests
- `OrchestratorServiceTest` cap-test (`test-chain reset cap stops the chain with an error ...`)
  uitgebreid: assert dat de melding het niet-werkende `leeg \`Error\` om opnieuw te proberen`-pad
  NIET bevat en wél `Paused = true` en `re-implement` als werkende escapes noemt.
- `mvn -Dtest=OrchestratorServiceTest test`: 51/51 groen.

### Niet gedaan
- Geen resume-increment / nieuw YouTrack-veld toegevoegd (botst met de story-aanname
  "geen nieuw verplicht YouTrack-veld"); melding-fix gekozen, conform de door de reviewer
  aangeboden optie.

## Review (reviewer, 2026-06-25)

Volledige story-diff t.o.v. `main` beoordeeld (7 bestanden, scope schoon — geen creep).

- **AC1 (geen developer-fix, keten-reset)**: `testSubtask` TEST_REJECTED → `handleTestRejection` →
  `resetStoryChainAfterRejection` (hergebruik manual-reject-pad). Geen dispatch meer. ✔
- **AC2 (feedback-blok)**: `writeTestFeedbackToStory` schrijft eigen `<!-- test-feedback -->`-blok,
  vervangt i.p.v. stapelen (regex DOT_MATCHES_ALL), placeholder bij lege reden. ✔
- **AC3 (cap)**: `countForRole(storyRun.id, TESTER) >= maxTestChainResets + 1` → Error op de
  test-subtaak + `Errored`, geen reset. Top-level error-guard (`StoryPipelineService:40`) skipt de
  subtaak daarna; `recoverRetryableIssueError` raakt deze melding niet → geen auto-clear. Triage-melding
  belooft niet langer het niet-werkende "leeg Error"-pad (eerdere blocker, nu fixed). ✔
- **AC4 (tests)**: reset+feedback, blok-vervanging, placeholder, cap→error-zonder-reset, idempotentie.
  Verplaatste developer-cap-test nu via review-rejected (correct). ✔
- **AC5 (idempotentie)**: na reset fase-leeg → "not-started"; getest. ✔
- **Specs**: functional-spec + technical-spec (`SF_MAX_TEST_CHAIN_RESETS`) consistent met de diff. ✔

[info] functional-spec zegt "komt de story in Error"; de code zet Error op de test-subtaak (analoog
aan de developer-loopback-cap, zoals de story-description vraagt). De dashboard-status surfacet een
subtaak-fout op storyniveau (`FactoryDashboardViews:235`), dus user-visible klopt "story in Error".
Geen blocker.

Akkoord — geen blocking of bug-findings.

## Test (tester, 2026-06-25)

Story-brede verificatie (SF-201) op branch `ai/SF-199`. Geen preview-deploy ingericht
(SF_PREVIEW_* leeg) → lokaal getest met Maven, geen browser/screenshots mogelijk.

### Resultaten
- `mvn -Dtest=OrchestratorServiceTest test`: **51/51 groen** — dekt alle ACs af:
  AC1 reset hele keten + géén agent-dispatch (`runtime.dispatches.isEmpty()`, subtaken/story → todo-lane,
  eerste subtaak op `start`); AC2 test-feedback-blok geschreven, vervangt i.p.v. stapelt (1 marker-blok),
  placeholder `(geen reden opgegeven)` bij lege reden; AC3 cap-overschrijding → `Errored` + Error op
  subtaak, géén reset, triage-melding noemt de wél werkende escapes (`Paused = true` / `re-implement`)
  en niet het niet-werkende "leeg Error"-pad; AC5 idempotentie na reset (fase-leeg → `Skipped("not-started")`,
  geen description-update/transitie).
- Code-diff gereviewd: alleen `SubtaskExecutionCoordinator.testSubtask`-TEST_REJECTED-tak gewijzigd
  (→ `handleTestRejection`), overige fase-overgangen ongemoeid; `OrchestratorSettings.maxTestChainResets`
  met env `SF_MAX_TEST_CHAIN_RESETS` (default 3, veilig). Scope schoon, geen secrets aangeraakt.

### Volledige suite — verschil onderzocht en als pre-existing/omgeving bevestigd
`mvn test` (hele suite) eindigt met BUILD FAILURE, maar **0 echte failures**. De afwijkingen zijn
omgevings-/pre-existing, geverifieerd tegen een schone `main`-worktree met identieke
`-Dsurefire.runOrder=alphabetical`:
- 1× `ModulithArchitectureTest` (cycle orchestrator→telegram→web→orchestrator): faalt identiek op
  schone `main` → pre-existing (bekende agent-tip).
- 11× e2e-tests (`PipelineFlowsE2eTest`, `FullRefineToDevelopE2eTest`, `FactoryUiDriverLoginTest`):
  "Could not find a valid Docker environment" — tester-omgeving heeft geen Docker-daemon
  (Testcontainers), idem op schone `main`.
- Forked-VM tail-crash ("terminated without properly saying goodbye / System.exit"): treedt aan de
  staart van de enkele grote fork-VM op (wisselende klasse afhankelijk van uitvoervolgorde) en
  **reproduceert identiek op schone `main`** (zelfde klasse `YouTrackClientTest` bij alfabetische
  volgorde, 337 vs 342 tests, beide 0 failures / 12 env-errors). De crashende klassen draaien los én
  samen in één fork probleemloos (runtime-package 32/32 groen). → omgeving/resource, geen SF-200-regressie.

Geen nieuwe failures geïntroduceerd door de branch; nieuw gedrag is via unit tests afgedekt.
Akkoord vanuit test.
