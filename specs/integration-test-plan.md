# Software Factory — End-to-end integratietest (plan)

Beschrijft een **end-to-end integratietest** die de hele factory-pijplijn uit
[specs.md](specs.md) in één keer aanstuurt: van een story met label
`ai-refinement`, via de vragen-en-antwoorden-flow over de web-UI, tot alle
subtaken klaar zijn. De test draait de **echte** Spring-applicatie (orchestrator-
loop, completion-pad, web-laag, git-orchestratie) en vervangt alleen de
buitenranden die je in een test niet echt wilt draaien: **de tracker** (een stateful
mini-server over HTTP), de **agent-uitvoering** (een scripted runtime i.p.v.
Docker + LLM), de **config/secrets** (Testcontainer-Postgres + mock-URL's) en de
**git-remote** — die laatste **níét gefaket** maar als een **lokale temp git-repo**,
zodat de echte git-laag meedraait (zie §8). De **GitHub-PR-laag** valt daarbij
vanzelf weg.

Het kernidee: zo veel mogelijk van de productie-keten loopt echt, zodat de test
ook de HTTP-serialisatie, de phase-overgangen, de UI-endpoints én de
git-orchestratie dekt. Alleen de echte externe systemen (tracker-cloud,
Docker-agent, GitHub) zitten achter een dubbel of een lokaal equivalent.

## Status (juni 2026)

De harness uit §2 is gebouwd op de `ai/SF-1`-branch; de meeste stappen zijn groen
(`FakeTrackerServerTest`, `TestAgentRuntimePollerTest`, `FactoryUiDriverLoginTest`
en de hele unit/poller/orchestrator-suite). Twee correcties waren nodig om de
context te laten booten en de UI-driver te laten werken:

- **`FactorySecretsConfiguration.factorySecrets()` kreeg `@ConditionalOnMissingBean`**
  zodat de `@Primary` test-override in `E2eTestConfig` écht wint (anders bouwt de
  productie-bean op en gooit 'ie op ontbrekende secrets).
- **De `TestRestTemplate` volgt geen redirects meer** (`Redirect.NEVER`) en de test
  verwacht **303 SEE_OTHER** i.p.v. 302; anders liep `POST /login` door naar een
  `text/html`-GET → 406 en verdween de login-cookie.

`FullRefineToDevelopE2eTest` (het volledige scenario) is inmiddels **groen**: de
git-naad is opgelost met een **lokale temp git-repo** (`LocalGitRemote`, zie §8) —
geen fakes. Daarbovenop dekt `PipelineFlowsE2eTest` de **flow-matrix** per subtaak-soort
(vraag/reject/manual + story-rejects, zie §9). De volledige suite draait groen:
**194 tests, 0 failures, 0 errors, 0 skipped**.

## 0. Uitgangspunten (besloten)

- **Stateful mini-tracker** over echte HTTP → de echte tracker-client praat
  ermee. (Niet de in-memory fake-tracker-bean — die slaat juist het
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
| **Config** | `ConfigApi` → `DefaultConfigApi` → `SecretsEnvLoader` leest `System.getenv()` + secrets-file. `FactoryDashboardAuth` krijgt `ConfigApi` geïnjecteerd → het is een Spring-bean. | `@Primary` test-`ConfigApi`-bean met een vaste `Map`: o.a. `SF_DATABASE_URL=<testcontainer>`, `SF_AI_SUPPLIER=mock`, `SF_DASHBOARD_USERNAME/PASSWORD=admin`. |
| **AgentRuntime** | `DockerAgentRuntime` (@Component) spawnt containers. | `@Primary` `TestAgentRuntime`-bean. |
| **Tracker HTTP** | de tracker-client → `${baseUrl}/api/...` via `java.net.http`. | Echte client, `baseUrl` wijst naar de embedded mock-server. |
| **Git-remote** | `GitApi`/`GitHubApi` → echte `git`/`gh` tegen GitHub. | **Niet gefaket**: target-repo → een lokale temp bare-repo, echte git draait; de PR-stap valt weg (slug=null). Zie §8. |

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
├── FakeTrackerServer.kt         # stateful mini-tracker (com.sun.net.httpserver)
├── FakeTrackerState.kt          # in-memory model: issues, tags, fields, comments, reactions
├── TestAgentRuntime.kt          # scripted AgentRuntime → schrijft agent-result.json
├── AgentScript.kt               # (role, phase, attempt) -> AgentRunCompleteRequest (+subtasks)
├── E2eTestConfig.kt             # @TestConfiguration: @Primary ConfigApi + AgentRuntime + FakeTrackerServer
├── FactoryUiDriver.kt           # login + POST naar echte controller-endpoints
├── AwaitDsl.kt                  # awaitPhase(...) helpers (Awaitility)
└── FullRefineToDevelopE2eTest.kt
```

### 2a. `FakeTrackerServer` — minimale endpoint-set (uit de tracker-client)

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

Awaitility-helpers die state lezen via de fake-tracker of de dashboard-API:

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
2. Fake-tracker geseed met project + custom fields.
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
  Given een mock-tracker draait en de app is gestart
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

1. **FakeTrackerServer + state** → unit-test tegen de echte tracker-client
   (breid het bestaande tracker-client-testpatroon uit). *Grootste risico, dus
   eerst.*
2. **TestAgentRuntime + script** → test dat een geschreven `agent-result.json`
   via de echte poller een phase-update oplevert.
3. **E2eTestConfig + bootstrap** met Testcontainer-Postgres → app start schoon met
   de overrides.
4. **FactoryUiDriver + AwaitDsl** → login + één POST werkt. ✅ (zie Status)
5. **Git-naad: lokale temp bare-repo** als target-repo (§8) → de sync/commit/push
   draait lokaal, de PR-stap valt weg.
6. **Volledig scenario** (§4) groen.
7. **Cucumber-laag** (§5).

## 7. Open risico's / aandachtspunten

- **Config-override**: verifiëren dat een `@Primary ConfigApi`-bean álle plekken
  dekt die nu `ConfigApi.default()` direct aanroepen (sommige code instantieert
  mogelijk direct i.p.v. via DI). Anders systeem-properties/env zetten vóór de
  Spring-context start.
- **Custom-field-validatie bij startup**: de app checkt mogelijk customFields in
  de tracker — de mock moet de verwachte field-definities teruggeven, anders faalt
  bootstrap.
- **`@EnableScheduling` + meerdere pollers** kunnen races geven; lage intervallen
  + Awaitility met ruime timeouts.
- **Workspace-paden**: `TestAgentRuntime` moet een echt bestaand temp-dir
  teruggeven (poller doet `Path.of(workspacePath).resolve("agent-result.json")`).
- **Testcontainers vereist een Docker-daemon** (voor de DB) — los van de
  agent-Docker die we juist vermijden. Bevestigen dat dat oké is in CI.

## 8. De git/GitHub-naad — echte git tegen een lokale repo (geen fake)

De vierde buitenrand (naast config, agent en tracker): bij de developer-completion
roept `StoryWorkspaceService.syncAfterAgent` de **git-laag** (`GitApi` →
`git clone/branch/commit/push`) en de **GitHub-laag** (`GitHubApi` → `gh`-CLI, PR)
aan. Dáár viel het volledige scenario eerst om. Oplossing: git **niet faken**.

**Git draait echt tegen een lokale, file-based remote.** Concreet:

- Maak vóór de test een **temp bare git-repo** (`git init --bare`), geseed met één
  base-commit op `main`.
- Zet de **target-repo van de story** — die de keten uit de tracker-
  projectbeschrijving leest (`factory.repo=...`) — op het **lokale pad** van die
  bare repo.
- De échte `GitCommandClient` draait er nu tegenaan (`git clone <pad>`,
  `checkout -B`, `commitAll`, `push`): lokaal, geen netwerk, **hoge fideliteit** —
  je test óók de echte git-orchestratie, niet een fake ervan.

**De GitHub-PR-laag valt vanzelf weg.** `GitRepositoryUrl.parse` levert alleen voor
github-SSH/HTTPS een `slug`; voor een lokaal pad is `slug == null`. En
`syncAfterAgent` doet `repositorySlug(targetRepo)?.let { ensurePullRequest(...) }`
→ bij een lokaal pad wordt de `gh`-CLI **niet** aangeroepen. Voor dit scenario
("refine tot alle subtaken afgerond") is geen PR nodig, dus **geen `FakeGitHubApi`
nodig**. Wil je later het **PR/merge-pad** expliciet testen, dán komt er een kleine
getrouwe `FakeGitHubApi` bij (lokaal git kent geen PR-concept).

### Aandachtspunten bij deze naad

- **Seed**: de bare repo heeft een base-commit op `main` nodig; de
  factory-docs-skeleton installeert de keten zelf als die mist.
- **Iets om te committen**: voor `commitAll() == true` (en dus een push) moet de
  scripted agent (`TestAgentRuntime`/`AgentScript`) écht een bestand in
  `/work/repo` wijzigen — niet alleen `agent-result.json` (dat staat in `/work`,
  buiten de repo). Anders is er niets te committen; geen fout, maar dan dek je de
  push-tak niet.
- **Workspace-cleaner**: `FileSystemAgentWorkspaceCleaner` werkt op de echte
  workspace-dir; met een geslaagde lokale clone bestaat die → cleanup slaagt. (De
  eerdere fout was gevolgschade van de mislukte github-clone.)
- **Git-auth**: voor een file-remote is geen token nodig; `gitAuthEnv` mag de
  `githubToken` gewoon meegeven, git negeert 'm.

### Assertions — niet alleen groen, maar écht testen

Breid het scenario uit zodat het de **echte gevolgen** verifieert, niet alleen de
tracker-fasen:

- per dev-subtaak is gecommit/gepusht (lees de branch + commits uit de lokale bare
  repo).
- de eindtoestand klopt (alle subtaken approved, eindsamenvatting geschreven).
- de UI-antwoorden hebben de transities **echt** gedreven (vraag verscheen →
  antwoord → volgende fase), niet door auto-approve gemaskeerd.

### Werkstappen — ✅ geïmplementeerd

1. ✅ **`LocalGitRemote`** (test-source): `git init --bare -b main` + één seed-commit,
   exposeert het pad.
2. ✅ **`E2eTestConfig`** maakt de fake-tracker-state met
   `projectDescription = "factory.repo=<lokale remote>"`.
3. ✅ **`@Disabled` verwijderd**; test groen op de eerste run.

Notities uit de uitvoering:

- De **echte git-laag draait mee**: `StoryWorkspaceService.prepare` doet
  `git clone <lokaal pad>`, `checkout -B ai/<key> origin/main` en installeert de
  docs-skeleton (= echte file-changes). De **PR-stap valt vanzelf weg** (lokaal pad
  → `slug == null`), precies zoals voorspeld.
- Een aparte file-wijziging door `AgentScript` bleek **niet nodig** voor groen: de
  skeleton-install levert al wijzigingen. De **push/commit-tak** loopt pas bij
  `autoSyncAfterAgent=on` (in de test staat 'ie op `off`, conform de productie-
  default — sync is daar deferred). Wil je die tak óók in de e2e dekken, zet dan
  `autoSyncAfterAgent=on` in de test-`FactorySecrets` en assert de branch/commits in
  de lokale bare-repo.
- Geen `FakeGitHubApi` nodig voor dit scenario. Alleen als je later het PR/merge-pad
  expliciet wilt testen.

## 9. Flow-dekking per subtaak-soort — ✅ geïmplementeerd

Naast het happy-path-scenario ([FullRefineToDevelopE2eTest]) dekt
[PipelineFlowsE2eTest] de **matrix** van subtaak-soorten × flow-varianten. De infra
die dat mogelijk maakt:

- **`AgentScript` is configureerbaar** (van object → class): per test stel je in welke
  rollen op attempt 1 een **vraag** stellen (`*AsksQuestion`) en welke **subtaken** de
  planner declareert (`plannedSubtasks` / `AgentScript.subtasks(...)`). De agent
  produceert altijd de "kale" eindfasen (`developed`/`reviewed`/`tested`/`summarized`);
  de approve/reject-**gate** ligt bij auto-approve of de gebruiker (UI).
- **`E2eTestBase`** boot de app één keer en reset de gedeelde statics (mock-tracker +
  scripted runtime) per test (`@BeforeEach`). Helpers: `loginUi()`, `awaiter()`,
  `createStory(autoApprove)`, `refineAndPlan` (auto-approve aan) / `approveRefineAndPlan`
  (auto-approve uit, handmatige gates), `awaitDispatchCount(role, n)` (voor reject-loops).
  Elke test gebruikt een **unieke story-key** → eigen workspace + story-run.

Gedekte flows:

| Subtaak / niveau | Vraag-flow | Reject-flow |
|---|---|---|
| development | (in happy-path) | `developed` → reject → developer-loopback → approve |
| review | reviewer stelt vraag → antwoord → approved | (zie development; review-reject = developer-loopback) |
| test | tester stelt vraag → antwoord → approved | `tested` → reject → developer fixt → re-test → approved |
| summary | summarizer stelt vraag → antwoord → approved | `summarized` → reject → **summarizer** opnieuw → approved |
| manual | n.v.t. (geen agent) | `awaiting-human` → `manual-action-done`, géén agent |
| story-refine | (in happy-path) | `refined` → reject → refiner opnieuw → approve |
| story-planning | — | `planned` → reject → planner opnieuw → approve |

- **Vraag-flows** draaien met `Auto-approve=on` (gates gaan vanzelf), zodat de vraag het
  enige menselijke moment is.
- **Reject-flows** draaien met `Auto-approve=off`, zodat de test de approve/reject-gate
  zelf stuurt.
- **Let op (test-isolatie):** een test die op een onbeantwoorde vraag blijft hangen
  (bv. een loopback-developer die per ongeluk een vraag stelt) verstoort via de gedeelde
  context de volgende tests. Zet daarom voor reject-loops `developerAsksQuestion=false`.
