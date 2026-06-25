# SF-206 - Worklog

Story-context bij eerste pickup:
Testrapport, screenshots en preview-URL in Telegram 'test-subtaak klaar'-melding

Breid TelegramNotificationService.notifySubtaskDone uit voor terminaal-geworden TEST-subtaken bij actieve auto-approve (detectie op subtaskType==test). Voeg aan de bestaande tekstmelding toe: (1) het testrapport van de tester, afgekapt ~1200 tekens; (2) de preview-/test-URL als regel wanneer niet-null; (3) de tester-screenshots als foto's via TelegramClient.sendPhoto. Belangrijk: eerst de tekst, dan recordNotified, daarna de foto's. Soft-fail overal. Niet-test-subtaken blijven ongewijzigd.

## Story in eigen woorden

Als een test-subtaak klaar en goedgekeurd is (auto-approve aan), wil ik dat de Telegram-melding
niet alleen het story-overzicht toont, maar ook het testrapport van de tester, de gemaakte
screenshots (als foto's) en — voor projecten met een preview — een klikbare preview-link. Voor
niet-test-subtaken mag er niets veranderen, en één ontbrekend onderdeel mag de melding nooit breken
of dubbele meldingen veroorzaken.

## Checklist

- [x]: read issue and target docs
- [x]: YouTrackApi.downloadAttachmentBytes (soft-fail default) + YouTrackClient-implementatie
- [x]: FactoryDashboardService.testerReportFor + previewUrlFor (publieke helpers)
- [x]: TelegramNotificationService — test-detectie, rapport + preview-link in tekst, screenshots als foto's, overflow als links
- [x]: volgorde tekst → recordNotified → foto's (idempotentie/geen herverzending)
- [x]: unit tests uitgebreid (TelegramNotificationServiceTest)
- [x]: run relevant tests (TelegramNotificationServiceTest + aanpalende suites groen)
- [x]: specs bijgewerkt (functional-spec.md)
- [x]: update story-log met resultaten

## Wat is er gedaan en waarom

### YouTrack-laag
- `YouTrackApi.downloadAttachmentBytes(attachment): ByteArray?` toegevoegd als interface-default
  (`null`), zodat bestaande implementaties/fakes niets hoeven te overschrijven (soft bootstrap).
- `YouTrackClient.downloadAttachmentBytes` implementeert de download via de attachment-`url`
  (relatief pad op `baseUrl`, of absolute URL) met Bearer-auth en `HttpResponse.BodyHandlers.ofByteArray`.
  Volledig soft-fail: `runCatching` + `null` bij ontbrekende URL of niet-2xx.

### Dashboard-service
- `testerReportFor(storyKey)` ontsluit de samenvatting van de laatste TESTER-agent-run op de
  (parent-)story; soft-fail → `null` bij DB-fout of lege tekst.
- `previewUrlFor(storyKey)` ontsluit de tot nu toe private `UiStoryRun.previewUrl()`
  (`previewApi.render(previewUrlTemplate, prNumber)`); `null` voor projecten zonder preview.

### Telegram-melding
- `notifySubtaskDone` detecteert `SubtaskType.fromTracker(subtaskType) == TEST`. Alleen dan worden
  rapport, preview-link en screenshots toegevoegd; andere subtaaktypen lopen exact als voorheen.
- `buildSubtaskDoneInfo` geeft nu ook de `parentKey` terug (rapport/preview/screenshots staan op de
  parent-story, consistent met `syncTesterScreenshots`).
- `buildMessage` kreeg een optionele `extraSections`-parameter (niet her-afgekapt) die ná de
  context maar vóór de reply-instructie/link wordt ingevoegd. Het testrapport wordt zelf op 1200
  tekens afgekapt (`TESTER_REPORT_LIMIT`).
- Screenshots: attachments met prefix `factory-tester-screenshot__`, op naam gesorteerd. Maximaal
  10 (`MAX_SCREENSHOT_PHOTOS`) als foto via `sendPhoto` (bytes → tempfile → versturen → opruimen);
  de rest komt als publieke YouTrack-link(s) in de tekst.
- Volgorde: tekstmelding → `store.recordNotified` → foto's. Een gefaalde `sendPhoto` (return false)
  blokkeert de tekst niet en triggert geen herverzending, want de signature is al vastgelegd.
- Soft-fail overal: tracker-calls, rapport-/preview-lookup, download en `sendPhoto` in
  `runCatching`/return-false; tempfiles worden in een `finally` opgeruimd.

### Tests
`TelegramNotificationServiceTest` uitgebreid met SF-207-cases: rapport+preview-link+foto's in de
tekst, rapport-afkapping op 1200, niet-test-subtaak ongewijzigd, degraderen bij ontbrekende delen,
gefaalde `sendPhoto` zonder herverzending, en overflow (>10 screenshots → 10 foto's + links). De
test-doubles (`FakeTracker`, `FakeDashboard`, `RecordingTelegramClient`) zijn uitgebreid met
attachments/bytes, rapport/preview-overrides en een `sendPhoto`-recorder.

## Specs bijgewerkt
- `docs/factory/functional-spec.md`: sectie "Telegram-melding bij afgeronde test-subtaak (SF-206)"
  toegevoegd met het nieuwe gedrag (rapport, preview-URL, screenshots, volgorde/idempotentie,
  degradatie).

## Build/test-resultaat
- `mvn -f softwarefactory/pom.xml test -Dtest=TelegramNotificationServiceTest`: 16/16 groen.
- Aanpalende suites groen: `FactoryDashboardServiceTest`, `YouTrackClientTest`,
  `FakeYouTrackServerTest`, `AgentRunCompletionServiceTest` (samen 50 tests, 0 failures).

## Review-notitie (reviewer, SF-207, 2026-06-25)
- Volledige story-diff t.o.v. `main` beoordeeld (TelegramNotificationService, FactoryDashboardService,
  YouTrackApi/Client, tests, functional-spec).
- Verificatie: alle aangeroepen API's bestaan en signaturen kloppen
  (`TelegramClient.sendPhoto`, `SubtaskType.fromTracker/TEST`, `AgentRole.markerKeyPart`,
  `TrackerAttachment.url`, hergebruik bestaande private `UiStoryRun.previewUrl()`,
  `secrets.youTrackPublicUrl`, `repository.latestStoryRun/agentRunsForStory`).
- ACs gedekt: test-detectie + rapport (afgekapt 1200) + preview-link + screenshots als foto,
  niet-test ongewijzigd, idempotentie (tekst → recordNotified → foto's), soft-fail overal,
  overflow >10 als links. Unit-tests dekken elk van deze gevallen.
- Scope netjes afgebakend; functional-spec consistent bijgewerkt. Geen blockers.
- [info] Build niet lokaal gedraaid (geen mvn in reviewer-omgeving); statische review, CI is leidend.
- Akkoord.

## Test-notitie (tester, SF-208, 2026-06-25)
- `mvn compile` (main-sources): groen.
- Gerichte suites groen: `TelegramNotificationServiceTest` (16), `FactoryDashboardServiceTest` (29),
  `YouTrackClientTest` (3), `AgentRunCompletionServiceTest` (13), `FakeYouTrackServerTest` (5) — 66/66, 0 failures.
- Volledige suite (`-Dsurefire.runOrder=alphabetical`): 348 tests, **0 Failures**, 12 Errors. Alle 12 errors
  zijn pre-existing/omgeving: 11 Docker-e2e (geen docker-daemon: FactoryUiDriverLoginTest, FullRefineToDevelopE2eTest,
  PipelineFlowsE2eTest) + ModulithArchitectureTest (cycle orchestrator→…). Modulith-violations vergeleken met
  schone `main` (zelfde runOrder): **identiek** → geen regressie. De telegram→web-dependency op
  `FactoryDashboardService` bestond al vóór deze story.
- AC-dekking geverifieerd via de unit-tests (SF-207-cases): rapport+preview-link+screenshots, rapport-afkapping,
  niet-test ongewijzigd, degraderen bij ontbrekende delen, gefaalde sendPhoto zonder herverzending, overflow>10 als links.
- Geen preview-omgeving beschikbaar (`SF_PREVIEW_URL` leeg) — conform tester-instructies lokaal getest met `mvn test`;
  geen browser/screenshot-test van toepassing.
- Oordeel: geslaagd, geen blockers.
