# SF-428 - Worklog

## Story in eigen woorden

Voer een security-review uit over alle modules van deze repo (`softwarefactory`,
`agentworker`, `dashboard-backend` en de Flutter `dashboard-frontend`) langs de
bekende patronen (injectie, in-/uitvoervalidatie & deserialisatie, onveilige
defaults, gelekte/ongeredigeerde secrets, ontbrekende auth/authz, kwetsbare
dependencies). Los alleen **kleine, gedrags-neutrale** kwetsbaarheden op en leg
álle bevindingen (inclusief de afwezigheid van fixes) vast in dit worklog. Grote,
risicovolle of gedrag-veranderende fixes worden niet zelf doorgevoerd. Geen
gevonden kwetsbaarheden is een geldige no-op. Wijzig geen integratie-/e2e-tests.

(Subtaak `SF-429` van story `SF-428`.)

## Stappenplan / checklist

- [x]: read issue and target docs
- [x]: review alle modules langs de in-scope patronen
- [x]: gedrags-neutrale fix(es) doorvoeren waar veilig
- [x]: unit-tests schrijven voor de fix
- [x]: relevante tests draaien
- [x]: bevindingen en (afwezigheid van) fixes vastleggen in dit worklog
- [x]: review-stap

## Doorgevoerde gedrags-neutrale fix

### Timing-side-channel in HMAC-/credential-vergelijking → constant-time compare

**Bevinding (HIGH, wel veilig op te lossen).**
`dashboard-backend` `AuthService.requireAuthorization()` vergeleek de
HMAC-SHA256-signature van het bearer-token met de gewone string-operator
(`signature != expected`). String-`==`/`!=` is een **non-constant-time**
vergelijking: het haakt af bij het eerste afwijkende teken. Daarmee ontstaat een
timing-side-channel waarmee een aanvaller een geldige token-signature byte-voor-byte
kan reconstrueren (token-forgery). Hetzelfde gold voor de wachtwoordvergelijking in
`AuthService.login()`.

Opvallend: de **zuster-implementatie** in `softwarefactory`
(`FactoryDashboardAuth.isValidRememberToken`) deed dit al wél correct via
`MessageDigest.isEqual` — de twee implementaties waren inconsistent en
`dashboard-backend` was de zwakkere.

**Fix (gedrags-neutraal).**
- `dashboard-backend/.../api/AuthService.kt`: signature- en wachtwoordvergelijking
  vervangen door een `constantTimeEquals(...)`-helper op basis van
  `MessageDigest.isEqual` (timing-safe op moderne JDK's). Accept/reject-uitkomst
  blijft exact gelijk; alleen het tijdsgedrag verandert.
- `softwarefactory/.../web/services/FactoryDashboardAuth.kt`: de
  wachtwoordvergelijking in `login()` eveneens via `MessageDigest.isEqual`
  (de cookie-signature gebruikte dit al), zodat beide modules consistent zijn.

Waarom gedrags-neutraal: dezelfde geldige tokens/credentials worden geaccepteerd
en dezelfde ongeldige geweigerd; er verandert geen API, response of config. Geen
integratie-/e2e-test geraakt.

**Tests (zelf geschreven, ontwikkelwerk).**
- Nieuw: `dashboard-backend/.../api/AuthServiceTest.kt` (8 tests): geldige login →
  token wordt door `requireAuthorization` geaccepteerd (round-trip), en weigering
  bij fout wachtwoord, fout gebruiker, ontbrekende/niet-bearer header, getamperde
  signature, verlopen token en garbage-input.
- Bestaand: `softwarefactory/.../web/FactoryDashboardAuthTest.kt` blijft groen
  (dekt de gewijzigde `login()`).

Resultaat: `mvn -f dashboard-backend/pom.xml test -Dtest=AuthServiceTest` →
8/8 groen; `mvn -f softwarefactory/pom.xml test -Dtest=FactoryDashboardAuthTest` →
4/4 groen. Beide modules compileren.

## Onderzochte patronen — overige bevindingen (géén code gewijzigd)

Onderstaande zaken zijn beoordeeld als **false positive** of als **bestaande,
bewuste ontwerpkeuze** waarvan het wijzigen het gedrag zou veranderen of buiten
scope valt. Geen van deze rechtvaardigt een onmiddellijke, gedrags-neutrale fix;
ze zijn hieronder gedocumenteerd als observatie/aanbeveling voor menselijke
opvolging.

### Injectie

- **SQL-injectie (FactoryDashboardRepository / DashboardRepository):** de
  `where`/`orderBy`-fragmenten die in de query-string worden geïnterpoleerd zijn
  **hardcoded interne constanten** (`"ended_at IS NULL"`, `"TRUE"`,
  `"final_status = 'merged'"`, etc.), geen gebruikersinvoer. Alle echte
  waarde-parameters lopen via `?`-placeholders. Geen exploiteerbare injectie.
- **Schema-interpolatie (`$schema`/`$table`)** in de repositories,
  `NightlyRepositories`, `TelegramStore`/`TelegramThreadStore`: de schema-naam komt
  uit config (`FactorySecrets`/`DashboardSecrets`), niet uit request-input. Postgres
  laat identifiers niet via placeholders binden, dus interpolatie is hier
  onvermijdelijk. **`dashboard-backend` valideert de schema-naam al** tegen
  `^[A-Za-z_][A-Za-z0-9_]*$` bij het laden (`DashboardSecretsLoader`). Geen actie
  nodig.
- **Command-injectie:** alle `ProcessBuilder`-aanroepen (agentworker git/AI-clients,
  `DashboardController.openWorkspaceInIntellij`) gebruiken **list-based args**, geen
  shell-string-concatenatie. `openWorkspaceInIntellij` normaliseert bovendien het
  pad en doet een `startsWith`-containment-check. Geen injectie.
- **Log-injectie:** logregels gebruiken parameter-placeholders / structurele data
  (id's, counts); geen ongesanitiseerde externe payloads in logs aangetroffen.
- **Template/HTML-injectie (XSS):** de server-side HTML in
  `FactoryDashboardViews.kt` escapet externe data consequent via de `.e()`-helper
  (incl. `descriptionPreview`, comment-bodies, story-summaries) en URL-segmenten via
  `.path()` (URL-encoding). De Flutter-frontend gebruikt type-veilige `Text`-widgets
  (geen HTML-concatenatie). Geen XSS gevonden.

### Onveilige defaults

- **Default credentials `admin`/`admin`:** beide dashboards vallen terug op
  `admin`/`admin` als `SF_DASHBOARD_USERNAME`/`SF_DASHBOARD_PASSWORD` niet gezet zijn.
  Dit is een **gedocumenteerde dev-default** (`secrets.env.example`,
  `properties.default.env`); in productie horen deze env-vars gezet te worden.
  Hardenen (fail-fast bij ontbreken) zou het opstartgedrag veranderen → buiten de
  gedrags-neutrale scope. *Aanbeveling voor mens:* overweeg fail-fast of een
  startup-waarschuwing bij ongewijzigde defaults.
- **`SF_DASHBOARD_COOKIE_SECURE` default `false`:** logisch voor lokale HTTP-runs;
  in productie achter HTTPS hoort dit `true`. De cookie heeft al `HttpOnly` +
  `SameSite=Lax`. Default omzetten = gedragsverandering → niet gewijzigd.
  *Aanbeveling:* zet `SF_DASHBOARD_COOKIE_SECURE=true` in productie.
- **CORS:** geen expliciete, te ruime CORS-config gevonden (Spring-defaults). In orde.

### Ontbrekende auth/authz

- **`dashboard-backend` (poort 9090):** alle `/api/v1/**`-endpoints dwingen
  `authService.requireAuthorization(...)` af; alleen `/healthz` is bewust publiek
  (alleen `{"status":"ok"}`). In orde.
- **`softwarefactory` web-dashboard (poort 8080):** `FactoryDashboardController`
  zit achter `FactoryDashboardAuth`; `FactoryApiController` `/api/restart` zit achter
  een bearer-token (`SF_FACTORY_API_TOKEN`), `/api/version` is bewust publiek.
- **Interne control-plane endpoints `softwarefactory`:** `AgentKnowledgeController`
  (`/agent-knowledge`, `/agent-knowledge/update`) en `AgentRunCompletionController`
  (`/agent-run/complete`) hebben **geen auth**. Dit zijn de callbacks die de
  agent-containers gebruiken; het is een bestaande ontwerpkeuze (interne API binnen
  de deployment). Auth toevoegen is **gedrag-veranderend en risicovol** (agents
  zouden credentials moeten meesturen) en valt buiten de gedrags-neutrale scope.
  *Aanbeveling voor mens:* zorg dat deze endpoints niet publiek bereikbaar zijn
  (bind op intern netwerk/loopback of plaats achter een gedeeld token), als dat nog
  niet op infra-niveau is afgedekt.

### Secrets

- Redactie is consistent geïmplementeerd: `FactorySecrets.redactedSummary()`,
  `DashboardSecrets.redactedSummary`, `SecretRedactor` (Postgres-URL's, Anthropic-,
  GitHub-tokens). DB-URL-credentials worden via regex geredigeerd. Geen
  ongeredigeerde secrets in code/logging/HTTP-output aangetroffen. Geen nieuwe
  secrets toegevoegd door deze wijziging.

### Dependencies

- Spring Boot **3.5.14** (parent voor alle 3 Maven-modules), Kotlin **2.1.21**,
  Spring Modulith **1.4.11**, Detekt-plugin **1.23.7**. Flutter/Dart: SDK `^3.9.0`,
  `http ^1.2.2`, `shared_preferences ^2.3.2`, `url_launcher ^6.3.1`,
  `cupertino_icons ^1.0.8`, `flutter_lints ^5.0.0`. Dit zijn actuele versies; geen
  evident kwetsbare/verouderde pin gevonden. Een echte CVE-scan
  (OWASP dependency-check / `dart pub outdated`) vereist netwerk/registry-toegang en
  is hier niet betrouwbaar uit te voeren. *Aanbeveling:* laat de CI-pipeline een
  dependency-vulnerability-scan draaien. Geen (major) upgrade doorgevoerd, conform
  scope-aanname.

## Review-stap

De wijziging is een minimale, lokaal geteste refactor van twee
vergelijkingsoperaties naar `MessageDigest.isEqual`, met nieuwe en bestaande
unit-tests groen. Gecontroleerd: geen API/contract-wijziging, geen secrets in
output, geen integratie-/e2e-test geraakt, expliciete imports gebruikt.

## Reviewer-notities (SF-429)

Volledige story-diff t.o.v. `main` beoordeeld (4 bestanden: `AuthService.kt`,
`AuthServiceTest.kt`, `FactoryDashboardAuth.kt`, dit worklog).

- [info] Fix is gedrags-neutraal: `MessageDigest.isEqual` levert dezelfde
  accept/reject-uitkomst als string-`==`/`!=` (incl. ongelijke lengte → false);
  alleen het tijdsgedrag verandert. Username-vergelijking blijft bewust gewone
  vergelijking (geen geheim). Imports (`StandardCharsets`, `MessageDigest`) waren
  al aanwezig resp. expliciet toegevoegd — compileert.
- [info] `AuthServiceTest` construeert `DashboardSecrets` met de 9 velden in de
  juiste volgorde (named args); dekt round-trip + alle weigeringspaden incl.
  getamperde signature, verlopen token en garbage. Goede dekking.
- [info] Geen integratie-/e2e-tests gewijzigd; geen secrets toegevoegd of
  ongeredigeerd; geen scope creep. Specs in `docs/factory/` ongewijzigd en
  consistent (geen functionele/architecturale wijziging).
- [info] De niet-opgeloste punten (default `admin/admin`, `COOKIE_SECURE=false`,
  onauth interne agent-callbacks) zijn pre-existing, bewuste ontwerpkeuzes; ze
  als pipeline-blokkerende error escaleren is hier niet passend. Documenteren als
  aanbeveling voor menselijke opvolging is akkoord binnen de gedrags-neutrale
  scope (AC4/AC6).

**Oordeel:** akkoord — coherent, getest, gedrags-neutraal en binnen scope.

## Tester-notities (SF-430)

Story-brede test uitgevoerd (geen preview-deploy ingericht → lokaal getest met `mvn test`).

- **Diff-scope geverifieerd** (`git diff --name-status main...HEAD`): alleen
  `AuthService.kt`, nieuwe `AuthServiceTest.kt`, `FactoryDashboardAuth.kt` en dit
  worklog. Geen integratie-/e2e-test gewijzigd (AC3/AC4 ✓).
- **Imports** `StandardCharsets` + `MessageDigest` aanwezig in beide gewijzigde
  bestanden; modules compileren.
- **Gedrags-neutraliteit**: `MessageDigest.isEqual` levert dezelfde accept/reject
  als string-`==`/`!=` (incl. ongelijke lengte → false). Round-trip test
  (geldig token → geaccepteerd) en alle weigeringspaden bevestigd via tests.
- **Testresultaten** (`mvn test`, alle drie modules):
  - `dashboard-backend`: 13/13 groen (incl. `AuthServiceTest` 8/8).
  - `softwarefactory`: `FactoryDashboardAuthTest` 4/4 groen; volledige suite
    396 tests, **Failures: 0**, 19 Errors — allemaal pre-existing/omgeving
    (Docker-e2e, Testcontainers `NightlyRepositoriesTest`, `ModulithArchitectureTest`
    cycle, `FactoryDashboardRepositoryScreenshotTest`), niet gerelateerd aan de
    auth-wijziging. De forked-VM tail-crash is eveneens pre-existing.
  - `agentworker`: 34/34 groen.
- **Secrets**: geen secrets toegevoegd of ongeredigeerd; testdata bevat alleen
  fictieve waarden.

**Oordeel tester:** geslaagd — fix is correct, gedrags-neutraal, getest en binnen scope.

## Conclusie

Eén concrete, exploiteerbare kwetsbaarheid gevonden die veilig en gedrags-neutraal
op te lossen was (timing-side-channel in token/credential-vergelijking) — opgelost
in beide betrokken modules, met nieuwe unit-tests. Overige onderzochte punten zijn
false positives of bestaande, bewuste ontwerpkeuzes die niet gedrags-neutraal te
wijzigen zijn; deze zijn als aanbevelingen voor menselijke opvolging
gedocumenteerd. Er zijn geen integratie-/e2e-tests gewijzigd en er zijn geen
secrets toegevoegd of ongeredigeerd. Geen `docs/factory`-spec geraakt (geen
functionele/architecturale wijziging).
