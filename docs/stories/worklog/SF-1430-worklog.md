# SF-1430 - Worklog

Story-context bij eerste pickup:
Constant-tijd tokenvergelijking in BridgeHub.handleHello

Vervang `hello.token != secrets.bridgeToken` in `dashboard-backend/src/main/kotlin/nl/vdzon/softwarefactory/dashboard/bridge/BridgeHub.kt` (regel ~110) door een timing-safe vergelijking. Voeg een private `constantTimeEquals(a: String, b: String): Boolean`-helper toe aan `BridgeHub.kt` die `MessageDigest.isEqual` gebruikt op UTF-8-bytes, exact analoog aan `AuthService.constantTimeEquals` (dashboard-backend/.../api/AuthService.kt, regel 80-81) en `BearerTokenAuthorizer.constantTimeEquals` (softwarefactory/.../config/BearerTokenAuthorizer.kt, regel 28-29). Gebruik `!constantTimeEquals(hello.token, secrets.bridgeToken)` in de bestaande conditie, met behoud van de `hello == null ||`- en `secrets.bridgeToken.isBlank() ||`-checks ervoor. Geen wijziging aan overige logica: null-check, logging, `CloseStatus.POLICY_VIOLATION`, sessievervanging, `connectedSince`/`factoryVersion`. `AuthService.kt` en `BearerTokenAuthorizer.kt` blijven ongewijzigd (buiten scope). Vul `BridgeHubTest.kt` aan met een happy-path test (correct token → sessie geaccepteerd) als die nog ontbreekt; de bestaande test 'weigert een hello met een fout token' moet blijven slagen.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- SF-1432 (development): `hello.token != secrets.bridgeToken` in `BridgeHub.handleHello`
  vervangen door `!constantTimeEquals(hello.token, secrets.bridgeToken)`. Nieuwe private
  `constantTimeEquals(a: String, b: String): Boolean` toegevoegd aan `BridgeHub.kt`
  (`MessageDigest.isEqual` op UTF-8-bytes), exact analoog aan `AuthService.constantTimeEquals`
  en `BearerTokenAuthorizer.constantTimeEquals`. `AuthService.kt`/`BearerTokenAuthorizer.kt`
  bewust ongewijzigd gelaten (buiten scope; geen gedeelde util-refactor). Overige logica in
  `handleHello` (null-check, blank-check, logging, sessievervanging, `connectedSince`/
  `factoryVersion`, `CloseStatus.POLICY_VIOLATION`) ongewijzigd.
- `BridgeHubTest.kt` aangevuld met een expliciete happy-path test
  (`accepteert een hello met het correcte token`) die verifieert dat de sessie geaccepteerd
  wordt (`isConnected()`, `connectedSince()`, `factoryVersion()`) bij een correct token; de
  bestaande test `weigert een hello met een fout token` bleef ongewijzigd en slaagt nog steeds.
- Geen aanpassingen nodig aan `docs/factory/functional-spec.md`/`technical-spec.md`/UX-docs:
  puur interne, gedragsneutrale beveiligingsfix zonder impact op API/gedrag/architectuur.
- Bewijs groen: `mvn -pl dashboard-backend -Dtest=BridgeHubTest test` → Tests run: 7, Failures: 0,
  Errors: 0. Volledig vangnet: `mvn verify` vanaf de repo-root → BUILD SUCCESS, alle modules
  (factory-contracts, factory-common, softwarefactory, agentworker, dashboard-backend) SUCCESS,
  dashboard-backend Tests run: 48, Failures: 0, Errors: 0.

## Review-notities (SF-1432)

- `BridgeHub.constantTimeEquals` komt byte-voor-byte overeen met `AuthService.constantTimeEquals`
  en `BearerTokenAuthorizer.constantTimeEquals` (zelfde `MessageDigest.isEqual` op UTF-8-bytes).
  `hello == null ||`- en `secrets.bridgeToken.isBlank() ||`-checks ongewijzigd; `!=` correct
  vervangen door `!constantTimeEquals(...)` als laatste voorwaarde in dezelfde `||`-keten.
- Gericht opnieuw gedraaid: `mvn -pl dashboard-backend -Dtest=BridgeHubTest test` →
  `Tests run: 7, Failures: 0, Errors: 0` (surefire-report bevestigt dit), inclusief de nieuwe
  happy-path-test en de bestaande fout-token-test. Geen volledige `mvn verify` opnieuw gedraaid
  (developer-bewijs in worklog al aanwezig; dat is de taak van de tester-subtaak SF-1433).
  AuthService.kt en BearerTokenAuthorizer.kt ongewijzigd, zoals vereist.
- `docs/factory/technical-spec.md`/`functional-spec.md`/UX-docs bevatten geen beschrijving van de
  interne tokenvergelijking-implementatie; geen spec-inconsistentie door deze wijziging.
- Akkoord: implementatie is coherent, exact volgens scope, geen scope creep, testdekking aanwezig.
