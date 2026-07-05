# Software Factory

Lokale setup voor de Software Factory applicatie, de agentworker build en de
lokale Docker services.

## Leeswijzer

Lees in deze volgorde, afhankelijk van wat je zoekt:

1. [runbook.md](runbook.md) — operatie: waar draait wat, config/secrets, troubleshooting.
2. [docs/factory/functional-spec.md](docs/factory/functional-spec.md) — **wat** de factory doet (functioneel).
3. [docs/factory/technical-spec.md](docs/factory/technical-spec.md) — **hoe** het gebouwd is (stack, modules, config).
4. [docs/onboarding-senior-developer.md](docs/onboarding-senior-developer.md) — onboarding voor nieuwe (senior) ontwikkelaars: het mentale model, de hoofdflow, de waarom's achter de architectuur, teststrategie, kookboekjes en een review-checklist.
5. [docs/kwaliteitsanalyse.md](docs/kwaliteitsanalyse.md) — de kwaliteitsanalyse en de recente refactor (fase 1 t/m 4, juli 2026).

Daarnaast: [docs/technical/](docs/technical/) (gegenereerde technische naslag) en
[specs/specs.md](specs/specs.md) (historisch archief).

## Procesoverzicht

De Software Factory werkt met een **twee-laags model** in YouTrack:

1. **Story-niveau** (`Story Phase`, zie `core/StoryPhase.kt`) — het refinement-proces:
   een refiner scherpt de story aan, een planner maakt een implementatieplan en
   declareert de subtaken.
2. **Subtaak-niveau** (`Subtask Type` + `Subtask Phase`, zie `core/TrackerModels.kt`
   en `core/SubtaskPhase.kt`) — de uitvoering: de gedeclareerde subtaken worden één
   voor één op een gedeelde story-branch uitgevoerd.

Werk start expliciet: een story (of subtaak) wordt **pas opgepakt als de fase op
`start` staat**; een lege fase betekent "nog niet starten". Er zijn geen
work-tags/labels meer. De repo waaraan gewerkt wordt komt uit het `Repo`-veld op de
story, dat verwijst naar een project in `projects.yaml` (zie §1b).

### Story-niveau: refine en plan

```mermaid
flowchart TD
    START["start"] --> REF["refining<br/>(refiner-agent)"]
    REF -->|"vragen"| REFQ["refined-with-questions<br/>wacht op antwoord gebruiker"]
    REFQ -->|"questions-answered"| REF
    REF --> REFINED["refined"]
    REFINED -->|"goedkeuring (of auto-approve)"| REFAPP["refined-approved"]
    REFAPP --> PLAN["planning<br/>(planner-agent)"]
    PLAN -->|"vragen"| PLANQ["planned-with-questions<br/>wacht op antwoord gebruiker"]
    PLANQ -->|"planning-questions-answered"| PLAN
    PLAN --> PLANNED["planned<br/>subtaken worden aangemaakt"]
    PLANNED -->|"goedkeuring (of auto-approve)"| PLANAPP["planning-approved"]
    PLANAPP -->|"Start developing"| INPROG["in-progress<br/>subtaak-keten draait"]
```

Afkeuren kan ook: `refined-rejected`/`planning-rejected` sturen de refiner/planner
opnieuw op pad met de afkeurreden.

### Subtaak-niveau: de keten

De planner declareert subtaken van het type `development`, `review`, `test`,
`manual` en `summary`. De factory dwingt daarnaast per story altijd deze
afsluitende subtaken af (in `SubtaskPlanMaterializer`):

- `documentation` — documenter-agent werkt de docs bij (altijd aan);
- `manual-approve` — handmatige goedkeur-poort vóór de merge (per project uit te
  zetten via `projects.yaml`; vervalt bij `Silent`-stories);
- `merge` — automatische squash-merge van de story-PR;
- `deploy` — deploy volgens `projects.yaml` (skip / rest-restart / openshift-watch).

> **Nightly config-pad (SF-787):** een nightly-job kan zijn subtaken declaratief
> vastleggen in `.factory/nightly/<job>/subtasks.yaml`. Dan slaat de factory refine +
> plan over en materialiseert exact de gedeclareerde subtaken — de bovenstaande
> afsluitende subtaken worden dan *niet* automatisch afgedwongen (de config is leidend).
> Zie `.factory/nightly/README.md`.

```mermaid
flowchart LR
    DEV["development"] --> REV["review"] --> TEST["test"] --> SUM["summary"]
    SUM --> DOC["documentation"] --> APPR["manual-approve"] --> MERGE["merge"] --> DEP["deploy"]
```

Elke AI-subtaak doorloopt op het `Subtask Phase`-veld hetzelfde patroon:
`start → *-ing → (*-with-questions ↔ *-questions-answered) → *-ed → *-approved`
(of `*-rejected` voor een loopback naar de developer). Zodra een subtaak zijn
terminale fase bereikt, zet de keten de volgende subtaak op `start`. Bij
`Auto-approve` (of `Silent`) lopen de goedkeurstappen automatisch door; de
`manual-approve`-poort vraagt altijd een mens (behalve bij `Silent`). Een
test-bevinding (`test-rejected`) reset de hele keten, met een cap van
`SF_MAX_TEST_CHAIN_RESETS` (default 3).

Tijdens uitvoering staat het werkdocument in
`docs/stories/worklog/<key>-worklog.md`; de summarizer levert de eindtekst en de
factory schrijft het definitieve document naar `docs/stories/<key>-<slug>.md`.

## Maven-modules

De root-`pom.xml` is een aggregator met vier Maven-modules:

- **`factory-common`** — gedeelde code (git, github, docs/skeleton, preview,
  support, `AgentRole`, het agent-result-contract, `ProjectRepoResolver`).
- **`softwarefactory`** — de hoofdapplicatie: orchestrator, pipeline, YouTrack,
  ingebouwd HTML-dashboard, Telegram, nightly.
- **`agentworker`** — de CLI die in de agent-Docker-container draait.
- **`dashboard-backend`** — JSON-API voor de Flutter `dashboard-frontend`
  (die zelf buiten de Maven-build valt).

Tests zijn gesplitst: `mvn test` draait de snelle unit-run; `mvn verify` draait
daarbovenop de e2e-/Testcontainers-tests (vereist draaiende Docker).

## Vereisten

- JDK 21
- Maven
- Docker Desktop of een werkende Docker Engine
- GitHub token met toegang tot de target repositories
- Voor de dashboard frontend is lokaal geen Flutter SDK nodig; de Docker build
  gebruikt een Flutter builder image.

## 1. Secrets Maken

Maak in de root van deze repo een lokale `secrets.env`:

```bash
cp secrets.env.example secrets.env
```

Vul daarna minimaal deze waarden in:

```env
SF_YOUTRACK_TOKEN=...
SF_GITHUB_TOKEN=...
```

De example is al ingesteld op de lokale Docker services:

```env
SF_YOUTRACK_BASE_URL=http://localhost:9700
SF_DATABASE_URL=postgresql://software_factory:software_factory@localhost:5432/software_factory
SF_DATABASE_SCHEMA=software_factory_dev
```

De applicatie polt YouTrack altijd zodra hij draait. Zorg dus dat YouTrack,
PostgreSQL en de verplichte secrets kloppen voordat je de applicatie start.

## 1b. Projecten → repo's koppelen

De repo waaraan een story werkt komt niet meer uit de YouTrack-projectbeschrijving,
maar uit een config-bestand naast `secrets.env`:

```bash
cp projects.yaml.example projects.yaml
```

Vul daarin per logisch project een naam en git-repo in:

```yaml
projects:
  - name: personal-feed
    repo: git@github.com:robbertvdzon/personal-feed.git
```

Op een story kies je in het **`Repo`**-veld (een multi-select dropdown in het rechterpaneel)
één van deze projectnamen; de factory gebruikt de bijbehorende repo. De keuzes komen rechtstreeks
uit `projects.yaml`. Eén YouTrack-project kan zo stories voor meerdere repo's bevatten; subtaken
erven automatisch de repo van hun parent-story. Een story met een leeg `Repo`-veld wordt niet
opgepakt en krijgt een `Error`.

Het `Repo`-veld wordt bij opstart automatisch in YouTrack aangemaakt en de keuzelijst wordt
gesynchroniseerd met `projects.yaml`. Het veld is **multi-value** (je kunt meerdere repo's kiezen),
maar de engine gebruikt voorlopig nog de eerste keuze — echte multi-repo-verwerking volgt later.
Welke YouTrack-projecten gescand worden, bepaalt `SF_YOUTRACK_PROJECTS` (leeg = alle). Het pad van
het config-bestand is te overschrijven met `SF_PROJECTS_FILE`.

## 2. Docker Services Starten

Start PostgreSQL, YouTrack, dashboard-backend en dashboard-frontend:

```bash
docker compose up -d --build
```

PostgreSQL draait daarna op `localhost:5432`.

YouTrack draait op:

```text
http://localhost:9700
```

Het externe dashboard draait op:

```text
http://localhost:9080
```

De dashboard-backend is direct bereikbaar op:

```text
http://localhost:9090
```

Bij een verse YouTrack installatie vraagt YouTrack om een wizard token. Haal die
uit de logs:

```bash
docker compose logs -f youtrack
```

Maak na de wizard een permanent token in YouTrack en zet dat in
`SF_YOUTRACK_TOKEN` in `secrets.env`. Start daarna de dashboard-backend opnieuw
als die al gestart was:

```bash
docker compose up -d --build softwarefactory-dashboard-backend
```

## 3. Code Bouwen

Bouw en test de Maven projecten vanaf de root. Snelle unit-run:

```bash
mvn test
```

Volledig vangnet inclusief e2e-/Testcontainers-tests (Docker vereist):

```bash
mvn verify
```

Of bouw packages:

```bash
mvn package
```

De Flutter dashboard frontend staat los van de Maven build.

## 4. Agent Images Bouwen

De software factory start agent-runs via lokale Docker images. Bouw deze op
elke machine waarop je de hoofdapplicatie draait:

```bash
./factory build-images
```

Dit maakt:

```text
agent:local
```

Eén gedeelde image voor alle agent-rollen. Zonder deze stap faalt een agent-run
met een Docker melding dat `agent:local` niet gevonden wordt.

## 5. Software Factory Starten

Start de applicatie vanaf de root, zodat `./secrets.env` gevonden wordt:

```bash
mvn -f softwarefactory/pom.xml spring-boot:run
```

Of gebruik het helper-script:

```bash
./factory start
```

De lokale webinterface draait standaard op:

```text
http://localhost:8080
```

## Handige Commands

Lokale AI coding agent met Ollama + OpenHands starten:

```bash
LOCAL_WORKSPACE="$(pwd)" docker compose -f docker/local-ai/docker-compose.yml up -d --build
```

Zie [docker/local-ai/README.md](docker/local-ai/README.md) voor de volledige
setup en gebruiksinstructies.

Alle lokale services starten:

```bash
docker compose up -d --build
```

Alle lokale services stoppen:

```bash
docker compose stop
```

Alleen PostgreSQL starten:

```bash
./factory local-db
```

Alleen PostgreSQL stoppen:

```bash
./factory local-db-stop
```

YouTrack logs volgen:

```bash
docker compose logs -f youtrack
```

## 6. YouTrack configureren

- Maak een nieuw project aan (bijv. met het Scrum-template).
- De factory maakt haar custom fields (`Story Phase`, `Subtask Phase`, `Repo`,
  `AI-supplier`, …) bij het opstarten zelf aan via de schema-bootstrap.
- Op een story: kies een `Repo` (uit `projects.yaml`, zie §1b), zet
  `AI-supplier` (bijv. `claude` of `mock`) en zet `Story Phase` op `start` om
  hem op te laten pakken. Labels of een git-url in de projectbeschrijving zijn
  niet meer nodig.