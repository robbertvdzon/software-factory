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
- Software Factory-refactor: **gestart op `main`**.

## Stapstatus

| Stap | Status | Bewijs | Volgende gate |
|---:|---|---|---|
| 0 | bezig | Productie-health is groen; execution options en repositoryaliases zijn op 2026-09-11 uitgelezen; een echte `STRUCTURED_GENERATION`-probe eindigde `SUCCEEDED` met job `3a2c4970-b6b3-426c-830a-488aa47ef8af`. | Mock- en repositoryprobes afronden. De allowlist bevat `test-repository`, maar er is nog geen online worker die die alias aanbiedt. |
| 1 | afgerond | [`ontwerp-runtimevervanging.md`](ontwerp-runtimevervanging.md) legt vervanging, correlatie, prompts, Git-eigenaarschap, modelconfiguratie, quota en workspace-aannames vast. | Runtime-consumer bouwen. |
| 2 | bezig | De v2-adapter maakt idempotente jobs, bouwt rolprompts/schema's, projecteert events/resultaten en gebruikt databasegestuurde modelkeuze. Refiner, planner en summarizer volgen hiermee het v2-pad. Het Settings-scherm beheert defaults en projectoverschrijvingen. Prompt en attachments gebruiken waar nodig hervatbare inputuploads. | Artifactvalidatie en expliciete status-/foutprojectie afronden. |
| 3 | bezig | Storybranch, dispatch, repositorybewijs, verificatiebewijs en PR worden zonder lokale checkout verwerkt; projectmodelkeuze gebruikt de canonieke repositoryprojectnaam. Ook audits gebruiken een read-only Runtime-checkout en getypeerd resultaat. | Promptpariteit, stale-weergave en de resterende repositoryscenario's automatiseren. |
| 4 | niet gestart | — | Stap 3 groen. |
| 5 | niet gestart | — | Oude runner verwijderd en volledige reactor lokaal groen. |

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
| Mockuitvoering | beschikbaar, live probe open | Contract en Runtime-tests ondersteunen mocks; productie verbiedt mocks terecht. Lokale/acceptatieprobe volgt zonder productieconsumer te muteren. |
| Tijdelijke repositoryketen | geblokkeerd voor live probe | `test-repository` is toegestaan maar niet beschikbaar op een online worker. Er wordt niet uitgeweken naar een productierepository. |

## Besluiten en blokkades

- De ontbrekende online `test-repository`-alias blokkeert alleen de destructieve live probe uit
  stap 0, niet de consumerimplementatie: dezelfde contracten zijn in Agent Runtime zelf getest en
  productie toont de echte repositorycapaciteit.
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
