# Software Factory v2 — migratie- en implementatieplan

Status: toekomstontwerp, nog niet geïmplementeerd

Peildatum: 2026-09-10

Doelrepository: `softwarefactory`

## Doel van dit document

Dit document beschrijft hoe de huidige Software Factory gecontroleerd naar een v2-architectuur kan
worden gebracht. Met **v2** wordt hier de nieuwe interne Software Factory bedoeld, niet alleen het
reeds bestaande externe endpoint `/api/integrations/v2` en ook niet het oude dashboard-UX-document
met “v2” in de naam.

Product Factory is inmiddels volgens het nieuwe capabilitymodel opgebouwd en gebruikt de gedeelde
Agent Runtime v2. Software Factory heeft al veel goed werkende functionaliteit en sterke
regressietests, maar combineert nog een eigen tracker, workflowmotor, Docker-agent-runtime,
agentworker, repositorypublicatie, dashboardprojecties en transportadapters in één organisch
gegroeide applicatie. Software Factory v2 maakt die verantwoordelijkheden expliciet, besteedt
duurzame agentuitvoering uit aan Agent Runtime en migreert zonder lopend productiewerk dubbel uit te
voeren of kwijt te raken.

Het plan is een strangler-migratie. De bestaande Software Factory blijft bruikbaar terwijl v2 per
capability wordt opgebouwd. Bestaande externe contracten blijven tijdens de migratie werken. Open
v1-stories worden niet midden in hun keten geconverteerd; zij mogen op v1 afronden. Nieuwe stories
kunnen pas naar v2 worden gestuurd nadat de betreffende projectroute aantoonbaar gereed is.

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
- behoudt het lokale control-plane en de uitgaande bridge naar het remote dashboard;
- heeft één begrijpelijke responsive frontend voor stories, mensacties, projecten, agents,
  audits, builds, deployments en operatie;
- kan per project gecontroleerd van v1 naar v2 en terug schakelen zolang de expand/contractfase
  loopt;
- verwijdert de oude tracker-, agentworker- en Docker-runtimecode pas nadat productiepariteit en
  herstel aantoonbaar zijn.

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
- de eigen Postgres-“tracker” bewaart stories en subtaken in één `issues`-model met stringvelden;
- het remote dashboard-backend praat via een uitgaande WebSocketbridge met de lokale factory;
- Product Factory gebruikt het bestaande `/api/integrations/v2`-contract;
- Telegram biedt meldingen, vragen, commands en een assistent;
- read-only audits kunnen vervolgstories voorstellen;
- Spring Modulith, moduledependencychecks, Detekt-ratchet en een brede Maven/e2e-suite zijn al
  aanwezig.

De onderhoudbaarheid is eerder aanzienlijk verbeterd. V2 herhaalt dat traject niet. Het doel is
het vervangen van de resterende fundamentele verantwoordelijkheidsvermenging en eigen
agent-runtime, met behoud van bewezen gedrag.

## Principes en harde randvoorwaarden

### Functionele continuïteit

- Geen bestaand product verliest de mogelijkheid om stories af te ronden.
- Geen open v1-story wordt automatisch naar een gedeeltelijk overeenkomstige v2-status vertaald.
- Eén story wordt altijd door precies één engineversie bestuurd.
- V1 en v2 mogen nooit dezelfde agentstap, merge of deploy voor dezelfde story uitvoeren.
- Publieke storykeys en bestaande links blijven stabiel.
- De bestaande Product Factory-v2-routes blijven backward-compatible.
- Telegram en dashboard blijven gedurende de migratie de actuele samengestelde toestand tonen.

### Eigenaarschap

- Iedere duurzame entiteit heeft één schrijvende capability.
- Modules wijzigen elkaars tabellen of interne JPA/JDBC-modellen niet.
- Cross-modulemutaties lopen via betekenisvolle publieke commands.
- Queries leveren immutable DTO's en zijn geen tweede schrijvend model.
- Transportadapters bevatten geen workflow- of productbeslissingen.
- De frontend bevat geen productwaarheid.

### AI-grens

- Software Factory kiest rol, taaktype, prompt, context, repository, model en productbeleid.
- Agent Runtime voert een technische job uit en kent geen story-, approval-, merge- of
  deploysemantiek.
- Iedere logische AI-taak heeft één duurzame Software Factory-correlatie en één Runtime-job.
- Een verloren response of restart maakt geen tweede Runtime-job.
- Software Factory vertrouwt nooit op alleen AI-proza als testbewijs.
- Providercredentials, Dockeruitvoering, workerleases en Runtime-retries verdwijnen uit Software
  Factory zodra de migratie is afgerond.

### Migratieveiligheid

- Expand vóór contract: eerst additieve schema's, adapters en projecties; pas na productiepariteit
  oude code verwijderen.
- Iedere projectomschakeling heeft een expliciete preflight, auditrecord en rollbackpad.
- Rollback verwijdert geen v2-data en draait geen reeds uitgevoerde externe effecten terug.
- Een onbekende of conflicterende externe toestand blokkeert; zij wordt niet geraden.
- Acceptatie gebruikt Agent Runtime `MOCK`; productie weigert mocks.
- Secrets worden nooit naar de database, frontend, prompts, transcripten of logs gekopieerd.

### Scope

Dit plan wijzigt uitsluitend de repository `softwarefactory`, behalve wanneer een afzonderlijk
later vervolg expliciet een contractconsumer aanpast. Agent Runtime en Product Factory worden als
bestaande externe systemen gebruikt en niet vanuit dit plan gewijzigd.

## Functionele pariteit die v2 verplicht behoudt

De migratie is pas compleet wanneer minimaal deze bestaande gedragingen aantoonbaar gelijk of
beter werken:

| Gebied | Verplicht v2-gedrag |
|---|---|
| Story-inname | Dashboard, Product Factory v2, interne audit en geautoriseerde API kunnen werk idempotent aanmaken. |
| Wachtrij | Per project is verklaarbaar welk werk als volgende start; bestaande `start` en `start-next`-betekenis blijft beschikbaar aan de grens. |
| Refine | Een ruwe aanvraag wordt een volledige, geversioneerde storyspecificatie of stelt gerichte vragen. |
| Plan | De planner maakt een minimale, geordende keten van uitvoerstappen. |
| Development | Een agent wijzigt de juiste repository op een geïsoleerde branch en publiceert één traceerbare PR. |
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
| Telegram | Geselecteerde meldingen en antwoorden blijven werken; assistentgedrag wordt apart gemigreerd of bewust uitgefaseerd. |
| Integratie | Product Factory leest alleen `OPEN`, `DONE`, `CANCELLED` en bij `DONE` een volledige commit-SHA. |

## Voorgesteld v2-domein

De definitieve namen worden in stap 1 vastgesteld. Dit model bepaalt wel de gewenste scheiding.

| Entiteit | Eigenaar | Betekenis |
|---|---|---|
| `Project` | Projectconfiguratie | Beheerde repository, default branch, verificatie, deployments, Runtime-alias en enginebeleid. |
| `WorkRequest` | Work intake | Oorspronkelijke onveranderlijke aanvraag, bron, opties en externe idempotentie. |
| `Story` | Storymanagement | Publieke identiteit, actuele status en relatie naar aanvraag en gekozen engine. |
| `StorySpecification` | Refinement | Onveranderlijke geraffineerde storyversie met criteria en gebruikte antwoorden. |
| `ExecutionPlan` | Planning | Bevroren planversie met een geordende set stapdefinities. |
| `WorkflowRun` | Orchestration | Eén duurzame uitvoering van een storyversie met status, trigger en correlaties. |
| `WorkStep` | Orchestration | Eén stapdefinitie zoals refine, plan, development, review, test, summary, documentation, approval, merge of deploy. |
| `StepRun` | Orchestration | Een hervatbare poging van een concrete stap, inclusief gebruikte inputversies. |
| `AiTask` | AI-uitvoering | Lokale correlatie, outbox en projectie van precies één Agent Runtime-job. |
| `Question` | Mensinteractie | Tijdelijke vraag van een stap aan een geadresseerde actor met antwoordhistorie. |
| `ApprovalRequest` | Mensinteractie | Versiegebonden goedkeuringspoort met beslissing, actor en reden. |
| `ManualAction` | Mensinteractie | Niet-AI-handeling die aantoonbaar door een mens moet worden uitgevoerd. |
| `RepositoryDelivery` | Repository delivery | Branch, commits, PR, diff, verificatiebewijs en mergebeslissing. |
| `DeploymentRun` | Deployment | Eén storycommit naar één of meer geconfigureerde targets met feitelijke status. |
| `AuditRun` | Audit | Read-only onderzoek met rapport, score, vragen en optioneel voorstel. |
| `KnowledgeItem` | Agentkennis | Versieerbare, rol- en projectgebonden tip; geen productwaarheid. |
| `NotificationDelivery` | Notificatie | Idempotente bezorging van één domeingebeurtenis aan één kanaal. |

De trackerbegrippen mogen tijdens compatibiliteit aan adapters blijven bestaan. Nieuwe
capabilitycode gebruikt geen vrij `Map<TrackerField, String>`-model en beslist niet op losse
fase-stringwaarden uit een generiek issue.

## Voorgestelde capability- en moduleverdeling

Stap 1 mag namen aanscherpen, maar moet dezelfde verantwoordelijkheden behouden.

| Mavenmodule/capability | Verantwoordelijkheid |
|---|---|
| `software-factory-api` | Publieke commands, queries, ID/value types en immutable DTO's tussen capabilities. |
| `software-factory-app` | Lokale composition root, geautoriseerde HTTP, schedules, configuratievalidatie en implementatiemanifest. |
| `project-impl` | Projectcatalogus, repository- en deploymentconfiguratie, engineversie en Runtime-grants. |
| `work-intake-impl` | WorkRequest/Story-aanmaak, bron-idempotentie, publieke keys en v1/v2-routering. |
| `refinement-impl` | StorySpecification, refineprocessessie en gerichte vragen. |
| `planning-impl` | ExecutionPlan en minimale stapmaterialisatie. |
| `workflow-impl` | WorkflowRun/WorkStep/StepRun, claims, state machines, outbox en hervatting. |
| `ai-execution-impl` | Agent Runtime `/v2`-consumer, uploads, jobs, events, resultaat, artifacts, usage en annulering. |
| `human-interaction-impl` | Questions, approvals, manual actions en persoonlijke actielijst. |
| `repository-delivery-impl` | Repositorywerk, PR-projectie, verificatiebeleid, reviewloop en mergegate. |
| `deployment-impl` | Deploycommando's, targetreconciliatie, completion en oplevercommit. |
| `audit-impl` | Auditplanning, rapporten, vragen, kennis en gecontroleerde storyvoorstellen. |
| `notification-impl` | Domeinevents naar Telegram en eventuele latere kanalen. |
| `dashboard-backend` | Remote auth- en bridgegateway; geen Software Factory-domeinbeslissingen. |
| `dashboard-frontend` | Product- en operatieprojecties; geen schrijvende waarheid. |

Niet iedere capability hoeft op dag één een aparte deployable te zijn. Zij worden in de eerste
versie modules binnen dezelfde lokale applicatie. De grens is belangrijker dan het aantal
processen.

## Topologie van v2

```text
Product Factory / Dashboard / Telegram
                 |
                 v
      remote dashboard-backend
                 |
       uitgaande WebSocketbridge
                 |
                 v
       lokale Software Factory v2
       - domein en orchestration
       - vragen/approvals
       - merge/deploybeleid
                 |
          HTTPS Agent Runtime /v2
                 |
                 v
       geschikte Runtime-worker
       - container en AI-provider
       - repositorycheckout/publicatie
       - attempts/leases/artifacts
```

De remote dashboard-backend blijft nodig omdat de lokale factory niet publiek bereikbaar hoeft te
zijn. Agent Runtime vervangt niet de Software Factory; hij vervangt de technische agentuitvoering.

## AI-taaktypen en Runtime-mapping

Software Factory registreert per agentrol een expliciete jobconfiguratie met
`vendorId`, `model`, `mode`, Runtime `taskType`, timeout en prompttemplateversie. Er is geen
provider- of modelfallback.

| Software Factory-rol | Runtime-job | Resultaat |
|---|---|---|
| Refiner | `APPLICATION_WORK` / structured generation | Storyspecificatie of vragen. |
| Planner | `APPLICATION_WORK` / structured generation | Geordende stapdefinities of vragen. |
| Developer | `REPOSITORY_WORK` | Branch, commit, PR, diffmetadata, gestructureerde status en verificatieartifact. |
| Reviewer | `APPLICATION_WORK` op exact PR-head/repositorysnapshot | Versiegebonden oordeel en volledige bevindingen. |
| Tester | `APPLICATION_WORK` met repositorysnapshot, browser en testcredentials | Testoordeel en bewijsartifacts; deterministische verificatie blijft apart verplicht. |
| Summarizer | `APPLICATION_WORK` / structured generation | Lange en korte samenvatting. |
| Documenter | `REPOSITORY_WORK` op dezelfde storybranch | Documentatiewijziging of aantoonbaar geen impact. |
| Auditor | `APPLICATION_WORK` met read-only repositorysnapshot | Rapport, score, vraag of storyvoorstel. |
| Telegram-assistent | `APPLICATION_WORK` zonder mutatiebevoegdheid | Antwoord of voorgesteld command; backend valideert en voert uit. |

De exacte mogelijkheden van `REPOSITORY_WORK` worden in stap 0 bewezen. Als Agent Runtime nog niet
veilig op een reeds bestaande storybranch kan voortbouwen of deterministische verificatie niet als
betrouwbaar artifact kan leveren, wordt dat als externe capabilitygap vastgelegd. Software Factory
bouwt dan niet stilzwijgend opnieuw een tweede algemene Runtime; de betreffende v2-stap blijft
geblokkeerd of gebruikt tijdelijk een expliciete v1-adapter.

## Migratiemodel

### Engineversie per story

Iedere nieuwe story krijgt bij aanmaak onveranderlijk `engineVersion=V1|V2`. Tijdens de migratie
kiest de projectconfiguratie de default voor nieuwe stories. De gekozen waarde kan niet worden
gewijzigd nadat uitvoering is gestart.

- Een v1-story wordt uitsluitend door de bestaande tracker/pipeline verwerkt.
- Een v2-story wordt uitsluitend door de nieuwe capabilityketen verwerkt.
- Dashboard en externe statusqueries projecteren beide smaken naar dezelfde publieke status.
- Handmatige commands worden op engineversie naar precies één commandhandler gerouteerd.
- Een projectomschakeling beïnvloedt alleen later aangemaakte stories.

### Geen actieve-statemigratie

Open of wachtende v1-stories blijven v1. Zij mogen afronden, geannuleerd worden of bewust opnieuw
als v2-request worden ingediend. Automatische conversie van bijvoorbeeld `reviewing` naar een
nieuwe `StepRun` is verboden: de exacte gebruikte context, attempthistorie en externe effecten zijn
niet betrouwbaar reconstrueerbaar.

### Historie

V1-historie blijft tijdens expand read-only beschikbaar via een legacyprojectie. Nieuwe v2-data
komt in capability-eigen tabellen. Na volledige cutover kan een aparte archiveringsstap afgeronde
v1-historie naar een read-only archiefprojectie kopiëren. Oude migraties en data worden niet in de
cutoverrelease verwijderd.

### Rollback

Rollback zet de projectdefault voor nieuwe stories terug naar v1 en stopt nieuwe v2-claims. Reeds
gestarte v2-stories blijven bewaard en worden gepauzeerd of gecontroleerd afgerond; zij worden niet
door v1 overgenomen. Na herstel kan dezelfde v2-sessie vanuit haar duurzame toestand verder.

## Overzicht van de stappen

| Stap | Naam | Belangrijkste resultaat |
|---:|---|---|
| 0 | Baseline en externe spikes | Actueel bewijs van gedrag, Agent Runtime-gaps en migratievoorwaarden. |
| 1 | Normatieve v2-specificatie | Besloten domein, module-API's, state machines, contracten en scenario's vóór code. |
| 2 | Technische fundering | Nieuwe modulebasis, schema, manifest, tests en side-by-side composition zonder productgedrag. |
| 3 | Projecten en work intake | V2 Project, WorkRequest en Story met stabiele keys en backward-compatible inname. |
| 4 | Duurzame workflowmotor | WorkflowRun, StepRun, claims, outbox, scheduling, pauze en herstel. |
| 5 | Agent Runtime v2 | Eén duurzame AI-grens voor alle rollen, zonder provider- of Dockerlogica in nieuwe code. |
| 6 | Refine en plan | Geversioneerde specificatie, vragen en minimale uitvoerplannen. |
| 7 | Repositorywerk, review en verificatie | Developer/documenter, PR, reviewerloop, tester en machinebewijs. |
| 8 | Mensinteractie, hotfix, merge en deploy | Approvalmodi, manual actions, korte keten en veilige oplevering. |
| 9 | Audits, kennis, notificaties en dashboard | Alle ondersteunende gebruikers- en beheerfuncties op v2-projecties. |
| 10 | Productiemigratie en contract | Projectgewijze canary, Product Factory machine-hotfix, rollback en v1-uitfasering. |
| 11 | Cleanup en eindbewijs | Oude runtime/trackercode verwijderen nadat alle gates langdurig groen zijn. |

## Stap 0 — Baseline, karakterisering en externe capabilityspikes

### Doel

Leg de actuele waarheid vast en bewijs alle externe aannames voordat een nieuwe architectuur erop
wordt gebouwd.

### Werk

1. Draai de actuele volledige Maven-, Flutter-, Docker-, module-, documentatie- en qualitygates op
   een schone hoofdbranch en leg commit plus tellingen vast.
2. Maak een machineleesbare inventaris van alle story- en subtaskfasen, transities, commands,
   notification events, schedulerjobs, endpoints, databasevelden en externe effecten.
3. Maak characterizationtests voor de normale keten, hotfix, vragen, drie approvalmodi,
   review/testloopback, quota, merge, multi-targetdeploy, annulering en Product Factory-status.
4. Inventariseer alle secrets en environmentvariabelen; classificeer eigenaar, consument en
   gewenste v2-bestemming zonder waarden te tonen.
5. Dien gecontroleerde Agent Runtime v2-probejobs in voor alle beoogde taaktypen.
6. Bewijs `REPOSITORY_WORK` voor een tijdelijke testrepository: branch, commit, PR, vervolgwerk op
   dezelfde branch, artifacts, cancel, timeout en verloren response.
7. Bewijs of deterministische `.factory/verification.yaml`-commando's door Runtime betrouwbaar
   kunnen worden uitgevoerd en revisiongebonden kunnen worden teruggeleverd.
8. Bewijs browsergebruik en projectcredentials zonder secretlekkage.
9. Meet huidige doorlooptijd, wachttijden, retries en handmatige acties als vergelijkingsbaseline.
10. Leg iedere ontbrekende Runtime-capability vast als blokkade of expliciet tijdelijk v1-pad.

### Opleveringen

- `docs/software-factory-v2/baseline.md` met exacte commit en bewijs;
- `docs/software-factory-v2/functionele-pariteitsmatrix.md`;
- `docs/software-factory-v2/agent-runtime-spike.md`;
- vaste characterizationtests en fixtures;
- lijst van harde designholdpoints voor stap 1.

### Exitcriteria

- De huidige hoofdbranch is groen of ieder rood punt is eerst als afzonderlijke reparatie opgelost.
- Geen kritieke bestaande overgang of externe side effect ontbreekt in de pariteitsmatrix.
- Bekend is welke Runtime-capabilities direct bruikbaar zijn en welke de migratie blokkeren.
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
5. Definieer idempotentiesleutels voor inname, Runtime-jobs, answers, approvals, PR-publicatie,
   merge, deploy en notifications.
6. Definieer crashpunten en herstelgedrag voor iedere externe mutatie.
7. Definieer de projectconfiguratie en vervanging van verspreide env-/YAML-toegang.
8. Definieer de lokale/remote bridgeprojecties en compatibiliteit met bestaande clients.
9. Definieer expand/contracttabellen, engineversieroutering en projectcutover.
10. Schrijf ADR's voor minimaal Agent Runtime-eigenaarschap, geen actieve-statemigratie,
    capabilitygrenzen, bridge-topologie, verification evidence en v1-verwijdering.
11. Maak een vaste Testbedcatalogus met beginsituatie, acties en verwachte publieke uitkomst.

### Beslissingen die expliciet genomen moeten worden

- Is `REPOSITORY_WORK` voldoende voor meerdere opeenvolgende rollen op dezelfde storybranch?
- Waar draait deterministische verificatie: Runtime-worker of een afzonderlijke Software
  Factory-verificatiejob?
- Blijft de Telegram-assistent onderdeel van Software Factory v2 of alleen notificatie-/antwoordkanaal?
- Wordt audit een volwaardige v2-capability of na de kernmigratie opnieuw ontworpen?
- Blijft `projects.yaml` invoerbron of wordt projectconfiguratie duurzaam in Postgres beheerd?
- Hoe lang blijft v1-history live querybaar na volledige cutover?

### Exitcriteria

- Iedere publieke entiteit en mutatie heeft precies één eigenaar.
- Iedere state machine heeft complete toegestane en verboden overgangen.
- Alle bestaande functionele pariteit is aan een v2-contract en toekomstig bewijs gekoppeld.
- Geen open designholdpoint wordt door een implementatieagent zelf ingevuld.
- Robbert heeft het doelontwerp expliciet goedgekeurd.

## Stap 2 — Technische fundering en side-by-side composition

### Doel

Maak een deploybare v2-skeletstructuur naast v1 zonder bestaand productgedrag te veranderen.

### Werk

1. Voeg een parent-managed `software-factory-api` en de eerste capabilitymodules toe.
2. Voeg `software-factory-app` als nieuwe composition root toe; start haar aanvankelijk alleen in
   `SHADOW`/niet-muterende modus.
3. Leg publieke root-API's, named interfaces en expliciete toegestane dependencies vast.
4. Voeg een apart v2-schema of eenduidig geprefixte capabilitytabellen toe; raak v1-tabellen niet
   destructief aan.
5. Voeg `ImplementationManifest` toe met actieve capabilityprovider, versie, artifact en bron-SHA.
6. Centraliseer klok, ID-generatie, JSON, transactionele outbox, health en veilige configuratie.
7. Voeg één gesloten secretloader toe met expliciete keycatalogus; verwijder nog geen v1-config.
8. Voeg v2-migratiesmoketests toe voor leeg, upgrade, restart en rollback van de applicatiecode.
9. Voeg Modulith-/Maven-boundarytests en een geen-interne-cross-module-importgate toe.
10. Bouw een v2 Testbedprofiel met PostgreSQL, fake bridge, fake Git/PR/deploy en Runtime mocks.
11. Laat CI en images beide composition roots bouwen zonder v2 in productie te activeren.

### Exitcriteria

- V1 draait functioneel ongewijzigd en alle bestaande regressies zijn groen.
- V2 start, migreert en rapporteert health zonder agents, stories, merges of deploys te starten.
- Acceptatie kan uitsluitend veilige mocks bereiken.
- Productie kan niet per ongeluk `SHADOW` als actieve schrijfroute gebruiken.
- De nieuwe modulegraph is klein, expliciet en cyclusvrij.

## Stap 3 — Projectconfiguratie, WorkRequest en Story-inname

### Doel

Maak de v2-ingang en stabiele story-identiteit zonder al uitvoering te starten.

### Werk

1. Implementeer `Project` met repositoryalias, publieke URL, default branch, verificationpolicy,
   deploymenttargets, Runtime-projectprefix, status en `defaultEngineVersion`.
2. Bouw één gevalideerde projectcatalogus; geen capability leest zelfstandig YAML of env.
3. Implementeer onveranderlijke `WorkRequest` en `Story` met source, options, engineversie,
   storykey en versions.
4. Behoud projectgebonden storykeys zonder botsing tussen v1 en v2. Gebruik tijdens coexistence één
   atomair keyallocatiecontract.
5. Voeg adapters toe voor dashboard-create, Product Factory v2 en auditvoorstellen.
6. Behoud Product Factory packagehash, attachmentvalidatie, source-ID/version en
   idempotentiegedrag.
7. Sla binaire input duurzaam op met hash en MIME-type; maak nog geen agentworkspace.
8. Implementeer `DRAFT`, `QUEUED`, `RUNNING`, `WAITING_FOR_HUMAN`, `DONE`, `CANCELLED` en
   `FAILED` als publieke projectie; interne details blijven capability-eigen.
9. Voeg per project een factory-owneractie toe om alleen nieuwe stories naar v2 te routeren.
10. Laat shadowmode requests lezen en valideren zonder ze te claimen of externe effecten te maken.

### Exitcriteria

- Een identieke Product Factory-request maakt bij herhaling precies één story.
- De bestaande v2-response en statusqueries blijven byte-/semantisch compatible.
- V1- en v2-storykeys kunnen niet botsen.
- Een projectomschakeling verandert geen reeds bestaande story.
- V2-inname kan veilig aanstaan terwijl alle uitvoering nog uitstaat.

## Stap 4 — Duurzame workflowmotor, claims en herstel

### Doel

Vervang polling op vrije trackerfasen door expliciete, getypeerde en hervatbare workflowruns.

### Werk

1. Implementeer `WorkflowRun`, `WorkStep`, `StepRun` en onveranderlijke plan-/inputversies.
2. Maak per project/story maximaal één onafgeronde logische workflowrun mogelijk.
3. Implementeer atomische claims met lease, fencingversie en begrensde retry/back-off.
4. Maak `runWorkflowSession(projectId)` de enige ingang die nieuw workflowwerk mag claimen.
5. Laat scheduler en handmatige start dezelfde publieke functie gebruiken.
6. Implementeer duurzame outboxeffecten voor Runtime-request, question-publicatie, PR/merge/deploy,
   notificatie en externe status.
7. Leg voor ieder effect request, idempotentiesleutel, response, fouttype en volgende retry vast.
8. Implementeer pause, resume, cancel, retry-current-step, re-implement en clear-error als
   betekenisvolle commands op v2-state.
9. Modelleer `WAITING_FOR_AI`, `WAITING_FOR_HUMAN`, `WAITING_FOR_QUOTA`, `WAITING_FOR_EXTERNAL`,
   `BLOCKED`, `SUCCEEDED` en `FAILED` zonder één generiek Error-veld als workflowmotor.
10. Voeg crashfixtures toe vóór/na iedere lokale commit en externe bevestiging.
11. Laat een actieve v1-story nooit door de v2-claimquery gevonden worden.

### Exitcriteria

- Iedere crashfixture hervat dezelfde logische stap zonder dubbel extern effect.
- Verschillende projecten mogen parallel; dezelfde story niet.
- Een wachtende sessie houdt geen thread, database-lock of worker vast.
- Handmatige en geplande starts hebben identiek domeingedrag.
- Onbekende externe toestand blijft veilig geblokkeerd.

## Stap 5 — Agent Runtime v2 als enige nieuwe AI-uitvoeringsgrens

### Doel

Laat v2 AI-taken duurzaam uitvoeren via Agent Runtime zonder Software Factory-eigen provider-,
container-, lease- of workerlogica.

### Werk

1. Implementeer lokale `AiTask`, inputobjecten, outbox, Runtime-correlatie, events, attempts,
   artifacts, usage en annulering.
2. Gebruik alleen Agent Runtime `/v2` met expliciete `vendorId`, `model`, `mode` en `taskType`.
3. Lees beschikbare execution options en environmentkeys; gebruik nooit een model fallback.
4. Upload prompt, schema, attachments en andere grote inputs via hervatbare objectuploads.
5. Maak Runtime-jobs idempotent en reconcilieer status plus events periodiek; SSE mag versnellen
   maar polling blijft correctheidsfallback.
6. Valideer resultaatschema, artifactdeclaraties, MIME, grootte en SHA vóór domeinpublicatie.
7. Kopieer blijvende artifacts naar Software Factory-eigendom wanneer Runtime-retentie korter is
   dan story-/auditretentie.
8. Toon Runtime-attempts apart van een bewuste nieuwe Software Factory-taakpoging.
9. Modelleer quota vanuit Runtime als wacht-/beschikbaarheidsstatus zonder Claude-specifieke
   parsers in het v2-domein.
10. Gebruik acceptatie uitsluitend met `mock/mock/MOCK` en een afzonderlijk test-control-token.
11. Voeg een tijdelijke v1-runtimeadapter alleen toe voor door stap 0 bewezen capabilitygaps, met
    expliciete verwijdervoorwaarde en zichtbare gebruiksmetric.

### Exitcriteria

- Verloren job-create-response, uploadonderbreking en restart maken geen tweede Runtime-job.
- Een ongeldig JSON-resultaat of artifact wordt niet gepubliceerd als geslaagde stap.
- Geen providercredential of lokale Docker-socket is nodig in `software-factory-app`.
- Runtime-jobstatus, events, usage en veilige fout zijn zichtbaar in Operatie.
- V1 blijft ongewijzigd zijn eigen runtime gebruiken totdat een story v2 is.

## Stap 6 — Refinement, vragen en planning

### Doel

Maak van een v2 WorkRequest een complete immutable StorySpecification en een minimaal
ExecutionPlan.

### Werk

1. Implementeer refineprocessessies die exacte request-, product-, comment-, vraag-, config-,
   prompt- en repositoryversies bevriezen.
2. Laat de refiner een strikt schema retourneren met specificatie, samenvattingen, criteria,
   aannames en optionele vragen.
3. Publiceer iedere geldige StorySpecification als onveranderlijke versie.
4. Implementeer Question met geadresseerde, bronstep, versie, meerdere vragen, answerhistorie en
   hervatting van exact dezelfde logische refine-/planstap.
5. Respecteer `questionsAllowed=false`: blokkeer/markeer clarification als fout volgens het huidige
   contract en wacht niet stil op een mens.
6. Laat de planner alleen een getypeerde lijst stapdefinities maken; hij schrijft geen trackerissues
   en voert geen repositorywerk uit.
7. Dwing de standaardketen deterministisch aan, inclusief verplichte documentation, eventuele
   manual-approve, merge en deploy.
8. Bewaar planversie en stapvolgorde; een lopende run leest geen later gewijzigd plan.
9. Houd het aantal stappen minimaal en voorkom dubbele reviewer-/testerrollen zonder reden.
10. Publiceer dashboard- en Telegramvragen uitsluitend via Human Interaction en Notification.

### Exitcriteria

- Ruwe, al complete en onveilige/ambigue requests hebben ieder voorspelbaar gedrag.
- Vragen en meerdere antwoordrondes hervatten zonder dubbele AI-taak of nieuwe story.
- Een planneroutput kan verplichte systeemstappen niet verwijderen of omordenen.
- Een v2-plan bestaat zonder v1-subtaskissues of vrije fasevelden.
- Characterizationtests tonen functionele pariteit met refine/plan v1.

## Stap 7 — Repositorywerk, reviewloop, tester en verificatiebewijs

### Doel

Lever een traceerbare PR op met versiegebonden review en onafhankelijk machinebewijs.

### Werk

1. Implementeer `RepositoryDelivery` en reserveer per story één branch/PR-identiteit.
2. Dien developerwerk als `REPOSITORY_WORK` in met exact bevroren specification, planstep,
   antwoorden, relevante docs en toegestane credentials.
3. Zorg dat vervolgdeveloper- en documenterjobs aantoonbaar op dezelfde storybranch en actuele
   PR-head voortbouwen.
4. Bewaar branch, commits, PR-URL, base/head-SHA, diffmetadata en Runtime-jobcorrelaties.
5. Laat de reviewer een read-only exact PR-head beoordelen en bind ieder oordeel aan die SHA.
6. Laat een afwijzing alle bevindingen in één ronde teruggeven en een nieuwe developerstep op
   dezelfde delivery starten.
7. Laat de tester applicatie en repository read-only onderzoeken en bewijsartifacts teruggeven.
8. Voer `.factory/verification.yaml` deterministisch uit volgens het in stap 1 gekozen eigenaarschap.
9. Valideer command-ID, argv, timeout, toolstatus, exitcode, outputgrens, configversie, HEAD en
   worktree-tree onafhankelijk van agentproza.
10. Laat rood, timeout, ontbrekende tooling of revisionmismatch nooit passeren.
11. Voer documenter als repositorywerk uit en controleer dat productiecode buiten zijn scope niet
   wijzigt; bij geen impact levert hij een gestructureerde verklaring.
12. Voer summarizer uit op de werkelijk opgeleverde diff en bewijsset, niet op alleen het plan.
13. Behoud begrensde developer/reviewer/testloopbacks en een zichtbare blocker na de cap.

### Exitcriteria

- Iedere wijziging is aan één story, branch, commit, PR en exacte specification gekoppeld.
- Reviewer- en testerbesluiten gelden nooit voor een verouderde head-SHA.
- Alleen groen deterministisch bewijs kan de mergevoorwaarde vervullen.
- Restart tussen repositoryjob, PR-publicatie en lokale correlatie maakt geen tweede PR.
- Developer noch model krijgt Git-publicatiecredentials.
- De volledige v2-keten werkt op een tijdelijke echte Gitrepository met Runtime-worker.

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
   actuele PR-head.
9. Maak merge idempotent op storydelivery en bewaar de werkelijke mergecommit.
10. Implementeer DeploymentRun per target, start/reconcile/retry en terminale uitkomst.
11. Rapporteer story `DONE` pas wanneer het vastgelegde oplevercontract is voltooid; publiceer de
    volledige delivered commit-SHA.
12. Bewaar handmatige merge/deploycommands als dezelfde use cases en geen bypass.

### Exitcriteria

- Alle drie approvalmodi voldoen aan characterizationtests.
- Een hotfix heeft exact de korte keten en geen extra controle ten opzichte van het bestaande
  functionele contract.
- Alleen één centrale groene gate kan mergen, automatisch én handmatig.
- Merge en ieder deploymenttarget zijn crash- en retry-idempotent.
- Product Factory ziet `DONE` uitsluitend met een volledige commit-SHA.

## Stap 9 — Audits, kennis, notificaties, Telegram en dashboard

### Doel

Migreer de ondersteunende capabilities en maak de samengestelde v1/v2-toestand begrijpelijk.

### Werk

1. Implementeer read-only AuditRun met projectplanning, oudste-eerstkeuze, vragen, rapport, score,
   kennis en hooguit één gecontroleerd storyvoorstel.
2. Gebruik Agent Runtime voor audits en verwijder directe Dockerdispatch uit nieuwe auditcode.
3. Implementeer versieerbaar KnowledgeItem per project en rol; houd vragen en productwaarheid
   erbuiten.
4. Publiceer getypeerde domeinevents voor de acht bestaande notification events.
5. Implementeer idempotente NotificationDelivery per event, story, ontvanger en kanaal.
6. Migreer Telegrammeldingen en reply-antwoorden naar publieke questions/approvals/commands.
7. Neem een expliciet besluit en tests op voor de vrije Telegram-assistent; geef hem geen directe
   mutatiepoort vanuit AI-output.
8. Maak remote dashboard-backend een dunne auth-/bridgeadapter op v2 application-API's.
9. Bouw samengestelde v1/v2-lijsten en details met één publiek presentatiemodel.
10. Toon story, specification, plan, steps, vragen, approvals, Runtime-jobs, PR, bewijs, merge,
    deploy, notifications en veilige fouten.
11. Behoud builds, downloads, settings, audit en agents/operations; haal usage/kosten uit Agent
    Runtime in plaats van lokaal prijzen te dupliceren.
12. Maak v2-routes responsive, bookmarkbaar en getest; behoud compatibiliteit met nog uitgerolde
    dashboardclients tijdens expand.

### Exitcriteria

- Een gebruiker kan v1- en v2-stories volgen zonder engine-interne velden te hoeven begrijpen.
- Iedere mensactie roept één geautoriseerd publiek command aan.
- Telegramherhaling of restart maakt geen dubbele melding of beslissing.
- Audit kan geen repository wijzigen en maakt maximaal één idempotent voorstel.
- Dashboard-backend bevat geen nieuwe workflowbeslissing.

## Stap 10 — Product Factory-contract, canary en productiecutover

### Doel

Activeer v2 project voor project, behoud het bestaande externe contract en maak de tijdelijke
Product Factory-hotfixworkaround overbodig met een echte machine-hotfixroute.

### Werk

1. Laat `/api/integrations/v2/status`, create, get/list en cancel op een stabiele façade werken die
   v1/v2 intern routeert.
2. Behoud alle bestaande requestvelden, validation, attachments, packagehash en idempotentie.
3. Voeg backward-compatible een expliciete optionele uitvoermodus toe voor machine-aanmaak,
   bijvoorbeeld `executionMode=STANDARD|HOTFIX`, met default `STANDARD`.
4. Laat `type=BUGFIX` nooit automatisch `HOTFIX` betekenen.
5. Autoriseer de hotfixmodus met hetzelfde gescopete Product Factory-integratietoken; gebruik geen
   dashboardgebruikerssessie.
6. Publiceer het bijgewerkte contract en contracttests in Software Factory. Een eventuele Product
   Factory-consumerwijziging is een afzonderlijk vervolg en geen verborgen wijziging in deze repo.
7. Voeg een canaryproject toe met echte Runtime-worker, testrepository, PR, verificatie en veilige
   testdeployment.
8. Draai v2 eerst in shadow/read-only, daarna voor handmatig aangemaakte canarystories en pas daarna
   voor Product Factory-verkeer.
9. Voer vóór iedere projectomschakeling een preflight uit op open v1-werk, Runtimecapaciteit,
   credentials, verificationconfig, branch protection en deploymenttargets.
10. Bewaar cutoveractor, tijdstip, configuratieversie en bewijs.
11. Meet foutpercentage, doorlooptijd, loopbacks, vragen, Runtimewachttijd, merges en deployments
    tegen de stap-0-baseline.
12. Test rollback door alleen nieuwe inname terug naar v1 te zetten; bewijs dat geen story dubbel
    wordt uitgevoerd.
13. Schakel projecten één voor één om en wacht tot het bewijsvenster per project groen is.

### Exitcriteria

- Bestaande Product Factory-clients blijven zonder wijziging functioneren.
- Het nieuwe machine-hotfixveld is optioneel, expliciet en default veilig uit.
- Canary doorloopt normale story, vragen, afwijzing, hotfix, cancel, merge en deploy.
- Rollback is werkelijk uitgevoerd en heeft geen dubbele agentjob, PR, merge of deploy gemaakt.
- Alle actieve projecten draaien na bewuste goedkeuring op v2; resterend v1-werk is zichtbaar en
  aflopend.

## Stap 11 — Contractfase, legacyverwijdering en eindbewijs

### Doel

Verwijder v1 pas nadat v2 langere tijd aantoonbaar stabiel is en maak de nieuwe architectuur de
enige actuele waarheid.

### Ingangseisen

- Alle projecten maken nieuwe stories via v2.
- Er zijn geen open, wachtende, gepauzeerde of retrybare v1-stories meer.
- Het afgesproken productie-observatievenster is zonder kritieke regressie voltooid.
- Product Factory, dashboard en Telegram gebruiken alleen de stabiele façades.
- Robbert heeft de contractfase expliciet goedgekeurd.

### Werk

1. Maak een onveranderlijk archief/export van v1-story-, subtask-, run-, comment- en eventhistorie.
2. Bewijs dat publieke historie en changelog vanuit v2 plus legacyarchief volledig blijven.
3. Verwijder de v1-engine, trackerfasecoördinatoren en legacycommandrouting.
4. Verwijder lokale DockerAgentRuntime, agentworker, result-file completionpoller en bijbehorende
   providerclients nadat geen v2-fallback ze gebruikt.
5. Verwijder oude Runtime-/provider-/Dockersecrets uit Software Factory-configuratie en deployment.
6. Verwijder tijdelijke engineflags, shadowprojecties en dual-readcode.
7. Houd historische databaseobjecten minimaal één release read-only; drop ze alleen in een aparte,
   herstelbaar geback-upte migratie na expliciet besluit.
8. Vereenvoudig Maven, images, CI en lokale installatie naar de definitieve modules.
9. Genereer de definitieve dependencygraph en bewijs dat iedere capability alleen publieke API's
   gebruikt.
10. Werk README, functionele/technische specs, runbook, installatie, API-docs en UX-documentatie bij
    naar uitsluitend de actuele v2-werking.
11. Verwijder of archiveer documenten die v1 en v2 als gelijktijdig actueel presenteren.
12. Draai de volledige pariteitsmatrix, failure-injectiontests, securitytests en productiesmoke.

### Exitcriteria

- Geen productiecode kan nog een v1-story claimen of een lokale agentcontainer starten.
- Software Factory bevat geen AI-providercredential of Dockerworkerverantwoordelijkheid meer.
- Alle actuele documentatie beschrijft uitsluitend v2.
- Back-up en herstelproef van v1-history zijn geslaagd voordat iets destructiefs gebeurt.
- De eindpariteitsmatrix heeft geen `TODO`, `UNKNOWN` of onbewezen kritieke regel.
- Dezelfde immutable artifacts staan gezond op de bedoelde omgevingen.

## Verplichte Testbed- en ketenscenario's

Stap 1 specificeert ze volledig; uiterlijk stap 10 zijn minimaal deze scenario's geautomatiseerd:

1. complete standaardstory zonder menselijke interventie;
2. refiner stelt meerdere vragen en hervat exact dezelfde sessie;
3. planner stelt een vraag en materialiseert daarna de correcte vaste keten;
4. developer wordt door reviewer afgewezen en herstelt op dezelfde PR;
5. tester of deterministische verificatie wijst af en start begrensde herstelroute;
6. documenter wijzigt alleen documentatie en behoudt dezelfde storydelivery;
7. approvalmode automatisch;
8. approvalmode alleen manual-poort;
9. approvalmode elke stap, inclusief reject;
10. hotfix met exact developer, merge en deploy;
11. hotfix met rode verificatie start geen merge;
12. questionsAllowed uit resulteert in zichtbare clarificationfout;
13. Product Factory-create is idempotent met attachments en verloren response;
14. Product Factory machine-hotfix is expliciet en `BUGFIX` blijft standaard;
15. quota/worker niet beschikbaar wordt wachtstatus en hervat later;
16. Runtime-jobtimeout en late gefencete completion worden veilig verwerkt;
17. crash vóór en na Runtime-create, resultaatpublicatie en artifactkopie;
18. crash vóór en na PR-publicatie, merge en ieder deploymenttarget;
19. CI pending wacht, rood blokkeert en oude-SHA-groen telt niet;
20. pause/resume/kill/retry/re-implement/clear-error op geldige en ongeldige momenten;
21. storyannulering vóór uitvoering, tijdens AI-wacht en na PR maar vóór merge;
22. multi-targetdeployment met gedeeltelijke tijdelijke fout;
23. audit stelt een vraag, hervat en maakt maximaal één storyvoorstel;
24. notification eventsets, Telegramdelivery en reply-idempotentie;
25. twee projecten parallel zonder cross-projectstory, credential of artifactlekkage;
26. v1- en v2-story naast elkaar zonder dubbele claim;
27. projectcutover beïnvloedt alleen nieuwe stories;
28. rollback routeert alleen nieuw werk terug en behoudt v2-state;
29. dashboard toont samengestelde v1/v2-status en correcte mensacties;
30. restart met wachtende workflow, verlopen claim, outboxeffect en onbekende externe toestand.

## Verplichte bewijzen per implementatiestap

Iedere stap is pas klaar wanneer voor haar scope:

1. de Mavenreactor en alle relevante unit-, integratie- en e2e-tests groen zijn;
2. Flutter analyze, tests en productiebuild groen zijn bij frontendimpact;
3. nieuwe migraties vanaf leeg en boven op de laatste productieversie slagen;
4. module- en dependencychecks geen ongeautoriseerde grens openen;
5. idempotentie, expected-versionconflict, restart en retry aantoonbaar zijn getest;
6. externe adapters dezelfde contracttests tegen fake en echte/stubgrens doorlopen;
7. `./quality/run.sh` niet verslechtert en geen nieuwe suppressie toevoegt;
8. secrets niet voorkomen in Git, logs, events, prompts, transcripten, artifacts of testfixtures;
9. actuele documentatie alleen werkelijk geïmplementeerd gedrag beschrijft;
10. een reviewer en tester op de exacte story-SHA akkoord hebben gegeven;
11. CI groen is en de release dezelfde immutable artifacts gebruikt;
12. de voortgangsmatrix exacte commit, PR, tests, bewijs, beslissingen en volgende startgate bevat.

## Aanbevolen uitvoeringsorganisatie

Maak vóór uitvoering naast dit document:

- `docs/software-factory-v2/VOORTGANG.md` als enige live voortgangsbron;
- `docs/software-factory-v2/PARITEIT.md` als requirement-naar-bewijsmatrix;
- per stap een zelfstandig uitvoerbestand zodra stap 0 en 1 de actuele omvang hebben bewezen.

Voer stappen 0 en 1 sequentieel uit. Stap 2 volgt pas na ontwerpgoedkeuring. Binnen latere stappen
mogen uitsluitend expliciet niet-overlappende adapters, frontend en fixtures parallel worden
gebouwd. Domein, schema en state-machinewerk blijven sequentieel totdat hun publieke contract is
gemerged.

Iedere uitvoeringsstory:

- start vanaf de actuele groene hoofdbranch in een eigen worktree;
- heeft één afgebakende capability en geen opportunistische naburige refactor;
- levert code, migratie, tests, documentatie en operationele zichtbaarheid samen op;
- gebruikt een feature/configuratiegate zolang zij nog niet productie-authoritative is;
- verwijdert geen v1-pad voordat stap 11 dat expliciet toestaat;
- wordt pas gemerged na volledige vereiste verificatie.

## Belangrijkste risico's

| Risico | Beheersing |
|---|---|
| Big-bang rewrite breekt productie | Per-story engineversie, projectgewijze canary en expand/contract. |
| V1 en v2 voeren hetzelfde werk uit | Onveranderlijke enginekeuze, gescheiden claimqueries en cross-engine e2e-test. |
| Runtime kan huidige branch-/verificationflow niet dragen | Stap-0-spike en tijdelijke expliciete v1-adapter; geen stil tweede Runtime-ontwerp. |
| Externe Product Factory-integratie breekt | Backward-compatible façade en golden contracttests vóór interne migratie. |
| Storykeybotsing tijdens coexistence | Eén atomair keyallocatiecontract zolang beide engines kunnen creëren. |
| Actieve story verliest context bij migratie | Geen actieve-statemigratie; v1 rondt eigen werk af. |
| Dubbele PR, merge of deploy na crash | Duurzame outbox, externe lookup en idempotentiesleutel per effect. |
| Nieuwe modules worden alleen nieuwe dozen rond dezelfde godservices | API-first contracten, één eigenaar, architectuurtests en geen interne imports. |
| Secrets verschuiven van factory naar prompts/server | Runtime keygrants, worker-only waarden en resultaat-/artifactcontrole. |
| Dashboard wordt een tweede domein | Backend als dunne bridgeadapter; commands en projecties uit capability-API's. |
| V1 blijft permanent bestaan | Expliciete stap-11-ingangseisen, gebruiksmetrics en verwijderbesluit. |

## Buiten scope

- Product Factory v2 opnieuw ontwerpen of implementeren.
- Agent Runtime intern wijzigen vanuit deze repository.
- Targetapplicaties zoals PvdD aanpassen om Software Factory v2 mogelijk te maken.
- Een microservicesplitsing per capability.
- Automatische conversie van actieve v1-stories.
- Een nieuw extern workflow- of issuetrackersysteem.
- Een extra diffguard in hotfix.
- Agent Runtime productbeslissingen laten nemen over approvals, merge of deploy.
- V1-tabellen of historie verwijderen vóór de afzonderlijke contractfase.

## Definitie van klaar voor Software Factory v2

Software Factory v2 is klaar wanneer alle nieuwe stories op alle actieve projecten uitsluitend door
de v2-capabilities worden verwerkt; Agent Runtime `/v2` de enige technische agentuitvoeringsgrens
is; iedere story, stap, vraag, approval, Runtime-job, PR, verificatie, merge, deployment en
notificatie duurzaam en herleidbaar is; Product Factory en dashboard hun bestaande contracten
behouden; normale en hotfixketens functioneel gelijkwaardig zijn; crash-, retry-, quota-, rollback-
en securityscenario's groen zijn; er geen open v1-work meer bestaat; en de v1-engine plus lokale
agentworker aantoonbaar veilig zijn verwijderd of alleen als read-only historisch archief bestaan.
