# Development

## Build En Test

```bash
mvn -f softwarefactory/pom.xml test
mvn -f agentworker/pom.xml test
```

Of vanaf de root beide projecten:

```bash
mvn test
```

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

- `softwarefactory/src/main/kotlin`: software-factory applicatiecode.
- `softwarefactory/src/test/kotlin`: software-factory unit tests.
- `agentworker/src/main/kotlin`: los startbare agent worker code.
- `softwarefactory/pom.xml`: Maven build voor de web/orchestrator applicatie.
- `agentworker/pom.xml`: Maven build voor de agentworker container.
- `pom.xml`: root aggregator die alleen de twee onafhankelijke builds onder elkaar zet.
- `specs/specs.md`: volledige productspecificatie.
- `docs/factory`: agent-context voor deze repo.
- `docs/stories`: story logs en implementatieplannen.

## Conventies

- Kotlin, JDK 21, Spring Boot en Maven.
- Houd stories klein en testbaar.
- Nieuwe configuratie krijgt altijd een `SF_` env-var prefix.
- Secrets worden nooit gelogd behalve geredigeerd.
- Werk het relevante `docs/stories/<key>-<korte-omschrijving>.md` bestand bij
  tijdens implementatie.
