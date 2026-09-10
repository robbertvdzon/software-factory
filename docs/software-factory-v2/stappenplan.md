# Software Factory v2 — implementatieplan

Status: toekomstontwerp, nog niet geïmplementeerd

Peildatum: 2026-09-10

Doelrepository: `softwarefactory`

Uitvoeringsgrens: uitsluitend deze repository; wijzigingen in siblingrepositories zijn verboden

## Doel van dit document

Dit document beschrijft hoe de huidige Software Factory naar een v2-architectuur wordt gebracht.
Met **v2** wordt hier de nieuwe interne Software Factory bedoeld, niet alleen het reeds bestaande
externe endpoint `/api/integrations/v2` en ook niet het oude dashboard-UX-document met "v2" in de
naam.

Product Factory is inmiddels volgens het nieuwe capabilitymodel opgebouwd en gebruikt de gedeelde
Agent Runtime v2. Software Factory heeft al veel goed werkende functionaliteit en sterke
regressietests, maar combineert nog een eigen tracker, workflowmotor, Docker-agent-runtime,
agentworker, repositorypublicatie, dashboardprojecties en transportadapters in één organisch
gegroeide applicatie. Software Factory v2 maakt die verantwoordelijkheden expliciet en besteedt
duurzame agentuitvoering en al het lokale repositorywerk uit aan Agent Runtime.

Dit is **geen** strangler-migratie. De software is op dit moment niet in gebruik. V1 wordt daarom
vroeg en in zijn geheel verwijderd, en v2 wordt in dezelfde applicatie opnieuw opgebouwd. Er komt
geen dubbele engine, geen coexistence, geen canary en geen rollbackpad. Dat scheelt ongeveer een
derde van het werk en juist het deel met de meeste randgevallen.

## Uitvoeringswijze

Deze afspraken gaan vóór alle gewoontes uit de bestaande documentatie en vóór de manier waarop de
Software Factory zelf normaal stories uitvoert.

- Alle werk gebeurt **rechtstreeks op `main`** in deze repository, in kleine, begrijpelijke commits.
- Er wordt **geen storybranch, geen pull request en geen review-agent** gebruikt voor dit werk.
- Er wordt **geen CI-pipeline per stap** gedraaid. Iedere commit tot en met stap 9 krijgt
  `[skip ci]` in de commit message, zodat `verify.yml` en daarmee ook de image-builds en de
  automatische image-bump niet starten.
- Verificatie gebeurt lokaal en gericht: `mvn -B verify` op de geraakte modules, `flutter analyze`
  en `flutter test` bij frontendwerk, en `./quality/run.sh` waar dat zinvol is. Niet elke stap
  hoeft de volledige reactor groen te hebben.
- **De Software Factory mag tijdens stap 2 tot en met 9 kapot zijn.** De applicatie hoeft niet te
  draaien, het dashboard hoeft niet te werken, de Product Factory-integratie hoeft niet te werken
  en er hoeft geen story afgerond te kunnen worden. Voorzichtigheid die alleen bestaat om
  productie te beschermen is hier verspilling.
- Pas in stap 10 wordt alles weer echt werkend gemaakt: pipeline groen, images gebouwd,
  deployment op OpenShift gezond en de Product Factory-integratie aantoonbaar werkend.
- Het Git- en branchprotocol verderop in dit document beschrijft hoe **de Software Factory straks
  werk in targetprojecten uitvoert**. Het geldt niet voor de bouw van v2 zelf.

## Instructie voor de implementerende agent

Dit document is de zelfstandige uitvoeringsopdracht voor Software Factory v2. Lees vóór iedere
implementatiestap de actuele code, tests, deploymentmanifests en documentatie in deze repository;
gebruik dit plan als norm voor de gewenste eindtoestand en gebruik de bestaande code als bewijs
voor de actuele uitgangssituatie.

De implementerende agent werkt uitsluitend in de repository `softwarefactory` en moet:

1. de stappen in dit document op volgorde uitvoeren;
2. `docs/software-factory-v2/VOORTGANG.md` aanmaken en gedurende de uitvoering actueel houden;
3. per stap code, migraties, tests en documentatie samen opleveren;
4. open beslissingen niet zelf invullen, maar als expliciete holdpoint aan Robbert voorleggen;
5. geen code, configuratie of documentatie in een andere repository aanpassen;
6. geen tijdelijke lokale Git-, AI- of workerimplementatie bouwen om een externe dependency te
   omzeilen.

Niet beginnen voordat Robbert heeft bevestigd dat Agent Runtime klaar is (zie hieronder).

## Externe prerequisite: Agent Runtime v2

De Runtime-wijzigingen die Software Factory nodig heeft, worden apart uitgevoerd in de siblingrepo
`agent-runtime`. De normatieve opdracht daarvoor staat in het
[Agent Runtime-prerequisitedocument](../../../agent-runtime/docs/software-factory-v2-integratieplan.md).

Dit Software Factory-plan mag die siblingrepo alleen read-only inspecteren. Het mag de Runtime niet
zelf aanpassen. Ontbreekt een vereiste capability, dan registreert de implementerende agent in
`VOORTGANG.md` welke contractversie of capability ontbreekt en blokkeert hij uitsluitend het
daarvan afhankelijke werk. Er komt geen lokale workaround in Software Factory.

Voor de consumerimplementatie zijn uit die prerequisite vooral deze garanties relevant:

- Software Factory levert bij repositorygebruik een geregistreerde alias en een reeds bestaande
  remote branch aan, geen lokale map en geen repositorycredential;
- muterende jobs gebruiken `REPOSITORY_WORK`, `REPOSITORY_AGENT` en publicatiemodus
  `COMMIT_AND_PUSH`;
- read-only reviewer-, tester- en auditjobs gebruiken `APPLICATION_WORK`, `REPOSITORY_AGENT` en
  publicatiemodus `NONE`;
- de Runtime-worker maakt een verse tijdelijke checkout, maar maakt geen storybranch of PR;
- het gevalideerde AI-resultaat en het Runtime-beheerde repositoryresultaat blijven afzonderlijk
  beschikbaar;
- `checkoutCommitSha` en een eventuele gepubliceerde `commitSha` zijn bewijsmetadata, niet het
  overdrachtsprotocol tussen agents;
- `NO_CHANGES` is een geldige technische publicatie-uitkomst;
- een gewijzigde remote branch leidt veilig tot `BRANCH_CHANGED`, nooit tot force-push;
- workerselectie houdt rekening met repositoryaliasbeschikbaarheid;
- een crash na push wordt gereconcileerd zonder een tweede commit;
- bestaand `repositorySnapshot`-gedrag voor andere consumers blijft intact.

Gebruik tijdens implementatie het daadwerkelijk gepubliceerde Agent Runtime `/v2`-OpenAPI-contract
als technische bron. Als namen tijdens de Runtime-implementatie gecontroleerd zijn aangescherpt,
werk dan alleen de Software Factory-consumer en dit document bij; behoud de bovenstaande semantiek.

**Startvoorwaarde:** Robbert bevestigt expliciet dat de Agent Runtime-wijzigingen klaar en
beschikbaar zijn. Tot dat moment wordt er in deze repository niets geïmplementeerd.

## Genomen beslissingen

Deze beslissingen zijn genomen en hoeven niet opnieuw te worden afgewogen.

| Onderwerp | Beslissing |
|---|---|
| Migratiestrategie | Geen coexistence. Geen `engineVersion` per story, geen dubbele claimqueries, geen WebSocketbridge in stand houden, geen `SHADOW`-modus, geen canary, geen rollbackpad, geen expand/contract-fasering. |
| V1-code | Wordt in stap 2 in zijn geheel verwijderd: lokale `softwarefactory`-orchestrator, `agentworker`, DockerAgentRuntime, trackerfasecoördinatoren, WebSocketbridge. |
| Bestaande data | Bij voorkeur migreren naar het v2-model. Blijkt dat onevenredig veel werk, dan wordt de oude data na expliciet akkoord van Robbert weggegooid. |
| Beschikbaarheid tijdens de bouw | De Software Factory hoeft tussen stap 2 en 9 niet te werken. |
| Product Factory | Mag tijdens de bouw stuk zijn. Moet in stap 10 weer werken volgens het bestaande contract. |
| Werkwijze | Rechtstreeks op `main`, geen PR's, CI geskipt met `[skip ci]` tot stap 10. |
| Telegram | Blijft onderdeel van v2, inclusief de assistent. |
| Audit | Blijft een volwaardige v2-capability. |
| Projectconfiguratie | `projects.yaml` blijft voorlopig de bron. Configuratie in Postgres vraagt een eigen beheer-UI en wordt bewust uitgesteld. |
| Rename | Backend, frontend, images en deployments worden hernoemd naar `software-factory-backend` en `software-factory-frontend`; de publieke host wordt `softwarefactory.vdzonsoftware.nl`. Dit gebeurt vroeg, niet aan het eind. |
| Uitvoerder | Robbert laat dit door een agent rechtstreeks uitvoeren; de Software Factory bouwt zichzelf niet via een eigen story. |

## Openstaande beslissingen

Deze punten mogen niet door een implementerende agent zelf worden ingevuld.

1. **Hoe draait de deterministische verificatie in v2?** Zie de aparte paragraaf hieronder.
2. **Hoe diep gaat de datamigratie?** Alleen afgeronde stories als historie, of ook openstaand
   werk, comments, agent-runs en kosten?
3. **Wie voert de hostwissel uit?** DNS, de OpenShift-route en de SSO-redirectconfiguratie liggen
   buiten deze repository.
4. **Blijft de bestaande AI-routing bestaan?** Vandaag bepaalt `AiRouting` (supplier + AI-level 0-10)
   het model en de effort. Agent Runtime v2 wil een expliciete `vendorId`, `model` en `mode` per job
   en kent geen fallback. Wordt de bestaande routing vertaald, of wordt per agentrol een vast model
   vastgelegd?
5. **Pinnen we een Agent Runtime-contractversie?** Zodat consumercode niet breekt terwijl aan de
   Runtime nog geschaafd wordt.

### Holdpoint: het pad voor deterministische verificatie

Dit is het grootste open punt en het blokkeert stap 7 en stap 8.

Vandaag draait `.factory/verification.yaml` in de **agentworker**, in de story-workspace op de
laptop (`TesterVerificationRunner`). Software Factory valideert per commando de `id`, `argv`,
`exitCode`, `toolStatus`, outputgrens en configversie, en koppelt dat bewijs aan een revisie.

In v2 verdwijnt de agentworker, mag Software Factory zelf geen repositorycheckout meer hebben, en
krijgt Agent Runtime bewust geen generieke shell-executor. Daarmee is er op dit moment geen
uitvoerder meer voor die commando's. Er zijn drie mogelijke uitwerkingen:

- **A. Bestaande project-CI als bewijs.** Elk beheerd project heeft al een GitHub Actions-workflow
  die op een PR draait en inhoudelijk hetzelfde doet als `.factory/verification.yaml` (bij `pvdd`
  bijvoorbeeld documentatiecheck, `mvn verify` en de Flutter-checks). Software Factory leest de
  check-status van die workflow op de story-PR. Goedkoopste optie, geen wijziging in targetrepo's.
  Nadeel: het bewijs is "de workflow was groen op deze commit", niet langer per commando met
  `argv` en `exitCode`, en `.factory/verification.yaml` wordt daarmee overbodig of alleen nog
  documentatie.
- **B. Een eigen verificatieworkflow per targetproject.** Elk project krijgt een workflow die
  letterlijk `.factory/verification.yaml` uitvoert en per commando rapporteert. Dichtst bij het
  huidige bewijsmodel. Nadeel: het vraagt een wijziging in élk targetproject, en "targetapplicaties
  aanpassen" staat nu als buiten scope in dit plan.
- **C. Verificatie als Runtime-taaktype.** Agent Runtime krijgt een expliciete, niet-generieke
  verificatie-executor die een vastgelegde commandoset in de container draait. Behoudt het bewijs
  per commando zonder targetrepo's te wijzigen. Nadeel: het vraagt een contractuitbreiding in de
  siblingrepo en dus afstemming met het Runtime-werk.

Zolang deze keuze open staat, worden stap 7 en stap 8 wel gebouwd, maar blijft de mergegate
expliciet geblokkeerd op ontbrekend verificatiebewijs.

## Samenvatting van de gewenste eindtoestand

Software Factory v2:

- bewaart haar eigen productwaarheid in expliciete domeinentiteiten in plaats van een generieke
  tracker-issue als gedeeld intern datamodel;
- heeft één publieke API-module en capabilitymodules met één schrijvende eigenaar per entiteit;
- gebruikt duurzame processessies, workitems, outbox-effecten en idempotente commands;
- gebruikt Agent Runtime `/v2` voor queueing, workers, containers, provideruitvoering, leases,
  retries, transcripten, artifacts, usage en kosten;
- houdt zelf de softwareontwikkelworkflow, productregels, vragen, approvals, verificatiebeleid,
  mergebeslissing en deploybeslissing in eigendom;
- ondersteunt dezelfde normale storyketen en dezelfde korte hotfixketen;
- behoudt het publieke Product Factory v2-contract en maakt een expliciete machine-hotfixroute
  mogelijk zonder een tijdelijk dashboardgebruikers-token;
- draait volledig op OpenShift in de uit `dashboard-backend` doorgegroeide
  `software-factory-backend`; er hoeft niets van Software Factory op een ontwikkellaptop te draaien;
- heeft zelf geen checkout of worktree van targetrepositories en gebruikt geen gedeelde
  netwerkmap met Agent Runtime;
- gebruikt per story één remote Git-branch als overdrachtspunt: Software Factory reserveert de
  branch, iedere Runtime-job haalt de actuele branch op en muterende jobs committen en pushen erop;
- heeft in `software-factory-frontend` één begrijpelijke responsive UI voor stories, mensacties,
  projecten, agents, audits, builds, deployments en operatie, bereikbaar op
  `https://softwarefactory.vdzonsoftware.nl`;
- bevat geen tracker-, agentworker-, bridge- of Docker-runtimecode meer.

## Huidige uitgangssituatie

De huidige Software Factory is functioneel volwassen:

- stories gaan via refiner, planner, developer, reviewer, tester, summarizer, documenter,
  approval, merge en deploy;
- een hotfix gebruikt de korte keten `hotfix -> merge -> deploy`;
- vragen, goedkeuring, notificatie-events, quota-wachtstatus, retries en handmatige commands zijn
  geïmplementeerd;
- repositorywijzigingen worden door een eigen agentworker in lokale Dockercontainers uitgevoerd;
- deterministische verificatie gebruikt `.factory/verification.yaml` en revisiegebonden bewijs;
- agentcompletion, Git-publicatie en PR-aanmaak worden lokaal door Software Factory afgehandeld;
- de eigen Postgres-"tracker" bewaart stories en subtaken in één `issues`-model met stringvelden;
- het remote dashboard-backend praat via een uitgaande WebSocketbridge met de lokale factory;
- Product Factory gebruikt het bestaande `/api/integrations/v2`-contract;
- Telegram biedt meldingen, vragen, commands en een assistent;
- read-only audits kunnen vervolgstories voorstellen;
- Spring Modulith, moduledependencychecks, Detekt-ratchet en een brede Maven/e2e-suite zijn al
  aanwezig.

Dit gedrag is de functionele meetlat voor v2. Het is uitdrukkelijk **niet** iets dat tijdens de
verbouwing operationeel in de lucht moet blijven.

## Principes en harde randvoorwaarden

### Functionele dekking

- Alles wat v1 functioneel kon, kan v2 aan het eind ook; de tabel verderop is de checklist.
- Publieke storykeys en bestaande links blijven stabiel voor zover er data wordt gemigreerd.
- De Product Factory-v2-routes zijn aan het eind weer backward-compatible.
- Telegram en dashboard tonen aan het eind de volledige v2-toestand.

### Eigenaarschap

- Iedere duurzame entiteit heeft één schrijvende capability.
- Modules wijzigen elkaars tabellen of interne JPA/JDBC-modellen niet.
- Cross-modulemutaties lopen via betekenisvolle publieke commands.
- Queries leveren immutable DTO's en zijn geen tweede schrijvend model.
- Transportadapters bevatten geen workflow- of productbeslissingen.
- De frontend bevat geen productwaarheid.

### AI-grens

- Software Factory kiest rol, taaktype, prompt, context, repository, base branch, storybranch,
  model en productbeleid.
- Agent Runtime voert een technische job uit en kent geen story-, approval-, merge- of
  deploysemantiek.
- Alleen Agent Runtime maakt tijdelijke checkouts voor agentwerk en voert `fetch`, `checkout`,
  `pull`, `commit` en `push` uit. Software Factory mount of bewaart geen repositoryworkspace.
- Binnen Agent Runtime is de worker eigenaar van alle muterende Git-acties. De AI-agent ontvangt
  alleen de reeds voorbereide worktree en mag bestanden wijzigen en tests uitvoeren, maar maakt
  geen branch, wisselt niet van branch, commit niet, pusht niet en maakt of mergt geen PR.
- Read-only Git-commando's zoals `status`, `diff` en `log` mogen voor context beschikbaar blijven.
  De agent krijgt nooit Git-publicatiecredentials.
- Opeenvolgende agentjobs delen geen filesystem. De remote storybranch is hun enige overdrachtspunt
  voor repositorywijzigingen.
- Iedere logische AI-taak heeft één duurzame Software Factory-correlatie en één Runtime-job.
- Een verloren response of restart maakt geen tweede Runtime-job.
- Software Factory vertrouwt nooit op alleen AI-proza als testbewijs.
- Providercredentials, Dockeruitvoering, workerleases en Runtime-retries verdwijnen uit Software
  Factory.

### Correctheid blijft wél gelden

Het feit dat de applicatie tijdens de bouw stuk mag zijn, verlaagt de eisen aan het eindresultaat
niet:

- idempotentie, crashherstel en fencing zijn onderdeel van het ontwerp, niet van een latere
  hardeningronde;
- een onbekende of conflicterende externe toestand blokkeert; zij wordt niet geraden;
- acceptatie gebruikt Agent Runtime `MOCK`; productie weigert mocks;
- secrets worden nooit naar de database, frontend, prompts, transcripten of logs gekopieerd.

### Bestaand mechanisme voor projectsecrets blijft ongewijzigd

- Een plaintext `secrets.env` in de eigen lokale Git-map van een targetproject blijft
  developer-beheerd, gitignored en uitsluitend lokaal.
- Software Factory en Agent Runtime lezen, kopiëren, mounten, synchroniseren of wijzigen dat lokale
  bestand niet.
- Er komt voor deze migratie geen netwerkshare, secret-sync of nieuw projectsecretmechanisme.
- Operationele credentials die Software Factory en Agent Runtime zelf nodig hebben, worden zoals nu
  via hun deploymentconfiguratie aangeboden en nooit aan prompts toegevoegd.

### Scope

Dit plan wijzigt uitsluitend de repository `softwarefactory`. Agent Runtime, Product Factory en
targetapplicaties worden alleen als externe systemen benaderd en mogen vanuit deze opdracht niet
worden aangepast. Een noodzakelijke wijziging buiten deze repository wordt als afzonderlijke
dependency gerapporteerd en niet stilzwijgend meegenomen. Als optie B uit het verificatieholdpoint
wordt gekozen, moet die grens expliciet worden verruimd.

De bestaande Agent Runtime-server op OpenShift en Runtime-worker op een MacBook blijven geldige
externe systemen; de eis dat niets meer op de laptop hoeft te draaien geldt voor Software Factory,
niet voor Agent Runtime.

## Functionele dekking die v2 moet halen

De migratie is pas compleet wanneer minimaal deze bestaande gedragingen aantoonbaar gelijk of
beter werken:

| Gebied | Verplicht v2-gedrag |
|---|---|
| Story-inname | Dashboard, Product Factory v2, interne audit en geautoriseerde API kunnen werk idempotent aanmaken. |
| Wachtrij | Per project is verklaarbaar welk werk als volgende start; bestaande `start` en `start-next`-betekenis blijft beschikbaar aan de grens. |
| Refine | Een ruwe aanvraag wordt een volledige, geversioneerde storyspecificatie of stelt gerichte vragen. |
| Plan | De planner maakt een minimale, geordende keten van uitvoerstappen. |
| Development | Een Runtime-job haalt de remote storybranch op, wijzigt die en pusht; Software Factory volgt één traceerbare PR zonder eigen checkout. |
| Review | Bevindingen zijn volledig, versiegebonden en kunnen de developer gericht laten herstellen. |
| Test | AI-onderzoek en deterministische repositoryverificatie zijn beide nodig; rood kan nooit groen worden verklaard. |
| Documentatie | Actuele documentatie wordt binnen dezelfde story bijgewerkt of aantoonbaar als niet geraakt beoordeeld. |
| Samenvatting | De aanvrager krijgt een begrijpelijke lange en korte opleversamenvatting. |
| Vragen | Iedere agentrol kan veilig wachten, meerdere vragen stellen en na antwoord exact dezelfde logische stap hervatten. |
| Goedkeuring | `automatisch`, `alleen-manual-poort` en `elke-stap` behouden hun huidige betekenis. |
| Meldingen | De acht concrete notification events behouden hun betekenis en idempotentie. |
| Hotfix | Alleen expliciet gekozen; exact één developerstap plus bestaande verificatie, merge en deploy; geen extra diffguard. |
| Merge | Alleen de centrale projectbewuste groene mergegate kan mergen; pending is geen permanente fout. |
| Deploy | Alle geconfigureerde targets worden gevolgd; voltooiing bevat de werkelijke merge-/oplevercommit. |
| Pauze en herstel | Pause, resume, kill, retry, re-implement, clear-error en annuleren hebben een duurzame, verklaarbare uitkomst. |
| Quota | Providerquota wordt als wachtstatus behandeld; Agent Runtime-attempts en Software Factory-domeinretry blijven onderscheiden. |
| Audits | Read-only projectaudits, vragen, rapporten, geheugen en maximaal één voorstel per run blijven bestaan. |
| Dashboard | Stories, mensacties, agents/jobs, builds, downloads, audits, settings en operatie blijven bruikbaar. |
| Telegram | Meldingen, antwoorden, commands en de assistent blijven werken. |
| Integratie | Product Factory leest alleen `OPEN`, `DONE`, `CANCELLED` en bij `DONE` een volledige commit-SHA. |

## Voorgesteld v2-domein

De definitieve namen worden in stap 1 vastgesteld. Dit model bepaalt wel de gewenste scheiding.

| Entiteit | Eigenaar | Betekenis |
|---|---|---|
| `Project` | Projectconfiguratie | Beheerde repository, default branch, verificatie, deployments en Runtime-alias. |
| `WorkRequest` | Work intake | Oorspronkelijke onveranderlijke aanvraag, bron, opties en externe idempotentie. |
| `Story` | Storymanagement | Publieke identiteit, actuele status en relatie naar de aanvraag. |
| `StorySpecification` | Refinement | Onveranderlijke geraffineerde storyversie met criteria en gebruikte antwoorden. |
| `ExecutionPlan` | Planning | Bevroren planversie met een geordende set stapdefinities. |
| `WorkflowRun` | Orchestration | Eén duurzame uitvoering van een storyversie met status, trigger en correlaties. |
| `WorkStep` | Orchestration | Eén stapdefinitie zoals refine, plan, development, review, test, summary, documentation, approval, merge of deploy. |
| `StepRun` | Orchestration | Een hervatbare poging van een concrete stap, inclusief gebruikte inputversies. |
| `AiTask` | AI-uitvoering | Lokale correlatie, outbox en projectie van precies één Agent Runtime-job. |
| `Question` | Mensinteractie | Tijdelijke vraag van een stap aan een geadresseerde actor met antwoordhistorie. |
| `ApprovalRequest` | Mensinteractie | Versiegebonden goedkeuringspoort met beslissing, actor en reden. |
| `ManualAction` | Mensinteractie | Niet-AI-handeling die aantoonbaar door een mens moet worden uitgevoerd. |
| `RepositoryDelivery` | Repository delivery | Base branch, remote storybranch, PR, gepubliceerde commits, verificatiebewijs en mergebeslissing. |
| `DeploymentRun` | Deployment | Eén storycommit naar één of meer geconfigureerde targets met feitelijke status. |
| `AuditRun` | Audit | Read-only onderzoek met rapport, score, vragen en optioneel voorstel. |
| `KnowledgeItem` | Agentkennis | Versieerbare, rol- en projectgebonden tip; geen productwaarheid. |
| `NotificationDelivery` | Notificatie | Idempotente bezorging van één domeingebeurtenis aan één kanaal. |

Nieuwe capabilitycode gebruikt geen vrij `Map<TrackerField, String>`-model en beslist niet op losse
fase-stringwaarden uit een generiek issue. De trackerbegrippen verdwijnen volledig.

## Voorgestelde capability- en moduleverdeling

Stap 1 mag namen aanscherpen, maar moet dezelfde verantwoordelijkheden behouden.

| Mavenmodule/capability | Verantwoordelijkheid |
|---|---|
| `software-factory-api` | Publieke commands, queries, ID/value types en immutable DTO's tussen capabilities. |
| `software-factory-backend` | De uit `dashboard-backend` doorgegroeide composition root op OpenShift: auth, HTTP, schedules, configuratie en alle v2-capabilities. |
| `project-impl` | Projectcatalogus, repository- en deploymentconfiguratie en Runtime-grants. |
| `work-intake-impl` | WorkRequest/Story-aanmaak, bron-idempotentie en publieke keys. |
| `refinement-impl` | StorySpecification, refineprocessessie en gerichte vragen. |
| `planning-impl` | ExecutionPlan en minimale stapmaterialisatie. |
| `workflow-impl` | WorkflowRun/WorkStep/StepRun, claims, state machines, outbox en hervatting. |
| `ai-execution-impl` | Agent Runtime `/v2`-consumer, uploads, jobs, events, resultaat, artifacts, usage en annulering. |
| `human-interaction-impl` | Questions, approvals, manual actions en persoonlijke actielijst. |
| `repository-delivery-impl` | Remote branch- en PR-regie, verificatiebeleid, reviewloop en mergegate; geen lokale checkout. |
| `deployment-impl` | Deploycommando's, targetreconciliatie, completion en oplevercommit. |
| `audit-impl` | Auditplanning, rapporten, vragen, kennis en gecontroleerde storyvoorstellen. |
| `notification-impl` | Domeinevents naar Telegram en eventuele latere kanalen. |
| `software-factory-frontend` | De hernoemde Flutter-frontend voor product- en operatieprojecties; geen schrijvende waarheid. |

Niet iedere capability hoeft een aparte deployable te zijn. Zij zijn modules binnen dezelfde op
OpenShift draaiende applicatie. De grens is belangrijker dan het aantal processen.

## Topologie van v2

```text
software-factory-frontend / Product Factory / Telegram
                            |
                            v
    software-factory-backend op OpenShift
    - auth en publieke API's
    - domein en orchestration
    - vragen/approvals
    - remote branch/PR-regie
    - merge/deploybeleid
                         |
                 HTTPS Runtime /v2
                         |
                         v
             Agent Runtime-worker
             (MacBook blijft toegestaan)
             - container en AI-provider
             - tijdelijke repositorycheckout
             - pull/commit/push
             - attempts/leases/artifacts
```

Er is geen lokale Software Factory-orchestrator en geen WebSocketbridge meer. Agent Runtime
vervangt niet de Software Factory-productlogica, maar wel de technische agentuitvoering en de
lokale repositoryworkspace voor agents.

## Eenvoudig Git- en branchprotocol

Dit protocol beschrijft hoe de Software Factory straks werk in **targetprojecten** uitvoert. Het
geldt niet voor de bouw van v2 zelf; die gebeurt rechtstreeks op `main` van deze repository.

Voor ieder project is de **base branch** de geconfigureerde standaardbranch waarop nieuw werk wordt
gebaseerd, normaal `main` en alleen bij afwijkende projecten bijvoorbeeld `master`. Voor iedere
story geldt vervolgens één eenvoudig protocol:

1. Software Factory reserveert een unieke storybranch en maakt die remote aan vanaf de actuele base
   branch, bij voorkeur via de Git-provider-API en zonder repositorycheckout.
2. Software Factory geeft de geregistreerde Runtime-repositoryalias en storybranch door aan Agent
   Runtime. De base branch blijft Software Factory-domeindata voor branchaanmaak en PR-regie.
3. Iedere repositorygebruikende Runtime-job start schoon, clonet of fetcht de repository, checkt de
   storybranch uit en doet vóór gebruik een pull.
4. De Runtime-worker geeft de voorbereide worktree aan de AI-agent. De agent wijzigt uitsluitend
   bestanden en voert eventueel tests uit; checkout, pull, commit en push blijven workeracties.
5. Na de agent valideert de worker dat branch, begin-HEAD en Gitmetadata niet door de agent zijn
   gemuteerd. Vervolgens commit en pusht de worker geldige wijzigingen naar dezelfde remote
   storybranch. Een read-only job pusht niets.
6. Een volgende agent haalt opnieuw de actuele remote storybranch op. Er is geen gedeelde map,
   blijvende checkout, worktree of netwerkvolume tussen jobs nodig.
7. Na de eerste succesvolle push maakt Software Factory precies één PR van de storybranch naar de
   base branch. Alle latere agentcommits komen op diezelfde branch en daarmee automatisch in
   diezelfde PR terecht.
8. De PR blijft behouden als drager van pre-merge GitHub-verificatie en preview-identiteit. Pas aan
   het einde, na groen workflow- en verificatiebewijs, mergt Software Factory deze ene PR naar de
   base branch en verwijdert de storybranch volgens het projectbeleid.

SHA's zijn geen coördinatieprotocol tussen agentstappen. Commit-ID's blijven alleen als waargenomen
bewijsmetadata waar dat functioneel nodig is: om te zien op welke actuele branchstand
review/verificatie is uitgevoerd, om stale bewijs na een latere push te herkennen en om de
uiteindelijke merge-/oplevercommit extern te rapporteren. Per story is maximaal één muterende
repositoryjob tegelijk actief.

## AI-taaktypen en Runtime-mapping

Software Factory registreert per agentrol een expliciete jobconfiguratie met `vendorId`, `model`,
`mode`, Runtime `taskType`, timeout en prompttemplateversie. Er is geen provider- of modelfallback.
Hoe die configuratie zich verhoudt tot de bestaande `AiRouting` staat open (zie openstaande
beslissing 4).

| Software Factory-rol | Runtime-job | Resultaat |
|---|---|---|
| Refiner | `APPLICATION_WORK` / structured generation | Storyspecificatie of vragen. |
| Planner | `APPLICATION_WORK` / structured generation | Geordende stapdefinities of vragen. |
| Developer | `REPOSITORY_WORK` / `REPOSITORY_AGENT` / `COMMIT_AND_PUSH` | Worker-checkout/pull, agentwijziging, worker-validatie/commit/push, diffmetadata en gestructureerde status. |
| Reviewer | `APPLICATION_WORK` / `REPOSITORY_AGENT` / `NONE` op de actuele storybranch | Oordeel over de opgehaalde branchstand en volledige bevindingen. |
| Tester | `APPLICATION_WORK` / `REPOSITORY_AGENT` / `NONE`, browser en testcredentials | Testoordeel en bewijsartifacts; deterministische verificatie blijft apart verplicht. |
| Summarizer | `APPLICATION_WORK` / structured generation | Lange en korte samenvatting. |
| Documenter | `REPOSITORY_WORK` / `REPOSITORY_AGENT` / `COMMIT_AND_PUSH` op dezelfde storybranch | Documentatiewijziging, commit en push of aantoonbaar geen impact. |
| Auditor | `APPLICATION_WORK` / `REPOSITORY_AGENT` / `NONE` op de actuele base branch | Rapport, score, vraag of storyvoorstel. |
| Telegram-assistent | `APPLICATION_WORK` zonder mutatiebevoegdheid | Antwoord of voorgesteld command; backend valideert en voert uit. |

## Overzicht van de stappen

| Stap | Naam | Belangrijkste resultaat |
|---:|---|---|
| 0 | Baseline en externe contractcontrole | Bewezen Runtime-contract, vastgelegde functionele checklist en werkende probejobs. |
| 1 | Normatieve v2-specificatie | Besloten domein, module-API's, state machines, contracten en scenario's vóór code. |
| 2 | V1 slopen en fundering neerzetten | Oude engine weg, nieuwe modulestructuur, hernoemde backend/frontend, leeg v2-schema. |
| 3 | Projecten en work intake | V2 Project, WorkRequest en Story met stabiele keys. |
| 4 | Duurzame workflowmotor | WorkflowRun, StepRun, claims, outbox, scheduling, pauze en herstel. |
| 5 | Agent Runtime v2 | Eén duurzame AI-grens voor alle rollen, zonder provider- of Dockerlogica. |
| 6 | Refine en plan | Geversioneerde specificatie, vragen en minimale uitvoerplannen. |
| 7 | Repositorywerk, review en verificatie | Developer/documenter, PR, reviewerloop, tester en machinebewijs. |
| 8 | Mensinteractie, hotfix, merge en deploy | Approvalmodi, manual actions, korte keten en veilige oplevering. |
| 9 | Audits, kennis, notificaties, Telegram en dashboard | Alle ondersteunende gebruikers- en beheerfuncties op v2. |
| 10 | Datamigratie, oplevering en weer live | Oude data over of weg, pipeline groen, images, OpenShift, Product Factory werkend. |

## Stap 0 — Baseline en externe contractcontrole

### Doel

Bewijs alle externe aannames en leg de functionele meetlat vast voordat er gesloopt en gebouwd
wordt.

### Werk

1. Draai eenmalig de volledige Maven-, Flutter-, module-, documentatie- en qualitygates op een
   schone `main` en leg commit plus tellingen vast als vertrekpunt.
2. Maak een machineleesbare inventaris van alle story- en subtaskfasen, transities, commands,
   notification events, schedulerjobs, endpoints, databasevelden en externe effecten. Dit is de
   functionele checklist voor v2, geen bewijsapparaat voor pariteit.
3. Inventariseer uitsluitend de operationele secrets en environmentvariabelen van Software Factory
   en Runtime; wijzig het bestaande lokale `secrets.env`-mechanisme van targetprojecten niet en
   toon nooit waarden.
4. Lees het gekoppelde Agent Runtime-prerequisitedocument en vergelijk het met het daadwerkelijk
   gepubliceerde `/v2`-OpenAPI-contract. Leg per vereiste vast: beschikbaar, ontbrekend of bewezen.
5. Dien gecontroleerde Agent Runtime v2-probejobs in voor alle beoogde taaktypen.
6. Bewijs met een tijdelijke testrepository: remote storybranch aanmaken door Software Factory
   zonder checkout, schoon clonen/pullen in opeenvolgende Runtime-jobs, workercommit/push naar
   dezelfde branch, read-only gebruik, `NO_CHANGES`, één door Software Factory gemaakte PR,
   artifacts, cancel, timeout en verloren response.
7. Bewijs browsergebruik en projectcredentials zonder secretlekkage.
8. Leg iedere ontbrekende Runtime-capability vast als externe blokkade. Wijzig Agent Runtime niet
   vanuit deze repository en bouw geen workaround.

### Opleveringen

- `docs/software-factory-v2/VOORTGANG.md` als enige live voortgangsbron;
- `docs/software-factory-v2/baseline.md` met exacte commit en de functionele checklist;
- `docs/software-factory-v2/agent-runtime-contractcontrole.md` met de bewezen contractversie.

### Exitcriteria

- Iedere Runtime-prerequisite is tegen het actuele contract als beschikbaar, ontbrekend of bewezen
  geclassificeerd.
- Bewezen is dat twee opeenvolgende jobs uitsluitend via de remote branch kunnen samenwerken,
  zonder lokale Factory-workspace of gedeeld volume.
- Er is geen productie- of targetrepository gewijzigd door een onbeheerde spike.

## Stap 1 — Normatieve functionele en technische v2-specificatie

### Doel

Beslis de product- en architectuurcontracten vóór implementatie. Deze stap schrijft documentatie,
ADR's, schema's en testscenario's, geen halve productiearchitectuur.

### Werk

1. Schrijf een functionele specificatie met publieke begrippen, rollen, storyopties, normale keten,
   hotfix, vragen, approvals, manual actions, audit en notificaties.
2. Beslis de capability-eigenaren en publieke API's in `software-factory-api`.
3. Definieer de state machines van Story, WorkflowRun, iedere WorkStep, Question, Approval,
   RepositoryDelivery en DeploymentRun.
4. Definieer exact welke statuses extern zichtbaar zijn aan Product Factory en dashboard.
5. Definieer idempotentiesleutels voor inname, Runtime-jobs, remote branch-aanmaak, answers,
   approvals, PR-aanmaak, merge, deploy en notifications.
6. Definieer crashpunten en herstelgedrag voor iedere externe mutatie.
7. Definieer de projectconfiguratie op basis van `projects.yaml` en vervang verspreide env-/YAML-
   toegang door één gevalideerde catalogus; projectlokale `secrets.env`-bestanden blijven buiten
   scope.
8. Werk het verificatieholdpoint uit tot een besluit van Robbert en leg het gekozen pad vast.
9. Ontwerp de datamigratie van het oude `issues`-model naar de v2-entiteiten, inclusief wat níet
   meekomt.
10. Schrijf ADR's voor minimaal Agent Runtime-eigenaarschap, capabilitygrenzen, de gekozen
    verificatieroute, de backend-/frontendrename, de URL-migratie en de datamigratie.
11. Maak een vaste Testbedcatalogus met beginsituatie, acties en verwachte publieke uitkomst.

### Reeds genomen ontwerpbeslissingen

- De base branch is de per project geconfigureerde standaardbranch, normaal `main`.
- Software Factory maakt de remote storybranch aan zonder lokale checkout.
- Iedere agentjob gebruikt een verse Runtime-checkout en doet een pull van dezelfde storybranch;
  muterende jobs committen en pushen naar die branch.
- De Runtime-worker, niet de AI-agent, voert checkout, pull, commit en push uit. Software Factory
  maakt na de eerste push precies één PR per story.
- Branchnaam en workflowstatus coördineren de keten. SHA's zijn alleen bewijs.
- Software Factory bewaart geen targetrepositorycode en gebruikt geen shared folder of
  netwerkvolume met Runtime.
- De uit `dashboard-backend` doorgegroeide `software-factory-backend` is de enige backend.
- Het bestaande projectlokale secretsmechanisme blijft ongewijzigd.
- Agent Runtime krijgt in deze migratie geen nieuwe generieke shell-executor.

### Exitcriteria

- Iedere publieke entiteit en mutatie heeft precies één eigenaar.
- Iedere state machine heeft complete toegestane en verboden overgangen.
- Alle regels uit de functionele-dekkingstabel zijn aan een v2-contract gekoppeld.
- Geen open holdpoint wordt door een implementatieagent zelf ingevuld.
- Robbert heeft het doelontwerp expliciet goedgekeurd.

## Stap 2 — V1 slopen en de v2-fundering neerzetten

### Doel

Maak de repository leeg genoeg om v2 schoon te kunnen bouwen. Vanaf hier is de Software Factory
tijdelijk niet werkend, en dat is toegestaan.

### Werk

1. Verwijder de lokale `softwarefactory`-orchestrator, de trackerfasecoördinatoren en de
   legacycommandrouting.
2. Verwijder de `agentworker`-module, DockerAgentRuntime, de lokale repositorycheckouts, de
   result-file completionpoller en de bijbehorende providerclients.
3. Verwijder de WebSocketbridge, bridgeframes, reconnect-/offline-afhandeling en alle
   doorstuurcode.
4. Verwijder de oude Runtime-/provider-/Dockersecrets uit configuratie en deployment; wijzig of
   verwijder geen projectlokale `secrets.env`-bestanden.
5. Laat de bestaande v1-tabellen voorlopig staan; zij worden pas in stap 10 gemigreerd of gedropt.
6. Hernoem `dashboard-backend` naar `software-factory-backend` en `dashboard-frontend` naar
   `software-factory-frontend`, inclusief module, artifact, image, Deployment en Service. De
   publieke host blijft voorlopig `dashboard.vdzonsoftware.nl`.
7. Voeg `software-factory-api` en de eerste capabilitymodules toe, met expliciete named interfaces
   en toegestane dependencies.
8. Voeg een apart v2-schema of eenduidig geprefixte capabilitytabellen toe.
9. Voeg `ImplementationManifest` toe met actieve capabilityprovider, versie, artifact en bron-SHA.
10. Centraliseer klok, ID-generatie, JSON, transactionele outbox, health en veilige configuratie.
11. Hergebruik voor operationele Factory-credentials het huidige gesloten secretsmechanisme via een
    v2-adapter met expliciete keycatalogus.
12. Voeg Modulith-/Maven-boundarytests en een geen-interne-cross-module-importgate toe.
13. Bouw een v2 Testbedprofiel met PostgreSQL, fake Git/PR/deploy en Runtime-mocks.
14. Werk `pom.xml`, images, CI-workflows en OpenShift-manifests bij naar de nieuwe modulestructuur
    en namen, zonder ze in deze stap te draaien.

### Exitcriteria

- De repository bevat geen trackerfase-, agentworker-, bridge- of Docker-runtimecode meer.
- De Maven-reactor bouwt en de nieuwe modulegraph is klein, expliciet en cyclusvrij.
- De backend start lokaal, migreert het lege v2-schema en rapporteert health.
- De oude v1-tabellen zijn nog aanwezig en onaangeroerd.

## Stap 3 — Projectconfiguratie, WorkRequest en Story-inname

### Doel

Maak de v2-ingang en stabiele story-identiteit.

### Werk

1. Implementeer `Project` met repositoryalias, publieke URL, base branch, verificationpolicy,
   deploymenttargets, Runtime-projectprefix en status.
2. Bouw één gevalideerde projectcatalogus op basis van `projects.yaml`; geen capability leest
   zelfstandig YAML of env.
3. Implementeer onveranderlijke `WorkRequest` en `Story` met source, options, storykey en versions.
4. Behoud het bestaande projectgebonden storykeyformaat, zodat gemigreerde historie en bestaande
   links kloppen.
5. Voeg adapters toe voor dashboard-create, Product Factory v2 en auditvoorstellen.
6. Behoud Product Factory packagehash, attachmentvalidatie, source-ID/version en idempotentie.
7. Sla binaire input duurzaam op met hash en MIME-type; maak geen agentworkspace of
   repositorycheckout in Software Factory.
8. Implementeer `DRAFT`, `QUEUED`, `RUNNING`, `WAITING_FOR_HUMAN`, `DONE`, `CANCELLED` en `FAILED`
   als publieke projectie; interne details blijven capability-eigen.

### Exitcriteria

- Een identieke Product Factory-request maakt bij herhaling precies één story.
- De bestaande v2-response en statusqueries zijn semantisch compatible met het oude contract.
- Storykeys zijn stabiel en botsen niet met bestaande sleutels.

## Stap 4 — Duurzame workflowmotor, claims en herstel

### Doel

Vervang polling op vrije trackerfasen door expliciete, getypeerde en hervatbare workflowruns.

### Werk

1. Implementeer `WorkflowRun`, `WorkStep`, `StepRun` en onveranderlijke plan-/inputversies.
2. Maak per project/story maximaal één onafgeronde logische workflowrun mogelijk.
3. Implementeer atomische claims met lease, fencingversie en begrensde retry/back-off.
4. Maak `runWorkflowSession(projectId)` de enige ingang die nieuw workflowwerk mag claimen.
5. Laat scheduler en handmatige start dezelfde publieke functie gebruiken.
6. Implementeer duurzame outboxeffecten voor Runtime-request, remote branch- en PR-aanmaak,
   question-publicatie, merge/deploy, notificatie en externe status.
7. Leg voor ieder effect request, idempotentiesleutel, response, fouttype en volgende retry vast.
8. Implementeer pause, resume, cancel, retry-current-step, re-implement en clear-error als
   betekenisvolle commands op v2-state.
9. Modelleer `WAITING_FOR_AI`, `WAITING_FOR_HUMAN`, `WAITING_FOR_QUOTA`, `WAITING_FOR_EXTERNAL`,
   `BLOCKED`, `SUCCEEDED` en `FAILED` zonder één generiek Error-veld als workflowmotor.
10. Voeg crashfixtures toe vóór/na iedere databasecommit en externe bevestiging.

### Exitcriteria

- Iedere crashfixture hervat dezelfde logische stap zonder dubbel extern effect.
- Verschillende projecten mogen parallel; dezelfde story niet.
- Een wachtende sessie houdt geen thread, database-lock of worker vast.
- Handmatige en geplande starts hebben identiek domeingedrag.
- Onbekende externe toestand blijft veilig geblokkeerd.

## Stap 5 — Agent Runtime v2 als enige AI-uitvoeringsgrens

### Doel

Laat v2 AI-taken duurzaam uitvoeren via Agent Runtime zonder eigen provider-, container-, lease- of
workerlogica.

### Werk

1. Implementeer `AiTask`, inputobjecten, outbox, Runtime-correlatie, events, attempts, artifacts,
   usage en annulering.
2. Gebruik alleen Agent Runtime `/v2` met expliciete `vendorId`, `model`, `mode` en `taskType`.
3. Controleer als ingangseis dat het actuele OpenAPI-contract branchcheckout, publicatiemodus,
   afzonderlijk repositoryresultaat, aliasrouting en herstel na push bevat.
4. Lees beschikbare execution options, environmentkeys en repositoryaliasbeschikbaarheid via de
   consumerendpoints; gebruik nooit een modelfallback.
5. Upload prompt, schema, attachments en andere grote inputs via hervatbare objectuploads.
6. Maak Runtime-jobs idempotent en reconcilieer status plus events periodiek; SSE mag versnellen
   maar polling blijft correctheidsfallback.
7. Valideer resultaatschema, artifactdeclaraties, MIME, grootte en SHA vóór domeinpublicatie.
8. Verwerk het gevalideerde AI-resultaat en het Runtime-beheerde repositoryresultaat als twee
   verschillende gegevensdelen.
9. Kopieer blijvende artifacts naar Software Factory-eigendom wanneer Runtime-retentie korter is
   dan story-/auditretentie.
10. Toon Runtime-attempts apart van een bewuste nieuwe Software Factory-taakpoging.
11. Modelleer quota vanuit Runtime als wacht-/beschikbaarheidsstatus zonder providerspecifieke
    parsers in het v2-domein.
12. Gebruik acceptatie uitsluitend met `mock/mock/MOCK` en een afzonderlijk test-control-token, met
    mocks die ook `repositoryResult`, `NO_CHANGES`, `BRANCH_CHANGED` en herstel na push simuleren.
13. Geef bij repositoryjobs alleen de geregistreerde alias, bestaande remote branch en gewenste
    publicatiemodus door; geen lokale map, Gitcredential, base branch of verwachte input-SHA.

### Exitcriteria

- Verloren job-create-response, uploadonderbreking en restart maken geen tweede Runtime-job.
- Een ongeldig JSON-resultaat of artifact wordt niet gepubliceerd als geslaagde stap.
- Geen providercredential of lokale Docker-socket is nodig in `software-factory-backend`.
- Geen targetrepositorycheckout, shared volume of projectlokale `secrets.env` is nodig.
- Runtime-jobstatus, events, usage en veilige fout zijn zichtbaar in Operatie.
- De gebruikte Runtime-contractversie is in de contractcontrole vastgelegd.

## Stap 6 — Refinement, vragen en planning

### Doel

Maak van een WorkRequest een complete immutable StorySpecification en een minimaal ExecutionPlan.

### Werk

1. Implementeer refineprocessessies die exacte request-, product-, comment-, vraag-, config- en
   promptversies bevriezen. Repositorycontext komt via een read-only Runtime-checkout.
2. Laat de refiner een strikt schema retourneren met specificatie, samenvattingen, criteria,
   aannames en optionele vragen.
3. Publiceer iedere geldige StorySpecification als onveranderlijke versie.
4. Implementeer Question met geadresseerde, bronstep, versie, meerdere vragen, answerhistorie en
   hervatting van exact dezelfde logische refine-/planstap.
5. Respecteer `questionsAllowed=false`: markeer clarification als fout volgens het bestaande
   contract en wacht niet stil op een mens.
6. Laat de planner alleen een getypeerde lijst stapdefinities maken.
7. Dwing de standaardketen deterministisch af, inclusief verplichte documentation, eventuele
   manual-approve, merge en deploy.
8. Bewaar planversie en stapvolgorde; een lopende run leest geen later gewijzigd plan.
9. Houd het aantal stappen minimaal en voorkom dubbele reviewer-/testerrollen zonder reden.
10. Publiceer dashboard- en Telegramvragen uitsluitend via Human Interaction en Notification.

### Exitcriteria

- Ruwe, al complete en onveilige/ambigue requests hebben ieder voorspelbaar gedrag.
- Vragen en meerdere antwoordrondes hervatten zonder dubbele AI-taak of nieuwe story.
- Een planneroutput kan verplichte systeemstappen niet verwijderen of omordenen.

## Stap 7 — Repositorywerk, reviewloop, tester en verificatiebewijs

### Doel

Lever een traceerbare PR op met versiegebonden review en onafhankelijk machinebewijs.

### Werk

1. Implementeer `RepositoryDelivery` en reserveer per story één unieke remote storybranch/PR-
   identiteit.
2. Maak de remote storybranch idempotent aan vanaf de actuele base branch via de Git-providergrens
   en zonder checkout in Software Factory.
3. Dien developerwerk in als `REPOSITORY_WORK` met `taskType=REPOSITORY_AGENT`, de geregistreerde
   alias, bestaande storybranch en publicatiemodus `COMMIT_AND_PUSH`. Stuur de bevroren
   specification, planstep, antwoorden en relevante docs mee, maar geen repository-URL, base branch,
   lokale map, Gitcredential of verwachte input-SHA.
4. Vertrouw voor clone, pull, Gitmetadatabescherming, commit, push en crashreconciliatie op het
   Runtimecontract. Dupliceer die techniek niet.
5. Start per story nooit meer dan één muterende repositoryjob tegelijk.
6. Verwerk na agentuitvoering zowel het gestructureerde AI-resultaat als het afzonderlijke
   repositoryresultaat. Behandel `BRANCH_CHANGED`, een ambigue publicatie en een ontbrekend
   repositoryresultaat als expliciete toestand, niet als succes.
7. Maak na de eerste succesvolle push idempotent precies één PR van de storybranch naar de base
   branch. Gebruik die PR voor GitHub-checks en de bestaande previewomgeving.
8. Bewaar branch, gepubliceerde commits, PR-URL, diffmetadata en Runtime-jobcorrelaties.
9. Laat de reviewer de actuele storybranch ophalen met `APPLICATION_WORK`,
   `taskType=REPOSITORY_AGENT` en publicatiemodus `NONE`. Registreer `checkoutCommitSha` als de
   beoordeelde branchstand, zodat een latere push het oordeel zichtbaar stale maakt.
10. Laat een afwijzing alle bevindingen in één ronde teruggeven en een nieuwe developerstep na pull
    op dezelfde remote branch starten.
11. Laat de tester de actuele storybranch en applicatie onderzoeken met `APPLICATION_WORK`,
    `taskType=REPOSITORY_AGENT` en publicatiemodus `NONE`, en bewijsartifacts teruggeven.
12. Implementeer de deterministische verificatie volgens het in stap 1 gekozen pad uit het
    verificatieholdpoint. Zolang dat besluit ontbreekt, blijft de mergegate zichtbaar geblokkeerd.
13. Valideer het bewijs onafhankelijk van agentproza en koppel het aan de bij uitvoering opgehaalde
    branchstand.
14. Laat rood, timeout, ontbrekende tooling of bewijs voor een verouderde branchstand nooit
    passeren; voer bij een latere push de benodigde controles opnieuw uit.
15. Dien documenter in als `REPOSITORY_WORK`, `REPOSITORY_AGENT` en `COMMIT_AND_PUSH` op dezelfde
    alias en storybranch. Verwerk `NO_CHANGES` alleen als succes wanneer het gestructureerde
    agentresultaat aantoonbaar geen documentatie-impact meldt.
16. Voer summarizer uit op de werkelijk gepubliceerde branchdiff en bewijsset.
17. Behoud begrensde developer/reviewer/testloopbacks en een zichtbare blocker na de cap.

### Exitcriteria

- Iedere wijziging is aan één story, remote branch, commit, PR en exacte specification gekoppeld.
- Iedere agent begint met een verse pull; vervolgwerk vereist geen gedeelde map.
- De AI-agent heeft geen branch, checkout, commit, push, PR of merge uitgevoerd.
- Reviewer- en testerbesluiten gelden niet meer nadat de remote branch opnieuw is gewijzigd.
- Alleen groen deterministisch bewijs kan de mergevoorwaarde vervullen.
- Restart tussen branch-aanmaak, repositoryjob, push, PR-publicatie en lokale correlatie maakt geen
  tweede branch of PR.
- De volledige keten werkt op een tijdelijke echte Gitrepository met Runtime-worker, zonder lokale
  checkout in Software Factory.

## Stap 8 — Approvalmodi, hotfix, merge en deployment

### Doel

Rond werk mensgestuurd of automatisch af met dezelfde bestaande storyopties en zonder dubbele
merge/deploy-effecten.

### Werk

1. Implementeer `ApprovalRequest` en `ManualAction` als duurzame, versiegebonden entiteiten.
2. Behoud exact de modes `automatisch`, `alleen-manual-poort` en `elke-stap`.
3. Laat approve/reject altijd de verwachte step/storyversie controleren en de actor vastleggen.
4. Implementeer hotfix als expliciete `WorkMode.HOTFIX`, nooit afgeleid van storytype `BUGFIX` of
   urgentie.
5. Materialiseer voor hotfix exact developer, merge en deploy; gebruik dezelfde developerprompt en
   deterministische verificatie als standaardwerk.
6. Voeg geen reviewer, tester, documenter, manual approval of extra diffguard aan hotfix toe.
7. Centraliseer de projectbewuste mergegate en laat uitsluitend Repository Delivery mergen.
8. Behandel CI `PENDING` als wachtstatus, rood/ontbrekend bewijs als blocker en controleer de
   actuele remote PR-branch. Is die sinds review of verificatie gewijzigd, dan worden de vereiste
   controles opnieuw uitgevoerd.
9. Maak merge idempotent op storydelivery en bewaar de werkelijke mergecommit.
10. Implementeer DeploymentRun per target, start/reconcile/retry en terminale uitkomst.
11. Rapporteer story `DONE` pas wanneer het vastgelegde oplevercontract is voltooid; publiceer de
    volledige delivered commit-SHA.
12. Bewaar handmatige merge/deploycommands als dezelfde use cases en geen bypass.

### Exitcriteria

- Alle drie approvalmodi voldoen aan de Testbedscenario's.
- Een hotfix heeft exact de korte keten en geen extra controle.
- Alleen één centrale groene gate kan mergen, automatisch én handmatig.
- Merge en ieder deploymenttarget zijn crash- en retry-idempotent.
- Product Factory ziet `DONE` uitsluitend met een volledige commit-SHA.

## Stap 9 — Audits, kennis, notificaties, Telegram en dashboard

### Doel

Bouw de ondersteunende capabilities en de gebruikersinterface op v2.

### Werk

1. Implementeer read-only AuditRun met projectplanning, oudste-eerstkeuze, vragen, rapport, score,
   kennis en hooguit één gecontroleerd storyvoorstel.
2. Gebruik Agent Runtime voor audits; er is geen directe Dockerdispatch meer.
3. Implementeer versieerbaar KnowledgeItem per project en rol; houd vragen en productwaarheid
   erbuiten.
4. Publiceer getypeerde domeinevents voor de acht bestaande notification events.
5. Implementeer idempotente NotificationDelivery per event, story, ontvanger en kanaal.
6. Migreer Telegrammeldingen en reply-antwoorden naar publieke questions/approvals/commands.
7. Behoud de Telegram-assistent, maar geef hem geen directe mutatiepoort vanuit AI-output; de
   backend valideert en voert uit.
8. Bouw de dashboard-API's op de v2-capabilities binnen `software-factory-backend`.
9. Toon story, specification, plan, steps, vragen, approvals, Runtime-jobs, PR, bewijs, merge,
   deploy, notifications en veilige fouten.
10. Behoud builds, downloads, settings, audit en agents/operations; haal usage/kosten uit Agent
    Runtime in plaats van lokaal prijzen te dupliceren.
11. Maak de frontendroutes responsive, bookmarkbaar en getest, en bereid de nieuwe primaire host
    voor.

### Exitcriteria

- Een gebruiker kan alle stories en mensacties volgen zonder interne velden te hoeven begrijpen.
- Iedere mensactie roept één geautoriseerd publiek command aan.
- Telegramherhaling of restart maakt geen dubbele melding of beslissing.
- Audit kan geen repository wijzigen en maakt maximaal één idempotent voorstel.
- Dashboardcontrollers bevatten geen workflowbeslissingen.

## Stap 10 — Datamigratie, oplevering en weer live

### Doel

Zet de oude data over, maak de pipeline weer groen en breng v2 in productie.

### Werk

1. Migreer de bestaande v1-data naar de v2-entiteiten volgens het ontwerp uit stap 1: minimaal de
   afgeronde stories met hun publieke key, titel, status, oplevercommit en samenvatting. Blijkt de
   migratie onevenredig duur, dan wordt de oude data na expliciet akkoord van Robbert weggegooid.
2. Bewijs de migratie op een kopie van de productiedatabase voordat zij op productie draait.
3. Drop de oude v1-tabellen pas na een geslaagde migratie en een gecontroleerde back-up.
4. Haal `[skip ci]` van de werkwijze af en maak `verify.yml` groen op `main`.
5. Werk de image-workflows, de bump-automatisering en de OpenShift-manifests bij naar
   `software-factory-backend` en `software-factory-frontend` en bouw de images.
6. Publiceer `softwarefactory.vdzonsoftware.nl` als primaire route en behoud
   `dashboard.vdzonsoftware.nl` gedurende een afgesproken overgangsperiode als redirect of alias,
   met behoud van pad en querystring. DNS, route en SSO-redirect vallen buiten deze repository en
   worden door Robbert geregeld of expliciet gedelegeerd.
7. Herstel de Product Factory-integratie: `/api/integrations/v2/status`, create, get/list en cancel
   werken weer volgens het bestaande contract, met alle bestaande requestvelden, validatie,
   attachments, packagehash en idempotentie.
8. Voeg backward-compatible een expliciete optionele uitvoermodus toe voor machine-aanmaak,
   bijvoorbeeld `executionMode=STANDARD|HOTFIX`, met default `STANDARD`. `type=BUGFIX` betekent
   nooit automatisch `HOTFIX`. Autoriseer met het bestaande gescopete integratietoken, niet met een
   dashboardgebruikerssessie.
9. Publiceer het bijgewerkte contract en contracttests; een eventuele Product Factory-
   consumerwijziging is een afzonderlijk vervolg.
10. Draai de volledige Testbedcatalogus, failure-injectiontests, securitytests en een productiesmoke.
11. Voer één echte story end-to-end uit op een testrepository met de echte Runtime-worker: refine,
    plan, developer, review, tester, verificatie, documenter, approval, merge en deploy.
12. Werk README, functionele/technische specs, runbook, installatie, API-docs en UX-documentatie bij
    naar uitsluitend de actuele v2-werking, en archiveer wat v1 beschrijft.
13. Genereer de definitieve dependencygraph en bewijs dat iedere capability alleen publieke API's
    gebruikt.

### Exitcriteria

- De volledige pipeline is groen op `main` en dezelfde immutable artifacts staan gezond op
  OpenShift.
- De oude data is aantoonbaar gemigreerd of na expliciet akkoord verwijderd; de v1-tabellen zijn
  weg.
- `software-factory-frontend` is bereikbaar op `softwarefactory.vdzonsoftware.nl`; bestaande
  bookmarks blijven gedurende de afgesproken overgang werken.
- Product Factory werkt weer volgens het bestaande contract.
- Eén echte story is volledig door de v2-keten heen gekomen.
- Alle actuele documentatie beschrijft uitsluitend v2.
- Iedere regel uit de functionele-dekkingstabel is afgevinkt of bewust vervallen verklaard.

## Verplichte Testbed- en ketenscenario's

Stap 1 specificeert ze volledig; uiterlijk stap 10 zijn minimaal deze scenario's geautomatiseerd:

1. complete standaardstory zonder menselijke interventie;
2. refiner stelt meerdere vragen en hervat exact dezelfde sessie;
3. planner stelt een vraag en materialiseert daarna de correcte vaste keten;
4. developer wordt door reviewer afgewezen, pullt opnieuw en herstelt op dezelfde remote branch en
   PR;
5. tester of deterministische verificatie wijst af en start begrensde herstelroute;
6. documenter wijzigt alleen documentatie en behoudt dezelfde storydelivery;
7. approvalmode automatisch;
8. approvalmode alleen manual-poort;
9. approvalmode elke stap, inclusief reject;
10. hotfix met exact developer, merge en deploy;
11. hotfix met rode verificatie start geen merge;
12. `questionsAllowed` uit resulteert in zichtbare clarificationfout;
13. Product Factory-create is idempotent met attachments en verloren response;
14. Product Factory machine-hotfix is expliciet en `BUGFIX` blijft standaard;
15. quota/worker niet beschikbaar wordt wachtstatus en hervat later;
16. Runtime-jobtimeout en late gefencete completion worden veilig verwerkt;
17. crash vóór en na Runtime-create, resultaatpublicatie en artifactkopie;
18. crash vóór en na remote branch-aanmaak, Runtime-push, PR-publicatie, merge en ieder
    deploymenttarget;
19. CI pending wacht, rood blokkeert en groen bewijs voor een inmiddels gewijzigde branch telt niet;
20. pause/resume/kill/retry/re-implement/clear-error op geldige en ongeldige momenten;
21. storyannulering vóór uitvoering, tijdens AI-wacht en na PR maar vóór merge;
22. multi-targetdeployment met gedeeltelijke tijdelijke fout;
23. audit stelt een vraag, hervat en maakt maximaal één storyvoorstel;
24. notification eventsets, Telegramdelivery en reply-idempotentie;
25. twee projecten parallel zonder cross-projectstory, credential of artifactlekkage;
26. restart met wachtende workflow, verlopen claim, outboxeffect en onbekende externe toestand;
27. developer, reviewer, tester en documenter draaien in afzonderlijke verse Runtime-jobs en nemen
    wijzigingen uitsluitend via pull van dezelfde remote storybranch over;
28. de volledige storyketen gebruikt geen Software Factory-checkout, shared folder, netwerkvolume
    of lokaal Software Factory-proces;
29. een lokaal gitignored `secrets.env` van het targetproject wordt niet gelezen, gekopieerd,
    gemount, gewijzigd of naar Runtime gestuurd;
30. de rename behoudt API-paden en deeplinks; de nieuwe host werkt en de oude host volgt het
    afgesproken redirect-/aliasgedrag;
31. precies één storybranch en één PR bestaan ongeacht het aantal developer-, reviewer-, tester- en
    documenterjobs; geen AI-agent kan branch, HEAD, Gitmetadata, commit, push of PR muteren.

## Verplichte bewijzen per implementatiestap

Iedere stap is pas klaar wanneer voor haar scope:

1. de geraakte Mavenmodules lokaal bouwen en hun unit- en integratietests groen zijn;
2. `flutter analyze` en `flutter test` groen zijn bij frontendimpact;
3. nieuwe migraties vanaf leeg slagen;
4. module- en dependencychecks geen ongeautoriseerde grens openen;
5. idempotentie, expected-versionconflict, restart en retry aantoonbaar zijn getest;
6. externe adapters dezelfde contracttests tegen fake en echte/stubgrens doorlopen;
7. `./quality/run.sh` niet verslechtert en geen nieuwe suppressie toevoegt;
8. operationele secrets niet voorkomen in Git, logs, events, prompts, transcripten, artifacts of
   testfixtures, en projectlokale `secrets.env`-bestanden buiten Factory en Runtime blijven;
9. actuele documentatie alleen werkelijk geïmplementeerd gedrag beschrijft;
10. `VOORTGANG.md` de exacte commit, tests, bewijs, beslissingen en volgende startgate bevat.

De volledige reactor, de CI-pipeline, de images en de deployment worden pas in stap 10 als
verplicht bewijs gebruikt.

## Uitvoeringsorganisatie

- Voer stap 0 en 1 sequentieel uit; stap 2 start pas na ontwerpgoedkeuring door Robbert.
- Werk daarna per stap, in kleine commits rechtstreeks op `main`, met `[skip ci]` tot stap 10.
- Domein-, schema- en state-machinewerk blijft sequentieel. Alleen expliciet niet-overlappende
  adapters, frontend en fixtures mogen parallel.
- Iedere stap levert code, migratie, tests en documentatie samen op.
- Geen opportunistische refactor buiten de stap om.
- `VOORTGANG.md` is de enige live voortgangsbron en wordt bij iedere stap bijgewerkt.

## Belangrijkste risico's

| Risico | Beheersing |
|---|---|
| Runtime kan niet op een bestaande storybranch voortbouwen | Het Agent Runtime-prerequisitedocument is een externe gate; niet starten voordat Robbert bevestigt dat het klaar is. Geen lokale Factory-checkout, netwerkshare of keten van tussen-PR's als workaround. |
| Er is geen uitvoerder voor de deterministische verificatie | Expliciet holdpoint met drie uitgewerkte opties; de mergegate blijft geblokkeerd zolang er geen besluit is. |
| Sloop eerst, bouw later laat de repo lang stuk | Bewuste keuze omdat de software niet in gebruik is; stap 10 maakt alles aantoonbaar weer werkend, inclusief één echte end-to-end story. |
| Oude data gaat verloren | Migratie wordt in stap 1 ontworpen, in stap 10 op een kopie bewezen en pas na back-up gevolgd door het droppen van de v1-tabellen. |
| Nieuwe modules worden alleen nieuwe dozen rond dezelfde godservices | API-first contracten, één eigenaar, architectuurtests en geen interne imports. |
| Operationele secrets verschuiven naar prompts of resultaten | Runtime keygrants, worker-only waarden en resultaat-/artifactcontrole; het lokale project-`secrets.env` blijft geheel buiten de keten. |
| Opeenvolgende agents verwachten een gedeelde workspace | Remote storybranch is het enige overdrachtspunt; iedere job begint met een verse pull. |
| Twee muterende agents pushen tegelijk | Per story maximaal één muterende repositoryjob; overige stappen wachten of zijn read-only. |
| AI-agent voert zelf muterende Git-acties uit | Gitcredentials blijven buiten de agentcontainer; de worker legt branch en begin-HEAD vast en publiceert als enige. |
| Dubbele PR, merge of deploy na crash | Duurzame outbox, externe lookup en idempotentiesleutel per effect. |
| Rename breekt bookmarks, API-clients of loginredirects | API-paden blijven stabiel; de nieuwe host wordt vooraf getest en de oude host blijft tijdelijk redirect/alias, inclusief aangepaste SSO-redirectconfiguratie. |
| Controllers worden een tweede domeinlaag | Dashboard-, integratie- en Telegramadapters roepen alleen capability-API's aan. |
| De pipeline blijkt aan het eind ver weggezakt | Stap 10 begint met de pipeline groen maken en pas daarna de rest van de oplevering. |

## Buiten scope

- Product Factory v2 opnieuw ontwerpen of implementeren.
- Agent Runtime intern wijzigen vanuit deze repository.
- Targetapplicaties aanpassen — tenzij optie B uit het verificatieholdpoint wordt gekozen; dan
  wordt die grens expliciet verruimd.
- Het bestaande beheer, versleutelen, synchroniseren of automatisch aanpassen van projectlokale
  `secrets.env`-bestanden veranderen.
- Een gedeelde netwerkmap of andere blijvende repositoryworkspace tussen Software Factory en Agent
  Runtime introduceren.
- Een microservicesplitsing per capability.
- Projectconfiguratie verplaatsen naar Postgres met bijbehorende beheer-UI.
- Een nieuw extern workflow- of issuetrackersysteem.
- Een extra diffguard in hotfix.
- Agent Runtime productbeslissingen laten nemen over approvals, merge of deploy.

## Definitie van klaar voor Software Factory v2

Software Factory v2 is klaar wanneer alle stories door de v2-capabilities worden verwerkt; Agent
Runtime `/v2` de enige technische agentuitvoeringsgrens is; repositorywerk uitsluitend in tijdelijke
checkouts van de Agent Runtime-worker plaatsvindt en via de remote storybranch wordt overgedragen;
alle capabilities in `software-factory-backend` op OpenShift draaien; de lokale orchestrator,
agentworker en WebSocketbridge verwijderd zijn; `software-factory-frontend` op
`softwarefactory.vdzonsoftware.nl` beschikbaar is; Software Factory zelf geen targetcode bewaart en
niets op een ontwikkellaptop hoeft te draaien, los van de afzonderlijke Agent Runtime-worker;
iedere story, stap, vraag, approval, Runtime-job, PR, verificatie, merge, deployment en notificatie
duurzaam en herleidbaar is; Product Factory en dashboard hun bestaande contracten weer nakomen; het
huidige projectlokale secretsmechanisme ongewijzigd is; normale en hotfixketens functioneel
gelijkwaardig zijn aan v1; crash-, retry-, quota- en securityscenario's groen zijn; de oude data is
gemigreerd of bewust verwijderd; en de volledige pipeline groen op `main` staat.
