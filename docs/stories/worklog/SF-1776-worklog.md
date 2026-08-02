# SF-1776 - Worklog

Story-context bij eerste pickup:
Aanmaak-default meldingen naar 'als-klaar-en-gedeployed'

Verschuif de aanmaak-default van de meldingen-as van 'als-klaar' (NotifyMode.WHEN_DONE) naar 'als-klaar-en-gedeployed' (NotifyMode.WHEN_DONE_AND_DEPLOYED) op alle default-lagen:

1. DB: nieuwe Flyway-migratie (eerstvolgend vrij nummer, nu V27__notify_mode_default_deployed.sql) met uitsluitend `ALTER TABLE ${schema}.issues ALTER COLUMN notify_mode SET DEFAULT 'als-klaar-en-gedeployed';`. GEEN UPDATE/backfill van bestaande rijen (AC 6). Controleer bij aanvang of V27 intussen bezet is.
2. softwarefactory (Kotlin): core/contracts/WorkflowModels.kt:237 (TrackerIssueFields.notifyMode), dashboard/models/DashboardCommands.kt:16 (CreateStoryCommand.notifyMode), bridge/services/BridgeRequestHandler.kt:140 (fallback voor ontbrekende notifyMode in story.create).
3. softwarefactory: dashboard/services/DashboardCommandService.kt:101 - vervang `if (command.notifyMode != NotifyMode.WHEN_DONE.trackerValue) setNotifyMode(...)` door het ALTIJD wegschrijven van command.notifyMode na tracker.createStory(...). Nodig voor AC 5: anders is een expliciet gekozen 'als-klaar' niet te onderscheiden van 'niets gekozen'.
4. dashboard-backend: bridge/BridgeApiController.kt:607 - default van notifyMode in de create-story request body.
5. dashboard-frontend: lib/screens/stories_screen.dart:381 (initiele waarde _notifyMode in het Nieuwe-story-dialoog) en :519 (null-fallback van de dropdown-onChanged); lib/screens/story_detail_screen.dart:432 (weergave-fallback). Alle vier de dropdown-waarden blijven beschikbaar; alleen de voorselectie verschuift.
6. Documentatie: docs/factory/functional-spec.md:67 en docs/factory/technical-spec.md:243 - noem 'als-klaar-en-gedeployed' als default; nergens meer 'default als-klaar'.

NIET aanpassen (bewust): NotifyMode.fromTracker (WorkflowModels.kt:92) en TelegramNotificationService.kt:86 (getOrDefault(NotifyMode.WHEN_DONE)) blijven op WHEN_DONE - dat zijn leespaden voor bestaande data en een fail-safe bij mislukte parent-lookup, geen aanmaak-default; meeveranderen zou gedrag van bestaande story's wijzigen (AC 6). Zet er een korte comment bij die deze asymmetrie uitlegt.

Tests (onderdeel van deze subtaak): werk bestaande tests bij die de oude default vastpinnen (o.a. softwarefactory BridgeRequestHandlerTest, dashboard-backend BridgeApiControllerTest, dashboard-frontend story_detail_screen_test.dart) en voeg dekking toe voor: (a) story aangemaakt zonder expliciete notifyMode via de DB-route krijgt 'als-klaar-en-gedeployed', (b) bridge story.create zonder notifyMode-parameter idem, (c) een expliciet gekozen 'als-klaar' wordt ook echt als 'als-klaar' opgeslagen.

Eindcontrole: `grep -rn "als-klaar\"" en op NotifyMode.WHEN_DONE` om te bevestigen dat geen default-laag is gemist, en draai tools/verify-repository (build, Detekt/ratchet, unit- en e2e-tests, flutter analyze + flutter test) groen. Sluit af met een zelfreview van de eigen diff.

Stappenplan:
[x]: read issue and target docs
[x]: DB-migratie V27 (alleen kolomdefault, geen backfill)
[x]: Kotlin-defaults (WorkflowModels, DashboardCommands, BridgeRequestHandler, DashboardCommandService)
[x]: dashboard-backend request-body-default
[x]: dashboard-frontend aanmaakdialoog + weergave-fallback
[x]: documentatie (functional-spec, technical-spec)
[x]: tests schrijven/bijwerken (DB-route, bridge-route, REST-route, Flutter)
[x]: volledig vangnet draaien
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## SF-1783 — implementatie (developer)

### Wijzigingen

- **DB (leidend voor de API-/Telegram-route):** nieuwe migratie
  `softwarefactory/src/main/resources/db/migration/V27__notify_mode_default_deployed.sql` met
  uitsluitend `ALTER TABLE ${schema}.issues ALTER COLUMN notify_mode SET DEFAULT
  'als-klaar-en-gedeployed';`. V27 was nog vrij (hoogste bestaande migratie was V26). Bewust geen
  `UPDATE`/backfill (AC 6). Dit is de leidende laag omdat `PostgresTrackerClient.createStory`
  `notify_mode` niet in de INSERT opneemt — `TrackerStoryApiController.create` en
  `AuditGatewayAdapter.proposeStoryIfAny` erven daardoor de kolomdefault (AC 2 en AC 4).
- **softwarefactory (Kotlin):** aanmaak-defaults naar `NotifyMode.WHEN_DONE_AND_DEPLOYED` in
  `core/contracts/WorkflowModels.kt` (`TrackerIssueFields.notifyMode`),
  `dashboard/models/DashboardCommands.kt` (`CreateStoryCommand.notifyMode`) en
  `bridge/services/BridgeRequestHandler.kt` (fallback voor een ontbrekende `notifyMode`-parameter
  in `story.create`).
- **`DashboardCommandService.createStory`:** de "sla de set-call over als de waarde de default
  is"-vergelijking voor de meldingen-as is vervangen door het ALTIJD wegschrijven van
  `command.notifyMode` na `tracker.createStory(...)`. Zonder dat is een expliciet gekozen
  `als-klaar` niet te onderscheiden van "niets gekozen" (AC 5). De `approvalMode`-tak is bewust
  ongemoeid gelaten (andere as, buiten scope).
- **dashboard-backend:** `bridge/BridgeApiController.kt` — `CreateStoryRequest.notifyMode` default
  naar `als-klaar-en-gedeployed`.
- **dashboard-frontend:** `screens/stories_screen.dart` — initiële `_notifyMode` en de
  null-fallback van de dropdown; `screens/story_detail_screen.dart` — de weergave-fallback. Alle
  vier de waarden blijven in de keuzelijsten staan; alleen de voorselectie verschuift.
- **Bewust NIET gewijzigd (met toelichtende comments):** `NotifyMode.fromTracker` en
  `TelegramNotificationService` r86 (`getOrDefault(NotifyMode.WHEN_DONE)`) blijven op `als-klaar`.
  Dat zijn leespaden voor bestaande data resp. een fail-safe bij een mislukte parent-lookup;
  meeveranderen zou het meldingsgedrag van bestaande story's wijzigen (in strijd met AC 6).

### Specs bijgewerkt

- `docs/factory/functional-spec.md` (As 3): aanmaak-default is nu `als-klaar-en-gedeployed`, met
  expliciete vermelding dat de lees-fallback `als-klaar` blijft.
- `docs/factory/technical-spec.md` (`notify_mode`-kolom): kolomdefault `als-klaar-en-gedeployed`
  sinds V27, inclusief de reden dat de kolomdefault leidend is en dat er geen backfill is.

### Tests (zelf geschreven, onderdeel van deze subtaak)

- `softwarefactory` `TrackerCapabilityPersistenceE2eTest` (Testcontainers, echte Flyway-migraties):
  een story aangemaakt zonder expliciete `notifyMode` krijgt `als-klaar-en-gedeployed` (assertie op
  het domeinobject én rechtstreeks op de DB-kolom), en een expliciet gezette `als-klaar` blijft
  staan.
- `softwarefactory` `NotifyModeDefaultMigrationTest` (nieuw): pint vast dat V27 uit precies één
  `ALTER COLUMN ... SET DEFAULT`-statement bestaat en geen `UPDATE`/`DELETE` bevat (AC 6).
- `softwarefactory` `BridgeRequestHandlerTest` + `BridgeTestFixtures`: `FakeTrackerApi` ondersteunt
  nu `createStory` en registreert álle veldschrijfacties; twee nieuwe tests voor `story.create`
  zonder `notifyMode` (→ `als-klaar-en-gedeployed`) en mét expliciete `als-klaar`.
- `softwarefactory` `FactoryDashboardServiceTest`: de twee `createStory`-tests keken naar
  `lastFieldUpdate`/`lastUpdatedKey` en braken doordat de meldingen-as nu altijd als laatste
  weggeschreven wordt. Ze asserteren nu per veld over de volledige schrijfhistorie, en controleren
  er meteen bij dat `NOTIFY_MODE` op de nieuwe default gezet wordt.
- `dashboard-backend` `BridgeApiControllerTest`: `POST /api/v1/stories` zonder `notifyMode` in de
  body stuurt `als-klaar-en-gedeployed` door; mét expliciete `als-klaar` blijft dat `als-klaar`.
- `dashboard-frontend` `test/screens/stories_screen_test.dart` (nieuw): het aanmaakdialoog toont
  `Als klaar en gedeployed` als voorselectie, verstuurt die stand zonder tussenkomst, en een
  expliciet gekozen `Als klaar` komt ook echt als `als-klaar` op de POST terecht. Mutatiecheck
  gedaan: met de oude default vallen alle drie de tests om.
- `dashboard-frontend` `test/screens/story_detail_screen_test.dart`: een story zonder
  `notifyMode`-veld toont `Als klaar en gedeployed`; de bestaande wissel-test (AC 7) is ongewijzigd
  groen.

### Bewijs

- `mvn -B --no-transfer-progress clean verify` vanaf de repo-root: **BUILD SUCCESS**, exitcode 0,
  0 failures / 0 errors (16 + 55 + 694 + 76 + 60 + 53 tests over alle modules).
- `flutter analyze`: `No issues found!`. `flutter test`: 115/115 groen. `pubspec.lock` ongewijzigd.
- `tools/generate-module-dependencies --check`: actueel. `tools/audit-documentation`: PASS.
- `./quality/run.sh` (ratchet) is rood met 4 blocking findings — alle vier in bestanden die deze
  diff niet raakt (`AgentCli.kt`, `TelegramAuditQuestionService.kt`, `DeploySubtaskHandler.kt`,
  `AuditSeeding.kt`). Pre-existent op main, niet door deze story veroorzaakt en niet in
  `.factory/verification.yaml` opgenomen.
