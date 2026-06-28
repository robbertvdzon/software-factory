# SF-649 - Worklog

Story-context bij eerste pickup:
Security-audit + gedragsneutrale fixes

Loop de drie Maven-modules (softwarefactory, agentworker, dashboard-backend) en bijbehorende resources/config gericht na op de in-scope patronen: injectie (JdbcTemplate, gh/git-shell, padconstructie), in-/uitvoervalidatie (request-params, padtraversal, HTML-escaping), onveilige defaults, gelekte/ongeredigeerd gelogde secrets, ontbrekende authn/authz en kwetsbare dependencies. Flutter-frontend alleen voor evidente issues. Los uitsluitend gedragsneutrale bevindingen op; gedrag moet exact gelijk blijven en integratie-/e2e-tests worden niet gewijzigd.

## SF-650 — Security-audit + gedragsneutrale fixes (developer)

Stappenplan:
[x]: read issue and target docs
[x]: security-audit van de in-scope patronen over de drie modules
[x]: gedragsneutrale fix(es) doorvoeren + unittests
[x]: relevante tests draaien
[x]: worklog bijwerken met bevindingen en uitkomst

### Bevindingen per in-scope patroon

- **SQL-injectie (JdbcTemplate):** alle queries gebruiken `?`-placeholders. De
  enige string-interpolaties in SQL zijn `$schema`/`$table`/`${select()}`, en die
  komen uit interne constanten of uit `SF_DATABASE_SCHEMA`. Dat schema wordt al
  gevalideerd tegen `^[A-Za-z_][A-Za-z0-9_]*$` (softwarefactory
  `SecretsEnvLoader.resolveDatabaseSchema`, dashboard-backend
  `DashboardSecretsLoader`). Geen actie nodig.
- **Command-injectie (git/gh/shell):** alle externe processen lopen via
  `ProcessBuilder(List<String>)` (`LocalProcessRunner`) — geen shell, dus geen
  argument-injectie. De git-askpass helper schrijft het token niet in het script
  maar leest het uit env-var `$SF_GITHUB_TOKEN` (perms `rwx------`). Geen actie.
- **YAML/deserialisatie:** beide SnakeYAML-parsers gebruiken al
  `SafeConstructor` (SF-565). Geen actie.
- **Auth/authz:** `FactoryDashboardController` zit achter `FactoryDashboardAuth`
  (HMAC remember-cookie, constant-time `MessageDigest.isEqual`, SF-429);
  onauthenticated agent-callback-endpoints zijn een bewuste ontwerpkeuze.
  Geen actie.
- **Secret-logging:** geen logregel/`println` met ongeredigeerde
  token/secret/password/credential aangetroffen; `FactorySecrets`/`SupportApi`
  redigeren consequent. Geen actie.
- **HTML-escaping (server-rendered views):** `FactoryDashboardViews` escapet
  dynamische waarden via `.e()` (HTML) en `.path()` (URL-encoding). Geen actie.

### Doorgevoerde fix (gedragsneutraal)

- **Open-redirect bypass in de login-redirect.** `safeNext()`/`safeReturn()` in
  `FactoryDashboardController` weigerden een protocol-relatieve `//host`, maar
  niet de backslash-variant `/\host`. Browsers normaliseren `\`→`/`, waardoor
  `/\evil.com` als `//evil.com` wordt geïnterpreteerd en een redirect naar een
  externe host oplevert (Location-header + login-`next`).
  - Fix: nieuwe, geteste helper `SafeRedirect.localPath(value, default)` die naast
    `//` ook `/\` weigert; controller delegeert ernaar. Voor legitieme lokale
    paden (`/dashboard`, `/stories/SF-1`) is het resultaat exact gelijk, dus
    gedragsneutraal.
  - Bestanden: `web/controllers/SafeRedirect.kt` (nieuw),
    `web/controllers/FactoryDashboardController.kt` (delegatie),
    `test/.../web/controllers/SafeRedirectTest.kt` (nieuw, 6 cases).

### Afgewogen, niet doorgevoerd

- **Dependency-bumps:** een patch/security-bump telt alleen als veilig wanneer de
  volledige testsuite (incl. Docker-e2e) groen blijft. Dat is in deze
  developer-omgeving (geen Docker) niet volledig te verifiëren, dus geen bump
  doorgevoerd om geen gedrag-/build-risico te introduceren. Geen blokkerende,
  triviale patch-bump aangetroffen die zich gedragsneutraal liet vaststellen.

### Testuitkomst

- `mvn -f softwarefactory/pom.xml test -Dtest=SafeRedirectTest` → 6/6 groen.
- Regressiecheck rond het gewijzigde gebied:
  `-Dtest=FactoryDashboardAuthTest,FactoryDashboardViewsTest,FactoryDashboardServiceTest`
  → 68/68 groen.
- De brede suite kent bekende, niet door deze story veroorzaakte main-failures
  (ModulithArchitectureTest module-cycle, AgentResultFileCompletionPollerTest in
  volledige run) en Docker-afhankelijke e2e-tests; die draaien in de pipeline.
