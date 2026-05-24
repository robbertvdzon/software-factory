# SF-020 - Local Postgres Runtime

## Story

Allow the factory to use either Neon Postgres or a local Postgres instance in
Docker by switching `SF_DATABASE_URL`, while keeping schemas isolated per
environment or development branch.

## Plan

[x]: document the story and implementation steps
[x]: relax `SF_DATABASE_SCHEMA` validation to allow safe non-`factory` schemas
[x]: add a Docker Compose Postgres service for local development
[x]: add a `./factory` helper command for the local database
[x]: update specs, docs and `secrets.env.example`
[x]: run tests and verify the local database can start

## Work Log

- Created this story document before changing code so the local database change
  has the same traceability as earlier implementation work.
- Relaxed schema validation: any valid Postgres identifier is accepted except
  `factory`, so Neon can use `software_factory` and local/branch work can use
  schemas like `software_factory_dev` or `software_factory_sf_020`.
- Added `docker-compose.yml` with the `postgres:16-alpine` image, database
  `software_factory`, user `software_factory`, password `software_factory`,
  a persistent Docker volume and healthcheck.
- Added `./factory local-db` and `./factory local-db-stop` helper commands.
- Fixed Postgres URL normalization so `postgresql://user:pass@host:port/db`
  works with the JDBC driver instead of being passed through as an invalid
  authority-style JDBC URL.
- Updated specs, factory docs and `secrets.env.example` with Neon-at-home and
  local-Docker-at-work examples.
- Verification: `mvn -q test` passed. `./factory local-db` started a healthy
  local Postgres container, and Flyway applied 4 migrations to a temporary
  `software_factory_smoke` schema with 7 tables before the smoke schema was
  dropped.
