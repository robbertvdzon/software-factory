# SF-1796 — Story-brede test

## Scope

Getest tegen branch `ai/SF-1776`, HEAD `0f69c30`. Gecontroleerd is de nieuwe
`NotifyMode`-aanmaakdefault via database, tracker/API-afleiding, bridge en dashboard, plus behoud van
expliciete waarden en bestaande rijen. Er is voor deze repository geen preview-URL ingericht.

## Resultaten

- `mvn -B --no-transfer-progress verify`: exitcode 0, `BUILD SUCCESS` in 4:17.
  De XML-rapporten bevatten samen 972 tests, 0 failures, 0 errors en 0 skipped
  (710 softwarefactory-unit/integratie + 78 E2E, 16 contracts, 55 common, 61 agentworker en 52
  dashboard-backend).
- De PostgreSQL 16/Testcontainers-runs pasten alle 29 Flyway-migraties succesvol toe. De
  `TrackerCapabilityPersistenceE2eTest` bevestigde dat `createStory` zonder `notify_mode` de waarde
  `als-klaar-en-gedeployed` terugleest; 23/23 scenario's groen.
- Bridge- en dashboardgedrag: `BridgeRequestHandlerTest` 35/35 en
  `BridgeApiControllerTest` 25/25 groen. Hiermee zijn de ontbrekende bridgeparameter, de
  backend-requestdefault en expliciet `als-klaar` afgedekt.
- `flutter test test/screens/stories_screen_test.dart`: exitcode 0, 2/2 groen. Het dialoog toont de
  nieuwe voorselectie zonder renderexception en verstuurt die in de POST-payload zonder dat de
  gebruiker de meldingenkeuze aanraakt.
- `flutter test test/screens/story_detail_screen_test.dart`: exitcode 0, 10/10 groen, waaronder
  wijzigen van `als-klaar` naar `als-klaar-en-gedeployed` via het notify-mode-endpoint.
- `flutter analyze`: exitcode 0, geen issues.
- `tools/audit-documentation`: exitcode 0 (`documentation-audit/v1: PASS`). In `docs/factory` staat
  geen oude tekst `default als-klaar` meer.
- V29 bevat uitsluitend `ALTER COLUMN ... SET DEFAULT` en geen `UPDATE`; bestaande rijen worden dus
  niet gebackfilled. Historische V20- en leesfallbacks bleven conform de story ongewijzigd.

## Besluit

Alle acceptancecriteria zijn afgedekt en alle uitgevoerde gates en gedragstests zijn groen. Geen
flakes of regressies waargenomen.
