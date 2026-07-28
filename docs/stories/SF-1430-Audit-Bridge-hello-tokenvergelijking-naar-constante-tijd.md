# SF-1430 - [Audit] Bridge-hello tokenvergelijking naar constante tijd

## Story

[Audit] Bridge-hello tokenvergelijking naar constante tijd

<!-- refined-by-factory -->

## Samenvatting
De WebSocket-hello van de factory-bridge vergelijkt het gedeelde geheime token met een gewone `!=`-vergelijking. Dat is niet veilig tegen timing-aanvallen. Elders in de code wordt hiervoor al een veilige, "constante tijd"-vergelijking gebruikt. Deze story past dezelfde veilige methode toe op de bridge-hello, zonder verder gedrag te veranderen.

## Scope
- Bestand: `dashboard-backend/src/main/kotlin/nl/vdzon/softwarefactory/dashboard/bridge/BridgeHub.kt`, functie `handleHello` (regel ~110).
- Vervang `hello.token != secrets.bridgeToken` door een timing-safe vergelijking op basis van `MessageDigest.isEqual`, analoog aan `AuthService.constantTimeEquals` (dashboard-backend/.../api/AuthService.kt, regel 80-81) en `BearerTokenAuthorizer.constantTimeEquals` (softwarefactory/.../config/BearerTokenAuthorizer.kt, regel 28-29).
- Omdat `BridgeHub.kt` en `AuthService.kt` in dezelfde module (dashboard-backend) zitten, mag de bestaande helper hergebruikt worden (bijv. door hem toegankelijk te maken) óf een eigen private `constantTimeEquals`-helper in `BridgeHub.kt` toegevoegd worden die hetzelfde `MessageDigest.isEqual`-patroon volgt. Beide opties zijn acceptabel; een aparte helper per bestand is het bestaande patroon in de codebase (zie ook de aparte kopie in `BearerTokenAuthorizer.kt` in de andere module) en dus de veiligste, minst risicovolle keuze.
- Geen wijziging aan de overige logica in `handleHello` (null-check, blank-check, logging, verbindingsafhandeling blijven ongewijzigd).
- Buiten scope: `AuthService.kt` en `BearerTokenAuthorizer.kt` zelf blijven ongewijzigd; dit is geen refactor-story naar een gedeelde util-functie.

## Acceptance criteria
- In `handleHello` wordt de token-vergelijking uitgevoerd via een timing-safe vergelijking (`MessageDigest.isEqual` op UTF-8 bytes), niet meer via `!=` op de String.
- Bij een correct token verloopt de hello-handshake exact zoals voorheen (sessie wordt geaccepteerd, oude sessie vervangen indien aanwezig, `connectedSince`/`factoryVersion` gezet).
- Bij een onjuist of ontbrekend token wordt de verbinding nog steeds geweigerd met dezelfde `logger.warn`-melding en `CloseStatus.POLICY_VIOLATION`, identiek aan het huidige gedrag.
- De bestaande check op `secrets.bridgeToken.isBlank()` blijft behouden (blanco server-token wordt nooit als geldig geaccepteerd).
- Geen regressie in bestaande tests rond `BridgeHub`/bridge-hello (indien aanwezig); build en testsuite van dashboard-backend slagen.

## Aannames
- Er is geen bestaande unit-test specifiek voor `handleHello`/token-afwijzing gevonden in deze verificatie; als die ontbreekt, voegt de developer bij voorkeur een kleine test toe die correct/incorrect token gedrag vastlegt, maar dit is geen harde eis van de story omdat het een pure beveiligingsfix zonder gedragswijziging betreft.
- De keuze tussen hergebruik van de bestaande `AuthService`-helper en een eigen kopie in `BridgeHub.kt` wordt aan de developer overgelaten; beide voldoen aan de acceptance criteria.

## Eindsamenvatting

Ik heb voldoende context. Alles is duidelijk: implementatie, tests en review zijn afgerond zonder open vragen.


**Eindsamenvatting SF-1430 — Constant-tijd tokenvergelijking in BridgeHub.handleHello**

**Wat is gebouwd**
- In `BridgeHub.kt` (`handleHello`) is de onveilige `hello.token != secrets.bridgeToken` vergelijking vervangen door `!constantTimeEquals(hello.token, secrets.bridgeToken)`.
- Een nieuwe private `constantTimeEquals(a, b)`-helper is toegevoegd aan `BridgeHub.kt`, gebaseerd op `MessageDigest.isEqual` over UTF-8-bytes — byte-voor-byte identiek aan de bestaande patronen in `AuthService.constantTimeEquals` en `BearerTokenAuthorizer.constantTimeEquals`.
- Alle overige logica in `handleHello` (null-check, blank-token-check, logging, `CloseStatus.POLICY_VIOLATION`, sessievervanging, `connectedSince`/`factoryVersion`) is ongewijzigd gebleven.

**Gemaakte keuzes**
- Er is gekozen voor een eigen private helper in `BridgeHub.kt` in plaats van hergebruik van de bestaande helper uit `AuthService`, conform het bestaande codepatroon (elke module/klasse heeft zijn eigen kopie). `AuthService.kt` en `BearerTokenAuthorizer.kt` zijn bewust ongewijzigd gelaten — dit was expliciet buiten scope.

**Wat is getest**
- `BridgeHubTest.kt` is aangevuld met een nieuwe happy-path test (`accepteert een hello met het correcte token`) die verifieert dat een sessie met correct token geaccepteerd wordt (`isConnected()`, `connectedSince()`, `factoryVersion()`).
- De bestaande test voor een foutief token blijft ongewijzigd en slaagt nog steeds.
- Gericht: `mvn -pl dashboard-backend -Dtest=BridgeHubTest test` → 7 tests, 0 failures/errors.
- Volledig: `mvn verify` vanaf de repo-root → BUILD SUCCESS over alle modules, dashboard-backend 48 tests, 0 failures/errors.
- Reviewer heeft de implementatie geverifieerd tegen de referentiepatronen en akkoord gegeven; geen scope creep geconstateerd.

**Bewust niet gedaan**
- Geen refactor naar een gedeelde util-functie voor `constantTimeEquals` over modules heen — expliciet buiten scope van deze story.
- Geen wijzigingen aan `AuthService.kt`, `BearerTokenAuthorizer.kt`, functional-spec, technical-spec of UX-documentatie, omdat dit een puur interne, gedragsneutrale beveiligingsfix betreft zonder impact op API of architectuur.
