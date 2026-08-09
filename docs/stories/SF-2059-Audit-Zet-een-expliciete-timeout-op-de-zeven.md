# SF-2059 - [Audit] Zet een expliciete timeout op de zeven HttpRequest-plekken die er geen hebben

## Story

[Audit] Zet een expliciete timeout op de zeven HttpRequest-plekken die er geen hebben

<!-- refined-by-factory -->

## Samenvatting

De factory praat via HTTP met GitHub en met een paar eigen endpoints. Op zeven van die plekken staat geen maximale wachttijd ingesteld, waardoor de factory in het ergste geval eindeloos blijft wachten op een antwoord dat nooit komt.

Dat is extra vervelend omdat alle periodieke taken van de factory achter elkaar op één lijn staan: blijft er één hangen, dan staat de hele pijplijn stil. Ook het wachten op een preview-omgeving kan er door blijven hangen, terwijl daar juist een tijdslimiet voor is ingesteld.

Deze story zet op die zeven plekken een expliciete wachttijd van 10 seconden, en zorgt daarnaast dat het opzetten van de verbinding zelf ook een limiet krijgt. Het gedrag verandert verder niet: een verzoek dat vandaag op tijd antwoordt, doet dat daarna precies zo.

## Scope

**1. Expliciete request-timeout van 10 seconden toevoegen aan zeven `HttpRequest`-builders:**

| bestand:regel | wat het doet |
|---|---|
| `softwarefactory/.../dashboard/services/GitHubReleaseClient.kt:41` | releases ophalen |
| `softwarefactory/.../dashboard/services/GitHubActionsClient.kt:208` | workflow-runs ophalen |
| `softwarefactory/.../maintenance/services/GitHubProtectedShaSource.kt:76` | open PR's ophalen |
| `softwarefactory/.../maintenance/services/GitHubReleaseCleanupClient.kt:76` | releases lijsten/verwijderen |
| `softwarefactory/.../maintenance/services/GitHubPackageCleanupClient.kt:87` | package-versies lijsten/verwijderen |
| `softwarefactory/.../dashboard/services/ProjectDeployClient.kt:27` (`forceRestart`) | herstart-webhook |
| `agentworker/.../flows/TesterPreviewFlow.kt:66` | preview-URL pollen |

Vorm: `.timeout(Duration.ofSeconds(10))` op de builder, naar analogie van `StoryDeployReconciler.kt:188` en `TelegramResultNotifyPoller.kt:148`. In vier van deze bestanden moet `import java.time.Duration` nog toegevoegd worden (GitHubReleaseClient, GitHubProtectedShaSource, GitHubReleaseCleanupClient, GitHubPackageCleanupClient); GitHubActionsClient, ProjectDeployClient en TesterPreviewFlow hebben die import al.

**2. `connectTimeout` op zes gedeelde `HttpClient`-defaults:**
`GitHubReleaseClient.kt:23`, `GitHubActionsClient.kt:29`, `GitHubProtectedShaSource.kt:33`, `GitHubReleaseCleanupClient.kt:27`, `GitHubPackageCleanupClient.kt:28` en `ProjectDeployClient.kt:19` bouwen hun default met een kale `HttpClient.newHttpClient()`. Vervang die door de vorm uit `TelegramClient.kt:34-35`:

```
HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
```

De constructorparameter blijft een parameter met default, zodat bestaande tests hun eigen client kunnen blijven injecteren.

**Buiten scope:**
- Het samenvoegen van de vijf bijna identieke `sendJsonOrNull`-kopieën in de GitHub-clients. `dashboard` en `maintenance` zijn aparte Spring-Modulith-modules en `maintenance/package-info.java` staat maar een beperkte set afhankelijkheden toe; een gedeelde helper vraagt eerst een wijziging in de modulegrenzen.
- `connectTimeout` op de vier overige `newHttpClient()`-defaults (`StoryDeployReconciler.kt:77`, `DeploySubtaskHandler.kt:59`, `TelegramResultNotifyPoller.kt:72`, `TesterPreviewFlow.kt:31`) — die houden deze story minimaal en hebben al request-timeouts.
- De bestaande 3s-timeout op `ProjectDeployClient.fetchVersionBody` (`:43`) blijft ongewijzigd.
- Het configureren van een `TaskScheduler`-poolsize.
- Nieuw retry-, backoff- of foutafhandelingsgedrag; een timeout die afgaat volgt de bestaande foutpaden per klasse.

## Acceptance criteria

1. Alle zeven genoemde `HttpRequest`-builders hebben een expliciete `.timeout(Duration.ofSeconds(10))`; na de wijziging heeft elke `HttpRequest.newBuilder(...)` in `softwarefactory/src/main`, `agentworker/src/main`, `dashboard-backend/src/main` en `factory-common/src/main` een expliciete `.timeout(...)` — er blijft er geen één over zonder.
2. De timeout in `ProjectDeployClient.forceRestart` is groter dan of gelijk aan de 3 seconden die `fetchVersionBody` al hanteert, zodat dat bestand zichzelf niet tegenspreekt.
3. De zes genoemde `HttpClient`-defaults zetten een `connectTimeout(Duration.ofSeconds(10))` via `HttpClient.newBuilder()`, in dezelfde vorm als `TelegramClient.kt:34-35`.
4. De `httpClient`-constructorparameters blijven bestaan met een default, zodat testinjectie ongewijzigd werkt; er verandert niets aan de signatuur of de volgorde van constructorparameters.
5. Er verandert geen functioneel pad: geen wijzigingen aan URL's, headers, HTTP-methoden, body's, statuscode-afhandeling of returntypes.
6. `mvn -B --no-transfer-progress verify` is groen (minimaal `GitHubActionsClientTest`, `GitHubReleaseClientTest`, `GitHubProtectedShaSourceTest`, `GitHubReleaseCleanupClientTest`, `GitHubPackageCleanupClientTest`, `TesterPreviewFlowTest`); geen bestaande test hoeft aangepast te worden.
7. `./quality/run.sh` is groen en de ratchet-check meldt geen nieuwe of extra findings ten opzichte van `quality/baselines/plan-07-ratchet.json` (totale findingtelling gaat niet omhoog).

## Aannames

- 10 seconden is de standaardwaarde voor alle zeven plekken; er is geen aanleiding gevonden om per plek af te wijken.
- Een timeout op de request-builder verandert niets aan wat de nepclients in de bestaande tests teruggeven, dus er zijn geen testaanpassingen nodig en er worden ook geen nieuwe tests toegevoegd (er bestaat bijvoorbeeld geen `ProjectDeployClientTest`, en die wordt hier niet aangemaakt).
- Bij `TesterPreviewFlow.waitForHttp200` blijft de bestaande lus-structuur ongewijzigd; de request-timeout zorgt er enkel voor dat een hangende `send(...)` de deadlinecontrole tussen twee pogingen niet meer omzeilt, zodat `SF_PREVIEW_WAIT_TIMEOUT_SECONDS` weer betekenis heeft.
- Er is geen documentatie in `docs/factory/` of `docs/technical/` die deze timeoutwaarden vastlegt, dus deze story veroorzaakt geen doc-drift en vraagt geen doc-update.
- De verhouding request-timeout (10s) versus connect-timeout (10s) wordt bewust gelijk gehouden aan het `TelegramClient`-precedent; er wordt geen nieuwe configuratie-eigenschap of env-variabele geïntroduceerd voor deze waarden.

## Eindsamenvatting

Ik heb `.task.md` en het volledige worklog van SF-2059 (developer, reviewer, tester) gelezen. Hieronder de eindsamenvatting.

## Eindsamenvatting SF-2059 — Expliciete timeouts op zeven HttpRequest-plekken

**Wat is gebouwd**

- **Request-timeout van 10 seconden** toegevoegd op alle zeven `HttpRequest`-builders die er geen hadden: `GitHubReleaseClient`, `GitHubActionsClient`, `GitHubProtectedShaSource`, `GitHubReleaseCleanupClient`, `GitHubPackageCleanupClient`, `ProjectDeployClient.forceRestart` en `TesterPreviewFlow.waitForHttp200`. Bij de twee cleanup-clients zit de timeout in de gedeelde `authorizedRequest(...)`-helper, zodat zowel de GET (lijsten) als de DELETE (verwijderen) hem krijgt.
- **Connect-timeout van 10 seconden** op de zes gedeelde `HttpClient`-constructordefaults (kale `newHttpClient()` vervangen door `newBuilder().connectTimeout(...).build()`, naar het bestaande `TelegramClient`-precedent).
- Na de wijziging heeft élke `HttpRequest.newBuilder(...)` in de vier main-bronbomen (15 stuks) een expliciete `.timeout(...)`; er blijft er geen één over.

**Gemaakte keuzes**

- **Afwijking t.o.v. de storytekst, bewust:** de 10s staat niet als literal `Duration.ofSeconds(10)` in de builders, maar als constante in het companion object (`HTTP_TIMEOUT` / `RESTART_TIMEOUT` / `POLL_TIMEOUT`). Reden: detekt's `MagicNumber` vlagt elke literal `10`; de eerste versie mét literals liet de kwaliteits-findingtelling van 768 naar 781 lopen, wat botst met AC7. Met de constante is de telling weer exact gelijk. Waarde, gedrag en constructorsignaturen blijven identiek.
- `forceRestart` krijgt 10s, bewust ruimer dan de bestaande 3s van `fetchVersionBody` (die blijft ongewijzigd) — AC2.
- De `httpClient`-constructorparameters blijven op dezelfde positie mét default, zodat bestaande testinjectie en de handgeschreven fakes ongewijzigd blijven werken.
- **Nieuwe conventietest** `HttpRequestTimeoutConventionTest` als guardrail: broncontrole die eist dat iedere `HttpRequest.newBuilder(...)` een `.timeout(...)` zet — ook voor plekken die later bijkomen. Gekozen boven een HTTP-test per client, omdat er geen mock-framework in de repo is en echt op een timeout wachten per plek 10 seconden kost.

**Wat is getest**

- `mvn -B --no-transfer-progress verify` over alle modules: BUILD SUCCESS, 0 failures / 0 errors (o.a. 867 + 88 + 61 + 65 tests). Alle in AC6 genoemde tests groen; geen bestaande test hoefde aangepast te worden.
- `./quality/run.sh`: groen, `ok: true`, `new: []`, `newSuppressions: []`, `findingCount: 768` — geen stijging (AC7).
- **Faalbewijs van de guardrail:** dezelfde detectielogica op de pre-fix commit vindt exact de zeven storylocaties en op HEAD nul; met de timeout tijdelijk weggehaald werd de test rood.
- **Gedragsbewijs voor het eigenlijke storydoel:** tegen een server die de verbinding accepteert maar nooit antwoordt, gooit `TesterPreviewFlow.prepare(...)` nu na twee polls van 10s een `PreviewWaitException` op de ingestelde deadline van 15s. Vóór deze story bleef de eerste poll eindeloos hangen en werd `SF_PREVIEW_WAIT_TIMEOUT_SECONDS` nooit bereikt.
- Reviewer akkoord op AC1–AC7, geen blockers.

**Bewust niet gedaan**

- De vijf bijna identieke `sendJsonOrNull`-kopieën samenvoegen — vraagt eerst een wijziging in de Spring-Modulith-modulegrenzen.
- `connectTimeout` op de vier overige `newHttpClient()`-defaults (`StoryDeployReconciler`, `DeploySubtaskHandler`, `TelegramResultNotifyPoller`, `TesterPreviewFlow:31`) — die hebben al request-timeouts.
- Geen nieuw retry-, backoff- of foutafhandelingsgedrag; een afgaande timeout volgt de bestaande foutpaden. Geen nieuwe config-property of env-variabele. Geen `ProjectDeployClientTest` aangemaakt.
- Geen doc-update: geen enkele doc legt deze timeoutwaarden vast, dus geen doc-drift.

**Aandachtspunten voor een volgende story** (niet blokkerend, uit de review)

- De conventietest slaat een niet-bestaande bronroot stil over en kan bij een andere cwd vacuüm groen worden; een "elke root bestaat"-assertie maakt hem hard.
- De heuristiek kan bij een helper die een `HttpRequest.Builder` teruggeeft meeliften op een `.timeout(` elders in hetzelfde bestand.
- `ProjectDeployClient.RESTART_TIMEOUT` wordt ook als client-brede `connectTimeout` gebruikt; een neutralere naam dekt de lading beter.
- Het baselinebestand `quality/baselines/plan-07-ratchet.json` staat nog op 744 terwijl zowel HEAD als main op 768 staan — bestaande drift op main, niet van deze story, maar wel het opruimen waard.

<!-- deploy-summary:start -->
De factory wacht niet langer eindeloos op een antwoord dat niet komt: elke externe aanvraag stopt nu vanzelf na tien seconden. Daardoor kan één traag antwoord niet meer de hele reeks taken stilleggen. Ook het wachten op een testomgeving houdt zich nu weer netjes aan de ingestelde maximale wachttijd.
<!-- deploy-summary:end -->
