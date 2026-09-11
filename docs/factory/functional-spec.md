# Functionele specificatie

## Doel

Software Factory zet een story om in geteste, gereviewde en gedocumenteerde software. De factory
beheert de workflow; Agent Runtime v2 voert afzonderlijke AI-jobs uit. Een Runtime-job kent geen
story-, approval-, merge- of deploysemantiek.

## Stories en fasen

Een story wordt alleen opgepakt wanneer het `Repo`-veld naar een project uit `projects.yaml` wijst
en de storyfase startbaar is. De standaardketen is:

1. refiner scherpt scope en acceptatiecriteria aan;
2. planner maakt uitvoerbare subtaken;
3. developer wijzigt code;
4. reviewer beoordeelt de actuele branchstand;
5. tester controleert gedrag en bewijs;
6. summarizer maakt lange en korte samenvattingen;
7. documenter verwerkt documentatie-impact;
8. approvalgate vraagt zo nodig menselijke goedkeuring;
9. factory mergt de pull request;
10. deploy wordt gevolgd tot alle toepasselijke targets gezond zijn.

Een agent mag vragen stellen. De story of subtaak blijft dan zichtbaar wachten en hervat na het
antwoord dezelfde logische stap. `questionsAllowed=false` maakt een ontbrekende beslissing tot een
zichtbare clarificationfout.

## Branch en pull request

Iedere story heeft maximaal één remote storybranch en één pull request naar de geconfigureerde base
branch (doorgaans `main`). De factory maakt branch en PR idempotent. Alle muterende jobs werken
achtereenvolgens op diezelfde branch; geen filesystem wordt tussen jobs gedeeld.

De Runtime-worker verzorgt checkout, verificatie, commit en push. De AI-agent zelf voert geen
muterende Git-actie uit. De factory bewaart geen targetrepositorycheckout en ontvangt geen
provider- of Gitcredential van de worker.

Een veranderde branch geeft `BRANCH_CHANGED`, nooit force-push. `NO_CHANGES` is geldig wanneer de
rol aantoonbaar niets hoeft te publiceren.

## Verificatie en reviewbewijs

Een targetproject declareert deterministische verificatie in `.factory/verification.yaml`. Na een
muterende AI-ronde draait Runtime alle toepasselijke commands. Bij rood bewijs krijgt dezelfde agent
de diagnose en maximaal het geconfigureerde aantal herstelrondes. Er wordt pas gecommit en gepusht
als het bewijs groen is.

Software Factory valideert `verificationResult` onafhankelijk van agentproza. Missing tool,
timeout, non-zero exitcode, incomplete evidence of een revisionmismatch kan niet passeren.
Reviewer- en testerresultaten bevatten `checkoutCommitSha`; een latere push maakt dat bewijs stale.

De bestaande developer/reviewer/testerloopbacks en caps blijven gelden. Na de cap verschijnt een
blokkerende fout en start merge/deploy niet.

## Modelkeuze en kosten

Per agentrol bestaat een globale executionconfiguratie en optioneel een projectoverride met
`vendorId`, `model` en `mode`. De eerstvolgende job gebruikt de actuele keuze; lopende jobs blijven
op hun eerder gekozen uitvoering. `aiLevel` bestaat niet meer.

Usage en kosten komen uit het Runtime-resultaat en worden bij de agentrun opgeslagen/getoond.
Runtime-attempts binnen één job blijven onderscheiden van een nieuwe domeinrun.

## Wachten, retries en herstel

- Een logische stap heeft één duurzame Runtime-jobcorrelatie.
- Een verloren create-response of factoryrestart maakt geen tweede job.
- Polling reconcilieert de status; events mogen de UI versnellen maar zijn geen enige bron.
- Quota of ontbrekende worker wordt een zichtbare wachtstatus met herprobeertijd.
- Cancel en timeout zijn terminaal voor de betreffende poging; late completions worden gefenced.
- Een crash rond commit/push wordt via het Runtime-repositoryresultaat gereconcileerd zonder tweede
  commit.

## Approval, merge, preview en deploy

De bestaande approvalmodi blijven: automatisch, menselijke goedkeuring of de projectspecifieke
variant. Reject leidt terug naar de daarvoor geldige fase.

De PR blijft nodig voor GitHub-checks en preview-identiteit. Merge gebruikt de actuele head en de
verplichte checks uit `projects.yaml`. Preview- en deploylogica blijft eigendom van Software
Factory; Agent Runtime voert daar geen workflowbeslissingen over uit.

## Hotfix

Een hotfix slaat refine, plan, review en documentatie over en doorloopt developer, merge en deploy.
Dezelfde deterministische verificatiepoort geldt. Rood bewijs of een bereikte loopbackcap blokkeert
merge.

## Audits

Audits draaien read-only via Runtime op de base branch. Definities blijven onder
`.factory/nightly/` in het targetproject. Een audit levert een getypeerd rapport, score en eventueel
maximaal één vervolgstory. Een vraag beëindigt de huidige auditjob als `asked`; na beantwoording
plant de factory een vervolgrun.

## Telegram

Telegram ondersteunt configureerbare meldingen, antwoorden op agentvragen en een conversationele
assistent. Iedere assistentbeurt is een niet-muterende
`APPLICATION_WORK`/`STRUCTURED_GENERATION`-job. Gesprekshistorie wordt begrensd meegegeven; een foto
gaat als Runtime-inputobject mee; `/stop` annuleert de lopende job.

De assistent heeft geen directe toegang tot tracker, repositories, secrets, browser of cluster. Hij
kan meedenken en een concreet voorstel maken, maar voert dat niet uit en verzint geen actuele
status. Herbruikbare tips worden via `KnowledgeApi` onder de rol `assistant` opgeslagen.

## Product Factory

De machine-API ondersteunt status, story create/get/list, antwoorden en cancel, met het bestaande
token-, validatie-, attachment-, packagehash- en idempotentiecontract. Product Factory levert
project/repositorymetadata als domeininput; dat geeft een agent nooit directe toegang tot een lokale
workspace.

## Secrets

Het projectlokale gitignored `secrets.env` van targetprojecten blijft volledig buiten Software
Factory en Runtime. Het wordt niet gelezen, gemount, gekopieerd, gewijzigd of gepusht. De eigenaar
kan het in de eigen gitmap blijven beheren zoals vóór de migratie.

AI-provider- en Gitpublicatiecredentials van de Runtime-worker staan alleen bij Runtime. Software
Factory heeft uitsluitend zijn eigen tokens voor GitHubworkflow, database, Runtime-tenant,
Telegram, bridge/Product Factory en waar nodig OpenShiftbeheer.

## Niet-functionele garanties

- Geen lokale of gedeelde storyworkspace.
- Geen Docker-socket nodig voor productie-uitvoering van Software Factory.
- Geen geheime waarden in prompts, transcripten, events, artifacts of logs.
- Idempotente branch-, job-, PR-, completion- en Product Factory-operaties.
- Eén story kan geen repositoryalias, branch of bewijs van een ander project publiceren.
- Errors en wachtstatussen zijn zichtbaar en mogen niet stil als succes doorgaan.
