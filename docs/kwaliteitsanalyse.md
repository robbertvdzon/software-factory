# Kwaliteitsanalyse Software Factory

*Datum: 3 juli 2026 — analyse over de Kotlin-modules `softwarefactory`, `agentworker` en `dashboard-backend` (de map `work/` en `qualityrun/` bevatten gekloonde externe repos en vallen buiten scope).*

## 1. Eindoordeel in het kort

De codebase is **beter dan je zou verwachten van vibe-coding**. De architectuur is een echte modulaire monoliet: een zuivere `core` met domeinmodel en poorten (interfaces), adapters die de juiste kant op wijzen, géén package-cycles, en dat wordt zelfs afgedwongen door een Spring Modulith-test. Ook de teststrategie is volwassen: ~16.000 regels gedragsgerichte tests met handgeschreven fakes (geen mock-frameworks) en een e2e-vangnet dat de echte Spring-app draait tegen Testcontainers-Postgres, een fake-YouTrack over echte HTTP en echte git.

De problemen zitten niet in de grote lijnen maar geconcentreerd op vier plekken:

1. **Drie god-classes** die elk 3–4 verantwoordelijkheden mengen (dashboard-views, YouTrack-client, agent-completion).
2. **Kopie-code tussen modules**: `agentworker` bevat kopieën van hoofdmodule-bestanden die al uit elkaar gelopen zijn (de git-client mist daar bugfixes — een latente bug), en `dashboard-backend` heeft een derde YouTrack-client die nog het **oude procesmodel** gebruikt.
3. **Een handvol echte bugs/risico's**: XSS-gaten in de dashboard-HTML, `admin`/`admin` als default-login in dashboard-backend, en verzonnen usage-cijfers (`AgentUsage.random()`) als default in agentworker.
4. **Documentatierot aan de instapkant**: `README.md` en `specs/specs.md` beschrijven een procesmodel dat niet meer bestaat, terwijl `docs/factory/*` en `runbook.md` wél actueel zijn.

Conclusie: **niet herbouwen, wel gericht opschonen.** De fundering (modules, poorten, domeinmodel, teststijl) kan blijven staan; het werk zit in splitsen, ontdubbelen en de instapdocumentatie herschrijven.

---

## 2. Architectuur (macro-niveau)

### Wat goed is

- **Moduleschema met afgedwongen grenzen.** Packages als `youtrack`, `github`, `git`, `preview`, `docs`, `knowledge`, `telegram` volgen consequent hetzelfde patroon: een `XxxApi`-interface in de package-root, implementatie in `services`/`clients` eronder. `ModulithArchitectureTest` draait `ApplicationModules.verify()` — grenzen zijn geen afspraak maar een test.
- **Zuivere kern.** `core` importeert uitsluitend `core` en bevat het domeinmodel (`TrackerModels.kt`, `StoryPhase`, `SubtaskPhase`, `SubtaskType`) plus de poorten (`AgentRuntime`, `StoryPipeline`, `FactoryOperations`, `ChangeNotifier`, repositories). Alle modules praten in deze types; het domein is niet gedupliceerd binnen de hoofdmodule.
- **Nette inversies.** `orchestrator` en `pipeline` kennen elkaar niet: de orchestrator gebruikt de poort `core.StoryPipeline`, de pipeline implementeert die. De cycle web↔telegram is bewust doorbroken via `core.FactoryOperations` en `core.ChangeNotifier`.
- **De hoofdflow is traceerbaar** in lagen: `OrchestratorPoller` → `OrchestratorService.pollOnce()` → `StoryPipelineService` → `StoryRefinementCoordinator`/`SubtaskExecutionCoordinator` → `AgentDispatcher` → (agent draait) → `AgentRunCompletionService.complete()`.
- **`nightly` is het voorbeeldpackage**: scheduler → pure planner-functie → gateway; maximaal testbaar.

### Verbeterpunten

| # | Bevinding | Waar | Voorstel |
|---|---|---|---|
| A1 | `kubectl` draait direct via ProcessBuilder **in de pipeline-module** — pipeline is niet testbaar zonder kubectl | `pipeline/service/DeploySubtaskHandler.kt:211-240` | Achter een `DeploymentStatusApi`-poort zetten (analoog aan `GitApi`) |
| A2 | `FactoryDashboardService` (739 r.) is viewmodel-bouwer + implementatie van domeinpoort `FactoryOperations` + raw HttpClient + ProcessBuilder (`open -a "IntelliJ IDEA"`) | `web/services/FactoryDashboardService.kt` | Splitsen; `FactoryOperations`-implementatie uit `web`; IntelliJ-launch achter een local-mode-flag of eruit |
| A3 | `AgentRunCompletionService`: 625 regels, 16 constructor-afhankelijkheden; `RuntimeApi.complete()` retourneert een Spring `ResponseEntity` (framework lekt de module-API in) | `runtime/services/AgentRunCompletionService.kt`, `runtime/RuntimeApi.kt:16` | Opdelen in completion-parsing, fase-transitie en side-effect-stappen; domeinresultaat retourneren |
| A4 | Poller injecteert de **concrete** `TelegramNotificationService?` (nullable) i.p.v. een poort | `orchestrator/schedulers/OrchestratorPoller.kt:37` | `NotificationApi`-poort in core, telegram implementeert |
| A5 | `System.getenv` op 10+ plekken buiten `config`; `OrchestratorSettings.fromEnvironment` doet env-parsing ín core | o.a. `SubtaskExecutionCoordinator`, `DeploySubtaskHandler`, `GitHubCliClient`, `core/OrchestratorSettings.kt:46` | Alle env-toegang via `ConfigApi`; core env-vrij maken |
| A6 | Handmatige constructie binnen Spring-beans: merge/deploy-handlers `by lazy` zelf gebouwd; `StoryPurgeService` default-geconstrueerd "zodat tests compileren" | `SubtaskExecutionCoordinator.kt:53-64`, `OrchestratorService.kt:48-56` | Gewone Spring-beans van maken |
| A7 | Board-states (`"Done"`, `"Open"`, `"In Progress"`) als losse strings verspreid over 4+ classes; fase-overgangen worden op twee plekken geschreven (pipeline én completion) zonder één plek waar het toestandsdiagram staat | `SubtaskExecutionCoordinator.kt:45`, `ManualCommandService.kt:48/167`, e.a. | `BoardState`-enum in core + fase-transities documenteren/centraliseren |

**Kanttekening, geen actie:** de poorten `YouTrackApi`, `GitHubApi`, `PreviewApi` leven in hun eigen module i.p.v. in `core` ("hexagonaal-light"). Dat is een verdedigbare keuze; strikt ports-and-adapters doorvoeren levert hier weinig op.

---

## 3. Clean code (micro-niveau)

### Wat goed is

Idiomatisch Kotlin: data classes, sealed interfaces (`IssueProcessResult`, `DeployConfig`), vrijwel geen `var`-state, slechts 4× `!!`. Magic strings zijn grotendeels al gecentraliseerd in enums (`TrackerField`, `StoryPhase`, `SubtaskPhase`). De foutafhandelingsfilosofie — soft-fail met `logger.warn` op poll-grenzen — is consistent en bewust.

### De drie god-classes

1. **`web/views/FactoryDashboardViews.kt` (1.817 r.)** — HTML, CSS én JavaScript als string-interpolatie in één class, met handmatige `.e()`-escaping. **Elke vergeten `.e()` is een XSS-gat, en die zijn er**: `answerCard` (r. 1028: `$title`, `$context`), `approveRejectCard` (r. 1069), `actionsBar` (r. 1193), `myActionErrorCard` (r. 934). Bevat bovendien domeinlogica (`classifyStatus` r. 203, `realStatus` r. 234, `subtaskAwaitsHuman` r. 1252 — die laatste rendert een hele actiekaart om te kijken of de string leeg blijft).
2. **`youtrack/clients/YouTrackClient.kt` (1.079 r.)** — vier verantwoordelijkheden: HTTP-transport incl. SSL-truststore, schema-bootstrap, issue-CRUD en JSON→domein-mapping. Plus twee performance-issues: `subtasksOf` doet N+1 `getIssue`-calls per poll (r. 253-269) en `getIssue` haalt bij elke call de volledige projectlijst op (r. 97-103).
3. **`runtime/services/AgentRunCompletionService.kt` (625 r.)** — `complete()` is ~100 regels met tien verschillende side-effects; `materializeSubtasksIfPlanned` nog eens ~120. Alleen integraal testbaar.

### Duplicatie binnen de hoofdmodule

Het gevaarlijkste patroon: **drie handgesynchroniseerde kopieën van "wat wacht op een mens"** (`FactoryDashboardViews.subtaskActionCard`, `FactoryDashboardService.awaitsHuman`, `TelegramNotificationService.classifySubtask` — de comments zeggen zelf "mirror van..."), plus drie kopieën van `autoApproveActive`. Deze duplicatie heeft aantoonbaar al een bug opgeleverd (SF-164/SF-170: auto-approve via parent niet overal geresolved). Verder:

- Status-buckets 2× (Views r. 203 vs DashboardService r. 457)
- Marker-blok-schrijver 2× (SubtaskExecutionCoordinator r. 206 vs ManualCommandService r. 361)
- Screenshot-prefix/extensies 2×, model-id-lijsten 3×, `urlEncoded`/`pathEncoded` 3×
- ProcessBuilder-met-timeout 5+ implementaties terwijl `runtime/commands/CommandRunner` bestaat
- 15× hetzelfde auth-blok in `FactoryDashboardController` (één `HandlerInterceptor` scheelt ~90 regels)

### Overige punten

- **Default-argument-DI is een valkuil**: `AgentRunCompletionService` default naar `ProjectRepoResolver(emptyMap())` (r. 60) — als injectie ooit mist, draait productie stil met lege config. Idem `HttpClient.newHttpClient()`-defaults (onmockbaar).
- `ManualCommandService.updateIssue` (r. 471-496): 20-case `when` die per `TrackerField` een copy doet — elke nieuwe TrackerField moet nu op meerdere plekken worden bijgewerkt. Hoort als `TrackerIssueFields.applying(field, value)` in core.
- `println` i.p.v. logger in `DockerAgentRuntime` (r. 61-65), inclusief ongefilterde container-output; ~58 `runCatching{}.getOrNull()` zonder enige log; controller-POST-failures worden platgeslagen naar `?x=failed` zonder de exception te loggen.
- `parsePrdVersionJson` parseert JSON met een regex terwijl Jackson beschikbaar is (`FactoryDashboardService.kt:473`).

---

## 4. De kleine modules: kopieerschuld

### `agentworker` (~3.600 r.)

De module zelf is terecht een apart artefact (draait in de agent-container, bewust geïsoleerd van factory-internals) en netjes gestructureerd. Maar het is deels een **kopie-onderhouden codebase**:

- **9 bestanden byte-identiek** met de hoofdmodule (o.a. `StoryLogWriter`, `SecretRedactor`, `PreviewTemplateRenderer`).
- **5 kopieën al gedivergeerd** — het gevaarlijkst: `git/services/GitCommandClient.kt` mist de `reset --hard`/`clean -fd`-fixes en `mergeBaseIntoBranch` die de hoofdmodule wél kreeg. `TrackerModels` loopt achter (mist `ASSISTANT`, `SILENT`, `STORY_PHASE`, `ErrorCategory`).
- **Het result-file-contract is impliciet**: agentworker serialiseert `AgentWorkerResult`, de factory deserialiseert een apart bijgehouden type `AgentRunCompleteRequest` (`AgentResultFileCompletionPoller.kt:48`). Veldnamen blijven puur op discipline synchroon; geen gedeeld type, geen contract-test.
- Bugs: `AgentOutcome.usage` default = **`AgentUsage.random()`** (`AiClient.kt:34`) — runs die usage vergeten te zetten rapporteren verzonnen token/kostcijfers naar de factory-DB. Verder fase-strings als literals waar de hoofdmodule enums heeft, en `Thread.sleep(5000)` hardcoded in productiepad (`AgentCli.kt:144`).
- De drie AI-clients (`ClaudeCodeAiClient` 743 r., `CopilotAiClient`, `CodexAiClient`) dupliceren onderling het patroon runner + stream-parser + outcome-mapping zonder gedeelde basis.

### `dashboard-backend` (~1.400 r.)

Als apart deploybare read-API voor de Flutter-app verdedigbaar, maar het is **de derde implementatie op dezelfde data**, en die loopt achter:

- Eigen `YouTrackClient` queryt nog **`Stage: Develop`** en parst `factory.repo=` uit projectbeschrijvingen — het oude procesmodel dat de hoofdmodule expliciet als legacy bestempelt. Het dashboard toont dus een verouderde werkelijkheid.
- Eigen SQL op dezelfde `story_runs`/`agent_runs`-tabellen als `FactoryDashboardRepository` (impliciet DB-schema-contract), eigen auth naast `FactoryDashboardAuth`.
- **Security**: default-login `admin`/`admin` en `rememberSecret` default `"$username:$password"` (`DashboardConfig.kt:68-81`) — zonder expliciete config zijn login én HMAC-tokens voorspelbaar.
- N+1/N²-YouTrack-calls per dashboard-refresh (`DashboardController.loadRepositories`, r. 263-265), geen caching.
- `openWorkspaceInIntellij`-endpoint (`DashboardController.kt:282`) draait `open -a "IntelliJ IDEA"` — faalt gegarandeerd in de k8s-deploy van dezelfde jar.

---

## 5. Tests

### Wat goed is

- **Geen mock-frameworks; alles fakes** (`FakeYouTrackApi`, `InMemoryAgentRunRepository`, `FakeCommandRunner`). Tests asserten gedrag (toestandsovergangen) i.p.v. mock-verifies — dat voorkomt implementatie-naspiegeling.
- **Het e2e-vangnet bestaat al**: `E2eTestBase`/`E2eTestConfig` booten de echte app met Testcontainers-Postgres 16, embedded `FakeYouTrackServer` over echte HTTP, `TestAgentRuntime` (scripted agent) en `LocalGitRemote` (echte git). Awaitility, geen sleeps.
- Goed gedekt: fase-gate/story oppakken, subtaak-materialisatie incl. afgedwongen merge/deploy, vraag/antwoord/reject-loopbacks, silent-flow, manual-approve, kosten/credits-pauze, nightly, story-purge, YouTrack-client op wire-niveau.

### De gaten

| # | Gat | Waarom het telt |
|---|---|---|
| T1 | **Merge/deploy niet e2e-gedekt** — `FullRefineToDevelopE2eTest` staat `@Disabled` omdat de harness geen GitHub-PR kan simuleren | Precies waar de productie-incidenten zaten (SF-154/SF-164) |
| T2 | **Telegram-tweerichtingsflow ongetest** (`TelegramPoller`, `TelegramReplyService`, gespreksloop/stop/timeout van `ClaudeAssistantClient`) | Kritieke gebruikersinterface, nu blind |
| T3 | **dashboard-backend vrijwel kaal**: controller (323 r.), eigen YouTrack-client, repo, GitHub-client zonder test | Publieke leesinterface |
| T4 | `AgentCli`-hoofdloop (result-file-contract!) ongetest in agentworker | Hét koppelvlak factory↔worker |
| T5 | Pure logica goedkoop testbaar maar 0%: `NightlyJobsReader`, `AgentFailurePolicy`, `SecretRedactor`-gebruik in logging | Laaghangend fruit |
| T6 | `OrchestratorServiceTest` is een god-test (1.624 r., ~400 r. inline fakes, helper die 12 collaborators wired) | Elke constructorwijziging raakt dit bestand |
| T7 | Surefire `forkCount=1, reuseForks=false` → nieuwe JVM + eigen Postgres-container + Spring-context per testklasse | `mvn test` traag; unit- en e2e-run niet gescheiden (bv. failsafe/`mvn verify`-profiel) |

---

## 6. Documentatie

**Bruikbare basis, niet opnieuw beginnen.** `docs/factory/*` (functional-spec, technical-spec, secrets-local, development, deployment), `runbook.md` en `projects.yaml.example` zijn actueel — samen ~80% van wat een nieuwe senior nodig heeft. De rot zit aan de instapkant:

- **`README.md` beschrijft een systeem dat niet meer bestaat**: het één-niveau model (refiner→developer→reviewer→tester→summarizer op `Stage: Develop`, uitkomsten als `refined-finished`), plus een §6 die zichzelf tegenspreekt (git-url in projectbeschrijving + AI-label — allebei vervangen).
- **`specs/specs.md`** beschrijft work-tags als trigger; de code zegt letterlijk "Geen work-tags meer, fase-gate bepaalt". Mist de vier afgedwongen subtaaktypes.
- **`docs/technical/` is intern inconsistent** (de nightly doc-jobs hebben ongelijk ge-update): `overview.md` en `external-systems.md` beschrijven het oude model en noemen de **niet meer bestaande** `SF_AUTO_SYNC_AFTER_AGENT`; `endpoints.md` telt 17 endpoints waar er 39 zijn; de eigen README-tellingen kloppen niet met de sub-docs.
- **Gaten**: story purge, `/api/version|restart|admin/*`, `job.yaml`-schema, `SF_MAX_TEST_CHAIN_RESETS` in `properties.default.env`, dashboard-backend-API, merge-main-into-branch-gedrag.
- **docs-skeleton** (in target-repos geïnstalleerd) loopt achter op `docs/factory` en mist `agents/documenter.md` terwijl de documentation-subtaak altijd wordt afgedwongen.

---

## 7. Verbeterplan (voorstel)

Gerangschikt op impact; elke fase laat de factory werkend achter. Fase 1 en 2 zijn de kern; 3–5 kunnen desgewenst gefaseerd.

### Fase 1 — Bugs & security (klein, direct doen) — ✅ uitgevoerd op 2026-07-03

1. **XSS-gaten dichten** in `FactoryDashboardViews` (alle on-escaped `$var`: r. 934, 1028, 1069, 1193) + een test die escaping afdwingt.
2. **`AgentUsage.random()` → `AgentUsage.ZERO`** als default in agentworker (`AiClient.kt:34`).
3. **`admin`/`admin`-defaults weg** in dashboard-backend: opstart-fail (of expliciete "insecure local mode") bij ontbrekende `SF_DASHBOARD_PASSWORD`/`REMEMBER_SECRET`.
4. **Gedivergeerde `GitCommandClient` in agentworker bijtrekken** (mist workspace-cleanup-fixes) — pleister totdat fase 3 de duplicatie structureel oplost.
5. `println` → logger in `DockerAgentRuntime`; POST-failures in de controller loggen.

*Tijdens de fase 1-verificatie extra gevonden en gefixt:*
- **Deploy-Skip-volgordebug** (`DeploySubtaskHandler`): bij een project zonder deploy-config werd de deploy-subtaak al bij de eerste poll op `deploy-approved` gezet — nog vóór development/merge. De Skip-afhandeling gebeurt nu pas als de keten de subtaak bereikt (fase `start`).
- **e2e-suite was wekenlang rood door stale workspaces**: `work/stories/SP-*` van eerdere runs wees naar verwijderde temp-remotes. `E2eTestBase` ruimt die nu eenmalig per test-JVM op.
- **Await-race bij auto-approve**: de auto-start schuift `planning-approved` binnen enkele ms door naar `in-progress`; `AwaitDsl.awaitStoryPhase` accepteert nu ook die opvolger.
- *Restpunt voor fase 4*: enkele e2e-tests met dispatch-tel-assertions (`assertEquals(2, dispatched.count(...))`) zijn inherent racegevoelig tegen de live 100ms-poller en flaken incidenteel in een volledige suite-run; in isolatie slagen ze consistent.

### Fase 2 — Duplicatie & god-classes in de hoofdmodule (het echte refactorwerk) — ✅ uitgevoerd op 2026-07-03

*Resultaat in het kort: `core/HumanActionPolicy` vervangt de drie handgesynchroniseerde kopieën; `YouTrackClient` 1.075→436 regels (+ transport/mapper/schema-bootstrapper, N+1 in `subtasksOf` en projectlijst-per-call opgelost); `AgentRunCompletionService` 625→514 met een hoofdflow van ~20 regels + aparte `SubtaskPlanMaterializer`, `ResponseEntity` uit de module-API (nieuw `CompletionOutcome`); `FactoryDashboardService` 702→508 met aparte `FactoryOperationsService`/`WorkspaceDesktopLauncher`/`ProjectDeployClient` en Jackson i.p.v. regex/handparser; `FactoryDashboardViews` 1.819→115 (facade + 13 pagina-views + 9 shared componenten, JS naar `/static`, statusclassificatie ontdubbeld); auth via één `HandlerInterceptor` i.p.v. 15 kopieën; merge/deploy-handlers als Spring-beans met `advanceChain` per aanroep; kubectl achter `core.DeploymentStatusProbe` (adapter in runtime); default-argument-DI verwijderd; `OrchestratorSettings`-env-parsing uit core naar config; `TrackerIssueFields.applying`, `BoardState`-enum, `TesterScreenshots`- en `AiRouting.MODELS_BY_SUPPLIER`-consolidaties. Gotcha onderweg gevonden: de e2e-`FakeYouTrackState` rendert gelinkte issues nu — net als echte YouTrack — met volledige custom fields, anders leek elke sibling fase-loos en wees de keten-advance terug naar de eerste subtaak.*

6. **Eén `HumanActionPolicy` in core** voor awaitsHuman/autoApproveActive/actiekaart-classificatie; Views, DashboardService en TelegramNotificationService consumeren die. *(Elimineert de duplicatie die al een bug gaf.)*
7. **`YouTrackClient` splitsen** in transport / schema-bootstrapper / issue-mapper + de N+1 in `subtasksOf` en de projectlijst-per-call oplossen.
8. **`AgentRunCompletionService` opdelen** in parsing, fase-transitie en side-effect-stappen; `materializeSubtasksIfPlanned` naar een eigen `SubtaskPlanMaterializer`; `ResponseEntity` uit `RuntimeApi`.
9. **`FactoryDashboardViews` opsplitsen** per pagina, JS/CSS naar `/static`, domeinlogica eruit; escaping automatisch via kotlinx.html-DSL (of JTE) i.p.v. handmatige `.e()`.
10. **`FactoryDashboardService` splitsen** (A2) + auth-interceptor i.p.v. 15 gekopieerde blokken (incl. logging).
11. **Kleinere consolidaties**: `TrackerIssueFields.applying(...)` in core, `BoardState`-enum, gedeelde constanten (screenshots, model-lijsten, url-encoding), default-argument-DI verwijderen, handlers als Spring-beans, `DeploymentStatusApi`-poort voor kubectl, env-toegang via `ConfigApi`.

### Fase 3 — Modulegrenzen (groot mag, zei je) — ✅ uitgevoerd op 2026-07-03

*Resultaat in het kort: nieuwe Maven-module `factory-common` (zelfde packages, dus minimale import-churn) met git/github/support/preview/docs/FactorySecrets/AgentRole + de docs-skeleton-resources; alle agentworker-kopieën verwijderd (structurele drift onmogelijk). Het result-file-contract is één gedeeld DTO (`contract/AgentResultFile`) met 4 contract-tests die wire-breuken laten falen. dashboard-backend queryt nu het huidige procesmodel (`Story Phase`/`Repo`-veld via de gedeelde `ProjectRepoResolver`), heeft een 7s-TTL-cache i.p.v. N² YouTrack-calls, en het IntelliJ-endpoint zit achter `SF_DASHBOARD_LOCAL_MODE`. Buiten de opdracht om gefixt: `Dockerfile.agent` bouwde geen reactor (mini-reactor-build toegevoegd) en `factory-loop.sh` installeert common nu eerst in ~/.m2. **Deploy-actie nodig**: de k8s-deploy van dashboard-backend mount geen `projects.yaml` — mount die of zet `SF_PROJECTS_FILE`, anders blijft de repositories-tab leeg.*

12. **Gedeelde `factory-common` Maven-module** voor de 14 gedeelde/gedivergeerde bestanden (git, github, docs, support, preview, TrackerModels); `softwarefactory` en `agentworker` hangen ervan af. Drift is dan structureel onmogelijk.
13. **Result-file-contract expliciet**: één gedeeld DTO in factory-common voor `AgentWorkerResult` ↔ `AgentRunCompleteRequest` + contract-test.
14. **dashboard-backend moderniseren**: migreren naar het huidige procesmodel (`Story Phase`/`Repo`-veld), YouTrack/DB-kennis delen via factory-common, IntelliJ-endpoint achter local-mode-flag, caching voor de N²-calls.

### Fase 4 — Tests

15. **Fake `GitHubApi` + deploy-simulatie in `E2eTestConfig`** en `FullRefineToDevelopE2eTest` weer aanzetten — dicht het enige gat in het pipeline-vangnet.
16. **Telegram-flowtests** (poller, reply→antwoord, /stop) tegen fake `TelegramClient`.
17. **`OrchestratorServiceTest` splitsen**: fakes naar een gedeeld test-support-pakket, knippen per flow.
18. **Basisdekking dashboard-backend + `AgentCli`-flowtest** (result-file-contract vastleggen) + goedkope unit-tests (`NightlyJobsReader`, `AgentFailurePolicy`).
19. **`mvn test` versnellen**: e2e naar een failsafe/`verify`-profiel zodat de unit-run snel blijft.
19b. **Workspace-origin valideren in `StoryWorkspaceService.prepare`**: een bestaande story-workspace waarvan de `origin` niet meer overeenkomt met de geconfigureerde repo opnieuw klonen. Gevonden tijdens fase 1: e2e-runs lieten `work/stories/SP-*`-workspaces achter die naar verwijderde temp-remotes wezen, waardoor de hele e2e-suite wekenlang faalde (harness-cleanup inmiddels toegevoegd in `E2eTestBase`); hetzelfde kan in productie gebeuren als de repo van een project in `projects.yaml` wijzigt.

### Fase 5 — Documentatie

20. **README herschrijven** (twee-laags model, fase-gate, subtaakketen; verouderde secties weg) met een leesvolgorde-instap.
21. **`docs/technical/overview.md` + `external-systems.md` + `endpoints.md` corrigeren**; `specs/specs.md` degraderen tot archief met verwijzing naar `docs/factory/functional-spec.md`.
22. **docs-skeleton synchroniseren** + `documenter.md`/`planner.md` toevoegen; `SF_MAX_TEST_CHAIN_RESETS` in `properties.default.env`.
23. **Onboarding-document voor de nieuwe senior developer** schrijven (de einddeliverable) — ná fase 2/3, zodat het de nieuwe structuur beschrijft en de "waarom"-beslissingen vastlegt (Docker voor agents, YouTrack als bron van waarheid, fakes i.p.v. mocks, soft-fail-filosofie, twee dashboards).
