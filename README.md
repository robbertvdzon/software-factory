# Software Factory

Lokale setup voor de Software Factory applicatie, de agentworker build en de
lokale Docker services.

## Procesoverzicht

De Software Factory zoekt in YouTrack naar stories die in `Develop` staan en
waarbij `AI supplier` niet leeg of `none` is. De orchestrator pakt zo'n story
op, maakt een story-run, workspace en branch aan en geeft het werk daarna door
aan het software team van agents.

### Story ophalen

```mermaid
flowchart LR
    subgraph YT["YouTrack"]
        PROJECT["Project<br/>bijv. PNF of SF"] --> STORIES["Stories"]
        STORIES --> CANDIDATE["Kandidaat-story<br/>Status: Develop<br/>AI supplier: mock/claude/copilot/openai/microsoft"]
    end

    CANDIDATE -->|"gevonden door poller"| ORCH["Orchestrator<br/>controleert velden, budget en actieve runs"]
    ORCH --> RUN["Story-run<br/>workspace + branch + agent metadata"]
    RUN --> WORKLOG["docs/stories/worklog<br/>runtime plan + agent notes"]
    RUN --> TEAM["Software team<br/>refiner, developer, reviewer, tester, summarizer"]
    ORCH -->|"AI Phase, workdir, errors, commands"| STORIES
    ORCH -->|"voor elke agent-run opnieuw ophalen"| STORIES
```

### Software team

```mermaid
flowchart TD
    RUN["Story-run van orchestrator"] --> REF["Refiner<br/>leest story, specs en repo-context"]
    REF -->|"refined-finished"| DEV["Developer<br/>implementeert de story in de workspace"]
    REF -->|"refined-with-questions-for-user"| WAITQ["Wacht op antwoord van gebruiker<br/>questions-answered-for-refinement"]
    WAITQ --> REF

    DEV -->|"developed"| REV["Reviewer<br/>controleert code, specs en wijzigingen"]

    REV -->|"review-finished"| TEST["Tester<br/>draait testen en valideert gedrag"]
    REV -->|"reviewed-with-feedback-for-developer"| DEV

    TEST -->|"tested-successfully"| SUM["Summarizer<br/>maakt eindsamenvatting"]
    TEST -->|"tested-with-feedback-for-developer"| DEV

    SUM -->|"summary-finished"| STORYDOC["Definitieve story-md<br/>docs/stories/&lt;key&gt;-&lt;slug&gt;.md"]
    STORYDOC --> READY["Klaar voor handmatige PO-test"]
    READY --> MANUAL["Handmatige actie<br/>sync, PR of merge"]
    MANUAL --> DONE["Story afgerond"]
```

Belangrijkste agent-uitkomsten:

| Agent | Uitkomsten |
| --- | --- |
| Refiner | `refined-finished`, `refined-with-questions-for-user` |
| Developer | `developed` |
| Reviewer | `review-finished`, `reviewed-with-feedback-for-developer` |
| Tester | `tested-successfully`, `tested-with-feedback-for-developer` |
| Summarizer | `summary-finished` |

Tijdens uitvoering staat het werkdocument in
`docs/stories/worklog/<key>-worklog.md`. Na een succesvolle tester-run maakt de
summarizer de eindtekst en schrijft de factory het definitieve document naar
`docs/stories/<key>-<slug>.md` met alleen de actuele YouTrack-story en de
eindsamenvatting.

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

Bouw en test de Maven projecten vanaf de root:

```bash
mvn test
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

## 6. Youtrack configureren
- Maak een nieuwe project aan, met Scrum template
- In descirption van het project: vul git url in
- Bij een story: voeg label 'AI' in om hem in AI beheer te zetten