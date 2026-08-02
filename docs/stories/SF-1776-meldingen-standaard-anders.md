# SF-1776 - meldingen standaard anders

## Story

meldingen standaard anders

<!-- refined-by-factory -->

## Samenvatting

Bij het aanmaken van een nieuwe story staat de meldingen-instelling nu standaard op
'Als klaar'. Dat wordt 'Als klaar en gedeployed', zodat je pas een Telegram-bericht
krijgt als het resultaat ook echt live staat.

Dit geldt voor elke manier waarop een story ontstaat: via het dashboard, via Telegram
en via de API. Wie in het aanmaakscherm bewust een andere stand kiest, houdt die stand.

Bestaande story's veranderen niet; alleen nieuwe story's krijgen de nieuwe standaard.

## Scope

De nieuwe standaardwaarde van de meldingen-as (`NotifyMode`) wordt
`als-klaar-en-gedeployed` (`NotifyMode.WHEN_DONE_AND_DEPLOYED`) op alle plekken waar
vandaag `als-klaar` als aanmaak-default staat:

- **Database (leidend voor de API-/Telegram-route):** nieuwe Flyway-migratie (eerstvolgend
  vrij nummer, nu `V27__*.sql`) die de kolomdefault wijzigt:
  `ALTER TABLE ${schema}.issues ALTER COLUMN notify_mode SET DEFAULT 'als-klaar-en-gedeployed';`.
  Geen `UPDATE`/backfill van bestaande rijen.
  Reden dat dit de leidende plek is: `PostgresTrackerClient.createStory` neemt `notify_mode`
  niet op in de INSERT, dus `TrackerStoryApiController.create` (`POST /api/tracker/stories`,
  gebruikt door `tools/sf-story` en de Telegram-assistent) en
  `AuditGatewayAdapter.proposeStoryIfAny` erven de kolomdefault.
- **softwarefactory (Kotlin):**
  - `core/contracts/WorkflowModels.kt:237` — `TrackerIssueFields.notifyMode`-default.
  - `dashboard/models/DashboardCommands.kt:16` — `CreateStoryCommand.notifyMode`-default.
  - `dashboard/services/DashboardCommandService.kt:101` — de "sla de set-call over als de
    waarde de default is"-vergelijking moet meebewegen; equivalent en robuuster is de
    gekozen `notifyMode` altijd wegschrijven na `tracker.createStory(...)`.
  - `bridge/services/BridgeRequestHandler.kt:140` — fallback voor een ontbrekende
    `notifyMode`-parameter in `story.create`.
- **dashboard-backend:** `bridge/BridgeApiController.kt:607` — default van het
  `notifyMode`-veld in de create-story request body.
- **dashboard-frontend:** `screens/stories_screen.dart:381` (initiële waarde in het
  "Nieuwe story"-dialoog) en `:519` (null-fallback van de keuzelijst), plus de
  weergave-fallback in `screens/story_detail_screen.dart:432`.
- **Documentatie:** `docs/factory/functional-spec.md:67` ("default `als-klaar`") en
  `docs/factory/technical-spec.md:243` ("`notify_mode` (`TEXT`, default `'als-klaar'`)").
- **Tests:** bestaande tests die de oude default vastpinnen bijwerken, en dekking toevoegen
  voor de nieuwe default op minimaal de DB-route (story aangemaakt zonder expliciete
  `notifyMode`) en de bridge-`story.create`-route zonder `notifyMode`-parameter.

Buiten scope:

- Bestaande story's/subtaken migreren of hun huidige meldingen-stand wijzigen.
- De betekenis of het gedrag van de vier `NotifyMode`-waarden zelf, inclusief de
  QUESTION-uitzondering en de idempotentie van de "echt live"-melding.
- De andere twee assen (`questions_allowed`, `approval_mode`).
- De interpretatie-fallback voor onbekende/lege opgeslagen waarden (zie Aannames).

## Acceptance criteria

1. Een story die via het dashboard wordt aangemaakt zonder de meldingen-keuze aan te
   raken, heeft na aanmaken `notify_mode = als-klaar-en-gedeployed`; het aanmaakdialoog
   toont die stand ook als voorgeselecteerde waarde.
2. Een story die via `POST /api/tracker/stories` (Telegram-assistent, `tools/sf-story`,
   overige API-clients) wordt aangemaakt, heeft `notify_mode = als-klaar-en-gedeployed`.
3. Een story die via bridge-operatie `story.create` zonder `notifyMode`-parameter wordt
   aangemaakt, heeft `notify_mode = als-klaar-en-gedeployed`.
4. Een door de auditor voorgestelde vervolg-story krijgt eveneens
   `notify_mode = als-klaar-en-gedeployed`.
5. Kiest de gebruiker bij het aanmaken expliciet een andere stand (`geen`,
   `na-elke-stap`, `als-klaar`), dan wordt precies die stand opgeslagen — ook wanneer die
   gelijk is aan de vroegere default `als-klaar`.
6. Bestaande story's behouden na de migratie exact hun huidige `notify_mode`-waarde; de
   migratie wijzigt geen enkele bestaande rij.
7. Het wijzigen van de meldingen-stand achteraf (story-detail, `story.setNotifyMode`,
   `POST /api/v1/stories/{key}/notify-mode`) werkt onveranderd voor alle vier de waarden.
8. `docs/factory/functional-spec.md` en `docs/factory/technical-spec.md` noemen
   `als-klaar-en-gedeployed` als default; er staat nergens meer "default `als-klaar`".
9. `tools/verify-repository` (build, Detekt/ratchet, unit- en e2e-tests, flutter analyze +
   flutter test) is groen.

## Aannames

- "Meldingen standaard" verwijst naar as 3 uit SF-1261/SF-1234 (`TrackerField.NOTIFY_MODE`,
  enum `NotifyMode`); 'Als klaar en gedeployed' is de trackerwaarde
  `als-klaar-en-gedeployed` (`NotifyMode.WHEN_DONE_AND_DEPLOYED`).
- Alleen nieuw aangemaakte story's veranderen; er komt geen backfill, omdat de story
  expliciet over het aanmaakmoment gaat en een backfill de melding-instelling van
  lopende story's stilzwijgend zou omzetten.
- De interpretatie-fallbacks voor ontbrekende/onbekende opgeslagen waarden blijven
  `als-klaar`: `NotifyMode.fromTracker(null/onbekend)` en de fail-safe
  `getOrDefault(NotifyMode.WHEN_DONE)` in `TelegramNotificationService` (r86). Die paden
  gaan over het lezen van bestaande data en over een mislukte parent-lookup, niet over de
  keuze bij aanmaken; ze meeveranderen zou het gedrag van bestaande story's kunnen
  wijzigen (in strijd met AC 6).
- Alle vier de waarden blijven beschikbaar in de keuzelijsten; alleen de voorselectie
  verschuift.

## Eindsamenvatting

Nieuwe stories krijgen via dashboard, tracker-API/Telegram, bridge en auditvoorstellen standaard de meldingenstand `als-klaar-en-gedeployed`. De database-default, Kotlin-contracten, backend en Flutter-voorselectie zijn gelijkgetrokken. Een expliciet gekozen andere stand, waaronder `als-klaar`, wordt altijd opgeslagen. De langere dropdownwaarde veroorzaakt geen UI-overflow.

Flyway-migratie V29 wijzigt uitsluitend de kolomdefault. Bestaande stories zijn niet aangepast; historische migraties en leesfallbacks voor ontbrekende of onbekende waarden blijven bewust op `als-klaar`. Functionele, technische en UX-documentatie zijn bijgewerkt.

De volledige Maven-verificatie is groen: 972 tests zonder failures, errors of skips. PostgreSQL/Testcontainers paste alle 29 migraties toe en bevestigde de nieuwe database-default. Bridge-, backend- en Fluttertests dekten ontbrekende parameters, expliciete waarden, de voorselectie en achteraf wijzigen af. Flutter-analyse en documentatie-audit zijn eveneens groen. Er was geen preview-URL ingericht; merge en productie-deploy volgen in de volgende subtaken.
