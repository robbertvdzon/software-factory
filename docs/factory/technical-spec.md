# Technical Spec

## Stack

- Kotlin
- JDK 21
- Spring Boot
- Maven
- Flyway voor DB-migraties
- Neon/Postgres
- Docker Engine voor agent-containers

## Config

Alle eigen environment variables beginnen met `SF_`. De loader leest eerst
`./secrets.env` en valt per key terug op system environment variables.

Verplichte keys:

- `SF_JIRA_BASE_URL`
- `SF_JIRA_EMAIL`
- `SF_JIRA_API_KEY`
- `SF_GITHUB_TOKEN`
- `SF_DATABASE_URL`

## Ontwerpregels

- Orchestrator-state blijft idempotent en herstelbaar.
- Jira blijft de zichtbare workflow-bron voor gebruiker en agents.
- Postgres is de bron voor run history, event logging en agent knowledge.
- Agents werken in tijdelijke clones en schrijven alleen via hun toegestane rol.
- Gebruik kleine, gerichte tests rond state-machine, config en adapters.
