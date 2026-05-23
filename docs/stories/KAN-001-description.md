# KAN-001 - Projectbasis, Config En Database Bootstrap

Story:
Als developer wil ik een werkende lokale Spring Boot/Kotlin basis met
projectconfiguratie en database-migraties, zodat alle volgende factory-features
op een stabiele basis gebouwd kunnen worden.

Subtaken:
[x]: Spring Boot/Kotlin/Maven project opzetten
[x]: Config-model maken voor Jira, GitHub, database, Docker, AI en runtime paths
[x]: Repo-root `secrets.env`, `secrets.env.example` en `.gitignore` inrichten
[x]: Start-wrapper `./factory start` maken
[x]: Flyway + Neon/Postgres schema `software_factory` aanleggen

Stappen:
[x]: create Maven Kotlin Spring Boot skeleton
[x]: add basic application entrypoint
[x]: implement `SF_` prefixed secrets loader
[x]: read `./secrets.env` with system environment fallback
[x]: fail fast when required config is missing
[x]: redact secrets in startup logging
[x]: add unit tests for file-load, env-fallback and fail-fast behavior
[x]: verify with `mvn test`
[x]: add database dependencies
[x]: create initial Flyway migration for the `software_factory` schema
[x]: add local start wrapper

Done / rationale:
- De applicatiebasis, `SF_` config-loader en repo-root secrets setup zijn
  geimplementeerd zodat de factory lokaal veilig kan starten zonder secrets in
  git.
- De database bootstrap gebruikt Flyway op het expliciete schema
  `software_factory`. Dat schema wordt door de applicatie aangemaakt en
  gemigreerd; het bestaande schema `factory` wordt bewust geweigerd in de
  configuratievalidatie.
- De `./factory` wrapper geeft een vaste lokale entrypoint voor starten en
  testen, zodat volgende stories dezelfde manier van uitvoeren kunnen gebruiken.
- `mvn test` is groen met secrets-loader tests voor file-load, env-fallback,
  fail-fast gedrag, schema-validatie en redactie van database-URL's.
- `./factory start` is uitgevoerd tegen de lokale `secrets.env`; daarna is in
  Postgres geverifieerd dat `software_factory` de Flyway history en de eerste
  factory-tabellen bevat.
