# Development

## Build En Test

```bash
mvn -f softwarefactory/pom.xml test
mvn -f agentworker/pom.xml test
mvn -f dashboard-backend/pom.xml test
```

Of vanaf de root alle Maven-modules (`softwarefactory`, `agentworker` en
`dashboard-backend`):

```bash
mvn test
```

De Flutter dashboard-frontend (`dashboard-frontend/`) staat los van de Maven
build en heeft een eigen Flutter/Dart-toolchain; lokaal is daar geen Flutter SDK
voor nodig omdat de Docker build een Flutter builder-image gebruikt.

```bash
mvn -f softwarefactory/pom.xml spring-boot:run
```

De applicatie start alleen als de verplichte `SF_*` configuratie aanwezig is in
`./secrets.env` of in de system environment.

## Lokale Postgres

Start lokale Postgres in Docker:

```bash
./factory local-db
```

Gebruik daarna in `secrets.env`:

```env
SF_DATABASE_URL=postgresql://software_factory:software_factory@localhost:5432/software_factory
SF_DATABASE_SCHEMA=software_factory_dev
```

Voor een aparte story/branch kun je een eigen schema kiezen, bijvoorbeeld
`software_factory_sf_020`. Flyway maakt het schema aan als het nog niet bestaat.

## Structuur

- `softwarefactory/src/main/kotlin`: software-factory applicatiecode (orchestrator
  + ingebouwd HTML-dashboard).
- `softwarefactory/src/test/kotlin`: software-factory unit tests en de
  e2e-/pipeline-integratietests (package `...e2e`, op basis van `E2eTestBase`
  met `@SpringBootTest`, Testcontainers Postgres, `FakeYouTrackServer`,
  `TestAgentRuntime`/`AgentScript`, `FactoryUiDriver` en Awaitility).
- `agentworker/src/main/kotlin`: los startbare agent worker code.
- `dashboard-backend/src/main/kotlin`: los Spring Boot dashboard-backend dat een
  JSON-API levert bovenop de factory-database, YouTrack en GitHub.
- `dashboard-frontend/lib`: Flutter (Dart) dashboard-frontend dat die JSON-API
  consumeert; geen Maven-module, eigen Flutter-toolchain/Docker build.
- `softwarefactory/pom.xml`: Maven build voor de web/orchestrator applicatie.
- `agentworker/pom.xml`: Maven build voor de agentworker container.
- `dashboard-backend/pom.xml`: Maven build voor de dashboard-backend.
- `pom.xml`: root aggregator over de drie onafhankelijke Maven-builds
  (`softwarefactory`, `agentworker`, `dashboard-backend`).
- `specs/specs.md`: volledige productspecificatie.
- `docs/factory`: agent-context voor deze repo.
- `docs/stories`: definitieve story-documentatie.
- `docs/stories/worklog`: story worklogs en implementatieplannen.

## Conventies

- Kotlin, JDK 21, Spring Boot en Maven.
- Houd stories klein en testbaar.
- Nieuwe configuratie krijgt altijd een `SF_` env-var prefix.
- Importeer expliciet per type; gebruik geen project-interne wildcard-imports
  (`import nl.vdzon.softwarefactory.<pkg>.*`).
- Secrets worden nooit gelogd behalve geredigeerd.
- Werk het relevante `docs/stories/worklog/<key>-worklog.md` bestand bij
  tijdens implementatie.
