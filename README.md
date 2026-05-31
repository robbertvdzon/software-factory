# Software Factory

Lokale setup voor de Software Factory applicatie, de agentworker build en de
lokale Docker services.

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
agent-base:local
agent-tester:local
```

Zonder deze stap faalt een agent-run met een Docker melding dat
`agent-base:local` of `agent-tester:local` niet gevonden wordt.

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

