# SF-537 - Worklog

## Story in eigen woorden

De bestaande e2e/integratietest-suite van de software-factory aanvullen met functionele
scenario's die de productiecode wél ondersteunt maar die nog niet (of slechts indirect) door de
huidige e2e-suite gedekt werden. Uitsluitend **testcode en test-infrastructuur** onder
`softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/`; productiegedrag blijft ongewijzigd.
Daarnaast een scenario-inventarisatie vastleggen (functional-spec → bestaande/nieuwe test, of bewust
niet gedekt + reden).

## Checklist

- [x]: issue + factory-docs + bestaande e2e-harness gelezen
- [x]: scenario-inventarisatie functional-spec ↔ e2e-suite opgesteld (zie onder)
- [x]: ontbrekende, haalbare scenario's als nieuwe e2e-tests toegevoegd (stijl-conform)
- [x]: test-only infra-uitbreiding (AgentScript planner-vraag + manual-approve-config) toegevoegd
- [x]: interne review: enkel `src/test/`-bestanden geraakt, geen productiecode
- [x]: build/test gedraaid voor zover lokaal mogelijk (geen Docker → Docker-e2e draaien in CI)

## Scenario-inventarisatie (functional-spec.md ↔ e2e-suite)

Legenda: **gedekt** = al door bestaande test; **nieuw** = met deze story toegevoegd;
**niet gedekt** = bewust niet, met reden.

| Functioneel scenario (spec) | Status | Test |
| --- | --- | --- |
| Refiner-vraagflow (`refined-with-questions` → antwoord → refined → planner) | **nieuw** (was alleen indirect via `refineAndPlan`-helper / disabled full-test) | `PipelineFlowsE2eTest.refinement stelt een vraag…` |
| Planner-vraagflow (`planned-with-questions` → antwoord → planned + subtaken) | **nieuw** (nergens gedekt) | `PipelineFlowsE2eTest.planning stelt een vraag…` |
| Developer-vraagflow niet-silent (`developed-with-questions` → antwoord → keten door) | **nieuw** (was alleen in disabled `FullRefineToDevelopE2eTest`) | `PipelineFlowsE2eTest.development-subtaak stelt een vraag…` |
| Reviewer-/tester-/summarizer-vraagflow | gedekt | `PipelineFlowsE2eTest` (3 bestaande vraag-tests) |
| Documenter-vraagflow (SF-213) | gedekt | `SpecScenarioCoverageE2eTest.documentation-subtaak stelt een vraag…` |
| Refinement-reject / planning-reject (story-niveau) | gedekt | `PipelineFlowsE2eTest` |
| Development-reject → developer-loopback (1×/2×) | gedekt | `PipelineFlowsE2eTest`, `PipelineLoopbackE2eTest` |
| Review-reject binnen development-subtaak → developer-loopback | gedekt | `PipelineLoopbackE2eTest` |
| Developer-loopback-cap → Error | gedekt | `PipelineLoopbackE2eTest` |
| Summary-reject → opnieuw | gedekt | `PipelineFlowsE2eTest` |
| Test-bevinding reset de keten (SF-200), 1×/2× | gedekt | `PipelineFlowsE2eTest`, `PipelineLoopbackE2eTest` |
| Test-chain-reset cap → Error (SF-200) | gedekt | `SpecScenarioCoverageE2eTest` |
| Manual-subtaak (`awaiting-human`, geen agent) | gedekt | `PipelineFlowsE2eTest` |
| Silent autonoom hele keten (SF-335) | gedekt | `SpecScenarioCoverageE2eTest` |
| Silent: agent-vraag → `[CLARIFICATION]`-Error i.p.v. wachten (SF-335) | gedekt | `PipelineLoopbackE2eTest` |
| Documentatie-stap afgedwongen op juiste plek (SF-213) | gedekt | `SpecScenarioCoverageE2eTest`, (disabled) `FullRefineToDevelopE2eTest` |
| Manual-approve-poort: wacht óók bij Auto-approve + reject reset de keten (SF-192) | **nieuw** (poort stond in e2e uit; nergens e2e-gedekt) | `ManualApproveGateE2eTest` |
| Automatische merge → volgende subtaak / merge-fout → Error (SF-244) | **niet gedekt** | zie reden hieronder |
| Deploy-pad (skip/rest-restart/openshift-watch) | **niet gedekt** | zie reden hieronder |
| Telegram-meldingen (SF-206) / Telegram-assistent | **niet gedekt** (buiten e2e-harness-scope) | unit-getest elders (`TelegramNotificationService`-tests) |
| Nightly scheduler (SF-350) | **niet gedekt** (buiten e2e-harness-scope) | `NightlyPlannerTest` / `NightlySchedulerTest` |

### Bewust niet gedekt — redenen

- **Automatische merge (SF-244) + deploy (heractivering `@Disabled FullRefineToDevelopE2eTest`):**
  de e2e-harness gebruikt een lokale file-based git-remote (`LocalGitRemote`), geen GitHub. De
  merge-subtaak merget via de GitHub-API met een PR-nummer; dat is in de harness niet aanwezig
  (lokaal pad → geen slug/PR), waardoor de merge op een poll-race afhankelijk in `Error` kan
  belanden. Een betrouwbare dekking vereist een **fake GitHub-API** (PR-nummer + merge-result) in
  `E2eTestConfig` — een niet-triviale, op zichzelf staande infra-uitbreiding. Dat is bewust buiten
  deze story gehouden om te voorkomen dat we óf productiecode raken óf een test toevoegen die het
  huidige (in de harness onbedoelde) faalpad "bevriest". Het `@Disabled`-pad blijft gemarkeerd met
  exact deze conditie ("re-enable zodra de harness merge/deploy simuleert").
- **Telegram (SF-206 / assistent) en Nightly (SF-350):** deze leven buiten de pipeline-staatmachine
  die `E2eTestBase` aanstuurt (eigen poller/scheduler + externe Telegram-API). Ze zijn al met
  gerichte unit-tests gedekt; ze in de e2e-harness trekken zou onevenredige, niet test-only infra
  (Telegram-fake, scheduler-klok) vergen zonder extra functionele dekking van de pipeline.

## Wat is er precies gedaan en waarom

1. **`AgentScript.kt`** — een test-only vlag `plannerAsksQuestion` toegevoegd (analoog aan de
   bestaande `*AsksQuestion`-vlaggen). De `PLANNER`-tak levert nu op attempt 1 een
   `planned-with-questions`/`questions`-uitkomst (zónder subtaken) en pas op attempt 2 het plan met
   subtaken. Dit spiegelt exact de bestaande refiner-vraagflow en raakt geen productiecode; de
   productie ondersteunt `PLANNED_WITH_QUESTIONS` al volledig
   (`StoryRefinementCoordinator`/`AgentRunCompletionService`).
2. **`PipelineFlowsE2eTest.kt`** — drie nieuwe vraag-flow-tests toegevoegd, in lijn met de bestaande
   per-flow-conventie (unieke story-key, scripted `AgentScript`, UI-acties via `FactoryUiDriver`,
   Awaitility-waits): refiner-vraag (key `-160`), planner-vraag (key `-170`), niet-silent
   developer-vraag (key `-150`). Deze vulden de gaten in de per-flow-vraagdekking (review/test/
   summary/documentation waren al aanwezig; refiner/planner/developer ontbraken als standalone test).
3. **`ManualApproveE2eTestConfig.kt`** (nieuw) — een `@TestConfiguration` die `E2eTestConfig`
   uitbreidt en enkel de `ProjectRepoResolver` overschrijft met `manualApprove = true` voor
   `sample`. Hergebruikt alle overige buitenrand-dubbels. Test-only; geen productiewijziging.
4. **`ManualApproveGateE2eTest.kt`** (nieuw) — een aparte context (poort AAN) die de twee
   spec-eigenschappen van de manual-approve-poort (SF-192) e2e bewijst: de poort wacht óók met
   `Auto-approve=on` (`manual-approve-needed`), en afkeuren (`manually-not-approved`) reset de hele
   keten zodat de developer opnieuw draait. Het `@factory:command`-pad dat de afkeurreden in de
   story-description schrijft (`ManualCommandService`) is apart unit-getest en wordt hier bewust niet
   gesimuleerd; de test stuurt rechtstreeks de `Subtask Phase` die dat commando uiteindelijk zet.

## Build / test

- Lokale omgeving: `mvn 3.9.10` + JDK 21 + netwerk aanwezig, **geen Docker**.
- `mvn -f softwarefactory/pom.xml test-compile` → **groen** (incl. de nieuwe testklassen en de
  `E2eTestConfig`-subclass; bevestigt dat de all-open/kotlin-spring-plugin de config open maakt).
- Niet-Docker fixturetests gedraaid als regressie-sanity: `FakeYouTrackServerTest` (5) +
  `SubtaskPhaseTerminalTest` (7) → **12 groen, 0 fail**. `FakeYouTrackServerTest` borgt dat de
  schema-seed niet brak door de harness-wijziging.
- De Docker-afhankelijke e2e-tests (`@SpringBootTest` + Testcontainers-Postgres), inclusief de hier
  toegevoegde tests, kunnen lokaal niet draaien (geen Docker) en draaien in de factory-pipeline/CI.

## Specs bijgewerkt

Geen `docs/factory/`-spec-wijzigingen nodig: deze story voegt uitsluitend testdekking toe voor
reeds-gespecificeerd en reeds-geïmplementeerd gedrag; de functional-spec beschreef de gedekte
scenario's al correct.
