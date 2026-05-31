# SF-043 - Pool Postgres connections

## Story

De orchestrator-poll duurt lokaal ruim 20 seconden. Bij debuggen met een thread
dump blijkt de scheduler-thread vast te staan in `DriverManagerDataSource` bij
het openen van een nieuwe PostgreSQL-connectie naar Neon tijdens
`JdbcProcessedCommentStore.isProcessed(...)`.

De factory moet runtime databaseverkeer via een connection pool doen, zodat
elke `JdbcTemplate` query niet opnieuw een TCP/TLS/Postgres-connect hoeft op te
zetten.

## Stappenplan

[x]: Start de service lokaal en bevestig de trage poll.
[x]: Neem een thread dump tijdens de lopende poll.
[x]: Identificeer de blokkerende call.
[x]: Vervang `DriverManagerDataSource` door HikariCP.
[x]: Vermijd processed-marker lookups voor comments zonder relevante factory-instructies.
[x]: Werk specs en technische docs bij.
[x]: Voeg een regressietest toe voor de datasource configuratie.
[x]: Draai de tests en meet de poll opnieuw.

## Uitwerking

De thread dump toonde `scheduling-1` in:

```text
org.postgresql.core.PGStream.createSocket
org.springframework.jdbc.datasource.DriverManagerDataSource.getConnection
JdbcProcessedCommentStore.isProcessed
CostMonitorService.applyBudgetTriggers
OrchestratorService.pollOnce
```

De root cause was dat `DriverManagerDataSource` geen pool gebruikt. De
applicatie gebruikt nu `HikariDataSource` met een kleine pool:

- `maximumPoolSize = 5`
- `minimumIdle = 1`
- `connectionTimeout = 10s`
- `idleTimeout = 10m`
- `maxLifetime = 30m`

Daarmee blijft ten minste een databaseconnectie warm en hoeft de poll niet per
comment-marker check opnieuw te verbinden met Neon.

Na de pool-fix bleek een tweede deel van de polltijd te komen uit
`YouTrackClient.hasProcessedCommentMarker(...)`. De manual-command en
cost-monitor flows controleerden eerst voor elke comment of die al verwerkt was,
en parseerden pas daarna of de comment uberhaupt een relevant command bevatte.
Die volgorde is omgedraaid: gewone comments en agent-comments zonder
`@factory`, `BUDGET=` of `CONTINUE` veroorzaken nu geen processed-marker HTTP
lookup meer.
