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
- `SF_DATABASE_SCHEMA`

`SF_DATABASE_SCHEMA` moet `software_factory` zijn. Gebruik niet het schema
`factory`; dat schema bestaat al in de gedeelde Neon database en hoort bij een
ander systeem.

Orchestrator tuning gebruikt ook `SF_` env-vars. Defaults:

- `SF_ORCHESTRATOR_POLLING_ENABLED=false` totdat de Docker runtime actief is.
- `SF_POLL_INTERVAL_MS=15000`
- `SF_MAX_PARALLEL_REFINER=1`
- `SF_MAX_PARALLEL_DEVELOPER=2`
- `SF_MAX_PARALLEL_REVIEWER=2`
- `SF_MAX_PARALLEL_TESTER=1`
- `SF_MAX_PARALLEL_TOTAL=4`
- `SF_MAX_DEVELOPER_LOOPBACKS=5`
- `SF_MAX_TRANSIENT_RETRIES=2`
- `SF_AGENT_HARD_TIMEOUT_MINUTES=60`

## Ontwerpregels

- Orchestrator-state blijft idempotent en herstelbaar.
- Jira blijft de zichtbare workflow-bron voor gebruiker en agents.
- Postgres is de bron voor run history, event logging en agent knowledge.
- Agents werken in tijdelijke clones en schrijven alleen via hun toegestane rol.
- Gebruik kleine, gerichte tests rond state-machine, config en adapters.
