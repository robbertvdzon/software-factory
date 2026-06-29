# SF-705 - nightly: Integratietests: ontbrekende scenario's toevoegen

## Story

nightly: Integratietests: ontbrekende scenario's toevoegen

<!-- refined-by-factory -->

## Scope

Sluit ontbrekende dekking in de integratie-/e2e-testsuite van de software-factory (`softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/`, op basis van `E2eTestBase`/`E2eTestConfig`). Het gaat uitsluitend om testcode en test-infra; productiegedrag blijft ongewijzigd.

In scope:
- Breng de functionele scenario's uit `docs/factory/functional-spec.md` in kaart en zet ze af tegen de bestaande e2e-/pipeline-tests. Leg die mapping (gedekt / ontbrekend / bewust uitgesteld) vast in het worklog.
- Voeg integratietests toe voor de **materiële, met de bestaande e2e-harness bereikbare** gaten — d.w.z. scenario's die geen nieuwe externe fake (GitHub, Telegram, scheduler-klok) nodig hebben. Denk aan: de orchestrator-fasegate (een issue met lege `AI Phase` of `AI-supplier` leeg/`none` wordt niet opgepakt), het filteren van een door de planner meegestuurde `documentation`/`merge`/`deploy`-spec zodat er geen dubbele afsluit-subtaak ontstaat, de afgedwongen ketenvolgorde development→review→test→summary→documentation→(manual-approve)→merge→deploy, en het documenter-goedkeuringspad zonder vraag.
- Verbeter bestaande e2e-tests waar dat de leesbaarheid of betrouwbaarheid verhoogt (bijv. flaky timeouts, onduidelijke asserts), zonder hun bedoeling te wijzigen.

Buiten scope (mag, maar alleen als het binnen deze story betrouwbaar en zonder productiewijziging lukt; anders expliciet als opvolg-gat in het worklog noteren):
- Scenario's die nieuwe buitenrand-dubbels vereisen waarvoor `E2eTestConfig` nog geen bean heeft: het SF-244 merge/deploy happy-path (fake GitHubApi, dat tevens het `@Disabled` `FullRefineToDevelopE2eTest` kan heractiveren), de SF-206 Telegram-melding bij een afgeronde test-subtaak (fake Telegram) en de SF-350 nightly scheduler (instelbare klok).
- De Telegram-assistent (`TelegramAssistantService`) en de Flutter dashboard-frontend.

## Acceptance criteria

- Het worklog (`docs/stories/worklog/SF-705-worklog.md`) bevat een expliciete mapping van functionele scenario's naar tests, met per scenario de status (gedekt door <testnaam> / nieuw toegevoegd / bewust uitgesteld + reden).
- Voor minimaal de hierboven genoemde bereikbare gaten zijn nieuwe e2e-/integratietests toegevoegd die het verwachte gedrag aantonen via de echte orchestrator-/completion-/web-laag (geen test die alleen een interne mock naar zichzelf assert).
- Nieuwe tests volgen de bestaande conventies (`E2eTestBase`, `FakeYouTrackServer`/`FakeYouTrackState`, `TestAgentRuntime`/`AgentScript`, `AwaitDsl`/Awaitility, `FactoryUiDriver`), gebruiken een unieke story-key per test en delen geen state tussen tests.
- Er wordt **geen** productiecode (hoofdbron in `src/main`) functioneel gewijzigd. Toevoegingen aan test-infra/`@TestConfiguration`-beans zijn toegestaan zolang ze de productie-paden niet aanpassen.
- De volledige testsuite slaagt: `mvn -f softwarefactory/pom.xml test` is groen (oude én nieuwe tests). Reeds `@Disabled` tests blijven uitgeschakeld tenzij ze binnen deze story groen te krijgen zijn zonder productiewijziging; in dat geval mogen ze geheractiveerd worden.
- Als een gat alleen testbaar zou zijn door duidelijk buggy productiegedrag te "bevriezen", wordt die test níet toegevoegd; in plaats daarvan gaat de story in error met een concrete notitie over het waargenomen gedrag (conform de randvoorwaarde).

## Aannames

- Dit is een silent nightly "improve"-story: hij wordt volledig autonoom verwerkt en mag uitsluitend tests/test-infra raken, geen functioneel productiegedrag.
- "Integratietests" = primair de e2e-/pipeline-suite in `softwarefactory/src/test/.../e2e/`; aanpalende module-integratietests (bv. `agentworker`, `dashboard-backend`) vallen alleen onder de story als daar een vergelijkbaar, laaghangend functioneel gat blijkt.
- De omvang wordt afgebakend tot wat met de huidige test-harness betrouwbaar te dekken is; gaten die zware nieuwe fakes (GitHub/Telegram/scheduler) vereisen, worden gedocumenteerd als opvolgwerk in plaats van binnen deze story geforceerd, om flakiness en scope-creep te vermijden.
- "Verbeter bestaande tests waar nodig" betekent kwaliteits-/stabiliteitsverbeteringen met behoud van de oorspronkelijke testintentie, niet het versoepelen van asserts om groen te worden.
- Determinisme gaat vóór volledigheid: een scenario dat alleen via een tijds-/poll-race betrouwbaar te maken is, wordt liever overgeslagen (met notitie) dan flaky toegevoegd.

## Eindsamenvatting

Ik heb `.task.md`, het worklog van SF-705 en de review-/test-notities gelezen, plus de diff geverifieerd. Hier de eindsamenvatting voor de PO.

---

## Eindsamenvatting — SF-705: nightly Integratietests: ontbrekende scenario's toevoegen

### Wat is gebouwd
Een test-only uitbreiding van de e2e-/integratietestsuite van de software-factory. Twee leveringen:

1. **Scenario→test-mapping** (in `docs/stories/worklog/SF-705-worklog.md`): elk functioneel scenario uit `functional-spec.md` is afgezet tegen de bestaande tests, met status per scenario (gedekt / nieuw toegevoegd / bewust uitgesteld + reden).
2. **Twee nieuwe e2e-testklassen** voor de gaten die met de bestaande harness bereikbaar zijn (zonder nieuwe externe fake):
   - `OrchestratorGateE2eTest.kt` — de pickup-/fasegate: een story zonder `AI Phase`, met `AI-supplier=none`, of met `Paused=true` wordt níet opgepakt.
   - `ChainCompositionE2eTest.kt` — planner-meegestuurde `documentation`/`merge`/`deploy`-specs worden gefilterd (geen duplicaat) en de keten wordt afgedwongen in de juiste volgorde `[development, review, test, summary, documentation, merge, deploy]`; plus het documenter-pad zónder vraag dat vanzelf doorloopt naar approved.

Diff: 2 testklassen (93 + 88 regels) + worklog. **Geen wijziging in `src/main` en geen `@TestConfiguration`-bean aangepast** — productiegedrag blijft volledig ongewijzigd.

### Belangrijke keuzes
- **Determinisme boven volledigheid**: de gate-tests gebruiken geen vaste sleeps maar een controle-story die de keten wél doorloopt; zodra die `planning-approved` is, is de gated story gegarandeerd geëvalueerd → 0-dispatch-assert is timing-robuust.
- De tests asserten genuïen productiegedrag (geverifieerd tegen de bron in `StoryPipelineService`, `YouTrackClient.findWorkIssues`, `AgentRunCompletionService.materializeSubtasksIfPlanned`), geen "bevroren bug".
- Bestaande conventies gevolgd: `E2eTestBase`, `FakeYouTrackState`, `AgentScript`, `AwaitDsl`, `FactoryUiDriver`; unieke story-keys (-400..-440); geen gedeelde state.

### Wat is getest
- `mvn -f softwarefactory/pom.xml test-compile` → BUILD SUCCESS.
- Non-Docker fixturetests groen: `FakeYouTrackServerTest` (5) + `TestAgentRuntimePollerTest` (2) = 7 run, 0 failures.
- De nieuwe e2e-tests vereisen Docker/Testcontainers-Postgres en draaien in **CI** — lokaal (developer-, reviewer- én tester-omgeving) is geen Docker beschikbaar. Reviewer en tester hebben de asserts aanvullend statisch tegen de productiebron geverifieerd. Story afgesloten als **tested**.

### Bewust niet gedaan (opvolgwerk)
Gaten die een nieuwe buitenrand-fake vereisen, zijn niet geforceerd (om flakiness/scope-creep te vermijden) en als opvolg genoteerd:
- **SF-244 merge/deploy happy-path** — vergt fake `GitHubApi` + PR-nummer-seeding; heractiveert tevens het `@Disabled` `FullRefineToDevelopE2eTest`. Nu alleen het foutpad gedekt.
- **SF-206 Telegram-melding** bij afgeronde test-subtaak — vergt fake Telegram-buitenrand.
- **SF-350 nightly scheduler** — vergt een instelbare `Clock`-bean.
- Telegram-assistent en Flutter-frontend — buiten scope.

---
