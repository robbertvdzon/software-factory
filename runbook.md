# Runbook — Software Factory

Dit runbook beschrijft de actuele uitvoering na de overstap naar Agent Runtime v2. Het
OpenShift-doelbeeld en de latere rename staan apart in
[`docs/software-factory-v2/topologie-naar-openshift.md`](docs/software-factory-v2/topologie-naar-openshift.md).

## Onderdelen en topologie

- `softwarefactory`: Kotlin/Spring-orchestrator, tracker, pipeline, Telegram, audits, maintenance
  en de integratie met Agent Runtime v2. In de huidige tussenfase draait dit proces nog lokaal.
- `dashboard-backend`: remote API en WebSocketbridge, op OpenShift.
- `dashboard-frontend`: Flutter-webinterface, op OpenShift.
- PostgreSQL: duurzame factorydata en Flyway-migraties.
- Agent Runtime v2: alle AI-uitvoering. De Runtime-worker mag op een MacBook draaien; Software
  Factory heeft zelf geen worker, Docker-socket, providercredential of repositorycheckout meer.

De lokale orchestrator maakt een uitgaande WebSocketverbinding naar `dashboard-backend`. Die bridge
verdwijnt pas in het afzonderlijke topologieplan wanneer de orchestratorfunctionaliteit naar de
backend op OpenShift verhuist.

## Story- en Git-flow

De normale keten is:

`refine → plan → develop → review → test → summary → document → approval → merge → deploy`

Software Factory reserveert per story één remote branch vanaf de geconfigureerde base branch en
maakt na de eerste push precies één pull request. Iedere repositoryjob haalt diezelfde remote branch
vers op. De Runtime-worker voert checkout, verificatie, commit en push uit. Het AI-proces wijzigt
bestanden, maar maakt geen branch, commit, push of PR. Review- en testerbewijs is gekoppeld aan de
daadwerkelijk uitgecheckte commit-SHA en vervalt als de branch daarna verandert.

Projectverificatie komt uit `.factory/verification.yaml` (`version: 1`) in het targetproject. Bij
muterende jobs draait Agent Runtime de commands na de AI-ronde, geeft rood bewijs terug aan dezelfde
agent en probeert maximaal het geconfigureerde aantal herstelrondes. Alleen groen bewijs kan worden
gepusht. `NO_CHANGES` is een geldige uitkomst; `BRANCH_CHANGED`, timeout en ontbrekend of ongeldig
bewijs zijn geen succes.

De orchestrator wordt bij relevante wijzigingen direct gewekt; het vaste poll-interval uit
`SF_POLL_INTERVAL_MS` blijft het vangnet wanneer geen wake-signaal binnenkomt.

## Lokaal bouwen, testen en draaien

Vereisten: JDK 21, Maven, Flutter voor frontendwerk, en Docker alleen voor Testcontainers/lokale
PostgreSQL. Docker wordt niet gebruikt om AI-agents uit te voeren.

```bash
# gerichte Maven-build
mvn -B --no-transfer-progress -pl softwarefactory -am test

# volledige reactor en integratietests
mvn -B --no-transfer-progress verify

# frontend
tools/verify-dashboard-frontend

# lokale ondersteunende services en app
./factory local-services
./factory start
./factory local-services-stop
```

De Spring-app luistert standaard op poort 8080. Flyway migreert het ingestelde schema bij start.
Applicatielogs staan onder `logs/`.

`factory-loop.sh` is alleen de huidige lokale proceswrapper (`git pull` en opnieuw starten). Hij
bouwt geen agentimages. De dashboardacties restart/stop sturen dit lokale proces aan; ze worden in
het latere OpenShift-topologieplan vervangen.

## Configuratie en secrets

`SecretsEnvLoader` laadt, van laag naar hoog:

1. `properties.default.env` — committed defaults;
2. `properties.env` — lokale, gitignored overrides;
3. `secrets.env` — lokale, gitignored secrets;
4. echte environmentvariabelen — hoogste prioriteit.

`projects.yaml` koppelt projectnamen aan geregistreerde Runtime-repositoryaliassen, base branches,
Telegramkanalen, previews en verplichte mergechecks.

Belangrijkste secrets:

- `SF_GITHUB_TOKEN`: branch-, PR-, merge- en releasehandelingen door Software Factory;
- `SF_DATABASE_URL` en `SF_DATABASE_SCHEMA`: PostgreSQL;
- `SF_AGENT_RUNTIME_TOKEN`: tenanttoken voor Agent Runtime v2;
- `SF_TELEGRAM_BOT_TOKEN` en chat-id's: optionele Telegramintegratie;
- `SF_FACTORY_API_TOKEN`: machinecalls op de lokale factory;
- `SF_PRODUCT_FACTORY_TOKEN`: Product Factory-integratie;
- `SF_KUBECONFIG`: alleen waar Software Factory previews/deployments of cleanup op OpenShift
  bestuurt;
- dashboardsecrets zoals Google client-id, e-mailallowlist, remember-secret en bridge-token.

AI-providercredentials en Gitcredentials van de Runtime-worker horen niet in Software Factory.
Het gitignored `secrets.env` van een targetproject wordt niet gelezen, gemount, gekopieerd,
gesynchroniseerd of gewijzigd. Dat bestaande projectsecretmechanisme verandert niet.

Zie [`docs/factory/secrets-local.md`](docs/factory/secrets-local.md) voor de volledige lijst.

## Agent Runtime controleren

- De actieve modelkeuze staat in `agent_role_execution_config`, globaal of per project/rol. De
  eerstvolgende job gebruikt de nieuwe keuze; een lopende job houdt zijn bestaande uitvoering.
- `agent_runs.runtime_job_id` correleert één logische agentstap met één Runtime-job.
- Runtime-status, events, fouten, artifacts, usage en kosten worden via `/v2` opgehaald en in de
  bestaande schermen geprojecteerd.
- Een verloren create-response of procesrestart hoort dezelfde idempotente job te hervinden.
- Quota/capaciteit wordt als zichtbare wachtstatus behandeld; een Runtime-attempt is niet hetzelfde
  als een nieuwe domeinrun.

Bij problemen: controleer eerst Runtime-jobstatus en `errorCode`, daarna de Software Factory-log en
de story/subtaakstatus. Maak bij `BRANCH_CHANGED` nooit een force-push; laat de pipeline een nieuwe
job op de actuele branch plannen.

## Telegram

Telegram levert configureerbare storymeldingen, vragen/antwoorden en een conversationele
assistent. De assistent draait per beurt als `APPLICATION_WORK`/`STRUCTURED_GENERATION` op Runtime
v2. Foto's gaan via het inputobjectprotocol. `/stop` annuleert de lopende Runtime-job.

De assistent heeft bewust geen directe tracker-, repository-, cluster-, browser- of secrettoegang.
Hij denkt mee en maakt voorstellen, maar claimt geen actuele status en voert geen actie uit.

Als de melding “Sessie verlopen. Log opnieuw in.” verschijnt zonder inlogmogelijkheid, behandel dat
als dashboard-authenticatieprobleem; het staat los van de Runtime-worker. Controleer cookie-,
Google-login- en backendconfiguratie en of de frontend een 401 naar de loginroute afhandelt.

## Product Factory-integratie

`/api/integrations/v1` ondersteunt status, create, get/list, answers en cancel. Een
`Idempotency-Key` hoort bij het bestaande Product Factory-HTTP-contract; de interne Runtime-job
heeft daarnaast zijn eigen duurzame correlatie.

- `400`: ongeldig verzoek; pas het verzoek aan, blind opnieuw sturen helpt niet.
- `401`: controleer het gedeelde Product Factory-token.
- `503`: de lokale factory is in de huidige topologie niet aan de bridge verbonden; retry kan
  zinvol zijn.
- `404`/`502`: factory- of bridgefout.
- `500`: serverfout; inspecteer backend- en factorylogs.

## Merge, preview en deploy

De PR is de drager van GitHub-checks en preview-identiteit. Automatische en handmatige merge lopen
door dezelfde projectpolicy en gebruiken de actuele PR-head. Een nieuwe push maakt eerder bewijs
ongeldig. Previews en deployments blijven Software Factory-domeinlogica; Agent Runtime kent geen
story-, approval-, merge- of deploysemantiek.

## Audits en maintenance

- Audits draaien read-only via Runtime op de base branch, schrijven een getypeerd rapport en
  kunnen maximaal één vervolgstory voorstellen. Een auditstatus `asked` is terminaal voor die job;
  na beantwoording plant de factory een vervolgrun.
- `MaintenanceCleanupScheduler` ruimt volgens `projects.yaml` oude releases en packageversies op.
- Agent-event-, agent-run- en completionretentie blijven databasehistorie opruimen.
- `WorkCleanupPoller` is alleen een achtervang voor overige tijdelijke `work/`-bestanden; er zijn
  geen actieve story- of agentworkspaces meer.

## Veelvoorkomende storingen

- **Story wordt niet opgepakt:** controleer `Repo`, storyfase, pauze/wachtstatus, fout en of al een
  run actief is.
- **Agent blijft wachten:** inspecteer Runtime-capaciteit/quota en de opgeslagen retrytijd; start
  niet handmatig opnieuw vóór die tijd.
- **Verificatie rood:** lees `verificationResult`; herstel test, tooling, timeout of config. Zet de
  poort niet fail-open.
- **Merge wacht:** controleer de exacte namen in `merge.requiredChecks` en checks op de actuele
  PR-head.
- **Preview/deploy faalt:** controleer de GitHub-pipeline, deploymenttargetconfig en zo nodig de
  minimaal gescopeerde kubeconfig.
- **Restart/cancel:** een herstart moet de bestaande `runtime_job_id` reconciliëren; een late
  completion na cancel mag door fencing geen domeinsucces publiceren.

## Conventies

- Code, commentaar en commits zijn Nederlands.
- Actuele gedragsdocumentatie beschrijft Runtime v2; oude ontwerpen horen expliciet als historisch
  gemarkeerd te zijn.
- Werk nooit in een blijvende gedeelde checkout. Repositoryoverdracht tussen jobs loopt uitsluitend
  via de remote storybranch.
