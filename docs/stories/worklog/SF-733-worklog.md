# SF-733 - Worklog

Story-context bij eerste pickup:
Security-audit en gedragsneutrale fixes

Voer een security-audit uit over de modules softwarefactory, agentworker, dashboard-backend en de Flutter dashboard-frontend op de in scope genoemde patronen: injectie (SQL/OS-command/path-traversal/template-YAML), ontbrekende in-/uitvoervalidatie en onveilige deserialisatie (bv. SnakeYAML zonder SafeConstructor), onveilige defaults (CORS/permissies/library-config/actuator), gelekte of hardcoded secrets en ongeredigeerd gelogde secrets, ontbrekende/zwakke authenticatie/autorisatie op endpoints, en verouderde/kwetsbare dependencies in de pom.xml's. Voer ALLEEN gedragsneutrale fixes zelf door (query parametriseren, secret in log redigeren, veiliger parser-config, patch/minor dependency-bump zonder API-/gedragswijziging, input-validatie die enkel reeds-ongeldige input afwijst), met een eigen review-stap. Houd functioneel gedrag, API-contracten en observeerbare output exact gelijk; pas geen integratie-/e2e-tests (package ...e2e) aan. Moet een fix alleen groen worden door een e2e-test te wijzigen, of zou een fix gedrag veranderen of is hij risicovol/groot, voer hem dan NIET door en ga in Error met concrete bevinding (locatie, risico, voorgestelde aanpak). Geen gedragsneutrale fix gevonden = geldige no-op. Leg alle bevindingen, genomen besluiten en eventuele no-op-onderbouwing vast in docs/stories/worklog/SF-733-worklog.md. Introduceer geen secrets en log geen ongeredigeerde secrets; volg de module-relatieve conventies (bv. ?: throw in dashboard-backend).

Stappenplan:
[x]: read issue and target docs
[x]: security-audit uitgevoerd over de in-scope patronen en modules
[x]: gedragsneutrale fix(es) doorgevoerd
[x]: relevante tests geschreven + gedraaid
[x]: update story-log met bevindingen en conclusie

## Audit-aanpak

Statische review (grep/lezen) over `softwarefactory`, `agentworker`, `dashboard-backend`
(Kotlin) en de Flutter `dashboard-frontend`, gericht op de in-scope patronen:
injectie, in-/uitvoervalidatie & deserialisatie, onveilige defaults, (gelogde) secrets,
auth/authz op endpoints en kwetsbare dependencies.

## Bevindingen

### Doorgevoerde fix (gedragsneutraal)

- **Timing-side-channel op de `/api/restart` Bearer-tokencheck.**
  Locatie: `softwarefactory/.../web/controllers/FactoryApiController.kt` (regel ~57).
  De token werd vergeleken met een gewone `providedToken != expectedToken`
  (`String.equals`, niet constant-tijd). Dat is exact dezelfde klasse kwetsbaarheid
  die SF-429 al verholpen heeft in `FactoryDashboardAuth.login` en dashboard-backend
  `AuthService` via `MessageDigest.isEqual`; dit endpoint was daarbij gemist.
  Fix: een private `constantTimeEquals(a, b)` (UTF-8 bytes + `MessageDigest.isEqual`),
  conform de bestaande conventie.
  Gedragsneutraal: gelijke token → `200`/restart, ongelijke/ontbrekende token → `401`,
  ongeconfigureerde token → `401`. API-contract en observeerbare output ongewijzigd;
  alleen het timing-verschil verdwijnt. Tests toegevoegd voor de mismatch- en
  ontbrekende-header-paden (`FactoryApiControllerTest`, nu 5 tests groen).

### Gecontroleerd en in orde bevonden (geen actie)

- **SQL-injectie:** alle JDBC-queries gebruiken `?`-parameters; `$schema`/`$table` in
  `TelegramStore`/`TelegramThreadStore`/`NightlyRepositories` zijn een constante tabel
  of het via `DATABASE_SCHEMA_PATTERN` gevalideerde schema (`SecretsEnvLoader`,
  `[A-Za-z_][A-Za-z0-9_]*` + reserved-check). dashboard-backend valideert idem
  (`DashboardConfig`). Geen injectie.
- **OS-command-injectie:** alle `ProcessBuilder`-aanroepen (git, docker, claude/codex/
  copilot, IntelliJ-open) gebruiken list-vorm argumenten, geen shell-string; user-/
  telegram-input (`claude -p <userMessage>`) gaat als losse arg. Geen injectie.
- **Deserialisatie / YAML:** geen `ObjectInputStream`/`XMLDecoder`/XStream. De twee
  SnakeYAML-parsers gebruiken al `SafeConstructor` (SF-565).
- **Auth/secrets dashboard-auth:** `FactoryDashboardAuth` (HMAC remember-cookie) is
  `httpOnly` + `SameSite=Lax`, constant-tijd wachtwoordcheck (SF-429), `secure` via
  `SF_DASHBOARD_COOKIE_SECURE` (bewuste default `false` voor lokaal, gedocumenteerd).
- **Onauth interne endpoints** (`/agent-knowledge`, `/agent-run/complete`) zijn een
  bewuste ontwerpkeuze (agent-callbacks); `/api/restart` achter token, `/api/version`
  publiek by design — niet wijzigen (zou gedrag/contract raken).
- **Gelogde secrets:** geen secret-waarden in logregels; config logt
  `redactedSummary()`. Geen lek.
- **Dependencies:** alle modules erven Spring Boot 3.5.14 (recente BOM); geen
  losse pinned/verouderde kwetsbare versies in de pom's. Geen bump nodig.

### Bewust NIET zelf opgelost (geen Error nodig)

Geen kwetsbaarheid gevonden waarvan de fix gedrag zou veranderen of risicovol/groot is.
De HTML-dashboard-views bouwen HTML via string-interpolatie; consequente output-encoding
toevoegen zou de observeerbare output kunnen wijzigen en valt daarmee buiten "gedrags-
neutraal" — niet aangeraakt in deze run. Story gaat dus niet in Error.

## Conclusie

Eén zekere, gedragsneutrale fix doorgevoerd (constant-tijd tokenvergelijking op
`/api/restart`) met bijbehorende unit-tests. Overige in-scope patronen zijn nagelopen
en in orde (grotendeels al gehard door eerdere stories). Geen e2e-/integratietest
gewijzigd.
