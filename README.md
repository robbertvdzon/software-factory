# Software Factory

Lokale setup voor de Software Factory applicatie, de agentworker build en de
lokale Docker services.

## Vereisten

- JDK 21
- Maven
- Docker Desktop of een werkende Docker Engine
- GitHub token met toegang tot de target repositories

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

Laat `SF_ORCHESTRATOR_POLLING_ENABLED=false` staan totdat YouTrack projecten,
GitHub toegang en de agent runtime goed staan. Dan start de applicatie wel,
maar verwerkt hij nog geen stories automatisch.

## 2. Docker Services Starten

Start PostgreSQL en YouTrack:

```bash
cd docker
docker compose up -d
cd ..
```

PostgreSQL draait daarna op `localhost:5432`.

YouTrack draait op:

```text
http://localhost:9700
```

Bij een verse YouTrack installatie vraagt YouTrack om een wizard token. Haal die
uit de logs:

```bash
cd docker
docker compose logs -f youtrack
```

Maak na de wizard een permanent token in YouTrack en zet dat in
`SF_YOUTRACK_TOKEN` in `secrets.env`.

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

## 4. Software Factory Starten

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

Alle lokale services starten:

```bash
./factory local-services
```

Alle lokale services stoppen:

```bash
./factory local-services-stop
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
cd docker
docker compose logs -f youtrack
```
