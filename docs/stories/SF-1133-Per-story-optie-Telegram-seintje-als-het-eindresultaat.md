# SF-1133 - Per-story optie: Telegram-seintje als het eindresultaat écht live/klaar staat

## Story

Per-story optie: Telegram-seintje als het eindresultaat écht live/klaar staat

<!-- refined-by-factory -->

## Scope
- Nieuw persistent per-story vlagveld `telegram_result_notify` (analoog aan bestaande `paused`/`silent` boolean-kolommen in `issues`, `V15__tracker_issues.sql`) via nieuwe Flyway-migratie, met bijbehorend `TrackerField`-entry (`factory-common/.../core/TrackerField.kt`) en mapping in `TrackerIssueFields`. Default `false`.
- Bridge-endpoint `story.setTelegramResultNotify` in `dashboard-backend/.../bridge/BridgeApiController.kt`, naar analogie van de bestaande `story.setAutoApprove`/`story.setSilent`-endpoints, plus de doorgeefroute in `FactoryDashboardService`.
- Toggle 'Meld op Telegram als het eindresultaat live/klaar staat' in de Flutter dashboard-frontend (story-detail-scherm), default uit, geschreven via het nieuwe endpoint.
- Nieuwe `@Scheduled`-poller (softwarefactory, interval ~60s) los van `DeploySubtaskHandler`, die per story met de vlag aan + "wacht op eindresultaat"-status (merge/deploy gestart, nog niet bevestigd, nog niet gemeld) controleert:
  - **openshift-watch**: hergebruikt de bestaande ArgoCD Synced/Healthy(+Succeeded)-logica en de image-heuristiek-fallback uit `DeploySubtaskHandler.pollOpenshiftWatch`/`DeploymentStatusProbe` (geen duplicatie), aangevuld met een HTTP-200-check op de live-URL.
  - **APK-projecten**: hergebruikt `GitHubReleaseClient.apkDownloads`/`GitHubActionsClient` (build-apk-job) om een nieuwe `.apk`-release na de merge-timestamp van de story te detecteren.
  - **rest-restart**: hergebruikt de bestaande `/api/version`-poll (commit + `startedAt` na trigger), zoals nu al in `DeploySubtaskHandler`.
- "Alleen pollen wanneer nodig": de poller query't eerst of er issues zijn met vlag aan + wachtstatus; zijn die er niet, dan slaat de run zijn werk over (of logt een duidelijk lagere-frequentie-run) zonder cluster-/GitHub-calls te doen.
- Bij bevestiging: nieuwe, aparte Telegram-melding via `TelegramNotificationService`/`TelegramClient` (naast, niet i.p.v. de bestaande DONE-melding bij subtaak-afronding) met link naar live-URL of APK-download. Idempotent via een 'gemeld'-markering op het issue (nieuwe timestamp-/boolean-kolom of hergebruik van het bestaande signature-patroon uit `TelegramNotificationService`).
- Opgeef-timeout (bv. enkele uren) waarna de poller stopt met wachten op die story en dit logt, zonder foutmelding naar de gebruiker te sturen.

## Acceptance criteria
- Story met vlag aan + openshift-deploy-project → precies één Telegram-melding zodra ArgoCD Synced/Healthy (of image-heuristiek) én de live-URL HTTP 200 geeft, met de live-URL in het bericht.
- Story met vlag aan + APK-project → precies één Telegram-melding zodra de nieuwe `.apk`-release (na de merge-tijd van de story) beschikbaar is via GitHub Releases, met downloadlink.
- Story met vlag aan + rest-restart-project (SF zelf) → precies één Telegram-melding zodra `/api/version` de nieuwe commit + een `startedAt` na de trigger toont.
- Story met vlag uit → geen extra melding van deze feature (de bestaande subtaak-DONE-melding blijft ongewijzigd bestaan).
- Zijn er geen stories die op hun eindresultaat wachten, dan doet de poller aantoonbaar minder werk (log-regel/metric toont "niets te doen, skip" i.p.v. cluster-/GitHub-calls per run).
- Elke story ontvangt deze melding maximaal één keer, ook bij herhaalde polls of herstarts van de applicatie (idempotentie overleeft een restart, dus in de database vastgelegd, niet in-memory).
- Na de opgeef-timeout stopt het pollen voor die story met een logregel; er verschijnt geen foutmelding richting de gebruiker en geen Telegram-bericht.

## Aannames
- Bestaat naast (niet i.p.v.) de huidige DONE-melding van `TelegramNotificationService` bij deploy-subtaak-afronding; dit is een aparte, latere melding zodra het resultaat écht extern zichtbaar is.
- APK-detectie loopt volledig via GitHub Releases (`GitHubReleaseClient`), zoals dashboard-frontend/downloads dat nu al doen — geen nieuwe artefact-opslag.
- De vlag is per story (niet per subtaak) en wordt niet automatisch overgeërfd door subtaken (in tegenstelling tot bv. `autoApprove`), omdat de melding het story-eindresultaat betreft, niet een individuele subtaak.
- "Wacht op eindresultaat" wordt afgeleid uit de bestaande deploy/merge-subtaakstatus (bv. deploy-subtaak gestart/`AWAITING_HUMAN`-achtig, nog niet `DEPLOY_APPROVED`/gefaald) — er komt geen nieuwe aparte statusmachine naast de bestaande `SubtaskPhase`.
- Opgeef-timeout is een technisch detail (orde grootte enkele uren, vergelijkbaar met bestaande deploy-timeouts van `DeployConfig.timeoutMinutes`), exacte waarde is aan de developer, geen harde functionele eis vanuit de gebruiker.

## Eindsamenvatting

Ik heb voldoende context. Hier is de eindsamenvatting voor de PO.

## Eindsamenvatting SF-1133 — Telegram-seintje bij écht live/klaar eindresultaat

**Gebouwd:**
- Nieuwe per-story opt-in vlag `telegram_result_notify` (migratie V18, default uit), zichtbaar als toggle "Meld op Telegram als het eindresultaat live/klaar staat" op het story-detailscherm in Flutter (niet overerfbaar naar subtaken).
- Bridge-endpoint `story.setTelegramResultNotify` (analoog aan bestaande auto-approve/silent-toggles) om de vlag te zetten.
- Nieuwe losse poller (`TelegramResultNotifyPoller`, ~60s interval) die per projecttype checkt of het eindresultaat écht extern zichtbaar is:
  - **openshift-watch**: bevestiging zodra de DEPLOY-subtaak al `deploy-approved` is; optionele extra HTTP-200-check op een nieuw configureerbaar `deploy.liveUrl`-veld.
  - **rest-restart**: bevestiging zodra `deploy-approved` (SHA-verificatie gebeurt al door de bestaande deploy-handler).
  - **APK-projecten**: detecteert een nieuwe `.apk`-release na het merge/deploy-moment via GitHub Releases.
  - Stuurt pas werk als er daadwerkelijk kandidaat-stories zijn (geen onnodige cluster-/GitHub-calls).
  - Idempotent via het bestaande Telegram-signature-mechanisme (overleeft herstarts), met een opgeef-timeout van 4 uur (stil, geen foutmelding).
- Specs (`functional-spec.md`, `technical-spec.md`, `ux/screens/story-detail.md`) bijgewerkt.

**Belangrijke ontwerpkeuze:** in plaats van de ArgoCD/image/SHA-verificatie te dupliceren, hergebruikt de poller het resultaat dat `DeploySubtaskHandler` al vaststelt zodra de DEPLOY-subtaak terminaal (`deploy-approved`) wordt — geen nieuwe parallelle statusmachine.

**Belangrijke fix tijdens review:** een blocker waarbij de poller stories miste zodra ze (bijna direct na `deploy-approved`) al op "Done" gezet waren door de orchestrator — opgelost door `findWorkIssues` met `includeFinished = true` aan te roepen, met een gerichte test die dit expliciet dekt.

**Getest:** volledige `mvn verify` (alle modules groen), gerichte unit tests voor de poller (11, incl. idempotentie, timeout, includeFinished-gedrag), bridge-endpoints, en Flutter widget-test + `flutter analyze` (0 issues) voor de toggle.

**Bewust niet gedaan:** de HTTP-200/live-URL-check voor openshift-watch werkt pas zodra ops het nieuwe `deploy.liveUrl`-veld per project instelt — dat viel buiten deze subtaak (geen regressie voor bestaande configs, wel expliciet in de specs benoemd).
