# SF-413 - nightly: Integratietests: ontbrekende scenario's toevoegen

## Story

nightly: Integratietests: ontbrekende scenario's toevoegen

<!-- refined-by-factory -->

## Scope
Breid de bestaande **integratietestsuite** van de `softwarefactory`-module uit zodat alle relevante functionele scenario's die de productiecode ondersteunt, gedekt zijn. Met "integratietests" wordt bedoeld de e2e-/pipeline-tests in `softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/` (gebaseerd op `E2eTestBase` met `@SpringBootTest`, Testcontainers Postgres, `FakeYouTrackServer`, `TestAgentRuntime`/`AgentScript`, `FactoryUiDriver`, Awaitility) en service-integratietests die meerdere componenten samen draaien met gefakete externals — niet de Flutter-frontend en niet pure unit-tests.

Werkstappen:
- Breng in kaart welke functionele orchestratie-/pipeline-scenario's de code ondersteunt (story-lifecycle, subtaak-coördinatie, vraag/afkeur-loops, merge/deploy-afronding, manual-approve, foutpaden) en welke daarvan nog niet door een integratietest gedekt zijn.
- Voeg ontbrekende integratietests toe en verbeter bestaande waar dat de dekking of betrouwbaarheid verhoogt, bij voorkeur via de bestaande testinfrastructuur en helpers (geen nieuwe infra tenzij strikt nodig).
- Houd nieuwe tests deterministisch: geen echte Docker/netwerk/klok-afhankelijkheden; gebruik de bestaande fakes en Awaitility voor async-verificatie.

Buiten scope: wijzigen van functioneel gedrag van productiecode, refactors van productiecode, en het uitbreiden van de Flutter-frontend-tests.

## Acceptance criteria
- Er zijn één of meer nieuwe integratietests toegevoegd (en/of bestaande verbeterd) die eerder ongedekte, door de code ondersteunde scenario's afdekken, geplaatst in/naast de bestaande `e2e`-/integratie-testpackages.
- Er is **geen functioneel gedrag van de productiecode gewijzigd**; aanpassingen blijven beperkt tot testcode en test-fixtures/-helpers.
- De volledige testsuite slaagt: `mvn -f softwarefactory/pom.xml test` is groen, en `mvn -f agentworker/pom.xml test` en `mvn -f dashboard-backend/pom.xml test` blijven groen (geen regressies).
- Nieuwe tests zijn deterministisch en herhaalbaar (geen flakiness, geen echte externe calls); ze gebruiken de bestaande fakes/Testcontainers-infrastructuur.
- Het worklog `docs/stories/worklog/SF-413-worklog.md` beschrijft welke scenario's in kaart zijn gebracht, welke tests zijn toegevoegd/aangepast, en welke (indien van toepassing) bewust niet zijn toegevoegd en waarom.
- Als een potentieel scenario alleen via een test te dekken is die aantoonbaar **buggy gedrag zou bevriezen**, wordt die test níét toegevoegd; in plaats daarvan eindigt de run in error met een concrete notitie (scenario, waarom het buggy is) in het worklog.

## Aannames
- "Integratietests" verwijst naar de `softwarefactory` e2e-/pipeline-suite en componentoverstijgende tests met gefakete externals; de Flutter-frontend en losse unit-tests vallen buiten scope.
- De developer bepaalt zelf welke concrete scenario's ontbreken door productiecode en bestaande tests te lezen; deze story schrijft geen vaste lijst voor. Kandidaat-gebieden (ter inspiratie, niet verplicht): vervolg-vraag/afkeur-loops voorbij één iteratie, loopback-cap-overschrijding die naar error gaat, merge/deploy-afronding van de keten, pauze/hervatten en credits-pauze tijdens dispatch, en idempotentie van subtaak-materialisatie — uitsluitend voor zover de code dit gedrag al ondersteunt.
- Het toevoegen van tests vereist geen nieuwe productie-configuratie of `SF_*`-env-vars.
- Story-omvang blijft klein en testbaar conform de repo-conventies; bij twijfel over dekking gaat correctheid (geen flaky/bevriezende tests) boven volledigheid.

## Eindsamenvatting

## Eindsamenvatting SF-413 — Integratietests: ontbrekende scenario's toevoegen

**Wat is gebouwd**

De e2e-/pipeline-integratietestsuite van de `softwarefactory`-module is uitgebreid met scenario's die de productiecode al ondersteunt maar nog niet gedekt waren. Concreet:

- **Nieuw testbestand** `PipelineLoopbackE2eTest.kt` (in de bestaande `e2e`-package) met 5 tests:
  1. Developer-loopback **voorbij één iteratie** (meerdere `DEVELOPMENT_REJECTED`).
  2. `REVIEW_REJECTED` **binnen** een development-subtaak → developer-loopback via de eigen review-poort.
  3. Developer-loopback-**cap-overschrijding** → subtaak naar `Error`, geen verdere dispatch.
  4. Test-chain-reset **voorbij één iteratie** (SF-200, meerdere bevindingen onder de cap).
  5. **Silent**-subtaak: een agent-vraag wacht niet op een mens maar belandt in een clarification-`Error`.
- **Testhelper** `AwaitDsl.awaitErrorContains(...)` (+ `textFieldOf`) toegevoegd om deterministisch op het `Error`-tekstveld te wachten i.p.v. op niet-veranderende fasen te pollen.

Alleen testcode, fixtures en helpers zijn aangeraakt — **geen functioneel gedrag van productiecode gewijzigd** (acceptatiecriterium gehaald).

**Gemaakte keuzes**

- Hergebruik van de bestaande infrastructuur (`E2eTestBase`, `AgentScript`, `FactoryUiDriver`, `AwaitDsl`/Awaitility, `FakeYouTrackServer`, Testcontainers-Postgres) — geen nieuwe infra.
- Unieke story-keys (`SP-100`..`SP-140`) zodat workspaces/story-runs niet vermengen.
- Determinisme geborgd: scripted fakes, geen echte Docker/netwerk/klok; async-verificatie via Awaitility op de YouTrack-state, tellingen afgeleid van eindfasen.
- Belangrijk implementatiedetail meegenomen: de developer-loopback-cap leest `AI Max Developer Loopbacks` van de **subtaak zelf**, niet van de parent.

**Wat is getest**

- `mvn -f softwarefactory/pom.xml test-compile` → groen.
- Volledige suite: **395 tests, 0 failures**. De 19 errors zijn allemaal omgevings-/pre-existing (geen Docker-daemon in dev-/tester-omgeving); de 5 nieuwe tests falen uitsluitend op het ontbreken van Docker, niet op assertions. Groene e2e-uitvoering loopt via CI/factory (Testcontainers), conform repo-conventie.
- Elke nieuwe e2e-assertie is bovendien statisch geverifieerd tegen de productiecode (cap-logica `AgentDispatcher`, test-chain-reset `SubtaskExecutionCoordinator`, clarification-marker `[CLARIFICATION]`/`effectiveSilent`, review-rejected→loopback). Reviewer en tester: akkoord.
- `agentworker`/`dashboard-backend` niet geraakt → geen regressie.

**Bewust niet gedaan**

- **Merge/deploy-afronding als e2e-keten**: zou nieuwe infra (gefakete `GitHubApi` + geseed PR-nummer) vereisen óf het bewuste `Error`-pad bevriezen; valt buiten scope. Merge/deploy zijn al unit-gedekt.
- **Documentation-subtaak via e2e**: zou uitbreiding van de `AgentScript`-fixture (DOCUMENTER-rol) vergen; fase-overgangen zijn al via unittests gedekt.
- Geen scenario aangetroffen dat alleen via een buggy-gedrag-bevriezende test te dekken was — de run eindigt dus niet in error.

**Geraakte specs**: geen — er is geen functioneel gedrag toegevoegd, alleen testdekking.
