# KAN-001 - Projectbasis, Config En Database Bootstrap

Story:
Als developer wil ik een werkende lokale Spring Boot/Kotlin basis met
projectconfiguratie en database-migraties, zodat alle volgende factory-features
op een stabiele basis gebouwd kunnen worden.

Subtaken:
[x]: Spring Boot/Kotlin/Maven project opzetten
[x]: Config-model maken voor Jira, GitHub, database, Docker, AI en runtime paths
[x]: Repo-root `secrets.env`, `secrets.env.example` en `.gitignore` inrichten
[ ]: Start-wrapper `./factory start` maken
[ ]: Flyway + Neon/Postgres schema `factory` aanleggen

Stappen:
[x]: create Maven Kotlin Spring Boot skeleton
[x]: add basic application entrypoint
[x]: implement `SF_` prefixed secrets loader
[x]: read `./secrets.env` with system environment fallback
[x]: fail fast when required config is missing
[x]: redact secrets in startup logging
[x]: add unit tests for file-load, env-fallback and fail-fast behavior
[x]: verify with `mvn test`
[ ]: add database dependencies
[ ]: create initial Flyway migration for the `factory` schema
[ ]: add local start wrapper

Done / rationale:
- De applicatiebasis, `SF_` config-loader en repo-root secrets setup zijn
  geimplementeerd zodat de factory lokaal veilig kan starten zonder secrets in
  git.
- De database bootstrap en start-wrapper zijn nog open en horen bij dezelfde
  foundation story.
