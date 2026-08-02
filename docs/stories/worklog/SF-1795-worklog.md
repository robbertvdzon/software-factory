# SF-1795 — Nieuwe NotifyMode-aanmaakdefault door alle lagen

## Story in eigen woorden

Nieuwe stories moeten via iedere aanmaakroute standaard pas een Telegram-melding sturen nadat het
resultaat klaar én gedeployed/live bevestigd is. Een expliciet gekozen andere meldingenstand blijft
leidend en bestaande stories mogen door de migratie niet veranderen. De historische leesfallbacks
blijven daarom op `als-klaar` staan.

## Checklist

- [x]: Factory-instructies, storycontext en relevante specs gelezen.
- [x]: V29-migratie toegevoegd die alleen de databasekolomdefault wijzigt, zonder backfill.
- [x]: Kotlin-, bridge-, backend- en Flutter-aanmaakdefaults aangepast.
- [x]: Dashboard schrijft de gekozen meldingenstand altijd op, ook expliciet `als-klaar`.
- [x]: Functionele, technische en relevante UX-specificaties bijgewerkt.
- [x]: Regressietests voor database-, bridge-, backend-, dashboard- en Fluttergedrag toegevoegd.
- [x]: Gerichte tests uitgevoerd en hersteld tot groen.
- [x]: Volledige repositorygate `tools/verify-repository` met 0 failures/errors uitgevoerd.

## Uitvoering en keuzes

Migratie `V29__notify_mode_creation_default.sql` gebruikt uitsluitend `ALTER COLUMN ... SET
DEFAULT`; er staat bewust geen `UPDATE` in. Daardoor erven `PostgresTrackerClient.createStory`, de
tracker-API/Telegram-route en auditvoorstellen de nieuwe default, terwijl bestaande rijen exact
behouden blijven.

De defaults in `TrackerIssueFields`, `CreateStoryCommand`, bridge-handler, dashboard-backend en
Flutter zijn gelijkgetrokken. `DashboardCommandService` schrijft na story-aanmaak de gekozen
meldingenstand onvoorwaardelijk weg. Dit is nodig omdat een gebruiker nog steeds bewust de oude
default `als-klaar` kan kiezen; een optimalisatie die die write overslaat zou nu ten onrechte de
nieuwe database-default opslaan.

De Flutter-aanmaakdialoog gebruikt voor alle dropdowns de beschikbare breedte. Daarmee veroorzaakt
de langere nieuwe voorselectie ook op een begrensde dialoog geen horizontale renderoverflow.

Bijgewerkte specs: `functional-spec.md` beschrijft de aanmaaksemantiek en het behoud van bestaande
waarden; `technical-spec.md` beschrijft de nieuwe database-default en V29; UX `stories.md` legt de
voorselectie vast en `story-detail.md` de weergavefallback.

De historische migratie V20 en de interpretatiefallbacks `NotifyMode.fromTracker(...)` en
`TelegramNotificationService.getOrDefault(NotifyMode.WHEN_DONE)` zijn bewust niet aangepast: die
beschermen bestaande/ontbrekende data en zijn geen aanmaakdefaults.

## Verificatiebewijs

- Gerichte softwarefactory-tests: 105 tests groen, 0 failures, 0 errors.
- Gerichte dashboard-backendtest: 25 tests groen, 0 failures, 0 errors.
- Gerichte Fluttertest voor het storiescherm: 2 tests groen; `flutter analyze` zonder issues.
- `tools/verify-repository`: exitcode 0. De volledige Maven-reactor en E2E-suite, quality-ratchet,
  moduledependency-drift, alle 114 Fluttertests, mini-reactor-smoke, agent-image-build-stage en
  documentatie-audit zijn groen. Voor de image-build is een tijdelijke Docker 28.3.0-client gebruikt,
  omdat de checkoutcontainer wel een Docker 28.3.0-daemon/socket maar geen clientbinary bevatte.
