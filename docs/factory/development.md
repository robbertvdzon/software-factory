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
- Werk het relevante `docs/stories/<key>-description.md` bestand bij tijdens
  implementatie.
