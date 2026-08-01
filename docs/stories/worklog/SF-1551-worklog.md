# SF-1551 - Worklog

Story-context bij eerste pickup:
SF_ALLOWED_EMAILS verplicht maken in DashboardSecretsLoader

Wijzig dashboard-backend/src/main/kotlin/nl/vdzon/softwarefactory/dashboard/config/DashboardConfig.kt: laad SF_ALLOWED_EMAILS via required(...) i.p.v. optional(...) ?: DEFAULT_ALLOWED_EMAIL (r51), houd de aanroepvolgorde SF_DASHBOARD_REMEMBER_SECRET -> SF_GOOGLE_CLIENT_ID -> SF_ALLOWED_EMAILS intact, en laat het laden ook falen (error(...) => IllegalStateException, melding bevat 'SF_ALLOWED_EMAILS') wanneer de waarde wel gezet is maar parseAllowedEmails een lege set oplevert (bijv. ',' of ' , '). Verwijder de constante DEFAULT_ALLOWED_EMAIL en het daardoor lege private companion object (r88-90); 'robbert@vdzon.com' mag niet meer in dit bestand voorkomen. Pas dashboard-backend/src/test/kotlin/nl/vdzon/softwarefactory/dashboard/config/DashboardSecretsLoaderTest.kt aan: vervang de test 'defaults the allowlist to robbert when omitted' (r27-35) door twee fail-tests in dezelfde vorm als r47-67 (assertFailsWith<IllegalStateException> + assertContains op 'SF_ALLOWED_EMAILS'), één voor volledig ontbrekend en één voor gezet-maar-leeg-geparsed. Laat de tests 'loads dashboard secrets from environment', 'parses a comma-separated allowlist and normalises whitespace and casing', 'startup fails when google client id is omitted' en 'startup fails when remember secret is omitted' ongewijzigd en groen. Doe daarna een eigen review tegen de acceptance criteria en draai 'mvn verify' vanaf de repo-root (0 failures, 0 errors). Wijzig geen andere bestanden dan deze twee plus docs/stories/worklog/SF-1551-worklog.md; documentatie, docker-/deploy-/secretsbestanden en AuthService blijven buiten scope.

In eigen woorden: het dashboard mocht tot nu toe starten zonder ingestelde allowlist en
viel dan terug op één hardgecodeerd e-mailadres. Wie toegang heeft moet een bewuste
instelling zijn, dus de backend weigert voortaan te starten als `SF_ALLOWED_EMAILS`
ontbreekt of na parsing geen enkel adres oplevert.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `DashboardConfig.kt`: `SF_ALLOWED_EMAILS` wordt nu via `required(...)` geladen (was
  `optional(...) ?: DEFAULT_ALLOWED_EMAIL`), met daarachter een `ifEmpty { error(...) }` op
  het resultaat van `parseAllowedEmails`. Zo faalt ook de vorm die wél gezet is maar niets
  oplevert (`","`, `" , "`) — die waarde is niet blank, dus `resolve`/`required` laat 'm door
  en alleen de na-parsing-check vangt 'm. Beide gevallen gooien `IllegalStateException` via
  `error(...)` en noemen de key, net als de andere verplichte keys.
- De aanroepvolgorde `SF_DASHBOARD_REMEMBER_SECRET` -> `SF_GOOGLE_CLIENT_ID` ->
  `SF_ALLOWED_EMAILS` is intact gelaten, zodat de bestaande fail-tests voor de eerste twee
  keys ongewijzigd over hún key blijven melden.
- Constante `DEFAULT_ALLOWED_EMAIL` en het daardoor lege `private companion object`
  verwijderd; `robbert@vdzon.com` komt niet meer voor in `DashboardConfig.kt` (geverifieerd
  met grep, exit 1 = geen match).
- `DashboardSecretsLoaderTest.kt`: de test `defaults the allowlist to robbert when omitted`
  vervangen door twee fail-tests in dezelfde vorm als de bestaande fail-tests
  (`assertFailsWith<IllegalStateException>` + `assertContains` op `SF_ALLOWED_EMAILS`): één
  voor volledig ontbrekend, één die zowel `","` als `" , "` afdekt. De vier overige tests zijn
  ongewijzigd.
- Bewust buiten scope gelaten (conform de story): documentatie, docker-/deploy-/secrets-
  bestanden en `AuthService`. Opgemerkt: `docs/factory/secrets-local.md` beschrijft de
  allowlist nog met "(default `robbert@vdzon.com`)" en is door deze wijziging feitelijk
  onjuist — kandidaat voor de documentation-subtaak (SF-1661) of een opvolgstory.

Bewijs vangnet:
- `mvn -B --no-transfer-progress clean verify` vanaf de repo-root: BUILD SUCCESS, exitcode 0.
  Alle modules groen (softwarefactory incl. Testcontainers-e2e's, agentworker,
  dashboard-backend 51 tests, 0 failures, 0 errors). Totale tijd ~4m22.

Review (SF-1658, 01-08-2026):
- Volledige story-diff `git diff main...HEAD` beoordeeld: alleen `DashboardConfig.kt`,
  `DashboardSecretsLoaderTest.kt` en dit worklog. Geen scope creep.
- Alle 8 acceptance criteria afgevinkt; volgorde `SF_DASHBOARD_REMEMBER_SECRET` ->
  `SF_GOOGLE_CLIENT_ID` -> `SF_ALLOWED_EMAILS` intact, dus de twee bestaande fail-tests
  blijven over hún key melden. `DEFAULT_ALLOWED_EMAIL`/`robbert@vdzon.com` weg uit het bestand.
- Geverifieerd dat geen enkele andere dashboard-backend-test de loader via een Spring-context
  aanroept: `BridgeHubTest` levert `DashboardSecrets` via een eigen `dashboardSecrets()`-bean
  (`HubTestConfig.secretsOverride`) en raakt `DashboardSecretsLoader` niet.
- Gerichte hercontrole naast het harness-geverifieerde bewijs:
  `mvn -pl dashboard-backend -am test -Dtest=DashboardSecretsLoaderTest` -> 6 tests,
  0 failures, 0 errors.
- Opvolging voor de documentation-subtaak (SF-1661): `docs/factory/secrets-local.md:46-47`
  noemt de allowlist nog "(default `robbert@vdzon.com`)" en is nu feitelijk onjuist; ook
  `docker/docker-compose.yml:29` houdt met `${SF_ALLOWED_EMAILS:-robbert@vdzon.com}` nog een
  fallback in het deploy-pad. Beide bewust buiten de scope van deze subtaak gelaten.

Test (SF-1659, story-brede test, 01-08-2026):
- Vangnet: `mvn -B --no-transfer-progress clean verify` vanaf de repo-root -> BUILD SUCCESS,
  exitcode 0, 5m38. Tests: contracts 16, common 52, softwarefactory 685 unit + 74 e2e,
  agentworker 60, dashboard-backend 51 = 938 totaal, 0 failures / 0 errors / 0 skipped.
  Geen flakes deze ronde (ook TesterVerificationEvidenceE2eTest en ChainCompositionE2eTest
  groen). `tools/audit-documentation` -> `documentation-audit/v1: PASS`, exit 0.
- Gedragstest fail-fast op de ECHTE gebouwde jar
  (`dashboard-backend/target/softwarefactory-dashboard-backend-0.0.1-SNAPSHOT.jar`, gedraaid
  in een lege werkdir met `env -i` zodat geen `secrets.env` of host-env meelift):
  - zonder `SF_ALLOWED_EMAILS` -> exit 1, contextstart afgebroken met
    `Missing required dashboard configuration: SF_ALLOWED_EMAILS` (AC1).
  - `SF_ALLOWED_EMAILS=","` en `SF_ALLOWED_EMAILS=" , "` -> exit 1, met
    `Empty dashboard configuration: SF_ALLOWED_EMAILS contains no e-mail addresses` (AC2).
  - Alle drie via `error(...)` => `IllegalStateException`, zichtbaar als
    `BeanInstantiationException ... Factory method 'dashboardSecrets' threw exception` (AC3).
  - Met een geldige waarde (` Tester@Example.com , second@example.com `) start de applicatie
    wel gewoon: `Started DashboardBackendApplicationKt in 2.141 seconds`, Tomcat op de
    testpoort (AC5, happy path niet geraakt).
  - Secret-redactie: in geen van de vier logs komt de meegegeven
    `SF_DASHBOARD_REMEMBER_SECRET`-waarde voor (grep-count 0).
- Uitrolpaden gecontroleerd: `docker/docker-compose.yml:29`, `deploy/base/sealed-secret-dashboard.yaml`,
  `deploy/secrets-cluster.env.example:14`, `secrets.env.example:39` en
  `docker/smoke-local-quickstart.sh:29` zetten de variabele alle vijf -> geen bestaand
  deploy-pad breekt.
- Diff-scope opnieuw bevestigd (`git diff main...HEAD --stat`): alleen de twee bestanden uit
  de scope + dit worklog (AC8). Geen browser/preview beschikbaar en geen UI-wijziging in deze
  story, dus geen screenshots.
- Bevinding overgenomen uit de review, blijft staan voor SF-1661: `docs/factory/secrets-local.md`
  noemt de allowlist nog met default `robbert@vdzon.com` en is nu feitelijk onjuist.
- Conclusie: goedgekeurd.
