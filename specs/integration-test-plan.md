# Software Factory — End-to-end integratietest (plan)

Beschrijft een **end-to-end integratietest** die de hele factory-pijplijn uit
[specs.md](specs.md) in één keer aanstuurt: van een story met label
`ai-refinement`, via de vragen-en-antwoorden-flow over de web-UI, tot alle
subtaken klaar zijn. De test draait de **echte** Spring-applicatie (orchestrator-
loop, completion-pad, web-laag) en faket alleen de twee buitenranden die je in
een test niet echt wilt draaien: **YouTrack** (een stateful mini-server over
HTTP) en de **agent-uitvoering** (een scripted runtime in plaats van Docker +
LLM).

Het kernidee: zo veel mogelijk van de productie-keten loopt echt, zodat de test
ook de HTTP-serialisatie, de phase-overgangen en de UI-endpoints dekt. Alleen
waar de keten de buitenwereld raakt (YouTrack, Docker-agent) zit een dubbel.

## 0. Uitgangspunten (besloten)

- **Stateful mini-YouTrack** over echte HTTP → de echte `YouTrackClient` praat
  ermee. (Niet de in-memory `FakeYouTrackApi`-bean — die slaat juist het
  HTTP-pad over waar in de praktijk de verrassingen zitten.)
- **Scripted `TestAgentRuntime`** in plaats van Docker → deterministisch, geen
  LLM, geen toeval.
- Hele Spring-app draait echt: `@EnableScheduling`, `OrchestratorPoller`,
  `AgentResultFileCompletionPoller`, web-controllers en views.
- Build: Maven multi-module (`pom.xml` → `softwarefactory`, `agentworker`).
  Test-framework JUnit5 (`kotlin-test-junit5`).

## 1. De drie naden

| Naad | Productie | In de test |
|---|---|---|
| **Config** | `ConfigApi` → `DefaultConfigApi` → `SecretsEnvLoader` leest `System.getenv()` + secrets-file. `FactoryDashboardAuth` krijgt `ConfigApi` geïnjecteerd → het is een Spring-bean. | `@Primary` test-`ConfigApi`-bean met een vaste `Map`: o.a. `SF_YOUTRACK_BASE_URL=http://localhost:<mockport>`, `SF_DATABASE_URL=<testcontainer>`, `SF_AI_SUPPLIER=mock`, `SF_DASHBOARD_USERNAME/PASSWORD=admin`. |
| **AgentRuntime** | `DockerAgentRuntime` (@Component) spawnt containers. | `@Primary` `TestAgentRuntime`-bean. |
| **YouTrack HTTP** | `YouTrackClient` → `${baseUrl}/api/...` via `java.net.http`. | Echte client, `baseUrl` wijst naar de embedded mock-server. |

### Hoe het completion-pad blijft draaien (geverifieerd)

Uit `AgentResultFileCompletionPoller`: de poller pakt elke actieve run, checkt
`agentRuntime.isContainerRunning(containerName)`; is die **false**, dan leest hij
`workspacePath/agent-result.json`, deserialiseert naar `AgentRunCompleteRequest`
en roept `runtimeApi.complete(...)` aan.

Daar haakt de scripted agent aan:

> `TestAgentRuntime.dispatch()` maakt een temp-workspace, schrijft daar meteen
> `agent-result.json` op basis van het script, en geeft die `workspacePath`
> terug. `isContainerRunning()` → altijd `false`. Het **echte** completion-pad
> (`runtimeApi.complete()` + phase-updates) loopt er ongewijzigd overheen. Geen
> mock van completion nodig.

`AgentRunCompleteRequest` (zie `runtime/RuntimeApi.kt`) bevat `storyKey`, `role`,
`containerName`, `phase`, `outcome`, `summaryText` en `subtasks`
(`AgentRunSubtaskPayload(type, title, ...)`). Dat is precies wat het script per
stap schrijft.

## 2. Componenten (alleen test-sources)

```
softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/
├── FakeYouTrackServer.kt        # stateful mini-YouTrack (com.sun.net.httpserver)
├── FakeYouTrackState.kt         # in-memory model: issues, tags, fields, comments, reactions
├── TestAgentRuntime.kt          # scripted AgentRuntime → schrijft agent-result.json
├── AgentScript.kt               # (role, phase, attempt) -> AgentRunCompleteRequest (+subtasks)
├── E2eTestConfig.kt             # @TestConfiguration: @Primary ConfigApi + AgentRuntime + FakeYouTrackServer
├── FactoryUiDriver.kt           # login + POST naar echte controller-endpoints
├── AwaitDsl.kt                  # awaitPhase(...) helpers (Awaitility)
└── FullRefineToDevelopE2eTest.kt
```

### 2a. `FakeYouTrackServer` — minimale endpoint-set (uit `YouTrackClient`)

Stateful en bidirectioneel. Te ondersteunen endpoints:

- `GET /api/issues?query=...&fields=...` — filter op project + tag
  (`ai-refinement`, `ai-development`); geeft work-issues terug.
- `GET /api/issues/{key}?fields=...` — één issue met fields/tags/comments.
- `POST /api/issues` — issue aanmaken (subtaken!), retourneert `idReadable`.
- `POST /api/issues/{key}` — fields updaten (Story Phase, Subtask Phase,
  AI-supplier, Paused, Error).
- `POST /api/commands` — tag toevoegen/verwijderen + commando's.
- `POST /api/issues/{key}/comments` — comment toevoegen.
- `POST/GET /api/issues/{key}/comments/{id}/reactions` — de "eyes"
  processed-marker.
- `GET /api/admin/projects` + customFields — minimale stub zodat
  startup/validatie niet struikelt.

State wordt geseed met één project + de verwachte custom-field-definities. De
test manipuleert de state ook **direct** (story aanmaken, label zetten); daarvoor
is geen HTTP vanuit de test nodig.

### 2b. `TestAgentRuntime` + `AgentScript`

Script = lookup op `(role, phase, attempt)` → `AgentRunCompleteRequest`:

- **refiner**, attempt 1 → `outcome="questions"`, `phase="refined-with-questions"`,
  `summaryText="<vraag>"`.
- **refiner**, na answered (attempt 2) → `phase="refined"`, `outcome="ok"`.
- **planner** → `phase="planned"` + `subtasks=[development, review, test, summary]`
  (`AgentRunSubtaskPayload`, `type`=trackerValue).
- **developer** subtask-1, attempt 1 → vraag; attempt 2 → `developed`.
- **reviewer/tester/summarizer** → richting approved.

`dispatch()` houdt per `(serializationKey, role)` een attempt-teller bij, schrijft
het bijbehorende `agent-result.json` en retourneert een **echt bestaand**
temp-workspacePath (de poller doet `Path.of(workspacePath).resolve(...)`).
Volledig deterministisch.

### 2c. `FactoryUiDriver` (speelt "de gebruiker")

`TestRestTemplate` tegen de random port. Eerst `POST /login` (admin/admin),
sessie/cookie vasthouden. Daarna de echte endpoints:

- `answerStory(key, answer)` → `POST /stories/{key}/story-phase`
  (`phase=...-questions-answered`, `comment=<answer>`).
- `startDeveloping(key)` → `POST /stories/{key}/start-developing`.
- `answerSubtask(key, answer)` → `POST /stories/{key}/subtask-phase`.
- `approve(...)` waar nodig.

### 2d. `AwaitDsl` (de async-kern)

Awaitility-helpers die state lezen via de FakeYouTrack of de dashboard-API:

- `awaitStoryPhase(key, expected, timeout=10s)`
- `awaitSubtasksCreated(key, count)`
- `awaitAllSubtasksApproved(key)`

Poll-intervallen laag in de test: `SF_POLL_INTERVAL_MS=100`,
`softwarefactory.agent-result-poll-ms=100`.

## 3. Dependencies (test-scope, `softwarefactory/pom.xml`)

- `org.springframework.boot:spring-boot-starter-test` (TestRestTemplate/MockMvc) —
  checken of al aanwezig.
- `org.awaitility:awaitility`
- `org.testcontainers:postgresql` + `org.testcontainers:junit-jupiter` (DB; H2
  valt af i.v.m. Postgres-specifieke SQL + Flyway).
- **Cucumber pas in fase 2** (zie §5).

## 4. Het scenario (eerste groene test)

1. Testcontainer-Postgres start; `@SpringBootTest(RANDOM_PORT)` met `E2eTestConfig`.
2. FakeYouTrack geseed met project + custom fields.
3. Test maakt story `KAN-1` (supplier `mock`) + label `ai-refinement`.
4. `awaitStoryPhase(refined-with-questions)` → refiner heeft een vraag gesteld.
5. `ui.answerStory("KAN-1", "ja, ga door")` → phase `refined-questions-answered`.
6. `awaitStoryPhase(refined)` → goedkeuren → planning →
   `awaitSubtasksCreated(KAN-1, 4)`.
7. `ui.startDeveloping("KAN-1")` → eerste subtask krijgt `ai-development`.
8. `awaitSubtaskPhase(developed-with-questions)` → `ui.answerSubtask(...)` →
   developed → review/test/summary doorlopen.
9. `awaitAllSubtasksApproved("KAN-1")` → assert eindtoestand + assert dat de
   `TestAgentRuntime`-dispatches in de verwachte volgorde gebeurden.

## 5. Cucumber — fase 2, dunne laag

Pas toevoegen als §2–4 groen is. Dan: `cucumber-java`,
`cucumber-junit-platform-engine`, `cucumber-spring`. Step-definitions worden
one-liners naar de harness (`FactoryUiDriver` + `AwaitDsl`); de feature ≈ het
scenario uit §4 in Gherkin. Zo blijft de async-complexiteit in de harness, niet
in de steps.

```gherkin
Scenario: Story van refine tot alle subtaken klaar
  Given een mock-YouTrack draait en de app is gestart
  And een story "KAN-1" met supplier "mock" en label "ai-refinement"
  When de orchestrator de story oppakt
  Then vraagt de refiner een vraag
  When ik via de UI antwoord "ja, ga door"
  Then worden er 4 subtaken aangemaakt
  When ik via de UI op "start developing" klik
  Then doorloopt elke subtask zijn pipeline
  And uiteindelijk zijn alle subtaken "approved"
```

## 6. Bouwvolgorde (incrementeel, elk los testbaar)

1. **FakeYouTrackServer + state** → unit-test tegen de echte `YouTrackClient`
   (breid het bestaande `YouTrackClientTest`-patroon uit). *Grootste risico, dus
   eerst.*
2. **TestAgentRuntime + script** → test dat een geschreven `agent-result.json`
   via de echte poller een phase-update oplevert.
3. **E2eTestConfig + bootstrap** met Testcontainer-Postgres → app start schoon met
   de overrides.
4. **FactoryUiDriver + AwaitDsl** → login + één POST werkt.
5. **Volledig scenario** (§4) groen.
6. **Cucumber-laag** (§5).

## 7. Open risico's / aandachtspunten

- **Config-override**: verifiëren dat een `@Primary ConfigApi`-bean álle plekken
  dekt die nu `ConfigApi.default()` direct aanroepen (sommige code instantieert
  mogelijk direct i.p.v. via DI). Anders systeem-properties/env zetten vóór de
  Spring-context start.
- **Custom-field-validatie bij startup**: de app checkt mogelijk customFields in
  YouTrack — de mock moet de verwachte field-definities teruggeven, anders faalt
  bootstrap.
- **`@EnableScheduling` + meerdere pollers** kunnen races geven; lage intervallen
  + Awaitility met ruime timeouts.
- **Workspace-paden**: `TestAgentRuntime` moet een echt bestaand temp-dir
  teruggeven (poller doet `Path.of(workspacePath).resolve("agent-result.json")`).
- **Testcontainers vereist een Docker-daemon** (voor de DB) — los van de
  agent-Docker die we juist vermijden. Bevestigen dat dat oké is in CI.
