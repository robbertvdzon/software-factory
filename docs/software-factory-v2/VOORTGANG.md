# Voortgang overstap naar Agent Runtime v2

Laatst bijgewerkt: 2026-09-11

Deze file is de enige live voortgangsbron voor
[`stappenplan.md`](stappenplan.md). Bewijs wordt alleen als afgerond gemarkeerd wanneer het
controleerbaar aanwezig is; ontwerpstatus in de Runtime-documenten telt niet als uitvoeringsbewijs.

## Huidige gate

- Agent Runtime repositoryprotocol: **beschikbaar**. Het prerequisitedocument meldt uitvoering en
  het gepubliceerde productiecontract bevat de branchgerichte checkout, afzonderlijk
  `repositoryResult`, aliasselectie en publicatiemodi.
- Verificatie binnen de job: **beschikbaar**. Het verificatiedocument meldt volledige implementatie
  en productiecontrole; het productiecontract bevat `JobVerification` en `VerificationResult`.
- Software Factory-refactor: **lokaal volledig geïmplementeerd op `main`**; CI/CD en live
  acceptatie uit stap 5 worden met de eerstvolgende commit geactiveerd.

## Stapstatus

| Stap | Status | Bewijs | Volgende gate |
|---:|---|---|---|
| 0 | bezig | Productie-health is groen; execution options en repositoryaliases zijn op 2026-09-11 uitgelezen; een echte `STRUCTURED_GENERATION`-probe eindigde `SUCCEEDED` met job `3a2c4970-b6b3-426c-830a-488aa47ef8af`. | Mock- en repositoryprobes afronden. De allowlist bevat `test-repository`, maar er is nog geen online worker die die alias aanbiedt. |
| 1 | afgerond | [`ontwerp-runtimevervanging.md`](ontwerp-runtimevervanging.md) legt vervanging, correlatie, prompts, Git-eigenaarschap, modelconfiguratie, quota en workspace-aannames vast. | Runtime-consumer bouwen. |
| 2 | afgerond | De v2-adapter maakt idempotente jobs, bouwt volledige rolprompts en begrensde schema's, projecteert events/resultaten/artifacts/usage/status/fouten en gebruikt databasegestuurde modelkeuze. Refiner, planner en summarizer volgen het v2-pad, inclusief vragen en hervatting. De lokale acceptatieroundtrip gebruikt uitsluitend `mock/mock/MOCK`. | Repositoryketen in stap 3 afronden. |
| 3 | lokaal afgerond | Storybranch, dispatch, repositorybewijs, verificatiebewijs en PR worden zonder lokale checkout verwerkt; projectmodelkeuze gebruikt de canonieke repositoryprojectnaam. Ook audits gebruiken een read-only Runtime-checkout en getypeerd resultaat. Alias, branch, publicatiemodus en actuele remote HEAD worden fail-closed getoetst; stale bewijs wordt zichtbaar geweigerd. De volledige lokale E2E-harness gebruikt hetzelfde branch-/completionprotocol. | Live story-, hotfix- en auditacceptatie in stap 5. |
| 4 | afgerond | Agentworker, lokale Docker-runtime, storyworkspaces, resultbestandcontracten, lokale AI-routes, providercredentials en AI-level zijn verwijderd. Actuele documentatie beschrijft Runtime v2. | — |
| 5 | bezig | Kwaliteitsratchet, volledige Maven-reactor, volledige Flutter-suite en `verify.yml` zijn groen. De dashboardimages `sha-6b7d86d` draaien `Synced`/`Healthy` op OpenShift. De Runtime-token is lokaal actief en de Product Factory v2-contractproef voor status/create/idempotent create/get/list/attachment/cancel is groen. | Een online, niet-productieve repositoryalias beschikbaar maken; daarna story-, hotfix-, audit- en Telegramacceptatie uitvoeren. |

## Contractcontrole

| Vereiste | Status | Bewijs |
|---|---|---|
| Bearer-auth per tenant | beschikbaar | Productie-aanroepen met de Software Factory-credential slagen. |
| Idempotente jobaanmaak | beschikbaar | `CreateJobRequest.idempotencyKey` is verplicht in het gepubliceerde `/v2`-contract. |
| `APPLICATION_WORK` / `STRUCTURED_GENERATION` | bewezen | Productieprobe `3a2c4970-b6b3-426c-830a-488aa47ef8af` leverde gevalideerd JSON-resultaat en usage. |
| `REPOSITORY_WORK` / `REPOSITORY_AGENT` | beschikbaar | Productie execution-options tonen online capaciteit voor alle aangeboden modellen. |
| Bestaande branch via alias | beschikbaar | OpenAPI bevat `RepositoryCheckout(alias, branch, publicationMode)`; Runtime-document meldt uitvoering. |
| Read-only branchjob | beschikbaar | Contract valideert `APPLICATION_WORK` + `REPOSITORY_AGENT` + `NONE`. |
| Afzonderlijk AI- en repositoryresultaat | beschikbaar | `JobResultView.result` en `JobResultView.repositoryResult` zijn afzonderlijke velden. |
| `NO_CHANGES` | beschikbaar | `RepositoryPublicationStatus.NO_CHANGES` staat in het productiecontract. |
| `BRANCH_CHANGED` zonder force-push | beschikbaar | Normatieve Runtime-documentatie meldt productie-uitvoering; consumerafhandeling volgt in stap 3. |
| Verificatie en herstelrondes | beschikbaar | Contract bevat `REPOSITORY_CONFIG`, `maxRepairAttempts` en getypeerd bewijs. |
| Resultaat bij terminale verificatiefout | beschikbaar | Resultaatendpoint documenteert gevalideerd resultaat plus bewijs bij terminale verificatiefout. |
| Events, artifacts, usage en cancel | beschikbaar | Gepubliceerde endpoints en contracttypen aanwezig; consumerimplementatie volgt in stap 2. |
| Repositoryaliascatalogus | bewezen | Productie meldde `software-factory` en de targetprojectaliases beschikbaar op één online worker. |
| Mockuitvoering | lokaal bewezen | De Software Factory-consumer doorloopt create en getypeerd resultaat met uitsluitend `mock/mock/MOCK`; productie verbiedt mocks terecht. |
| Tijdelijke repositoryketen | geblokkeerd voor live probe | `test-repository` is toegestaan maar niet beschikbaar op een online worker. Er wordt niet uitgeweken naar een productierepository. |

## Besluiten en blokkades

- De ontbrekende online `test-repository`-alias blokkeert alleen de destructieve live probe uit
  stap 0, niet de consumerimplementatie: dezelfde contracten zijn in Agent Runtime zelf getest en
  productie toont de echte repositorycapaciteit.
- `SF_AGENT_RUNTIME_TOKEN` is op 2026-09-11 door de eigenaar in het bestaande gitignored
  `secrets.env` gezet. Na herstart accepteerden zowel Runtime als de Software Factory-integratie de
  credential; de tokenwaarde is nergens gelogd of gecommit.
- Er komt geen lokale workaround, gedeelde checkout of netwerkvolume.
- Commits tot en met stap 4 eindigen op `[skip ci]`; stap 5 activeert de normale pipeline.

## Bewijslog

### 2026-09-11 — start stap 0 en 1

- Baseline: `bcf0afae7f9947a9a31e00bcab4fdb5f87c26038` op `main`, gelijk aan `origin/main`.
- Productie `GET /healthz`: `UP`.
- Productie `GET /v2/execution-options` voor `STRUCTURED_GENERATION` en `REPOSITORY_AGENT`:
  online worker beschikbaar.
- Productie `GET /v2/repository-aliases`: alle beheerde projectaliases beschikbaar;
  `test-repository` toegestaan maar zonder matching online worker.
- Productieprobe: één kleine OpenAI `STRUCTURED_GENERATION`-job, één attempt, getypeerd resultaat,
  geen artifacts.
- Geen repositorybranch, PR of targetrepository is voor deze probe gewijzigd.

### 2026-09-11 — begin stap 2

- Toegevoegd: getypeerde requests en responses voor jobs, resultaten, repositorybewijs,
  verificatiebewijs, events, execution options en aliases.
- Toegevoegd: HTTP-consumer voor create, status, resultaat, events, annulering en catalogi.
- Configuratie gebruikt `SF_AGENT_RUNTIME_URL` en het uitsluitend extern aangeleverde secret
  `SF_AGENT_RUNTIME_TOKEN`; het bestaande secretmechanisme is niet gewijzigd.
- Test: `mvn -B --no-transfer-progress -pl softwarefactory -am
  -Dtest=AgentRuntimeV2HttpClientTest -Dsurefire.failIfNoSpecifiedTests=false test` — groen.
- Migratie `V37` voegt rol-/projectconfiguratie en de duurzame koppeling tussen `agent_runs` en
  Runtime-jobs toe. Alle bestaande rijen en tabellen blijven intact.
- De configuratieservice valideert iedere nieuwe vendor/model/mode-combinatie live tegen de
  execution options van Runtime. De defaults zijn expliciet en er is geen model-fallback.
- Compilebewijs na de migratie en repositorylaag: `mvn -B --no-transfer-progress -pl
  softwarefactory -am -DskipTests compile` — groen.
- De Docker-runtime en resultbestandpoller zijn niet meer de standaard; alleen expliciete
  `softwarefactory.runtime=docker` activeert ze tijdens de overgang. De v2-adapter is de default.
- `projects.yaml` bevat per project de expliciete, niet-geheime Runtime-alias. URL's worden nooit in
  een Runtime-job opgenomen.
- Runtime-events worden met hun sequence idempotent naar de bestaande agentlogprojectie geschreven;
  terminale resultaten gaan door dezelfde duurzame domeincompletion als voorheen.
- Test: `ProjectConfigurationTest`, `AgentRuntimeV2HttpClientTest` en
  `AgentRuntimeV2ResultMapperTest` — 28 tests groen. Volledige testcompilatie van de geraakte reactor
  is eveneens groen.

### 2026-09-11 — begin stap 3

- De GitHub-client kan een ontbrekende remote storybranch idempotent vanaf de actuele base-head
  aanmaken en een bestaande branch zonder wijziging hergebruiken.
- PR-aanmaak gebruikt alleen repositoryslug, basebranch en storybranch en vereist geen lokale
  checkout. Een al geopende PR wordt hergebruikt.
- Test: `GitHubCliClientTest` — 15 tests groen, inclusief branchaanmaak en remote PR-hergebruik.
- De dispatcher maakt voor repositoryrollen nu alleen remote branchcontext aan en geeft geen
  `workspacePath` meer door. Refiner, planner en summarizer maken helemaal geen Gitbranch of map.
- De dubbele Flyway-versie die door gelijktijdige main-wijzigingen ontstond is opgelost: Runtime v2
  staat in `V37`, ná de bestaande story-samenvattingsmigratie `V36`. Een schone PostgreSQL-migratie
  tot en met v37 en de repository-integratietest zijn groen.
- Runtime-completion verwerkt AI-resultaat, `repositoryResult` en `verificationResult` afzonderlijk.
  Een muterende job moet groen bewijs hebben; `PUSHED` zonder commit, een verkeerde branch of
  publicatie ondanks rood bewijs wordt fail-closed geweigerd. Na de eerste geldige push wordt de ene
  remote PR idempotent gekoppeld, zonder workspace-sync.
- Read-only reviewer/tester/auditor-resultaten moeten publicatiemodus `NONE` hebben en worden tegen
  de actuele remote branch-head gecontroleerd; een latere push maakt het bewijs zichtbaar stale.
- Repository- en verificatiebewijs plus checkout-/commit-SHA worden duurzaam in
  `agent_runtime_jobs` opgeslagen. Een tijdelijk onleesbare terminale result-response wordt opnieuw
  gepolld en niet als domeinfout gepubliceerd.
- Test: `AgentRunCompletionServiceTest` en `AgentRuntimeV2ResultMapperTest` — 25 tests groen.

### 2026-09-11 — modelconfiguratie in Settings

- De settings-response bevat de opgeslagen rolconfiguraties, live beschikbare Runtime-opties en de
  configureerbare repositoryprojecten.
- Een ingelogde dashboardgebruiker kan per agentrol de standaard of een projectoverschrijving
  opslaan. De backend legt het gebruikersmailadres vast en de bestaande configuratieservice
  accepteert alleen een op dat moment beschikbare vendor/model/mode-combinatie.
- Projectoverschrijvingen worden bij dispatch opgezocht met de canonieke projectnaam die hoort bij
  de targetrepository; de trackerprojectkey wordt daarvoor niet misbruikt.
- `flutter analyze` is groen en de twee gerichte Settings-widgettests zijn groen. De volledige
  Flutter-testset heeft twee reeds bestaande failures in de quota-wachtstatusweergave; deze wijziging
  raakt die schermen niet.
- Gerichte Kotlin-verificatie (`BridgeRequestHandlerTest`, `DashboardQueryServiceTest` en
  `BridgeApiControllerTest`) is groen: 165 tests, zonder failures.

### 2026-09-11 — auditroute zonder workspace

- `AuditGatewayAdapter` maakt geen storyworkspace of lokale repositorycheckout meer. De auditjob
  krijgt de geregistreerde Runtime-alias en een read-only checkout van de basebranch (`main` als
  bestaande default).
- Auditresultaten komen uit het getypeerde Runtime-resultaat in plaats van `agent-result.json`.
  Rapport, vragen, bevindingen, knowledge-updates, vervolgstory, usage en kosten blijven via de
  bestaande auditpipeline lopen.
- De Runtime-completionpoller projecteert voor auditors wel events en status, maar publiceert ze
  niet naar de gewone trackercompletion: een synthetische auditkey is geen trackerissue.
- Repositorybewijs van een audit is verplicht, moet publicatiemodus `NONE` hebben en exact bij de
  verwachte alias, branch en actuele remote branch-head horen; anders faalt de audit zichtbaar.
- Een tijdelijk onleesbaar resultaat van een geslaagde terminale job blijft polbaar. Gerichte tests
  voor Runtime-resultaatmapping, workspacevrije auditdispatch en auditrapportpublicatie zijn groen.

### 2026-09-11 — hervatbare Runtime-inputuploads

- Commit: `feat: upload Runtime-invoer hervatbaar [skip ci]`.
- Migratie `V38` bewaart per idempotente agentstap en logische objectnaam het Runtime-upload- en
  object-ID, de inhoudshash en de laatst bekende offset. Een restart of verloren create-jobresponse
  hergebruikt daardoor exact dezelfde READY-objectreferenties.
- De authoritative `HEAD`-offset van Runtime bepaalt waar een onderbroken upload verdergaat. Een
  verlopen of verdwenen reservering wordt vervangen; gewijzigde inhoud, bestandsnaam of MIME onder
  dezelfde idempotentiesleutel wordt fail-closed geweigerd.
- Product Factory-attachments worden rechtstreeks uit de tracker gelezen en als Runtime-inputobject
  aangeboden. Er ontstaat geen gedeelde map of blijvende checkout. Een prompt boven de Runtime-limiet
  van 65.536 tekens wordt als `full-prompt` geüpload; de inline instructie verwijst naar het vaste
  workerpad. Het resultaatschema blijft inline, omdat het actuele v2-contract daar geen objectref voor
  accepteert.
- De consumer accepteert ook de actuele niet-terminale Runtime-status `WAITING_FOR_WORKER`.
- Test: upload-HTTP-contract, hervatten vanaf de serveroffset, Product Factory-attachmentdispatch,
  workspacevrije dispatch en de actuele wachtstatus zijn groen: 17 gerichte tests, geen failures.
  Een schone PostgreSQL/Flyway-run tot en met `V38` is groen en een afzonderlijke upgradeproef van
  `V37` naar `V38` behoudt een bestaande Runtime-job en maakt de uploadtabel aan.
- `quality/run.sh` is nog rood op 24 reeds tijdens de eerdere refactor opgebouwde afwijkingen ten
  opzichte van de oude ratchetbaseline. De nieuwe uploadservice en gesplitste uploadclient voegen na
  refactor geen eigen finding of suppressie toe; het herstellen/herijken van de volledige ratchet
  blijft een expliciete gate voor stap 5.

### 2026-09-11 — fail-closed Runtime-outputartifacts

- De tester declareert één optioneel Runtime-artifact met de contractgeldige logische naam
  `screenshots`, MIME-type `application/zip` en een maximum van 100 MiB. De agentinstructie noemt
  het exacte workerpad `/job/output/artifacts/screenshots` en staat alleen PNG, JPEG en WebP toe.
- Resultaatartifacts blijven immutable objectreferenties in de duurzame completionpayload. Voor
  enige run-, tracker- of artifactpublicatie valideert de Software Factory declaratie, uniciteit,
  READY-status, MIME, aangekondigde grootte, maximumgrootte en SHA-256 tegen de gedownloade bytes.
- Screenshot-ZIP's worden begrensd en veilig uitgepakt: onveilige paden, onbekende extensies,
  foutieve magic bytes, lege ZIP's, te veel bestanden en te grote uitgepakte inhoud worden
  geweigerd. Geldige screenshots gaan via de bestaande trackerattachment- en eventroute.
- Een Runtime-tester heeft geen lokale workspace of legacy `.factory/verification.yaml` nodig. De
  `tested`-fase steunt op het afzonderlijke read-only repositorybewijs, dat vóór trackerpublicatie
  nog steeds fail-closed tegen branch, publicatiemodus `NONE` en actuele remote HEAD wordt getoetst.
- Gerichte tests voor artifactvalidatie/extractie, resultaatmapping, legacy testerbewijs,
  workspacevrije Runtime-testers en blokkeren vóór domeinpublicatie zijn groen: 39 tests, geen
  failures.

### 2026-09-11 — expliciete Runtime-status- en foutprojectie

- De bestaande Agents-pagina leest per run ook Runtime-job-ID, status, fase, foutcode en foutmelding
  uit `agent_runtime_jobs`. Actieve en recente runs tonen deze informatie zonder nieuw scherm of
  tweede frontend.
- `SUCCEEDED` blijft wachten wanneer het resultaat tijdelijk niet leesbaar is. `FAILED` gebruikt
  een aanwezig getypeerd resultaat voor blijvend rode verificatie, maar projecteert
  `RESULT_NOT_READY` als de terminale Runtimefout. `CANCELLED` en `TIMED_OUT` vragen geen resultaat
  op dat volgens het contract niet bestaat en worden direct zichtbaar afgerond.
- De badgekleuren onderscheiden geslaagd, actief, wachtend en terminale fout/cancel/timeout.
- Vier pollertests, vier Agents-widgettests en de PostgreSQL-repositorytest zijn groen, inclusief
  transient-resultretry, verificatiefout met resultaat, databaseprojectie en foutweergave.

### 2026-09-11 — promptpariteit en mockacceptatie

- Alle acht Runtime-rollen hebben weer de kritieke gedragscontracten uit de oude agentworker:
  refiner-markers, minimale planneropdeling, developer-handover en verificatie, volledige reviewpass,
  read-only gedragstests, verplichte gebruikerssamenvattingen, docs-scope en hervatbare auditvragen.
- Algemene instructies leggen single-run-gedrag, leidende PO-comments, knowledge-updates, verplichte
  resultaatvelden en het verbod op muterende Gitacties vast. Developer-loopback, effort en
  niet-geheime previewcontext worden expliciet meegegeven; de previewdatabase-URL niet.
- Resultaatschema's staan alleen geldige fases per rol toe. Een afgeronde planner moet subtaken
  leveren en een afgeronde summarizer beide gebruikerssamenvattingen.
- Een lokale consumeracceptatietest doorloopt jobaanmaak en getypeerd resultaat uitsluitend met
  `mock/mock/MOCK`, zonder repositorycheckout. Aanvullende tests bewijzen vragen, subtaken,
  samenvattingen en alle rol-specifieke promptankers.

### 2026-09-11 — repositorybewijs en stale gate

- Muterende en read-only resultaten worden naast branch en publicatiemodus ook tegen de voor het
  targetproject geregistreerde Runtime-repositoryalias gecontroleerd. Een ontbrekende of afwijkende
  alias kan geen trackerfase of PR publiceren.
- Reviewer- en testerbewijs blijft gekoppeld aan `checkoutCommitSha`. Wanneer de actuele remote
  storybranch inmiddels verder staat, schrijft de Software Factory een zichtbare fout en schuift de
  review-/testfase niet door.
- Gerichte completiontests bewijzen zowel aliasverwisseling als stale reviewerbewijs zonder lokale
  workspace.

### 2026-09-11 — lokale worker-, Docker- en workspaceketen verwijderd

- Commits `9bb534cc`, `1fac333a`, `e6a626ab` en `8135392a` verwijderen achtereenvolgens de losse
  `agentworker`-module en agentimage, de lokale Docker-runtime, alle per-storyworkspaces en de
  hostgebonden actie om zo'n workspace in IntelliJ te openen.
- De Maven-reactor en CI bouwen geen lokale agentimage meer. Agentcompletion accepteert voor
  repositoryrollen uitsluitend Runtime-v2-resultaten, artifacts en repositorybewijs; de Software
  Factory clonet, commit en pusht niet meer zelf.
- Purge, merge en re-implement beheren alleen nog remote branch/PR/preview en duurzame state. Oude
  `workspace_path`-databasevelden blijven leesbaar voor bestaande data, maar sturen geen gedrag.
- De vier-module-testcompilatie is groen. Alle 862 functionele unit-tests in `softwarefactory`
  waren groen; alleen de al bekende Modulith-ratchet met de 24 eerder vastgelegde architectuurpunten
  blijft rood en wordt als expliciete stap-5-gate hersteld/herijkt.

### 2026-09-11 — Telegram-assistent via Agent Runtime

- De conversationele Telegram-assistent maakt per beurt een `APPLICATION_WORK` /
  `STRUCTURED_GENERATION`-job op Agent Runtime v2. Een meegestuurde afbeelding gaat via het
  hervatbare inputobjectprotocol; er is geen lokale container, checkout, toolmount of projectsecret.
- De rol `assistant` is toegevoegd aan de databasegestuurde modelconfiguratie en heeft een
  migratiedefault. Antwoordtekst en herbruikbare tips komen uit een begrensd JSON-schema; usage en
  kosten komen uit het Runtime-resultaat. `/stop` annuleert de lopende Runtime-job.
- `ClaudeAssistantClient`, `AssistantWorkspaceService`, `Dockerfile.assistant`, de lokale
  image-buildroute en de providercredentialvelden zijn verwijderd. `SF_AGENT_RUNTIME_TOKEN` is de
  enige AI-runtimecredential van de Software Factory.
- De gerichte assistent-, Telegramflow-, poller-, bridge- en secrets-loadertests zijn groen. De
  nieuwe `RuntimeAssistantClientTest` bewijst taaktype, modelkeuze, ontbrekende environmentkeys en
  mapping van tekst, tips en kosten.

### 2026-09-11 — stap 4 afgerond: legacy-uitvoering en actuele documentatie opgeschoond

- De losse lokale OpenHands/Ollama-stack en zijn Docker-socketmount zijn verwijderd. De repository
  bevat geen Software Factory-agentimage of lokale AI-uitvoeringsroute meer; Docker is alleen nog
  ontwikkelondersteuning voor Compose/Testcontainers en targetprojectpipelines kunnen uiteraard hun
  eigen images blijven bouwen.
- `README.md`, `runbook.md`, installatie/onboarding en de actuele documenten onder `docs/factory`
  en `docs/technical` beschrijven nu uitsluitend Agent Runtime v2, één remote storybranch en de
  huidige tijdelijke bridge-topologie. Het afzonderlijke OpenShift-/renameplan blijft buiten deze
  refactor.
- Twee achtergebleven repo-rootdetecties gebruikten het al verwijderde `agentworker`-pad; zij
  herkennen de reactorroot nu aan de root-POM. Oude sourcecommentaren en docs-skeletonprompts zijn
  eveneens bijgewerkt.
- `runtime :: v2` is een expliciete publieke Modulith-interface voor de bestaande dashboard- en
  Telegramconsumers; de al aanwezige pipeline→contract- en dashboard→GitHubrelaties zijn in de
  dependencyallowlist vastgelegd. `ModulithArchitectureTest` en `ModuleApiConventionTest` zijn nu
  volledig groen (9 tests; de eerdere 24 ratchetmeldingen zijn weg).
- De Detekt-ratchet is na het bewust verwijderen van de vijfde Mavenmodule herijkt naar de vier
  actuele modules. Het aantal blokkerende bevindingen bleef exact 185 en er kwam geen suppressie
  bij; `./quality/run.sh` is groen.

### 2026-09-11 — AI-levelroutering verwijderd

- Commit `8313d7a0` verwijdert `aiLevel`, de `LEVEL=`-commenttrigger en `AiRouting` uit productie,
  trackerprojectie, frontend en testfixtures. Bestaande databasekolommen blijven onaangeroerd maar
  sturen geen gedrag meer.
- `AgentRuntimeV2Adapter` kiest de uitvoering uitsluitend uit `agent_role_execution_config` en
  retourneert de werkelijk gekozen vendor, model en mode; `agent_runs` bewaart voortaan die
  Runtimekeuze in plaats van trackerafleiding.
- Een schone testcompilatie van `softwarefactory` en `dashboard-backend`, vijf gerichte Kotlin-
  testsuites en `flutter analyze` zijn groen.

### 2026-09-11 — E2E-harness op het Runtime-repositoryprotocol

- `TestAgentRuntime` levert asynchrone, getypeerde Runtime-completions met repository- en
  verificatiebewijs. Er is geen `agent-result.json`, tijdelijke storyworkspace of agentgestuurd
  PR-event meer nodig.
- De fake Git-provider maakt de remote storybranch en PR via dezelfde factorygrens als productie.
  Muterende jobs committen/pushen als Runtime-dubbel op de lokale bare remote; iedere volgende job
  leest de actuele branchstand opnieuw.
- De verificatielooptest bewijst dat twee rode developerjobs niets publiceren en teruglopen, waarna
  een groene derde job de review- en testketen vrijgeeft. Read-only testerbewijs wordt aan de
  huidige branch-SHA gekoppeld.
- Gerichte E2E-tests voor de volledige story, verificatieloop en handmatige poort zijn groen. Een
  volledige run van 90 E2E-tests vond één achterhaalde verwachting dat een PR ontbrak; die test is
  aangepast aan het nieuwe factory-beheerde PR-protocol en afzonderlijk groen herhaald.

### 2026-09-11 — resultbestandcontract verwijderd

- Het gedeelde `AgentResultFile`-wirecontract en zijn golden contracttest zijn verwijderd. Het
  resterende `factory-contracts` bevat alleen nog de dashboardbridgecontracten.
- De auditadapter projecteert het getypeerde Runtime-resultaat rechtstreeks naar een intern
  auditresultaat. `AgentRunCompleteRequest` gebruikt het eigen duurzame rate-limitmodel en bevat
  geen legacy agentworker-verificatiebewijs meer; verificatie komt uitsluitend uit het afzonderlijke
  Runtime-v2-resultaat.
- Retryclassificatie herkent Runtime-worker- en verbindingsfouten in plaats van ontbrekende
  `/work/agent-result.json`-bestanden. Testcompilatie en 36 gerichte tests voor completion, audit,
  retries en bridgecontract zijn groen.

### 2026-09-11 — workspace-opruimactie verwijderd

- De niet meer bestaande lokale workspace-opruimer is uit de backendcatalogus, de handmatige
  `Alles draaien`-route en het onderhoudsscherm verwijderd. Bestaande historische database-rijen
  met kind `workspaces` blijven gewone leesbare historie; er wordt alleen geen nieuwe ronde meer
  aangeboden of gestart.
- De Runtime-API-documentatie in de code noemt nu duurzame jobcorrelatie en getypeerde completions
  in plaats van containerworkspaces en resultbestanden.
- Drie gerichte backendtestsuites inclusief PostgreSQL/Flyway en alle 21 widgettests van het
  onderhoudsscherm zijn groen.

### 2026-09-11 — lokale opleveringsgates stap 5 groen

- Nieuwe stories bewaren geen legacy trackerkeuze voor AI-leverancier of model meer. De
  databasegestuurde configuratie per Runtime-rol/project is de enige uitvoeringsbron; bestaande
  velden blijven uitsluitend voor historische compatibiliteit leesbaar.
- Quota-/wachtstatussen in het dashboard zijn providerneutraal gemaakt en spreken over Agent
  Runtime. De bijbehorende tests gebruiken een blijvend toekomstige datum en zijn daardoor niet
  meer afhankelijk van de kalenderdatum waarop de suite draait.
- `./quality/run.sh` is groen: vier Kotlin-mainmodules, 709 bevindingen geregistreerd, geen nieuwe
  bevindingen, geen hernoemde bevindingen en geen nieuwe suppressies.
- `flutter analyze` meldt geen issues en alle 172 Flutter-tests zijn groen.
- `mvn -B --no-transfer-progress clean verify` is groen voor de volledige reactor: contracts,
  common, de Software Factory met unit- en E2E-tests, en dashboard-backend. Alle 39 Flyway-
  migraties zijn daarbij ook vanaf een lege PostgreSQL-database uitgevoerd.
- De volgende commit bevat bewust geen `[skip ci]` en start daarmee de normale verificatie- en
  imageketen. Pipeline-, image-, deployment- en live-acceptatiebewijs worden daarna hier
  toegevoegd.

### 2026-09-11 — pipeline, images en huidige deployment groen

- Opleveringscommit `6b7d86d4` is rechtstreeks naar `main` gepusht zonder `[skip ci]`.
- GitHub Actions-run `34607990679` (`Repository verification`) is volledig groen: releasebot,
  Flutter, Maven en de fail-closed verzamelgate zijn geslaagd.
- Image-runs `34608643508` en `34608643346` zijn groen. Backend en frontend zijn gepubliceerd als
  `sha-6b7d86d`; de Android-APK is eveneens gebouwd en als release gepubliceerd.
- De geautomatiseerde manifest-PR's `#483` en `#484` zijn na hun verplichte verificatie gemerged.
  Argo CD staat op revisie `8a695ef9`, `Synced` en `Healthy`; beide deployments zijn `1/1` en
  gebruiken image `sha-6b7d86d`. De publieke `/healthz` retourneert `ok`.
- De lokale orchestrator is via zijn bestaande beheer-API zonder actieve agentrun herstart. De
  Product Factory v2-status retourneert `connected=true`, `apiVersion=2` en factoryversie
  `8a695ef9`.
- De toen nog ontbrekende `SF_AGENT_RUNTIME_TOKEN` hield de echte story-, hotfix-, audit- en
  Telegramacceptatie tegen; de volgende bewijssectie legt de latere configuratie en hervatting vast.

### 2026-09-11 — Runtime-token actief en Product Factory v2 live hersteld

- De eigenaar heeft `SF_AGENT_RUNTIME_TOKEN` in het bestaande gitignored `secrets.env` gezet. Na
  herstart meldde Runtime `/healthz` `UP`; de geauthenticeerde repositorycatalogus was leesbaar en
  de Product Factory-status meldde `connected=true`, `apiVersion=2` en factoryversie `4bfd54ae`.
- De eerste live create-proef bracht een regressie aan het licht: nieuwe stories bewaren bewust
  geen legacy `ai_supplier`, terwijl `findAllStories` en `findAiIssues` die rijen nog wegfilterden.
  Daardoor maakten twee requests met dezelfde idempotentiesleutel twee stories. De twee uitsluitend
  voor deze proef gemaakte stories `SF-2392` en `SF-2393` zijn na controle inclusief hun lege
  subtasksets verwijderd; er is geen repositorywerk gestart.
- De trackerqueries accepteren nu Runtime-v2-stories en -subtaken zonder legacy supplier en het
  echte storyoverzicht levert alle stories onafhankelijk van dat veld. De regressiedekking gebruikt
  expliciet supplierloze stories. Gerichte database-, bridge- en dashboardtests zijn groen: 161
  tests, geen failures.
- De herhaalde live contractproef maakte `SF-2394` één keer aan met een kleine attachment. Dezelfde
  request en idempotentiesleutel retourneerden daarna `created=false` met dezelfde storykey; get en
  list vonden exact die story en cancel eindigde zichtbaar in `CANCELLED`. Er is geen muterende
  Gitstap bereikt.
- `./quality/run.sh` is opnieuw groen met 709 geregistreerde bevindingen en nul nieuwe findings of
  suppressies. `mvn -B --no-transfer-progress clean verify` is opnieuw groen voor de volledige
  reactor: 851 Software Factory-unittests, alle E2E-suites en 90 dashboard-backendtests.
- De live catalogus meldt alle echte projectaliases beschikbaar, maar `test-repository` nog steeds
  `available=false`. Daarom worden story-, hotfix- en auditacceptatie niet op een productierepository
  uitgevoerd. Telegramdelivery en menselijke reply-/commandinteractie blijven onderdeel van
  dezelfde resterende acceptatieronde.
