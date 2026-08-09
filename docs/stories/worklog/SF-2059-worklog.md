# SF-2059 - Worklog

Story-context bij eerste pickup:
Expliciete request-timeouts en connectTimeout toevoegen

Deel 1: voeg .timeout(Duration.ofSeconds(10)) toe aan de zeven HttpRequest-builders: GitHubReleaseClient.kt:41, GitHubActionsClient.kt:208, GitHubProtectedShaSource.kt:76, GitHubReleaseCleanupClient.kt:76, GitHubPackageCleanupClient.kt:87, ProjectDeployClient.kt:27 (forceRestart) en TesterPreviewFlow.kt:66. Vorm naar analogie van StoryDeployReconciler.kt:188. Voeg 'import java.time.Duration' toe in GitHubReleaseClient, GitHubProtectedShaSource, GitHubReleaseCleanupClient en GitHubPackageCleanupClient. Laat de bestaande 3s op ProjectDeployClient.fetchVersionBody (:43) ongewijzigd; de 10s in forceRestart is bewust ruimer.

Deel 2: vervang de kale 'HttpClient.newHttpClient()' constructor-default in GitHubReleaseClient.kt:23, GitHubActionsClient.kt:29, GitHubProtectedShaSource.kt:33, GitHubReleaseCleanupClient.kt:27, GitHubPackageCleanupClient.kt:28 en ProjectDeployClient.kt:19 door HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), in de vorm van TelegramClient.kt:34-35. Laat de httpClient-parameter een constructorparameter met default op dezelfde positie, zodat testinjectie ongewijzigd werkt.

Randvoorwaarden: geen wijzigingen aan URL's, headers, HTTP-methoden, body's, statuscode-afhandeling of returntypes; geen nieuw retry-/backoff-/foutafhandelingsgedrag; laat de lusstructuur van TesterPreviewFlow.waitForHttp200 en SF_PREVIEW_WAIT_TIMEOUT_SECONDS ongemoeid. Voeg geen gedeelde sendJsonOrNull-helper toe (buiten scope: Spring-Modulith-modulegrenzen). Raak StoryDeployReconciler.kt:77, DeploySubtaskHandler.kt:59, TelegramResultNotifyPoller.kt:72 en TesterPreviewFlow.kt:31 niet aan.

Verwacht geen testaanpassingen (nepclients blijven hetzelfde teruggeven); maak geen ProjectDeployClientTest aan. Blijkt tijdens de implementatie tóch een test nodig, dan hoort die bij deze subtaak.

Afronden: draai 'mvn -B --no-transfer-progress verify' en './quality/run.sh' (ratchet-telling mag niet omhoog t.o.v. quality/baselines/plan-07-ratchet.json) en controleer met een grep dat geen enkele HttpRequest.newBuilder( in softwarefactory/src/main, agentworker/src/main, dashboard-backend/src/main en factory-common/src/main nog zonder .timeout( staat (builders zijn vaak multi-line). Sluit af met een zelfreview van de diff tegen de acceptatiecriteria.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

## SF-2060 (development) — 09-08-2026

In eigen woorden: zeven HTTP-calls konden eindeloos blijven hangen omdat er geen maximale
wachttijd op stond. Omdat alle periodieke factory-taken op één scheduler-lijn staan, legt één
hangende call de hele pijplijn stil. Deze subtaak zet er overal 10 seconden op, plus een
connect-timeout op de zes gedeelde `HttpClient`-defaults.

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Alle zeven `HttpRequest`-builders hebben nu een expliciete `.timeout(...)` van 10s:
  `GitHubReleaseClient`, `GitHubActionsClient`, `GitHubProtectedShaSource`,
  `GitHubReleaseCleanupClient`, `GitHubPackageCleanupClient`, `ProjectDeployClient.forceRestart`
  en `TesterPreviewFlow.waitForHttp200`. Bij de twee cleanup-clients staat de timeout in de
  gedeelde `authorizedRequest(...)`-helper, zodat zowel de GET (lijsten) als de DELETE
  (verwijderen) 'm krijgt.
- De zes kale `HttpClient.newHttpClient()`-constructordefaults zijn vervangen door
  `HttpClient.newBuilder().connectTimeout(...).build()`, vorm van `TelegramClient.kt`. De
  `httpClient`-parameter blijft op dezelfde positie mét default, dus testinjectie en de
  handgeschreven fakes in `MaintenanceCleanupSchedulerTest` blijven ongewijzigd werken.
- Afwijking t.o.v. de letterlijke storytekst, bewust en machinaal onderbouwd: de 10s staat niet
  als literal `Duration.ofSeconds(10)` in de builders maar als constante in het companion object
  (`HTTP_TIMEOUT`, in `ProjectDeployClient` `RESTART_TIMEOUT`, in `TesterPreviewFlow`
  `POLL_TIMEOUT`). Reden: detekt's `MagicNumber` vlagt elke literal `10` en de eerste versie mét
  literals liet de ratchet-telling van 768 naar 781 lopen — dat botst met AC7. Detekt negeert
  companion-object-properties (`ignoreCompanionObjectPropertyDeclaration`), dus met de constante
  is de telling weer exact 768. Waarde, gedrag en constructorsignaturen zijn ongewijzigd.
- `ProjectDeployClient.forceRestart` krijgt 10s, ruimer dan de bestaande 3s van
  `fetchVersionBody` (die blijft ongewijzigd) — AC2 gehaald.
- Geen wijzigingen aan URL's, headers, methoden, body's, statuscode-afhandeling of returntypes;
  geen nieuw retry-/backoff-gedrag; de lus van `waitForHttp200` en
  `SF_PREVIEW_WAIT_TIMEOUT_SECONDS` zijn ongemoeid. `StoryDeployReconciler`,
  `DeploySubtaskHandler`, `TelegramResultNotifyPoller` en `TesterPreviewFlow.kt:31` niet
  aangeraakt. Geen gedeelde `sendJsonOrNull`-helper (buiten scope).

Tests:
- Nieuw: `softwarefactory/src/test/.../HttpRequestTimeoutConventionTest.kt` — broncontrole (zelfde
  recept als `ModuleApiConventionTest`) die eist dat élke `HttpRequest.newBuilder(...)` in
  `softwarefactory/src/main`, `agentworker/src/main`, `dashboard-backend/src/main` en
  `factory-common/src/main` een `.timeout(...)` zet, ook bij multi-line builders en builders die
  uit een helper worden teruggegeven. Bewust een guardrail i.p.v. een HTTP-test per client: er is
  geen mock-framework in deze repo en een echte time-out afwachten kost per plek 10 seconden,
  terwijl de regel juist over álle plekken tegelijk gaat — inclusief plekken die later bijkomen.
- Faalbewijs geleverd: met de timeout tijdelijk uit `GitHubReleaseClient` gehaald werd de test rood
  (`expected: <[]> but was: <[...GitHubReleaseClient.kt:44]>`); daarna hersteld en weer groen.
- Bestaande tests hoefden niet aangepast te worden (nepclients geven hetzelfde terug); er is geen
  `ProjectDeployClientTest` aangemaakt.

Bewijs (09-08-2026):
- `mvn -B --no-transfer-progress verify` vanaf de repo-root: BUILD SUCCESS, exitcode 0 —
  softwarefactory 88 tests, agentworker 61, dashboard-backend 65, alle 0 failures / 0 errors.
- `./quality/run.sh`: groen, `ok: true`, `new: []`, `newSuppressions: []`, `findingCount: 768`.
  Referentie via `git worktree add --detach /tmp/base HEAD` + detekt daarin: HEAD staat óók op
  768, dus de telling gaat niet omhoog (baselinebestand zelf staat nog op 744 door bestaande
  drift op main — die drift is niet van deze story).
- Grep-controle uit de storytekst machinaal afgedekt door de nieuwe conventietest: 0 builders
  zonder `.timeout(`.

Docs: geen doc-drift. `docs/factory/` en `docs/technical/` leggen deze timeoutwaarden nergens
vast, en `.factory/verification.yaml` verandert niet (geen wijziging aan build/testcommando's).

## Review SF-2060 (reviewer) — 09-08-2026

Akkoord. Diff `main...HEAD` beoordeeld tegen AC1–AC7; geen blockers.

Gecontroleerd:
- AC1: alle 15 `HttpRequest.newBuilder(` in de vier main-bronbomen hebben een `.timeout(...)`
  (7 nieuw; de rest — `StoryDeployReconciler`, `DeploySubtaskHandler` 2x, `TelegramClient` 3x,
  `TelegramResultNotifyPoller`, `ProjectDeployClient.fetchVersionBody` — had er al één).
  Bij de twee cleanup-clients zit de timeout in `authorizedRequest(...)`, dus zowel GET als DELETE.
- AC2: `forceRestart` 10s > `fetchVersionBody` 3s (die laatste ongewijzigd).
- AC3/AC4: zes `connectTimeout(10s)`-defaults in `HttpClient.newBuilder()`-vorm; `httpClient` staat
  nog op dezelfde positie mét default, dus de fakes in `MaintenanceCleanupSchedulerTest` en de
  positionele 1-arg-constructie blijven werken.
- AC5: geen wijziging aan URL's, headers, methoden, body's, statuscodes of returntypes; de lus van
  `waitForHttp200` en `SF_PREVIEW_WAIT_TIMEOUT_SECONDS` zijn ongemoeid.
- AC6/AC7 hercontrole in de reviewsandbox: `mvn -B --no-transfer-progress -pl
  factory-common,softwarefactory -am test -Dtest=HttpRequestTimeoutConventionTest,
  GitHubActionsClientTest,GitHubReleaseClientTest,GitHubProtectedShaSourceTest,
  GitHubReleaseCleanupClientTest,GitHubPackageCleanupClientTest,MaintenanceCleanupSchedulerTest`
  → 72 tests, 0 failures/0 errors, BUILD SUCCESS. `./quality/run.sh` → `ok: true`, `new: []`,
  `newSuppressions: []`, `findingCount: 768` (geen stijging; de diff voegt geen enkele numerieke
  literal aan mainbronnen toe).
- Spec-consistentie: geen doc in `docs/factory/` legt HTTP-client- of request-timeouts vast;
  `functional-spec.md:221` over `SF_PREVIEW_WAIT_TIMEOUT_SECONDS` wordt door deze wijziging juist
  waargemaakt. Geen inconsistentie.

Openstaande suggesties (niet blokkerend, voor een volgende story):
- `HttpRequestTimeoutConventionTest` slaat een bronroot stil over als die niet bestaat
  (`Files.isDirectory` → lege lijst) en kan dus vacuüm groen worden bij een andere cwd; een
  assertie "elke root bestaat" of "minstens N builders gescand" maakt 'm hard.
- De heuristiek "tekst tussen `newBuilder(` en de eerstvolgende `.build()`" valt bij een
  helper die een `HttpRequest.Builder` teruggeeft terug op de rest van het bestand; een
  timeoutloze helper zou dan meeliften op een `.timeout(` elders in datzelfde bestand.
- `ProjectDeployClient.RESTART_TIMEOUT` wordt óók als client-brede `connectTimeout` gebruikt
  (dus ook voor `fetchVersionBody`); een neutralere naam dekt de lading beter.

## SF-2061 — Story-brede test (tester)

Volledig vangnet (`.factory/verification.yaml`) in deze checkout op `f731d10`:

- `mvn -B --no-transfer-progress -o verify` → **exitcode 0, BUILD SUCCESS**, 0 failures / 0 errors
  over alle modules (o.a. 867 + 88 + 61 + 65 tests). De AC6-tests liepen allemaal groen:
  `GitHubActionsClientTest` (23), `GitHubReleaseClientTest` (11), `GitHubProtectedShaSourceTest` (9),
  `GitHubReleaseCleanupClientTest` (6), `GitHubPackageCleanupClientTest` (8),
  `TesterPreviewFlowTest` (2), `HttpRequestTimeoutConventionTest` (1).
- `./quality/run.sh` → **exitcode 0**, `ok: true`, `new: []`, `newSuppressions: []`,
  `findingCount: 768` (gelijk aan baseline, `resolved: 7`). AC7 ✓.

Gedragsverificatie (naast de unittests):

1. **Preview-deadline wordt weer gerespecteerd (het eigenlijke storydoel).** Wegwerp-driver
   buiten de repo (`/tmp`, na de run verwijderd) tegen een lokale HTTP-server die de verbinding
   wél accepteert maar nooit antwoordt, met `SF_PREVIEW_WAIT_TIMEOUT_SECONDS=15` en interval 0:
   `TesterPreviewFlow.prepare(...)` gooit `PreviewWaitException` na **20s** ("did not return HTTP
   200 within 15s"), dus twee polls van 10s. Vóór deze story bleef `send(...)` op de eerste poll
   eindeloos hangen en werd de deadline nooit bereikt. Geen productiecode of test aangepast.
2. **Guardrail is niet vacuüm.** Dezelfde detectielogica als
   `HttpRequestTimeoutConventionTest` losgelaten op `04fcdc4` (pre-fix) vindt exact de 7
   story-locaties (GitHubReleaseClient:41, GitHubActionsClient:208, ProjectDeployClient:27,
   GitHubProtectedShaSource:76, GitHubReleaseCleanupClient:76, GitHubPackageCleanupClient:87,
   TesterPreviewFlow:66) en op `HEAD` nul. De test draait met cwd = modulebasedir (geen
   `workingDirectory`-override in de pom's), dus de vier bronroots resolven echt.
3. **AC1** volledig nagelopen: alle 15 `HttpRequest.newBuilder(...)` in `softwarefactory`,
   `agentworker`, `dashboard-backend` en `factory-common` main-bronnen hebben een expliciete
   `.timeout(...)`; er blijft er geen één over. **AC2**: `forceRestart` 10s ≥ de 3s van
   `fetchVersionBody`. **AC3**: alle zes defaults gebruiken
   `HttpClient.newBuilder().connectTimeout(10s).build()`, in de vorm van `TelegramClient.kt:34-35`.
   **AC4**: de `httpClient`-parameters bestaan nog, met default en op dezelfde positie —
   bestaande tests injecteren ongewijzigd. **AC5**: de diff raakt geen URL, header, methode, body,
   statuscode-afhandeling of returntype.

Geen flakes gezien; geen bevindingen. Geen preview-omgeving voor deze repo, dus geen screenshots.
