# SF-537 - nightly: Integratietests: ontbrekende scenario's toevoegen

## Story

nightly: Integratietests: ontbrekende scenario's toevoegen

<!-- refined-by-factory -->

## Scope

Vul de bestaande end-to-end/integratietest-suite van de software-factory aan met functionele scenario's die de productiecode ondersteunt maar die nog niet (of slechts indirect) gedekt zijn. Het gaat uitsluitend om **testcode en test-infrastructuur**; productiegedrag blijft ongewijzigd.

Werkgebied: `softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/` (de bestaande harness: `E2eTestBase`, `E2eTestConfig`, `AgentScript`, `FakeYouTrackServer/State`, `FactoryUiDriver`, `AwaitDsl`, `LocalGitRemote`, `TestAgentRuntime`).

In scope:
- **In kaart brengen** welke functionele scenario's uit `docs/factory/functional-spec.md` de pijplijn ondersteunt en welke al/niet door de huidige e2e-suite gedekt zijn. Leg deze inventarisatie (scenario → bestaande/nieuwe test, of bewust niet gedekt + reden) vast in `docs/stories/worklog/SF-537-worklog.md`.
- **Ontbrekende, met de huidige harness haalbare scenario's** als nieuwe e2e-test toevoegen (passend bij de bestaande stijl: unieke story-key per test, scripted `AgentScript`, UI-acties via `FactoryUiDriver`, Awaitility). Verbeter bestaande tests waar dat de dekking aantoonbaar versterkt.
- Test-infrastructuur in de `e2e`-package mag worden uitgebreid (bijv. helpers in `AwaitDsl`/`FactoryUiDriver`, of een fake GitHub-API in `E2eTestConfig`) **mits dit puur test-only is** en geen productiecode raakt.

Niet-uitputtende kandidaat-gaten om te overwegen (de developer bepaalt op basis van de inventarisatie welke daadwerkelijk ontbreken en haalbaar zijn):
- Story-niveau **refiner-vraag**-flow als standalone per-flow-test (`refined-with-questions` → antwoord → `refined-approved`).
- **Developer-vraag** binnen een subtaak, niet-silent (`developed-with-questions` → antwoord → keten loopt door).
- **Manual-approve-poort (SF-192)**: afkeuren reset de hele story met de afkeurreden in het gemarkeerde blok in de story-description; goedkeuren laat de keten door (mits in de harness aanzetbaar).
- **Automatische merge (SF-244)** en/of **deploy**: succes → volgende subtaak, en merge-fout → `Error` — alleen als dit test-only te simuleren is (bijv. fake GitHub-API + PR-nummer); zie het al-`@Disabled` `FullRefineToDevelopE2eTest`.

Buiten scope:
- Elke wijziging aan niet-test (productie-)code, ook gedragsneutraal-ogende.
- Scenario's die alleen te dekken zijn door productiecode te wijzigen, of die enkel met onevenredige infra-uitbreiding test-only te simuleren zijn → documenteren in het worklog i.p.v. forceren.

## Acceptance criteria

- In `docs/stories/worklog/SF-537-worklog.md` staat een scenario-inventarisatie die de relevante functional-spec-scenario's koppelt aan bestaande of nieuw toegevoegde tests, met expliciete reden bij elk scenario dat bewust niet gedekt wordt.
- Voor de met de huidige harness haalbare, nog ontbrekende scenario's zijn nieuwe e2e-tests toegevoegd in de `e2e`-package, in lijn met de bestaande conventies (unieke story-key per test, scripted agents, UI-gestuurde menselijke acties, Awaitility-waits).
- Er is **geen** wijziging aan productie-/niet-testcode; uitsluitend bestanden onder `src/test/` (en daarbinnen bij voorkeur de `e2e`-package) zijn aangepast/toegevoegd.
- De volledige softwarefactory-testsuite slaagt: `mvn -f softwarefactory/pom.xml test` (oude + nieuwe tests groen).
- Een nieuwe test die aantoonbaar buggy/onbedoeld gedrag zou "bevriezen" wordt **niet** toegevoegd; in dat geval gaat de story in `Error` met een concrete notitie (conform de randvoorwaarde), i.p.v. de test stilletjes te laten meelopen.
- Eventuele niet-gedekte scenario's en hun reden (harness-beperking, vereist productiewijziging, e.d.) zijn in het worklog vastgelegd.

## Aannames

- Met "integratietests" worden de e2e-/pipeline-integratietests bedoeld die op `E2eTestBase` (`@SpringBootTest` + Testcontainers Postgres + `FakeYouTrackServer` + scripted `TestAgentRuntime`) draaien; lager-niveau unit-tests vallen buiten de scope tenzij ze direct nodig zijn om een functioneel scenario af te dekken.
- "Relevante scenario's" = de functionele scenario's uit `docs/factory/functional-spec.md` (o.a. refine/plan-gates, per-rol vraag- en reject-flows, loopback-caps, test-chain-reset + cap, silent-autonoom, documentatie-stap, manual-approve-poort, automatische merge/deploy), niet elke denkbare randvoorwaarde van losse units.
- Test-only uitbreiding van de e2e-harness (helpers, of een fake GitHub-API om merge/deploy te simuleren) is toegestaan omdat het geen productiegedrag wijzigt; het `@Disabled`-merge/deploy-pad mag heractiveerd worden zodra de harness dat test-only kan simuleren, maar dit is optioneel en geen blokkade.
- Dit is een silent nightly-story: bij echte onduidelijkheid of een bug-bevriezend scenario wordt geërrord met notitie (niet gewacht op een mens), conform de Silent-spec (SF-335) en de randvoorwaarden.
- De build draait met de bestaande Maven-/Testcontainers-toolchain; er is geen nieuwe runtime-afhankelijkheid of `SF_`-config nodig.

## Eindsamenvatting

Ik heb `.task.md`, het worklog en de review-/testnotities gelezen. Geen openstaande vragen — de story is door alle fases (developer → reviewer → tester) groen gekomen. Hier de eindsamenvatting voor de PO.

---

## Eindsamenvatting — SF-537: Integratietests: ontbrekende scenario's toevoegen

### Wat is gebouwd
De bestaande e2e-/integratietest-suite van de software-factory is aangevuld met functionele scenario's die de productiecode al ondersteunt maar nog niet (of slechts indirect) gedekt waren. Het werk is **uitsluitend test-only**; er is geen productiecode gewijzigd.

Concreet toegevoegd/aangepast (4 bestanden onder `src/test/.../e2e/` + het worklog):
- **3 nieuwe vraag-flow-tests** in `PipelineFlowsE2eTest.kt`, in lijn met de bestaande per-flow-conventie:
  - Refiner-vraagflow (`refined-with-questions` → antwoord → refined → planner)
  - Planner-vraagflow (`planned-with-questions` → antwoord → planned + subtaken) — voorheen nergens gedekt
  - Niet-silent developer-vraagflow (`developed-with-questions` → antwoord → keten loopt door)
- **`ManualApproveGateE2eTest.kt`** (nieuw) + bijbehorende **`ManualApproveE2eTestConfig.kt`** (nieuw): bewijst e2e de twee spec-eigenschappen van de manual-approve-poort (SF-192) — de poort wacht óók met *Auto-approve aan*, en afkeuren reset de hele keten zodat de developer opnieuw draait.
- **`AgentScript.kt`**: een test-only vlag `plannerAsksQuestion` toegevoegd (analoog aan de bestaande `*AsksQuestion`-vlaggen) om de planner-vraagflow scriptbaar te maken.
- **Scenario-inventarisatie** (functional-spec ↔ e2e-suite) vastgelegd in `docs/stories/worklog/SF-537-worklog.md`: per scenario gekoppeld aan een bestaande of nieuwe test, of expliciet als "bewust niet gedekt" met reden.

### Gemaakte keuzes
- Nieuwe tests volgen strikt de bestaande harness-stijl: unieke story-key per test, scripted `AgentScript`, menselijke acties via `FactoryUiDriver`, Awaitility-waits.
- De manual-approve-poort draait in een **aparte Spring-context** (eigen `@TestConfiguration` die `E2eTestConfig` uitbreidt en alleen `ProjectRepoResolver` overschrijft met `manualApprove=true`), zodat alle overige test-dubbels worden hergebruikt en er geen bean-conflict ontstaat.
- Geen spec-wijzigingen nodig: het gaat om dekking van reeds-gespecificeerd en reeds-geïmplementeerd gedrag.

### Wat is getest
- `mvn -f softwarefactory/pom.xml test-compile` → **groen**, incl. alle nieuwe testklassen en de config-subclass.
- Niet-Docker regressie-sanity: `FakeYouTrackServerTest` (5) + `SubtaskPhaseTerminalTest` (7) → **12 groen, 0 fail**.
- Statische verificatie tegen productie door reviewer én tester: alle gebruikte phase-strings, helpers en de `ProjectRepoResolver`-signatuur bestaan; de reset-keten bij `manually-not-approved` is bevestigd. Beide rollen: **akkoord / geslaagd, geen bevindingen**.
- Beperking: de Docker-afhankelijke e2e-tests (`@SpringBootTest` + Testcontainers-Postgres) draaien lokaal niet (geen Docker-daemon in dev/review/test-omgeving) en draaien in de factory-pipeline/CI.

### Bewust niet gedaan (met reden)
- **Automatische merge (SF-244) + deploy**: de e2e-harness gebruikt een lokale file-based git-remote (geen GitHub/PR-nummer). Betrouwbare dekking vereist een fake GitHub-API — een niet-triviale, op zichzelf staande infra-uitbreiding. Bewust uitgesteld om geen onbedoeld faalpad te "bevriezen"; het `@Disabled FullRefineToDevelopE2eTest` blijft gemarkeerd voor heractivering zodra de harness merge/deploy test-only kan simuleren.
- **Telegram (SF-206) en Nightly scheduler (SF-350)**: leven buiten de pipeline-statemachine die `E2eTestBase` aanstuurt en zijn al met gerichte unit-tests gedekt; ze in de e2e-harness trekken zou onevenredige, niet test-only infra vergen.

---
