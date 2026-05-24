# Development

## Build En Test

```bash
mvn test
```

```bash
mvn spring-boot:run
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

- `src/main/kotlin`: applicatiecode.
- `src/test/kotlin`: unit tests.
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
