# SF-1133 - Worklog

Story-context bij eerste pickup:
Telegram-melding bij écht live/klaar eindresultaat: vlag, poller, melding

Implementeer per-story opt-in vlag `telegram_result_notify` (nieuwe Flyway-migratie V18 op issues-tabel + TrackerField-entry + mapping), bridge-endpoint `story.setTelegramResultNotify` (BridgeApiController + BridgeRequestHandler + FactoryDashboardService-doorgeefroute), Flutter-toggle in story_detail_screen.dart, en een nieuwe losse @Scheduled-poller (~60s) die alleen werk doet als er stories met vlag aan + wacht-op-eindresultaat-status zijn. Poller detecteert per projecttype: OpenShift (hergebruik ArgoCD Synced/Healthy + image-heuristiek uit DeploySubtaskHandler.pollOpenshiftWatch, geëxtraheerd naar herbruikbare vorm, plus HTTP-200 op live-URL), APK (nieuwe .apk-release na merge-tijdstip via GitHubReleaseClient.apkDownloads), rest-restart (/api/version commit+startedAt zoals pollRestRestart). Bij bevestiging: aparte Telegram-melding via TelegramNotificationService/TelegramClient met live-URL/downloadlink, idempotent via DB-kolom telegram_result_notified_at (overleeft restart). Opgeef-timeout na enkele uren zonder foutmelding/Telegram-bericht. Inclusief unit tests voor poller-logica, idempotentie en de nieuwe endpoints.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- **Migratie + veld**: `V18__telegram_result_notify.sql` voegt `telegram_result_notify BOOLEAN NOT NULL DEFAULT false` toe aan `issues` (`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`, zelfde stijl als V17). `TrackerField.TELEGRAM_RESULT_NOTIFY` (factory-common) + `TrackerIssueFields.telegramResultNotify` (default false) + `applying()`-mapping (zelfde "on"/"off"-stringconventie als `AUTO_APPROVE`) + `PostgresTrackerClient` (mapRow/columnFor/columnValue/ISSUE_COLUMNS), analoog aan `PAUSED`/`SILENT`.
- **Bridge-endpoint**: `DashboardCommands.setTelegramResultNotifyFlag` (interface `DashboardApi.kt` + impl `DashboardCommandService`, exact het `setSilentFlag`-patroon) → bridge-operatie `story.setTelegramResultNotify` in `BridgeRequestHandler` (softwarefactory) → REST-endpoint `POST /api/v1/stories/{storyKey}/telegram-result-notify` in dashboard-backend's `BridgeApiController` (hergebruikt de bestaande `AutoApproveRequest`-DTO). Let op: de story-tekst noemt "FactoryDashboardService" als doorgeefroute; die klasse bestaat niet meer — de huidige split is `DashboardCommandService`/`DashboardQueryService`.
- **Flutter-toggle**: nieuwe `SwitchListTile` 'Meld op Telegram als het eindresultaat live/klaar staat' in `story_detail_screen.dart`, alleen zichtbaar op story-niveau (`if (isStory)`, zelfde regel als Silent — de vlag wordt niet overgeërfd door subtaken), default uit, roept `_toggleTelegramResultNotify` → nieuwe endpoint.
- **Poller**: `TelegramResultNotifyPoller` (`telegram/services/`, `@Scheduled(fixedDelayString = "${softwarefactory.telegram-result-notify-poll-ms:60000}")`). Ontwerpkeuze t.o.v. de story-tekst: in plaats van de ArgoCD-/image-/SHA-verificatie uit `DeploySubtaskHandler` te dupliceren, hergebruikt de poller het resultaat dat die handler al vaststelt — zodra de DEPLOY-subtaak `deploy-approved` bereikt (terminaal, niet `deploy-failed`), heeft `DeploySubtaskHandler` dat al geverifieerd (ArgoCD Synced+Healthy+Succeeded/image-heuristiek voor openshift-watch, SHA-match voor rest-restart). De poller voegt alleen de extra checks toe die de deploy-handler niet doet:
  - **openshift-watch**: optioneel `liveUrl`-veld op `DeployConfig.OpenshiftWatch` (nieuw, YAML `deploy.liveUrl`); geconfigureerd → extra HTTP-200-check; niet geconfigureerd → direct bevestigd (geen regressie voor bestaande configs).
  - **rest-restart**: direct bevestigd zodra de subtaak `deploy-approved` is (die SHA-verificatie is al gebeurd).
  - **projecten zonder deploy-config** (heuristiek voor "APK-project", er bestaat geen apart `deploy.type: apk`): nieuwe `.apk`-release na de deploy-referentietijd, via de nieuwe poort `ApkReleaseProbe` (`core.contracts`) met adapter `GitHubApkReleaseProbe` (`dashboard.services`, hergebruikt `GitHubReleaseClient.apkDownloads`, geen duplicatie).
  - Referentietijd = deploy-subtaak `agentStartedAt` (fallback `updatedAt`/`createdAt`).
  - "Alleen pollen wanneer nodig": `poll()` filtert eerst `findWorkIssues()` op `telegramResultNotify == true`; leeg → geen enkele subtasksOf/HTTP/GitHub-call (getest: `ThrowingSubtasksTracker`/`ApkReleaseProbe` die falen als ze toch aangeroepen worden).
  - Opgeef-timeout: 4 uur na de referentietijd (const `GIVEUP_HOURS`, technisch detail conform de Aannames) → alleen een warn-log, geen Telegram-bericht, geen foutmelding; wel als "afgehandeld" gemarkeerd (idempotentie-signature) zodat de poller niet blijft hangen.
  - Idempotentie: hergebruikt het bestaande `TelegramStore.alreadyNotified`/`recordNotified`-signature-patroon uit `TelegramNotificationService` (DB-backed via `telegram_notifications`, overleeft een herstart) met signature `"result-notify"` i.p.v. een nieuwe kolom — conform de Aannames ("hergebruik van het bestaande signature-patroon").
- **Modulith-verplaatsing**: de poller startte in `pipeline/service/` (naast `DeploySubtaskHandler`), maar `pipeline` mag geen `dashboard`- of `telegram`-module importeren (Spring-Modulith `ModulithArchitectureTest`). Verplaatst naar `telegram/services/` (die module mag wél `config`/`core`/`core::contracts`/`tracker` importeren, en `TelegramClient`/`TelegramStore` horen daar toch al thuis). De APK-detectie liep via `dashboard.services.GitHubReleaseClient`, ook niet toegestaan vanuit een niet-dashboard-module; opgelost met een nieuwe poort `ApkReleaseProbe` in `core.contracts` (overal injecteerbaar) + adapter `GitHubApkReleaseProbe` in `dashboard.services` die de bestaande client hergebruikt — zelfde patroon als de bestaande `DeploymentStatusProbe`-poort voor `DeploySubtaskHandler`.
- **Tests**:
  - `BridgeRequestHandlerTest`: nieuwe testcase voor `story.setTelegramResultNotify` (analoog aan de bestaande `setAutoApprove`-test).
  - `BridgeApiControllerTest` (dashboard-backend): nieuwe testcase voor het REST-endpoint (operatie + params doorgestuurd).
  - `story_detail_screen_test.dart`: nieuwe widget-test voor de toggle (tap → juiste endpoint/body).
  - `TelegramResultNotifyPollerTest` (10 tests): skip-fast zonder kandidaten, rest-restart happy-path + idempotentie (2× poll → 1 bericht), openshift-watch zonder `liveUrl`, nog-niet-gestart/nog-bezig (geen melding), `deploy-failed` (geen melding, wel gemarkeerd), opgeef-timeout (geen melding, geen GitHub-call), APK-happy-path/geen-nieuwe-release, story zonder vlag.
  - `GitHubApkReleaseProbeTest`: pure filter-/selectielogica (`newestAfter`) zonder HTTP.
- **Vangnet**: `mvn verify` vanaf de repo-root, twee volledige runs. Eerste run: 1 rode e2e-test (`PipelineFlowsE2eTest.development-subtaak stelt een vraag die de gebruiker beantwoordt`, timing-gevoelig, niet gerelateerd aan deze story). Losse herhaling van die test slaagde meteen (`mvn -f softwarefactory/pom.xml verify -Dit.test=PipelineFlowsE2eTest -Dsurefire.skip=true` → groen), dus omgevingsflakiness, geen echte regressie. Tweede volledige `mvn verify`-run: **BUILD SUCCESS**, exitcode 0, alle modules (factory-contracts, factory-common, softwarefactory incl. e2e/failsafe, agentworker, dashboard-backend) groen. Flutter: `flutter test` (6/6, incl. de nieuwe toggle-test) en `flutter analyze` (0 issues), beide lokaal gedraaid (Flutter-toolchain was in deze sandbox-run wél beschikbaar).
- `.factory/verification.yaml` ongewijzigd: de story raakt alleen paden die al onder de bestaande `repository-maven-verify`/`dashboard-flutter-*`-commando's vallen.

## Aangepaste specs (docs/factory/)

- `functional-spec.md`: nieuwe paragraaf over de opt-in Telegram-resultaatmelding (vlag, poller, wanneer 'ie meldt).
- `technical-spec.md`: nieuwe paragraaf met de architectuur (poort `ApkReleaseProbe`, hergebruik van `DeploySubtaskHandler`-bevestiging, idempotentie via `TelegramStore`, module-plaatsing).
- `ux/screens/story-detail.md`: de nieuwe toggle vermeld bij de bestaande auto-approve/silent-toggles.

## Review-notities (SF-1134, reviewronde 1)

Diff beoordeeld: `git diff main...HEAD` (22 bestanden). Migratie/veld/mapping, bridge-endpoint
(beide kanten + REST), Flutter-toggle en de nieuwe tests zijn correct en consistent met de specs.
Gerichte checks lokaal uitgevoerd (niet de volledige suite): `mvn -pl factory-common,softwarefactory
-am test-compile` (schoon) en gericht `mvn -pl softwarefactory -am test -Dtest=
TelegramResultNotifyPollerTest,GitHubReleaseClientTest,GitHubApkReleaseProbeTest,
BridgeRequestHandlerTest,ModulithArchitectureTest` (10+4+3+31+4 tests, 0 failures/errors) — dat
compileert en de poller-unittests zelf zijn groen, maar zie de blocker hieronder: die unittests
draaien de poller volledig los van `findWorkIssues`/`TrackerApi`, dus dekken de bug niet af.

**[blocker] `TelegramResultNotifyPoller.poll()` roept
`issueTrackerClient.findWorkIssues(maxResults = 200)` aan zonder `includeFinished = true`
(`softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/telegram/services/TelegramResultNotifyPoller.kt`,
`poll()`-methode). De default is `includeFinished = false`
(`TrackerCapabilities.findWorkIssues`), en `PostgresTrackerClient.findAiIssues` sluit STORY-rijen
met een `status` in `FinishedStatus.VALUES` dan uit — de tweede UNION-tak (niet-terminale
`subtask_phase`) redt dit niet, want die geldt alleen voor subtaak-rijen, niet voor de story zelf.**
Zodra de DEPLOY-subtaak `DEPLOY_APPROVED` bereikt en er geen volgende non-terminal subtaak meer is,
zet `SubtaskExecutionCoordinator.advanceSubtaskChain`
(`softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/pipeline/service/SubtaskExecutionCoordinator.kt`,
rond regel 460-468) de story-status meteen op "Done" — voor `DeployConfig.Skip`-projecten zelfs in
dezelfde aanroep als de `DEPLOY_APPROVED`-zet, voor openshift-watch/rest-restart binnen één
event-gewekte orchestrator-tick (elke tracker-write publiceert `FactoryStateChangedEvent`, dat de
`OrchestratorPoller` direct wekt). Het venster waarin de story nog wél via `findWorkIssues()`
zichtbaar is én de DEPLOY-subtaak al `DEPLOY_APPROVED` is, is dus vrijwel nul — bij een 60s
poll-interval (`softwarefactory.telegram-result-notify-poll-ms`) mist de poller die story in de
praktijk (bijna) altijd. Gevolg: de kernfunctionaliteit van deze story (de melding zodra het
eindresultaat live/klaar staat) vuurt in het gangbare geval niet af, voor geen van de drie
projecttypes (openshift-watch, rest-restart, APK) — de story wordt immers pas als kandidaat gezien
zolang 'ie nog niet "Done" is. De 10 nieuwe pollertests dekken dit niet, omdat ze `findWorkIssues()`
mocken met een vaste issue-lijst i.p.v. het status-filtergedrag van de echte tracker-client te
simuleren. Fix: `findWorkIssues(maxResults = 200, includeFinished = true)` — idempotentie/scope
blijven al gedekt door de bestaande `telegramResultNotify`-filter en `TelegramStore`-check, dus dat
is veilig en goedkoop.

**[suggestie]** `ApkReleaseProbe.kt`-KDoc verwijst nog naar
`nl.vdzon.softwarefactory.pipeline.service.TelegramResultNotifyPoller`; de poller is (terecht, i.v.m.
Modulith) verplaatst naar `telegram.services`. Kleine inconsistentie, geen blocker.

**[info]** `DeployConfig.OpenshiftWatch.liveUrl` is optioneel en default `null`; zolang geen enkel
project-config `deploy.liveUrl` instelt, gebeurt de in de AC beschreven HTTP-200-check + live-URL-in-
bericht voor openshift-watch-projecten nooit — de melding bevat dan alleen de generieke tekst zonder
URL. Bewust en gedocumenteerd (geen regressie voor bestaande configs), maar de AC ("... én de
live-URL HTTP 200 geeft, met de live-URL in het bericht") wordt zo pas waargemaakt nadat ops
`liveUrl` per project toevoegt — dat viel buiten deze subtaak. Verder geen scope-, security- of
spec-inconsistenties gevonden; migratie/veld/bridge/Flutter/tests zijn overigens correct en
consistent met de specs.

## Fix na reviewronde 1 (loopback, SF-1134)

- [x]: Blocker opgelost — `TelegramResultNotifyPoller.poll()`
  (`softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/telegram/services/TelegramResultNotifyPoller.kt`)
  roept nu `issueTrackerClient.findWorkIssues(maxResults = 200, includeFinished = true)` aan i.p.v.
  zonder `includeFinished`. Daarmee blijft een story die `SubtaskExecutionCoordinator.advanceSubtaskChain`
  al (vrijwel meteen) naar status "Done" heeft gezet zodra de DEPLOY-subtaak terminaal wordt, alsnog
  zichtbaar voor de poller — anders sluit `PostgresTrackerClient.findAiIssues` de story-rij uit en
  vuurt de melding in de praktijk nooit af.
- [x]: Nieuwe test toegevoegd —
  `TelegramResultNotifyPollerTest."poll vraagt findWorkIssues met includeFinished=true (...)"` — die
  `FakeTracker.lastIncludeFinished` vastlegt en asserteert dat de poller `true` doorgeeft. Dit is
  precies het gedrag dat de vorige 10 tests niet dekten (ze mockten `findWorkIssues()` met een vaste
  lijst i.p.v. het `includeFinished`-doorgeefgedrag te verifiëren).
- [x]: Suggestie opgelost — KDoc in `ApkReleaseProbe.kt` verwees nog naar
  `nl.vdzon.softwarefactory.pipeline.service.TelegramResultNotifyPoller`; verwijst nu naar het
  actuele `nl.vdzon.softwarefactory.telegram.services.TelegramResultNotifyPoller`.
- Info-punt over optionele `DeployConfig.OpenshiftWatch.liveUrl` is bewust ongewijzigd gelaten (geen
  blocker, viel buiten deze subtaak — zie reviewnotitie hierboven).
- **Vangnet**: `mvn verify` vanaf de repo-root, volledige run: **BUILD SUCCESS**, alle modules
  (factory-contracts, factory-common, softwarefactory incl. e2e/failsafe (~3 min), agentworker,
  dashboard-backend) groen, exitcode 0. Gericht vooraf ook
  `mvn -pl softwarefactory -am test-compile` (schoon) en
  `mvn -pl softwarefactory test -Dtest=TelegramResultNotifyPollerTest,BridgeApiControllerTest,
  BridgeRequestHandlerTest,GitHubReleaseClientTest -Dsurefire.failIfNoSpecifiedTests=false` (0
  failures/errors, incl. de nieuwe test).
