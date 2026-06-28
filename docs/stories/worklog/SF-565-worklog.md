# SF-565 - Worklog

Story-context bij eerste pickup:
Security-review uitvoeren en gedragsneutrale fixes doorvoeren

Loop de codebase (softwarefactory, agentworker, dashboard-backend, dashboard-frontend) systematisch na op injectie, in-/uitvoervalidatie (incl. padtraversal/deserialisatie), onveilige defaults (CORS/TLS/crypto/randomness), gelekte of ongeredigeerd gelogde secrets, ontbrekende auth/authz en kwetsbare/verouderde dependencies. Voer alleen gedragsneutrale fixes door (parametrized queries, secrets redigeren, defaults aanscherpen zonder waarneembare gedragsverandering, patch/minor dependency-upgrades zonder breaking changes en met groene tests). Volg de repo-conventies (geen project-interne wildcard-imports, SF_-prefix voor nieuwe config, stdlib-guards waar passend, module-relatief). Leg per fix in docs/stories/worklog/SF-565-worklog.md vast wat het probleem was, waar en waarom de fix gedragsneutraal is; markeer bij secrets dat handmatige rotatie nodig kan zijn. Wijzig geen integratie-/e2e-tests: als een fix alleen groen wordt door een integratietest te wijzigen, niet doorvoeren maar escaleren. Risicovolle/gedragsveranderende bevindingen en majors niet zelf doorvoeren maar via de error-route escaleren met concrete beschrijving. Geen veilig-oplosbare problemen gevonden? Dan een onderbouwde no-op in het worklog. Sluit af met een eigen review-stap die gedragsneutraliteit en conventies controleert.

Stappenplan:
[x]: read issue and target docs
[x]: security-review uitvoeren over de vier codebases (patroon-voor-patroon)
[x]: gedragsneutrale fix(es) doorvoeren + tests schrijven
[x]: relevante tests draaien
[x]: eigen review-stap (gedragsneutraliteit + conventies)
[x]: update story-log met bevindingen en resultaat

## Security-review: onderzochte patronen

Systematisch nagelopen over `softwarefactory`, `agentworker`, `dashboard-backend` en
`dashboard-frontend`:

- **SQL-injectie**: alle queries gebruiken `?`-placeholders (JdbcTemplate). De enige
  string-interpolatie in queries is `$schema` (tabel-prefix). Die komt uit
  `SF_DATABASE_SCHEMA` en wordt in `SecretsEnvLoader.resolveDatabaseSchema` al gevalideerd
  tegen `^[A-Za-z_][A-Za-z0-9_]*$` (+ reserved-schema-guard); dashboard-backend valideert
  identiek in `DashboardSecretsLoader`. → geen actie nodig.
- **Command-injectie**: alle `ProcessBuilder`-aanroepen gebruiken list-vorm (geen shell-string).
  De git-askpass `#!/bin/sh` schrijft de token niet in het script maar leest 'm via een
  env-var (`$SF_GITHUB_TOKEN`); proces-output wordt geredigeerd (`SupportApi.redact`). →
  geen injectie-pad gevonden.
- **Onveilige deserialisatie (YAML)**: **bevinding + fix**, zie hieronder.
- **Padtraversal**: file-paden in `web`/`dashboard-backend` worden gevormd uit config/cwd,
  niet uit untrusted request-input; `normalize()` waar van toepassing. → geen actie nodig.
- **TLS**: `YouTrackClient` voegt een custom truststore tóé aan de JVM-default (merged
  X509TrustManager) en valideert tegen beide; geen trust-all. → veilig.
- **CORS/cookies**: dashboard-cookie is `SameSite=Lax`, `HttpOnly`, en `Secure` via
  `SF_DASHBOARD_COOKIE_SECURE`; auth gebruikt `MessageDigest.isEqual` (constant-time, SF-429).
  → geen actie nodig.
- **Crypto/randomness**: `UUID.randomUUID()` wordt alleen voor niet-security-doeleinden
  gebruikt (multipart-boundaries, sessie-/container-namen). → geen actie nodig.
- **Gelekte/ongeredigeerde secrets in logging**: config wordt gelogd via
  `redactedSummary()`; token-logging meldt alleen aan/afwezigheid, nooit de waarde. →
  geen actie nodig.
- **Auth/authz**: ongeauthenticeerde endpoints (agent-callbacks) zijn een bewuste
  ontwerpkeuze; `/api/restart` zit achter `SF_FACTORY_API_TOKEN`, dashboard-backend
  `/api/v1/**` achter `requireAuthorization`. → geen actie nodig.
- **XSS (ingebouwd HTML-dashboard)**: dynamische/agent-beïnvloedbare data (titels,
  samenvattingen, descriptions, errors, vragen) wordt consequent via `String.e()`
  HTML-geëscaped; reeds-geëscapete waarden (bv. `context`) worden bewust niet
  dubbel-geëscaped. → geen actie nodig.
- **Dependencies**: Spring Boot 3.5.14, Kotlin 2.1.21 (recent). Geen concrete kwetsbare
  versie aangetroffen die met een veilige patch/minor-bump verholpen moest worden; een
  speculatieve bump zonder CVE valt buiten "veilig + gedragsneutraal". → geen actie.

## Doorgevoerde fix: SnakeYAML met SafeConstructor (onveilige deserialisatie)

**Probleem.** `Yaml().load(...)` met de default SnakeYAML-constructor staat instantiatie
van willekeurige Java-typen toe via expliciete YAML-tags (`!!some.java.Type [...]`). Bij
een payload met een gadget (klassiek `javax.script.ScriptEngineManager` +
`URLClassLoader`) kan dit tot remote code execution leiden.

**Waar.**
- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/config/ProjectRepoResolver.kt`
  (`fromYaml`, leest `projects.yaml`).
- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/nightly/NightlyJobsReader.kt`
  (`parseJob`, leest `.factory/nightly/<name>/job.yaml`). Dit pad is het meest relevant:
  `job.yaml` wordt via de GitHub contents-API uit *geconfigureerde project-repo's* gelezen,
  dus deels untrusted invoer i.p.v. lokale operator-config.

**Fix.** Beide aanroepen gebruiken nu `Yaml(SafeConstructor(LoaderOptions()))`, die enkel
standaard YAML-typen (maps/lijsten/scalars) toelaat en op een type-instantiatie-tag een
exception gooit. In `ProjectRepoResolver` zit dit achter de bestaande try/catch, dus een
kwaadaardig bestand levert — net als ander malformed YAML — een lege resolver op.

**Waarom gedragsneutraal.** Beide parsers consumeren uitsluitend platte data
(`as? Map<*,*>`, `as? List<*>`, `as? String`, booleans/scalars). `SafeConstructor` levert
voor geldige, niet-kwaadaardige YAML exact dezelfde Kotlin-objecten op als de default
constructor; alleen de mogelijkheid om willekeurige typen te instantiëren verdwijnt. Geen
enkele legitieme config gebruikt zulke tags, dus waarneembaar gedrag blijft gelijk.

**Tests.** Nieuwe unit-test
`ProjectRepoResolverTest > a yaml type-instantiation tag is refused and yields an empty resolver`
voert een `ScriptEngineManager`-gadget-payload in en verifieert dat er niets geïnstantieerd
wordt en een lege resolver volgt. Bestaande parse-tests blijven ongewijzigd groen.

## Tests gedraaid

`mvn -f softwarefactory/pom.xml test -Dtest='ProjectRepoResolverTest,ProjectRepoResolverMergeDeployTest'`
→ ProjectRepoResolverTest: 11 run, 0 failures; ProjectRepoResolverMergeDeployTest: 7 run,
0 failures. (De `logger.error`-stacktrace in de output is de verwachte, opgevangen afwijzing
van de kwaadaardige tag.) De hele `softwarefactory`-module compileert mee, dus de
`NightlyJobsReader`-wijziging compileert; die klasse heeft geen unit-test (vereist
gh-CLI/netwerk) en is niet gewijzigd in gedrag.

## Eigen review-stap (gedragsneutraliteit + conventies)

- Geen integratie-/e2e-test aangepast; alleen een nieuwe unit-test toegevoegd.
- Imports expliciet per type (geen wildcards), conform repo-norm.
- Geen nieuwe config/env-vars (dus geen `SF_`-kwestie); geen secrets gelogd.
- Wijziging is module-relatief tot `softwarefactory`; raakt geen publieke API of waarneembaar
  gedrag.

## Geëscaleerd / niet-opgelost

Geen. Er zijn geen risicovolle of gedragsveranderende bevindingen aangetroffen die
escalatie via de error-route vereisen; de overige onderzochte gebieden waren al afdoende
gehard (zie patroon-overzicht hierboven).
