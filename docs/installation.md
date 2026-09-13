# Installation

Deze instructie installeert de Software Factory: één Spring-applicatie (`softwarefactory`) met
orchestrator, dashboard-API en Product Factory-integratie, de Flutter-frontend, en alle
AI-uitvoering via Agent Runtime v2. Hoofdapp, frontend en PostgreSQL draaien op OpenShift; Agent
Runtime v2 blijft een afzonderlijke externe component.

## Vereisten

- JDK 21;
- Maven 3.9+;
- Git en GitHub CLI waar de factory GitHubhandelingen uitvoert;
- PostgreSQL 16+ of Docker voor de meegeleverde lokale database;
- Flutter alleen voor frontendontwikkeling;
- bereikbare Agent Runtime v2 met een geregistreerde repositoryalias per project;
- een Runtime-worker met de benodigde execution image, providercredentials en Gitrechten.

Software Factory zelf heeft geen Docker-socket, providercredential, targetcheckout of blijvende
agentworkspace nodig. Docker Desktop is lokaal alleen nodig voor de optionele Compose-services en
Testcontainers.

## Repository en configuratie

```bash
git clone git@github.com:robbertvdzon/software-factory.git
cd software-factory
cp secrets.env.example secrets.env
```

Vul minimaal in:

```env
SF_GITHUB_TOKEN=
SF_DATABASE_URL=postgresql://software_factory:software_factory@localhost:5432/software_factory
SF_DATABASE_SCHEMA=software_factory_dev
SF_AGENT_RUNTIME_TOKEN=
```

Voor de dashboardlogin, Telegram, Product Factory en OpenShift zijn extra keys nodig; zie
[`factory/secrets-local.md`](factory/secrets-local.md). Gebruik `properties.env` voor niet-geheime
lokale overrides.

## Projectcatalogus

De catalogus staat in de database en wordt beheerd op het Settings-scherm van het dashboard, als
YAML in de vorm van `projects.yaml.example`. Bij een lege database importeert de factory eenmalig
een lokaal `projects.yaml` (of `SF_PROJECTS_FILE`) als dat bestaat. Configureer ieder targetproject
met ten minste:

- canonieke projectnaam en repository;
- base branch;
- geregistreerde Agent Runtime-repositoryalias;
- verplichte GitHub-checks voor merge;
- optioneel Telegramkanaal, preview, deploy en cleanup.

De alias moet op de beschikbare Runtime-worker bekend zijn. Software Factory stuurt nooit een
repositorycredential of lokaal pad mee.

Ieder actief targetproject bevat een geldige `.factory/verification.yaml`. Agent Runtime voert die
config binnen de muterende job uit en pusht alleen na groen bewijs.

Voor de Software Factory-repository heet de verplichte GitHub-mergecheck `Repository verification`.

## Lokale database en backend

```bash
./factory local-services
```

Dit start de Compose-services uit `docker/docker-compose.yml`. Stop ze met:

```bash
./factory local-services-stop
```

## Bouwen en starten

```bash
mvn -B --no-transfer-progress test
./factory start
```

Flyway migreert het schema bij start. De hoofdapp luistert standaard op poort 8080.

## Dashboardfrontend

```bash
cd dashboard-frontend
flutter pub get
flutter analyze
flutter test
flutter run -d chrome
```

Configureer Google-login, secure cookies en de toegestane e-mailadressen. Bij sessieverloop moet de
frontend opnieuw naar de loginflow kunnen navigeren.

## Installatie controleren

1. `GET /healthz` en `GET /api/version` van de app.
2. Controleer dat Flyway zonder fout op de laatste migratie staat.
3. `GET /api/v1/status` zonder token geeft 401; na Google-login geeft het versie en starttijd.
4. Vraag Runtime execution options en repositoryaliassen op.
5. Dien een `mock/mock/MOCK` structured-generationjob in.
6. Dien een read-only repositoryjob in op een testalias.
7. Dien een muterende testjob in en controleer verificatie, commit/push en cleanup.
8. Open het dashboard, laat de sessie verlopen en controleer dat opnieuw inloggen mogelijk is.

## Productie

Gebruik secretobjects/environmentvariabelen in plaats van gecommitte `.env`-bestanden. Geef
GitHub-, packagecleanup-, previewcleanup- en Runtime-tokens elk de kleinste eigen scope.

De actuele OpenShift-deployment en de bijbehorende operationele instructies staan in
[`../deploy/README.md`](../deploy/README.md).
