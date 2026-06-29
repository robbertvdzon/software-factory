# SF-705 — Worklog

## Story in eigen woorden

Subtaak **SF-706** (development): sluit ontbrekende dekking in de e2e-/integratietestsuite van de
software-factory (`softwarefactory/src/test/.../e2e/`, op basis van `E2eTestBase`/`E2eTestConfig`).
Het gaat uitsluitend om testcode + test-infra; productiegedrag blijft ongewijzigd. Twee leveringen:

1. Een expliciete mapping van functionele scenario's (uit `docs/factory/functional-spec.md`) naar
   tests, met per scenario de status (gedekt / nieuw toegevoegd / bewust uitgesteld + reden).
2. Nieuwe e2e-tests voor de **met de bestaande harness bereikbare** gaten (geen nieuwe buitenrand-fake
   nodig): orchestrator-fasegate, planner-spec-filtering, afgedwongen ketenvolgorde, documenter-pad
   zonder vraag.

## Checklist

- [x]: read issue + target docs (functional-spec, bestaande e2e-suite, productie-fasegate/materialisatie)
- [x]: scenario→test-mapping vastleggen (zie hieronder)
- [x]: e2e-tests voor de bereikbare gaten toevoegen
- [x]: `mvn -f softwarefactory/pom.xml test-compile` groen
- [x]: non-Docker fixturetests (FakeYouTrackServerTest, TestAgentRuntimePollerTest) groen
- [ ]: volledige `mvn test` (Docker/Testcontainers) — kan lokaal niet (geen Docker); draait in CI

## Wat is gedaan en waarom

### Nieuwe tests

**`OrchestratorGateE2eTest.kt`** — de pickup-/fase-gate van de orchestrator, eerder ongedekt in e2e:

- `story zonder AI Phase wordt niet opgepakt` — geldige supplier maar lege Story Phase ⇒ niet starten
  (`StoryRefinementCoordinator.processStoryRefinement`: `StoryPhase.fromTracker(null) → Skipped`).
- `story met AI-supplier none wordt niet opgepakt` — supplier `none` ⇒ uit de poll-set
  (`YouTrackClient.findWorkIssues` filtert `aiSupplier !in {null,"","none"}`).
- `story op Paused wordt niet verwerkt` — `Paused=true` ⇒ pipeline skipt (`StoryPipelineService`).

Deterministisch gemaakt zonder vaste sleeps: elke test draait een **controle-story** die wél volledig
wordt opgepakt; zodra die `planning-approved` bereikt heeft de orchestrator meerdere poll-cycli
gedraaid en is de gated story gegarandeerd geëvalueerd. Daarna assert de test 0 dispatches onder de
gated story-key (de `serializationKey` van elke dispatch = parent-story-key, zie `AgentDispatcher`).

**`ChainCompositionE2eTest.kt`** — samenstelling van de afgedwongen keten, eerder alleen indirect/
@Disabled gedekt:

- `planner-meegestuurde documentation, merge en deploy worden niet gedupliceerd en in de juiste
  volgorde gezet` — de planner stuurt expliciet óók `documentation`/`merge`/`deploy` mee; de
  factory filtert die eruit (`AgentRunCompletionService.materializeSubtasksIfPlanned`) en dwingt ze
  zelf precies één keer af. Assert: types ==
  `[development, review, test, summary, documentation, merge, deploy]` (manual-approve staat in de
  e2e-config uit) én exact één documentation/merge/deploy. Dit dekt zowel de filtering als de
  ketenvolgorde, deterministisch op het materialisatiemoment (vóór `start-developing`, dus zonder
  keten-uitvoering).
- `documentation-subtaak zonder vraag loopt bij auto-approve vanzelf door naar approved` — het
  documenter-goedkeuringspad **zonder** vraag (`documenting → documented → documentation-approved`,
  één documenter-run). Complementair aan de bestaande WITH-question-test in
  `SpecScenarioCoverageE2eTest`.

Geen bestaande test functioneel gewijzigd; bestaande conventies gevolgd (`E2eTestBase`,
`FakeYouTrackState`, `TestAgentRuntime`/`AgentScript`, `AwaitDsl`, `FactoryUiDriver`), unieke
story-keys (-400..-440), geen gedeelde state.

### Verificatie

- `mvn -f softwarefactory/pom.xml test-compile` → BUILD SUCCESS.
- Niet-Docker fixturetests groen: `FakeYouTrackServerTest` (5), `TestAgentRuntimePollerTest` (2).
- De nieuwe tests zelf vereisen Docker (Testcontainers-Postgres) en draaien in CI; lokaal is geen
  Docker beschikbaar (`docker info` faalt). Conform de harness-aanname is determinisme boven
  volledigheid gekozen: er zijn geen tijds-/poll-races toegevoegd.

Geen productiecode in `src/main` aangeraakt; geen `@TestConfiguration`-beans gewijzigd (de nieuwe
tests draaien op de bestaande `E2eTestConfig`).

## Scenario → test-mapping (functional-spec.md)

| Functioneel scenario | Status | Test(s) |
| --- | --- | --- |
| Orchestrator pollt + filtert op `AI-supplier` niet leeg/none | **nieuw** | `OrchestratorGateE2eTest.story met AI-supplier none wordt niet opgepakt` |
| Fase-gate: lege `AI Phase` = niet starten, `start` = oppakken | **nieuw** | `OrchestratorGateE2eTest.story zonder AI Phase wordt niet opgepakt` (+ alle bestaande tests dekken de `start`-kant) |
| `Paused` ⇒ story niet verwerken | **nieuw** | `OrchestratorGateE2eTest.story op Paused wordt niet verwerkt` |
| Silent — volledige autonome keten (SF-335) | gedekt | `SpecScenarioCoverageE2eTest.silent story doorloopt de keten autonoom...` |
| Silent — story-vraag ⇒ `[CLARIFICATION]`-Error op story | gedekt | `SpecScenarioCoverageE2eTest.silent story zet een refiner-vraag...` |
| Silent — subtaak-vraag ⇒ `[CLARIFICATION]`-Error op subtaak | gedekt | `PipelineLoopbackE2eTest.silent story zet een agent-vraag in een clarification-Error...` |
| Documentatie-stap (SF-213) — vraag-pad | gedekt | `SpecScenarioCoverageE2eTest.documentation-subtaak stelt een vraag...` |
| Documentatie-stap (SF-213) — **geen** vraag ⇒ auto-approved | **nieuw** | `ChainCompositionE2eTest.documentation-subtaak zonder vraag loopt...` |
| Documentatie-spec van planner wordt gefilterd (geen duplicaat) | **nieuw** | `ChainCompositionE2eTest.planner-meegestuurde documentation, merge en deploy...` |
| Afgedwongen ketenvolgorde dev→review→test→summary→documentation→(manual-approve)→merge→deploy | **nieuw** | `ChainCompositionE2eTest.planner-meegestuurde documentation, merge en deploy...` (composition-assert) |
| Refiner/planner/developer/reviewer/tester/summarizer — vraag-flow | gedekt | `PipelineFlowsE2eTest.*stelt een vraag die de gebruiker beantwoordt*` |
| Development-reject loopback (1×, 2×) | gedekt | `PipelineFlowsE2eTest.development-subtaak afgekeurd...`, `PipelineLoopbackE2eTest.development-subtaak twee keer afgekeurd...` |
| Review-reject binnen development-subtaak | gedekt | `PipelineLoopbackE2eTest.review afgekeurd binnen de development-subtaak...` |
| Developer-loopback-cap ⇒ Error | gedekt | `PipelineLoopbackE2eTest.developer-loopback boven de cap...` |
| Test-bevinding reset de keten (SF-200), incl. 2× | gedekt | `PipelineFlowsE2eTest.test-subtaak afgekeurd reset de keten...`, `PipelineLoopbackE2eTest.test-bevinding reset de keten twee keer...` |
| Test-chain-reset cap (SF-200) ⇒ Error | gedekt | `SpecScenarioCoverageE2eTest.test-chain reset cap zet de test-subtaak in Error...` |
| Manual-subtaak (geen agent) | gedekt | `PipelineFlowsE2eTest.manual-subtaak wacht op de mens...` |
| Story-niveau refine/plan reject | gedekt | `PipelineFlowsE2eTest.refinement afgekeurd...`, `...planning afgekeurd...` |
| Handmatige goedkeur-poort (SF-192) — wacht ook bij auto-approve + reject reset | gedekt | `ManualApproveGateE2eTest.manual-approve poort wacht ook bij auto-approve...` |
| Handmatige goedkeur-poort (SF-192) — approve ⇒ door naar merge | gedekt | `ManualApproveGateE2eTest.manual-approve poort goedgekeurd zet de keten door...` |
| Merge altijd automatisch (SF-244) — **foutpad** in de e2e-harness | gedekt (foutpad) | `ManualApproveGateE2eTest.manual-approve poort goedgekeurd...` (merge faalt zonder PR-nummer → Error, keten stopt) |
| Volledig happy-path refine→alle subtaken afgerond | bewust uitgesteld | `FullRefineToDevelopE2eTest` (`@Disabled`): hangt op de afgedwongen merge die in de e2e-harness niet kan slagen. Re-enable vergt fake GitHubApi + PR-nummer. |

### Bewust uitgesteld (opvolgwerk — nieuwe buitenrand-fake nodig)

Deze gaten vereisen een nieuwe buitenrand-dubbel waarvoor `E2eTestConfig` nog geen bean heeft; ze zijn
**niet** binnen deze story geforceerd om flakiness/scope-creep te vermijden:

- **SF-244 merge/deploy happy-path** — vergt een fake `GitHubApi` + PR-nummer-seeding in de story_run.
  Heractiveert tevens het `@Disabled` `FullRefineToDevelopE2eTest`. Nu alleen het **foutpad** gedekt.
- **SF-206 Telegram-melding bij afgeronde test-subtaak** — vergt een fake Telegram-client/-store als
  e2e-buitenrand (deels al unit-getest via `TelegramNotificationService`-doubles).
- **SF-350 nightly scheduler** — vergt een instelbare klok (`Clock`-bean) in de e2e-context.
- **Telegram-assistent** en de **Flutter dashboard-frontend** — buiten scope van deze story.

## Geraakte docs

Geen `docs/factory/`-spec inhoudelijk gewijzigd: deze story raakt uitsluitend testcode en de specs
weerspiegelen de codebase al correct (de tests zijn juist tegen de bestaande spec geschreven). Dit
worklog legt de scenario→test-mapping vast zoals door de acceptance criteria gevraagd.

## Review-notitie (SF-706, reviewer)

Statische review van de volledige story-diff (`git diff main...HEAD`): twee nieuwe testklassen +
worklog, **geen** wijziging in `src/main` of in een `@TestConfiguration`-bean (geverifieerd:
`git diff --name-only` levert buiten test/worklog niets op). Akkoord.

- [info] Diff = `OrchestratorGateE2eTest.kt` (93r), `ChainCompositionE2eTest.kt` (88r), worklog (115r).
- [info] De drie gates assert genuïen productiegedrag, niet een bevroren bug:
  - lege AI Phase → `StoryPipelineService` (`AiPhase.fromTracker(null)?.takeIf{isActive} ?: null`),
  - `AI-supplier=none` → `YouTrackClient.findWorkIssues` filtert `lowercase() !in {null,"","none"}`,
  - `Paused=true` → `StoryPipelineService` guard `if (fields.paused) Skipped("paused")`.
  De 0-dispatch-assert is timing-robuust: de gated story wordt elke poll geskipt, dus de uitkomst
  hangt niet van het exacte synchronisatiemoment af (controle-story tot `planning-approved` volstaat).
- [info] `ChainCompositionE2eTest` volgordeassert `[development, review, test, summary, documentation,
  merge, deploy]` klopt met `AgentRunCompletionService.materializeSubtasksIfPlanned`
  (planner-MERGE/DEPLOY/DOCUMENTATION worden gefilterd; documentation+merge+deploy afgedwongen) én met
  `manualApproveFlags = {"sample" to false}` in `E2eTestConfig` (geen manual-approve-subtaak). Subtaken
  materialiseren bij planner-completion, dus de assert vóór `start-developing` is deterministisch.
- [info] Conventies gevolgd: `E2eTestBase`, `FakeYouTrackState`, `AgentScript`, `AwaitDsl`,
  `FactoryUiDriver`; unieke story-keys (-400..-440), `@BeforeEach`-reset → geen gedeelde state;
  `dispatched` correct gefilterd op `serializationKey == parent-story-key`.
- [info] Volledige `mvn test` niet lokaal draaibaar (geen Docker/Testcontainers in de reviewer-omgeving);
  `test-compile` + non-Docker fixturetests groen volgens developer. Vertrouwen op CI conform afspraak.
  De gate-/composition-asserts zijn statisch tegen de productiebron geverifieerd.

## Test-notitie (SF-707, tester)

Story-brede verificatie van de branch `ai/SF-705`. Test-only story; geen preview-deploy ingericht.

- **Scope-check**: `git diff --name-only main...HEAD` = `OrchestratorGateE2eTest.kt`, `ChainCompositionE2eTest.kt`, worklog. Geen `src/main`-wijziging, geen `@TestConfiguration`-bean gewijzigd.
- **`mvn -f softwarefactory/pom.xml test-compile`** → BUILD SUCCESS (alle door de nieuwe tests gebruikte harness-helpers bestaan).
- **Non-Docker fixturetests groen**: `FakeYouTrackServerTest` (5) + `TestAgentRuntimePollerTest` (2) = 7 run, Failures 0.
- **Statische verificatie asserts tegen productiebron** (genuïen gedrag, geen bevroren bug):
  - `StoryPipelineService.kt:47` filtert STORY met `aiSupplier` leeg/`none` → `Skipped("ai-supplier")`; `:38` `paused` → `Skipped("paused")`.
  - `AgentRunCompletionService.materializeSubtasksIfPlanned:322` filtert planner-meegestuurde MERGE/DEPLOY/DOCUMENTATION; volgorde = `plannedSpecs + documentationSpecs + manualApproveSpecs + chainClosingSpecs`.
  - `E2eTestConfig.kt:51` `manualApproveFlags = mapOf("sample" to false)` ⇒ geen manual-approve-subtaak; verwachte volgorde `[development, review, test, summary, documentation, merge, deploy]` klopt; `createStory` gebruikt repo `sample`.
- **Geen Docker in tester-omgeving** (`docker info` faalt): de nieuwe Testcontainers-Postgres e2e-tests draaien niet lokaal — bekende omgevingsbaseline, geen code-bug. Conform de tip draaien deze in CI.

Conclusie: conventies gevolgd (`E2eTestBase`, `FakeYouTrackState`, `AgentScript`, `AwaitDsl`, `FactoryUiDriver`), unieke story-keys (-400..-440), geen gedeelde state, geen productiewijziging. Acceptance criteria afgedekt. **tested**.
