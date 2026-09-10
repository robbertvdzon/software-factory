# Software Factory — overstap naar Agent Runtime v2

Status: toekomstontwerp, nog niet geïmplementeerd

Peildatum: 2026-09-10

Doelrepository: `softwarefactory`

Uitvoeringsgrens: uitsluitend deze repository; wijzigingen in siblingrepositories zijn verboden

> Dit document beschreef eerder een volledige herbouw met een nieuw domeinmodel ("Software Factory
> v2"). Dat is bewust verlaten. De opdracht is een **refactor**: de eigen agent-runner vervangen
> door Agent Runtime v2. Het pad blijft `docs/software-factory-v2/stappenplan.md`, omdat het Agent
> Runtime-document daarnaar verwijst.

## Doel

De Software Factory voert agentwerk nu zelf uit: `DockerAgentRuntime` start containers, de
`agentworker`-module draait daarin de AI-CLI, houdt een story-workspace met een checkout op de
laptop bij, draait de verificatie en publiceert de Git-wijzigingen. Die hele laag wordt vervangen
door jobs op **Agent Runtime v2**, dat containers, providers, leases, retries, transcripten,
artifacts en kosten al beheert en per job een verse tijdelijke checkout maakt.

Wat de factory functioneel doet verandert niet.

## Wat dit wel en niet is

**Wel:**

- de eigen agentuitvoering (Docker, AI-CLI-clients, workspaces, completion via resultbestanden)
  vervangen door Agent Runtime `/v2`-jobs;
- het repositoryprotocol aanpassen aan stateless jobs: één remote storybranch per story als enige
  overdrachtspunt, in plaats van een gedeelde workspace op de laptop;
- de deterministische verificatie meeverhuizen naar de Runtime-job, met een herstellus voordat er
  gepusht wordt;
- opruimen wat daarmee overbodig wordt.

**Niet:**

- geen nieuw domeinmodel; het bestaande `issues`/tracker-model blijft;
- geen nieuwe capabilitymodules of herverdeling van modulegrenzen;
- geen herschrijving van audits, kennis, maintenance, merge, Telegram of het dashboard;
- geen datamigratie, tenzij de topologiestap (stap 5) doorgaat;
- geen gedragsverandering in de storyketen, approvalmodi, hotfix of notificaties.

Het opschonen van het tracker-model is een aparte discussie voor later. Dit plan raakt het alleen
waar de runtimewissel dat afdwingt.

## Uitvoeringswijze

- Alle werk gebeurt **rechtstreeks op `main`**, in kleine, begrijpelijke commits.
- Geen storybranch, geen pull request en geen review-agent voor dit werk.
- Geen CI-pipeline per stap: iedere commit tot en met stap 5 krijgt `[skip ci]`, zodat `verify.yml`
  en daarmee de image-builds en de automatische bump niet starten.
- Verificatie gebeurt lokaal en gericht: `mvn -B verify` op de geraakte modules, `flutter analyze`
  en `flutter test` bij frontendwerk, `./quality/run.sh` waar zinvol.
- **De Software Factory mag tijdens stap 2 tot en met 5 kapot zijn.** De software is niet in
  gebruik; voorzichtigheid die alleen bestaat om productie te beschermen is hier verspilling.
- Stap 6 maakt alles weer aantoonbaar werkend.

## Wat verandert en wat blijft

Gemeten op 2026-09-10: 31.200 regels Kotlin (main) en 9.400 regels Dart.

| Onderdeel | Regels | Wat gebeurt ermee |
|---|---:|---|
| `agentworker` (module) | 2.881 | **Weg.** AI-CLI-clients, `CliProcessRunner`, `TaskFileManager`, `TargetRepositoryFlow` en `TesterVerificationRunner` worden overbodig; hun rol gaat naar de Runtime-worker. |
| `runtime/docker`, `runtime/workspaces` | deel van 4.176 | **Weg.** Containerstart en story-workspaces zijn Runtime-verantwoordelijkheid. |
| `runtime/services` completion | deel van 4.176 | **Vervangen.** `AgentResultFileCompletionPoller` en completion via resultbestanden worden jobcorrelatie, events en resultaatophaling bij Runtime. `DurableCompletionCoordinator` houdt zijn rol, maar op een Runtime-job. |
| Prompts (`AgentPromptContracts`) | — | **Blijft**, verhuist naar de job-instructie; aangevuld met de eis dat de agent zelf de tests groen achterlaat en geen Git-acties doet. |
| `factory-common/github` (`GitHubCliClient`) | deel van 2.932 | **Blijft en groeit**: Software Factory maakt voortaan zelf de remote storybranch en de PR. |
| Verificatie (`.factory/verification.yaml`, `TesterVerificationEvidenceValidator`) | — | **Blijft als contract**, maar wordt uitgevoerd binnen de Runtime-job. |
| `audit` | 1.267 | **Blijft**; alleen de dispatch verandert. Nul afhankelijkheden op tracker of agentdispatch. |
| `knowledge`, `maintenance`, `merge` | 1.347 | **Blijft ongewijzigd.** |
| `telegram` | 2.592 | **Blijft**; alleen waar het de oude agentdispatch raakt (1 van 14 bestanden). |
| `dashboard` | 4.443 | **Blijft**; de agent-/joblogschermen gaan Runtime-gegevens tonen (7 van 20 bestanden raken de oude runtime). |
| `pipeline`, `orchestrator`, `tracker` | 5.565 | **Blijft.** Raakt alleen waar een stap een agent start of een workspace veronderstelt. |
| `dashboard-frontend` | 9.401 (Dart) | **Blijft**; alleen de agent-/logschermen volgen de nieuwe gegevens. |

## Externe prerequisite: Agent Runtime v2

De benodigde Runtime-wijzigingen worden apart uitgevoerd in de siblingrepo `agent-runtime`. De
normatieve opdracht staat in het
[Agent Runtime-prerequisitedocument](../../../agent-runtime/docs/software-factory-v2-integratieplan.md).

Deze repository mag die siblingrepo alleen read-only inspecteren. Ontbreekt een capability, dan
wordt dat in `VOORTGANG.md` vastgelegd en blijft het afhankelijke werk zichtbaar geblokkeerd. Er
komt geen lokale workaround. De enige uitzondering die is gemaakt: het hieronder genoemde
specificatiedocument is als nieuw bestand in die repo neergezet, zonder bestaande bestanden aan te
raken.

Relevante garanties uit die prerequisite:

- Software Factory levert een geregistreerde alias en een reeds bestaande remote branch aan, geen
  lokale map en geen repositorycredential;
- muterende jobs gebruiken `REPOSITORY_WORK`, `REPOSITORY_AGENT` en publicatiemodus
  `COMMIT_AND_PUSH`; read-only jobs gebruiken `APPLICATION_WORK` met publicatiemodus `NONE`;
- de worker maakt een verse tijdelijke checkout en maakt geen storybranch of PR;
- het gevalideerde AI-resultaat en het Runtime-beheerde `repositoryResult` blijven afzonderlijk
  beschikbaar;
- `NO_CHANGES` is een geldige technische publicatie-uitkomst;
- een gewijzigde remote branch leidt tot `BRANCH_CHANGED`, nooit tot force-push;
- workerselectie houdt rekening met repositoryaliasbeschikbaarheid;
- een crash na push wordt gereconcileerd zonder tweede commit.

### Tweede prerequisite: verificatie binnen de job

Bovenop dat document is één capability nodig die er nog niet in staat, en die het huidige document
zelfs uitsluit ("Agent Runtime krijgt geen generieke shell-executor"): een muterende job voert na de
agent de projectverificatie uit, geeft rood terug aan diezelfde agent (maximaal drie rondes) en
publiceert alleen bij groen.

Dat is uitgewerkt in een eigen specificatie met stappenplan:
[Agent Runtime — verificatie binnen de job](../../../agent-runtime/docs/verificatie-in-de-job.md).

Zonder die capability is er na het verdwijnen van de `agentworker` niemand meer die de commando's
uit `.factory/verification.yaml` draait. De PR-CI als alternatief is bewust afgewezen: dan ziet een
reviewer een gebroken unit test pas na een pipeline van minuten en moet de keten helemaal terug naar
de developer, en verdwijnt bovendien het bewijs per commando.

**Volgorde:** eerst worden beide Runtime-documenten in de siblingrepo uitgevoerd, daarna pas deze
repository. Robbert bevestigt expliciet wanneer dat klaar is. Tot dat moment wordt hier niets
geïmplementeerd.

## Genomen beslissingen

| Onderwerp | Beslissing |
|---|---|
| Aard van het werk | Refactor, geen herbouw. Het tracker-model en alle bestaande capabilities blijven. |
| Werkwijze | Rechtstreeks op `main`, geen PR's, `[skip ci]` tot stap 6. |
| Beschikbaarheid | De factory hoeft tijdens stap 2 tot en met 5 niet te werken. |
| Product Factory | Mag tijdens de verbouwing stuk zijn; moet in stap 6 weer werken volgens het bestaande contract. |
| Verificatie | Binnen de Runtime-job met herstellus (maximaal drie rondes), niet via de PR-CI en niet in een eigen runner. |
| Telegram, audits, kennis | Blijven ongewijzigd werken; `.factory/nightly/` blijft de bron van auditdefinities. |
| Projectconfiguratie | `projects.yaml` blijft de bron. |
| AI-level | Vervalt. `aiLevel` en `AiRouting` verdwijnen. |
| Modelkeuze | Eén configuratie waarin per agentrol de `vendorId`, het `model` en de `mode` staan, op elk moment te wisselen zonder herbouw of herstart. |
| Volgorde | Eerst de twee Agent Runtime-documenten uitvoeren in de siblingrepo, daarna pas deze repository aanpassen. |
| Contracttests tegen Runtime | Niet doen. De consumers zitten allemaal in de eigen `git`-map en zijn overzichtelijk. |
| Bestaande data | Alleen afgeronde stories hoeven te overleven. Zonder stap 5 blijft de database staan waar hij staat en is er niets te migreren. |
| DNS | Robbert regelt zo nodig de host in Cloudflare. |

## Openstaande punten

1. **Gaat stap 5 (topologie) mee in deze slag of later?** Het is de enige stap met een datamigratie
   en met werk buiten deze repository.
2. **Waar leeft de modelconfiguratie?** De eis is: per agentrol instelbaar en op elk moment te
   wisselen. Dat pleit voor opslag in de database met de bestaande settingsschermen erboven, in
   plaats van `projects.yaml`, dat bij het opstarten wordt gelezen. Te bevestigen in stap 1.

## Uitvoeringsmodel na de refactor

### AI-grens

- Software Factory kiest rol, taaktype, prompt, context, repository, base branch, storybranch,
  model en productbeleid.
- Agent Runtime voert een technische job uit en kent geen story-, approval-, merge- of
  deploysemantiek.
- Alleen Agent Runtime maakt checkouts en voert `fetch`, `checkout`, `pull`, `commit` en `push` uit.
  Software Factory mount of bewaart geen repositoryworkspace.
- Binnen de Runtime is de worker eigenaar van alle muterende Git-acties. De agent wijzigt bestanden
  en draait tests, maar maakt geen branch, commit niet, pusht niet en maakt geen PR. Read-only
  commando's als `status`, `diff` en `log` blijven beschikbaar.
- Opeenvolgende agentjobs delen geen filesystem.
- Iedere logische agentstap heeft één duurzame correlatie en één Runtime-job. Een verloren response
  of restart maakt geen tweede job.
- Software Factory vertrouwt nooit op alleen AI-proza als testbewijs.

### Git- en branchprotocol per story

1. Software Factory reserveert een unieke storybranch en maakt die remote aan vanaf de
   geconfigureerde base branch, via de Git-provider-API en zonder checkout.
2. Software Factory geeft alleen de geregistreerde repositoryalias en de storybranch door.
3. Iedere repositorygebruikende job start schoon, clonet of fetcht en checkt die branch uit.
4. De worker geeft de voorbereide worktree aan de agent; na afloop valideert hij dat branch,
   begin-HEAD en Gitmetadata niet zijn gemuteerd, draait hij de verificatie en pusht hij pas bij
   groen.
5. Een volgende agent haalt opnieuw dezelfde remote branch op.
6. Na de eerste succesvolle push maakt Software Factory precies één PR van de storybranch naar de
   base branch; alle latere commits komen automatisch in diezelfde PR.
7. De PR blijft drager van GitHub-checks en preview-identiteit en wordt aan het eind door Software
   Factory gemerged.

SHA's zijn geen coördinatieprotocol tussen stappen. `checkoutCommitSha` wordt vastgelegd om te zien
op welke branchstand review en verificatie zijn gedaan, zodat een latere push dat bewijs stale
maakt. Per story is maximaal één muterende repositoryjob tegelijk actief.

### Rol naar Runtime-job

| Rol | Runtime-job | Resultaat |
|---|---|---|
| Refiner | `APPLICATION_WORK` / structured generation | Storyspecificatie of vragen. |
| Planner | `APPLICATION_WORK` / structured generation | Stapdefinities of vragen. |
| Developer | `REPOSITORY_WORK` / `REPOSITORY_AGENT` / `COMMIT_AND_PUSH` | Wijziging, groene verificatie, commit en push door de worker. |
| Reviewer | `APPLICATION_WORK` / `REPOSITORY_AGENT` / `NONE` | Oordeel over de opgehaalde branchstand. |
| Tester | `APPLICATION_WORK` / `REPOSITORY_AGENT` / `NONE`, browser en testcredentials | Testoordeel en bewijsartifacts. |
| Summarizer | `APPLICATION_WORK` / structured generation | Lange en korte samenvatting. |
| Documenter | `REPOSITORY_WORK` / `REPOSITORY_AGENT` / `COMMIT_AND_PUSH` | Documentatiewijziging of aantoonbaar geen impact. |
| Auditor | `APPLICATION_WORK` / `REPOSITORY_AGENT` / `NONE` op de base branch | Rapport, score, vraag of voorstel. |
| Telegram-assistent | `APPLICATION_WORK` zonder mutatiebevoegdheid | Antwoord of voorgesteld command. |

### Secrets

- Het projectlokale, gitignored `secrets.env` van targetprojecten wordt niet gelezen, gekopieerd,
  gemount, gesynchroniseerd of gewijzigd. Er komt geen netwerkshare of nieuw secretmechanisme.
- Providercredentials en Git-publicatiecredentials verdwijnen uit Software Factory en blijven bij
  de Runtime-worker. Zij komen nooit in prompts, transcripten, artifacts of logs.

## Stappen

| Stap | Naam | Resultaat |
|---:|---|---|
| 0 | Contractcontrole en probejobs | Bewezen Runtime-contract en werkende proefjobs. |
| 1 | Ontwerp van de vervanging | Vastgelegd wat waardoor vervangen wordt, plus jobcorrelatie en idempotentie. |
| 2 | Runtime-consumer voor niet-repositorywerk | Refiner, planner en summarizer draaien via Runtime. |
| 3 | Repositorywerk via Runtime | Developer, reviewer, tester, documenter en auditor via Runtime, met branch, PR en verificatie. |
| 4 | Oude runner verwijderen | Agentworker, Docker-runtime, workspaces en AI-level weg. |
| 5 | Topologie naar OpenShift (apart besluit) | Lokale orchestrator en bridge weg, rename en nieuwe host. |
| 6 | Oplevering | Pipeline groen, images, deployment gezond, echte story end-to-end, documentatie bij. |

## Stap 0 — Contractcontrole en probejobs

### Werk

1. Maak `docs/software-factory-v2/VOORTGANG.md` als enige live voortgangsbron.
2. Lees het Agent Runtime-prerequisitedocument en vergelijk het met het daadwerkelijk gepubliceerde
   `/v2`-OpenAPI-contract. Leg per vereiste vast: beschikbaar, ontbrekend of bewezen.
3. Controleer expliciet of de verificatie-in-de-job aanwezig is; zo niet, blokkeer het afhankelijke
   deel van stap 3 zichtbaar.
4. Dien probejobs in voor alle beoogde taaktypen, inclusief `MOCK`.
5. Bewijs met een tijdelijke testrepository: remote branch aanmaken door Software Factory zonder
   checkout, opeenvolgende jobs die alleen via die branch samenwerken, workercommit/push, read-only
   gebruik, `NO_CHANGES`, `BRANCH_CHANGED`, één PR door Software Factory, artifacts, cancel, timeout
   en verloren response.
6. Controleer de repositoryaliassen van de worker via het consumerendpoint.

### Exitcriteria

- Iedere benodigde Runtime-capability is als beschikbaar, ontbrekend of bewezen geclassificeerd.
- Twee opeenvolgende jobs werken aantoonbaar samen zonder lokale workspace of gedeeld volume.
- Er is geen productie- of targetrepository gewijzigd door een spike.

## Stap 1 — Ontwerp van de vervanging

Kort en concreet: een besluit per te vervangen onderdeel, geen nieuwe architectuurdocumentatie.

### Werk

1. Leg per bestaande klasse vast wat ermee gebeurt: vervangen, blijft, of weg. Minimaal
   `DockerAgentRuntime`, `runtime/workspaces`, `AgentResultFileCompletionPoller`,
   `DurableCompletionCoordinator`, `AgentRunCompletionService`, `TesterVerificationRunner`,
   `TargetRepositoryFlow` en de AI-CLI-clients.
2. Ontwerp de jobcorrelatie: één agentstap ↔ één Runtime-job, idempotentiesleutel,
   statusreconciliatie via polling met SSE als versnelling, en herstel na restart.
3. Bepaal wat er met `agent_runs` en `agent_events` gebeurt nu Runtime transcripten, attempts en
   kosten bijhoudt: behouden, verwijzen of afslanken.
4. Bepaal hoe de bestaande prompts naar job-instructies gaan, inclusief de nieuwe regels over tests
   groen achterlaten en geen Git-acties uitvoeren.
5. Bepaal het eigenaarschap van branch- en PR-aanmaak in `GitHubCliClient` en waar dat in de
   bestaande pipeline wordt aangeroepen.
6. Inventariseer alle plekken die een story-workspace of gedeeld filesystem tussen stappen
   veronderstellen, en bepaal per plek de vervanging.
7. Ontwerp de modelconfiguratie: per agentrol een `vendorId`, `model` en `mode`, met een
   standaard, per project te overschrijven, en op elk moment te wijzigen zonder herbouw of
   herstart. Beschrijf tegelijk het verwijderen van `aiLevel` en `AiRouting`.
8. Bepaal hoe quota vanuit Runtime op de bestaande wachtstatus wordt gemapt.

### Exitcriteria

- Voor elk te vervangen onderdeel staat vast waardoor het vervangen wordt.
- Er is geen open ontwerpvraag meer die een implementerende agent zelf zou moeten invullen.
- Robbert is akkoord.

## Stap 2 — Runtime-consumer voor niet-repositorywerk

### Doel

Laat de eerste agentrollen via Agent Runtime lopen, zonder repositorywerk, zodat de correlatie- en
herstellogica bewezen is voordat Git erbij komt.

### Werk

1. Bouw de Runtime-consumer: jobs aanmaken met expliciete `vendorId`, `model`, `mode` en `taskType`,
   hervatbare uploads voor prompt, schema en attachments, events, resultaat, artifacts, usage en
   annulering.
2. Maak jobaanmaak idempotent en reconcilieer status periodiek.
3. Valideer resultaatschema, artifactdeclaraties, MIME, grootte en SHA vóór publicatie in het
   domein.
4. Map quota op de bestaande wachtstatus; houd Runtime-attempts en de eigen domeinretry
   onderscheiden.
5. Zet refiner, planner en summarizer om naar Runtime-jobs.
6. Toon Runtime-jobstatus, events en fouten in de bestaande agent-/logschermen.
7. Gebruik in acceptatie uitsluitend `mock/mock/MOCK`.

### Exitcriteria

- Een verloren create-response, een uploadonderbreking of een restart maakt geen tweede Runtime-job.
- Een ongeldig resultaat wordt niet als geslaagde stap gepubliceerd.
- Refine, plan en samenvatting werken end-to-end via Runtime, met vragen en hervatting zoals nu.

## Stap 3 — Repositorywerk via Runtime

### Doel

Verplaats alle repositorygebonden rollen naar Runtime-jobs op één remote storybranch, met de PR en
het verificatiebewijs in eigen beheer.

### Werk

1. Laat Software Factory per story een unieke remote storybranch aanmaken vanaf de base branch, via
   de Git-providergrens en zonder checkout. Maak dat idempotent.
2. Dien developer en documenter in als `REPOSITORY_WORK` met `taskType=REPOSITORY_AGENT`, de
   geregistreerde alias, de bestaande branch en publicatiemodus `COMMIT_AND_PUSH`. Stuur geen
   repository-URL, base branch, lokale map, Gitcredential of verwachte input-SHA mee.
3. Start per story nooit meer dan één muterende repositoryjob tegelijk.
4. Verwerk het AI-resultaat en het `repositoryResult` als twee losse gegevensdelen. Behandel
   `BRANCH_CHANGED`, een ambigue publicatie en een ontbrekend repositoryresultaat als expliciete
   toestand, niet als succes.
5. Maak na de eerste succesvolle push idempotent precies één PR van de storybranch naar de base
   branch en koppel die aan de story.
6. Zet reviewer, tester en auditor om naar `APPLICATION_WORK` met `REPOSITORY_AGENT` en
   publicatiemodus `NONE` op de actuele branch. Registreer `checkoutCommitSha` als de beoordeelde
   branchstand, zodat een latere push het oordeel stale maakt.
7. Neem het verificatiebewijs uit het Runtime-resultaat over en valideer het onafhankelijk van
   agentproza, met dezelfde controles die `TesterVerificationEvidenceValidator` nu doet.
8. Laat rood, timeout, ontbrekende tooling of bewijs voor een verouderde branchstand nooit
   passeren.
9. Verwijder alle aannames over een gedeelde story-workspace tussen stappen; iedere vervolgstap
   krijgt zijn context uit de branch en uit het domein.
10. Behoud de bestaande begrensde developer/reviewer/testloopbacks en de zichtbare blocker na de
    cap.
11. Behoud de bestaande mergegate en deployketen ongewijzigd; alleen de bron van het bewijs
    verandert.

### Exitcriteria

- Iedere wijziging is gekoppeld aan één story, branch, commit en PR.
- Er bestaat precies één storybranch en één PR, ongeacht het aantal jobs.
- Geen agent heeft zelf gebranchd, gecommit, gepusht of een PR gemaakt.
- Reviewer- en testeroordelen vervallen zichtbaar na een nieuwe push.
- Een rode verificatie bereikt de reviewer niet: de job faalt en pusht niet.
- Restart tussen branch-aanmaak, job, push, PR en correlatie maakt geen tweede branch of PR.
- De volledige keten werkt op een echte testrepository zonder lokale checkout in Software Factory.

## Stap 4 — Oude runner verwijderen

### Werk

1. Verwijder de `agentworker`-module inclusief AI-CLI-clients, `CliProcessRunner`,
   `TaskFileManager`, `TargetRepositoryFlow`, `TesterPreviewFlow` en `TesterVerificationRunner`.
2. Verwijder `runtime/docker`, `runtime/workspaces`, `AgentResultFileCompletionPoller` en de
   completion via resultbestanden.
3. Verwijder `Dockerfile.agent` en de agent-image-stap uit de build.
4. Verwijder providercredentials, Docker-socketgebruik en workspace-instellingen uit configuratie,
   `properties.env`-sjablonen en deployment.
5. Verwijder `aiLevel` en `AiRouting`, inclusief de testfixtures die het veld zetten; de
   modelkeuze komt uit de nieuwe configuratie.
6. Ruim de retentie- en opruimlogica op die alleen bestond voor lokale workspaces en
   resultbestanden.
7. Werk `README.md`, `runbook.md`, `docs/factory/*` en `docs/technical/*` bij naar de nieuwe
   uitvoering.

### Exitcriteria

- De Maven-reactor bouwt zonder `agentworker`.
- Er is geen Docker-socket, geen story-workspace en geen providercredential meer nodig.
- Er staat nergens meer documentatie die de oude runner beschrijft.

## Stap 5 — Topologie naar OpenShift (apart besluit)

Deze stap is niet nodig om de runtimewissel af te ronden en staat daarom apart. Zonder eigen
agentcontainers en zonder story-workspaces heeft de lokale orchestrator geen technische reden meer
om lokaal te draaien, dus het kán — maar het is de enige stap met een datamigratie en met werk
buiten deze repository.

### Werk als het doorgaat

1. Verplaats de orchestrator naar de bestaande `dashboard-backend` op OpenShift.
2. Verwijder de uitgaande WebSocketbridge en de bijbehorende reconnect-/offline-afhandeling.
3. Richt Postgres op OpenShift in; vandaag draait de database lokaal via
   `docker/docker-compose.yml` met een lokaal volume.
4. Migreer de afgeronde stories naar die database; de rest hoeft niet mee. Bewijs de migratie op
   een kopie voordat zij echt draait, en maak vooraf een back-up.
5. Hernoem `dashboard-backend` naar `software-factory-backend` en `dashboard-frontend` naar
   `software-factory-frontend`, inclusief module, artifact, image, Deployment en Service.
6. Publiceer `softwarefactory.vdzonsoftware.nl` als primaire route en houd
   `dashboard.vdzonsoftware.nl` tijdelijk als redirect of alias, met behoud van pad en querystring.
   DNS regelt Robbert zo nodig in Cloudflare; de SSO-redirectconfiguratie beweegt mee.
7. Behoud alle bestaande API-paden en deeplinks.

### Exitcriteria

- Er draait geen Software Factory-proces meer op een laptop; alleen de Agent Runtime-worker mag daar
  blijven.
- Er bestaat geen runtime-WebSocketverbinding meer tussen twee Software Factory-backends.
- De afgeronde stories zijn aantoonbaar mee, met een back-up vooraf.
- De nieuwe host werkt en bestaande bookmarks blijven tijdens de overgang werken.

## Stap 6 — Oplevering

### Werk

1. Laat `[skip ci]` los en maak `verify.yml` groen op `main`.
2. Bouw de images en breng de deployment gezond op de bedoelde omgeving.
3. Herstel de Product Factory-integratie: status, create, get/list en cancel werken weer volgens het
   bestaande contract, met alle bestaande validatie, attachments, packagehash en idempotentie.
4. Draai één echte story end-to-end op een testrepository met de echte Runtime-worker: refine, plan,
   developer, review, tester, verificatie, documenter, approval, merge en deploy.
5. Draai een hotfix end-to-end.
6. Draai een audit end-to-end, inclusief een voorgestelde vervolgstory.
7. Controleer de Telegram-meldingen, vragen en commands.
8. Werk de resterende documentatie bij en archiveer wat de oude uitvoering beschrijft.

### Exitcriteria

- De volledige pipeline is groen op `main` en de images staan gezond op de bedoelde omgeving.
- Eén echte story, één hotfix en één audit zijn volledig door de nieuwe uitvoering heen gekomen.
- Product Factory werkt weer volgens het bestaande contract.
- Alle actuele documentatie beschrijft de nieuwe uitvoering.

## Testscenario's

Minimaal deze scenario's zijn geautomatiseerd voordat stap 6 klaar is:

1. complete standaardstory zonder menselijke interventie;
2. refiner stelt vragen en hervat exact dezelfde sessie;
3. developer wordt door reviewer afgewezen, pullt opnieuw en herstelt op dezelfde branch en PR;
4. verificatie faalt in de job, de agent herstelt binnen drie rondes en pusht daarna;
5. verificatie blijft na drie rondes rood: de job faalt en er is niets gepusht;
6. documenter meldt geen documentatie-impact en levert `NO_CHANGES`;
7. de drie approvalmodi, inclusief reject;
8. hotfix met exact developer, merge en deploy;
9. hotfix met rode verificatie start geen merge;
10. `questionsAllowed` uit resulteert in de bestaande zichtbare clarificationfout;
11. Product Factory-create is idempotent met attachments en verloren response;
12. quota of geen beschikbare worker wordt wachtstatus en hervat later;
13. Runtime-jobtimeout en late gefencete completion worden veilig verwerkt;
14. crash vóór en na jobcreatie, resultaatpublicatie, push, PR-aanmaak, merge en ieder
    deploymenttarget;
15. `BRANCH_CHANGED` leidt tot een nieuwe job op de actuele branch en nooit tot force-push;
16. reviewbewijs is stale na een latere push en wordt niet hergebruikt;
17. pause, resume, kill, retry, re-implement en clear-error op geldige en ongeldige momenten;
18. annulering vóór uitvoering, tijdens AI-wacht en na PR maar vóór merge;
19. audit stelt een vraag, hervat en maakt maximaal één voorstel;
20. Telegramdelivery en reply-idempotentie;
21. twee projecten parallel zonder cross-projectlekkage;
22. de volledige keten gebruikt geen Software Factory-checkout, gedeelde map of netwerkvolume;
23. een lokaal gitignored `secrets.env` van het targetproject wordt niet gelezen, gekopieerd,
    gemount, gewijzigd of naar Runtime gestuurd.

## Bewijs per stap

Iedere stap is klaar wanneer voor haar scope:

1. de geraakte Mavenmodules bouwen en hun tests groen zijn;
2. `flutter analyze` en `flutter test` groen zijn bij frontendimpact;
3. nieuwe migraties vanaf leeg en boven op de bestaande database slagen;
4. de moduledependencychecks geen ongeautoriseerde grens openen;
5. idempotentie, restart en retry aantoonbaar getest zijn;
6. `./quality/run.sh` niet verslechtert en geen nieuwe suppressie toevoegt;
7. secrets niet voorkomen in Git, logs, events, prompts, transcripten, artifacts of testfixtures;
8. de documentatie alleen werkelijk geïmplementeerd gedrag beschrijft;
9. `VOORTGANG.md` de commit, tests, bewijs en volgende startgate bevat.

De volledige reactor, de pipeline, de images en de deployment gelden pas in stap 6 als verplicht
bewijs.

## Risico's

| Risico | Beheersing |
|---|---|
| De verificatie-in-de-job komt er niet | Stap 0 stelt dat vast; zolang die ontbreekt draait de verificatie niet en blijft dat deel van stap 3 zichtbaar geblokkeerd. Geen eigen runner terugbouwen. |
| Verborgen aannames over een gedeelde workspace | Stap 1 inventariseert ze expliciet, stap 3 haalt ze weg, scenario 22 bewaakt het. |
| Dubbele Runtime-job of dubbele push na crash | Idempotentiesleutel per logische stap, reconciliatie en de Runtime-publicatie-intentie. |
| Prompts verliezen impliciete context uit de oude workspace | Prompts worden bij de omzetting één voor één nagelopen; de agent krijgt een verse checkout plus expliciete context uit het domein. |
| De refactor groeit alsnog uit tot een herbouw | Alles buiten de expliciet genoemde onderdelen blijft; opportunistische refactors worden niet meegenomen. |
| Kosten en transcripten raken versnipperd | Stap 1 beslist wat `agent_runs` en `agent_events` nog doen nu Runtime usage en transcripten bijhoudt. |
| De pipeline is aan het eind ver weggezakt | Stap 6 begint met de pipeline groen maken. |

## Buiten scope

- Een nieuw domeinmodel of het vervangen van het `issues`/tracker-model.
- Herverdeling van modulegrenzen of een capabilitysplitsing.
- Audits, kennis, maintenance, merge, Telegram of het dashboard herschrijven.
- Agent Runtime intern wijzigen vanuit deze repository.
- Product Factory of targetapplicaties aanpassen.
- Het projectlokale `secrets.env`-mechanisme wijzigen.
- Een gedeelde netwerkmap of blijvende repositoryworkspace tussen Factory en Runtime.
- Projectconfiguratie verplaatsen naar Postgres.

## Klaar wanneer

De overstap is klaar wanneer alle agentwerk via Agent Runtime `/v2` loopt; er geen `agentworker`,
Docker-uitvoering, story-workspace of providercredential meer in Software Factory zit;
repositorywerk uitsluitend in tijdelijke Runtime-checkouts gebeurt en via één remote storybranch per
story wordt overgedragen; de verificatie binnen de job draait en een rode uitkomst niet gepusht
wordt; Software Factory zelf de storybranch en de ene PR beheert en de bestaande mergegate en
deployketen ongewijzigd werken; audits, Telegram, kennis, maintenance en dashboard blijven doen wat
ze deden; de pipeline groen is op `main`; en één echte story, één hotfix en één audit aantoonbaar
door de nieuwe uitvoering heen zijn gekomen.
