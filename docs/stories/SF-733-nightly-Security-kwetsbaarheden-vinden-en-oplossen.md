# SF-733 - nightly: Security: kwetsbaarheden vinden en oplossen

## Story

nightly: Security: kwetsbaarheden vinden en oplossen

<!-- refined-by-factory -->

## Scope

Voer een security-audit uit op de code van deze repository (de Maven-modules `softwarefactory`, `agentworker`, `dashboard-backend` en de Flutter `dashboard-frontend`) en verhelp gevonden kwetsbaarheden, uitsluitend zolang de fix het functionele gedrag exact gelijk houdt.

Te onderzoeken patronen:
- Injectie (SQL/OS-command/template/path-traversal e.d.).
- Ontbrekende in-/uitvoervalidatie en onveilige deserialisatie.
- Onveilige defaults (bijv. te ruime permissies, onveilige library-configuratie).
- Gelekte of hardcoded secrets, en secrets die ongeredigeerd gelogd worden.
- Ontbrekende of te zwakke authenticatie/autorisatie op endpoints.
- Verouderde of bekend-kwetsbare dependencies.

Gedragsneutrale fixes (bijv. parameteriseren van een query, secret uit log redigeren, veiliger parser-configuratie zoals SnakeYAML `SafeConstructor`, dependency-bump zonder API-/gedragswijziging, input-validatie die alleen al-ongeldige/onbedoelde input afwijst) worden zelf doorgevoerd. Worden er geen gedragsneutrale fixes gevonden, dan is dit een geldige no-op: leg de bevindingen en de conclusie vast in `docs/stories/worklog/SF-733-worklog.md` zonder codewijziging.

Buiten scope: elke wijziging die observeerbaar gedrag verandert, en wijzigingen die alleen groen worden door een integratietest aan te passen.

## Acceptance criteria

- De relevante modulecode is nagelopen op de in scope genoemde security-patronen; bevindingen en genomen besluiten staan in `docs/stories/worklog/SF-733-worklog.md`.
- Doorgevoerde codewijzigingen zijn aantoonbaar gedragsneutraal: geen wijziging in functioneel gedrag, API-contracten of observeerbare output.
- Alle bestaande tests blijven slagen (`mvn test` over `softwarefactory`, `agentworker`, `dashboard-backend`).
- Geen enkele integratie-/e2e-test (package `...e2e`) is gewijzigd. Zou een fix alleen groen worden door een integratietest aan te passen, dan wordt die fix niet doorgevoerd en gaat de story in `Error` met een duidelijke beschrijving.
- Een gevonden kwetsbaarheid waarvan de fix gedrag zou veranderen of risicovol/groot is, wordt niet zelf opgelost: de story gaat in `Error` met een concrete beschrijving van de bevinding (locatie, risico, voorgestelde aanpak) zodat een mens het oppakt.
- Er worden geen secrets geïntroduceerd of in (niet-geredigeerde) vorm gelogd.
- Bij een no-op (geen gedragsneutrale fix gevonden) staat dat met onderbouwing in de worklog en is er geen functionele codewijziging.

## Aannames

- Dit is de terugkerende nightly `security`-job en draait `Silent=true`: de story wordt autonoom afgewerkt; onduidelijkheden leiden tot een `[CLARIFICATION]`-error in plaats van wachten op een mens.
- "Functioneel gedrag exact hetzelfde" wordt operationeel afgebakend door de bestaande test-suite plus de integratie-/e2e-tests als vangnet: ongewijzigd groen blijven is de meetlat voor gedragsbehoud.
- "Verouderde of kwetsbare dependencies" verhelpen blijft beperkt tot bumps zonder API-/gedragswijziging; een major upgrade of een bump met breaking changes valt onder "risicovol" → `Error`, niet zelf doorvoeren.
- Het bijwerken van de worklog en (waar van toepassing) security-relevante documentatie geldt niet als functionele gedragswijziging en is toegestaan.
- De omvang per nachtelijke run is bewust klein gehouden: liever een paar zekere, gedragsneutrale fixes (of een gemotiveerde no-op) dan een grote, risicovolle wijziging.

## Eindsamenvatting

## Eindsamenvatting SF-733 — nightly Security: kwetsbaarheden vinden en oplossen

### Wat is gebouwd
Een security-audit over de in-scope modules (`softwarefactory`, `agentworker`, `dashboard-backend`, Flutter `dashboard-frontend`) met één gedragsneutrale fix:

- **Timing-side-channel weggenomen op `/api/restart`.** De Bearer-tokencheck in `FactoryApiController.kt` vergeleek het token met een gewone `!=` (`String.equals`, niet constant-tijd). Dit is dezelfde kwetsbaarheidsklasse die SF-429 eerder al verhielp in `FactoryDashboardAuth` en de dashboard-backend `AuthService`; dit endpoint was daarbij over het hoofd gezien. Opgelost met een private `constantTimeEquals` (UTF-8-bytes + `MessageDigest.isEqual`), conform de bestaande conventie.

### Gemaakte keuzes
- Alleen een **gedragsneutrale** fix doorgevoerd: gelijk token → `200`/restart, ongelijk/ontbrekend/ongeconfigureerd token → `401`. API-contract en observeerbare output ongewijzigd; enkel het timingverschil verdwijnt.
- Overige in-scope patronen nagelopen en **in orde bevonden** (grotendeels al gehard door eerdere stories): SQL-injectie (overal `?`-parameters, schema's gevalideerd), OS-command-injectie (alle `ProcessBuilder`-aanroepen in list-vorm), deserialisatie/YAML (SnakeYAML met `SafeConstructor`, SF-565), dashboard-auth (HMAC-cookie httpOnly + SameSite, constant-tijd wachtwoord), gelogde secrets (alleen `redactedSummary()`), en dependencies (alle modules erven recente Spring Boot 3.5.14-BOM, geen bump nodig).

### Wat is getest
- `FactoryApiControllerTest`: 5 tests groen (incl. nieuwe mismatch- en ontbrekende-header-paden).
- Volledige `softwarefactory`-suite: 444 run, **0 Failures**; de 32 Errors zijn exact de bekende no-Docker env-baseline (e2e + 2 container-tests).
- `agentworker`: 34/0 Failures · `dashboard-backend`: 13/0 Failures.
- Geen enkele integratie-/e2e-test (`...e2e`) gewijzigd; diff beperkt tot worklog, `FactoryApiController.kt` en bijbehorende unittest.

### Wat bewust NIET is gedaan (geen Error)
- De HTML-dashboard-views bouwen HTML via string-interpolatie. Consequente output-encoding toevoegen zou de observeerbare output kunnen wijzigen en valt daarmee buiten "gedragsneutraal" — niet aangeraakt in deze run. Aanbevolen als losse, bewust gescopete vervolgstory voor een mens.
- Onauthenticeerde interne endpoints (`/agent-knowledge`, `/agent-run/complete`) en het publieke `/api/version` zijn bewuste ontwerpkeuzes (agent-callbacks resp. versie-info) en zijn niet aangepast.

**Conclusie:** één zekere, gedragsneutrale security-fix opgeleverd met groene tests; geen blokkades, geen Error.
