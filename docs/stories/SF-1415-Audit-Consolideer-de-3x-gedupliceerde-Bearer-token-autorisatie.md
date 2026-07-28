# SF-1415 - [Audit] Consolideer de 3x gedupliceerde Bearer-token-autorisatie in softwarefactory-controllers

## Story

[Audit] Consolideer de 3x gedupliceerde Bearer-token-autorisatie in softwarefactory-controllers

<!-- refined-by-factory -->

## Samenvatting
Drie API-controllers in de factory checken elk op hun eigen manier of een binnenkomend verzoek een geldig Bearer-token heeft. Die controlelogica is drie keer bijna woordelijk gekopieerd. We bundelen dit in één herbruikbaar stukje code, zodat er nog maar één plek is om te onderhouden. Het gedrag voor gebruikers en aanroepende systemen verandert niet: dezelfde endpoints blijven dezelfde tokens accepteren en dezelfde foutmeldingen geven.

## Scope
- Consolideer de Bearer-token-autorisatielogica die nu apart voorkomt in:
  - `FactoryApiController.kt` (`restart()` + `constantTimeEquals`, regels 48-73)
  - `TrackerStoryApiController.kt` (`authorize()` + `constantTimeEquals`, regels 140-156)
  - `CompletionOperationsController.kt` (`authorized()`, regels 42-51)
- Trek dit naar één gedeelde helper (bijv. in de `config`-package naast `ConfigApi`, of een nieuw gedeeld bestand in `web/controllers`), en laat alle drie controllers deze helper aanroepen.
- De helper moet, functioneel identiek aan het huidige gedrag in elke controller:
  - het token ophalen via `ConfigApi.resolvedValues()["SF_FACTORY_API_TOKEN"]`;
  - bij ontbrekende/lege env-var: geen toegang verlenen (401), zoals nu in elke controller gebeurt;
  - de `Authorization`-header lezen, het `Bearer `-prefix strippen (alleen als de header ermee begint, anders lege string, zoals nu);
  - constante-tijd vergelijken via `MessageDigest.isEqual`;
  - bij mismatch/ontbrekend token: 401 Unauthorized.
- Pure verplaatsing/consolidatie — geen gedragswijziging: zelfde env-var, zelfde header-parsing, zelfde vergelijkingsmethode, zelfde 401-response.
- `dashboard-backend`'s eigen `AuthService` (Google-SSO, andere flow) blijft volledig ongemoeid — dit betreft alleen de drie genoemde `softwarefactory`-controllers.
- Bestaande logregels (`logger.warn(...)` bij ontbrekend token / ongeldig token) mogen desgewenst verplaatst worden naar de helper, zolang het geloggede gedrag functioneel gelijkwaardig blijft (het is geen extern zichtbaar gedrag en dus geen harde eis, maar wel netjes om te behouden).
- Testbestanden die dit gedrag raken en dus moeten blijven slagen zonder aanpassing van hun verwachtingen: `FactoryApiControllerTest.kt` en `TrackerStoryApiControllerTest.kt` (geen bestaande test gevonden voor `CompletionOperationsController`, dus daar is er geen regressierisico vanuit tests, maar wel vanuit de e2e-laag).

## Acceptance criteria
- Er bestaat één gedeelde, herbruikbare autorisatie-helper voor het Bearer-token-patroon tegen `SF_FACTORY_API_TOKEN`.
- `FactoryApiController.restart()`, `TrackerStoryApiController.authorize()` en `CompletionOperationsController.requeue()` gebruiken alle drie deze gedeelde helper in plaats van hun eigen duplicaat-implementatie en eigen `constantTimeEquals`.
- Er is geen eigen `constantTimeEquals`/token-vergelijkingslogica meer gedupliceerd over deze drie controllers.
- Het gedrag van alle drie endpoints (`/api/restart`, `/api/tracker/...`, `/api/completions/.../requeue`) blijft exact hetzelfde:
  - geldig Bearer-token -> toegang;
  - ontbrekend/leeg `SF_FACTORY_API_TOKEN` -> 401;
  - ontbrekende/foutieve `Authorization`-header of token -> 401.
- `mvn -f softwarefactory/pom.xml test` slaagt, inclusief de bestaande `FactoryApiControllerTest.kt` en `TrackerStoryApiControllerTest.kt` zonder aanpassing van hun assertions op het autorisatiegedrag.
- Idealiter ook `mvn -f softwarefactory/pom.xml verify` (e2e-laag) succesvol, ter bevestiging dat het end-to-end gedrag ongewijzigd is.
- De docstring-comment in `TrackerStoryApiController.kt` (regel 140, "Bearer-token-check, zelfde patroon als FactoryApiController.restart") wordt bijgewerkt of verwijderd zodra de duplicatie is opgelost, zodat hij niet langer verwijst naar een los duplicaat maar naar de gedeelde helper.

## Aannames
- De exacte locatie van de nieuwe gedeelde helper (config-package naast `ConfigApi`, of een nieuw bestand in `web/controllers`) is een implementatiekeuze voor de developer; beide genoemde opties in de story-beschrijving zijn akkoord.
- Loggingteksten/logniveaus mogen tijdens de consolidatie licht anders gefraseerd worden (bijv. generieke helper-boodschap i.p.v. per-endpoint tekst), zolang er bij falende autorisatie nog steeds een waarschuwing gelogd wordt; dit is geen extern waarneembaar gedrag en dus geen harde eis.
- `CompletionOperationsController` heeft geen bestaande unit-test; de bestaande e2e-suite (indien aanwezig voor `/api/completions/.../requeue`) dient als vangnet voor regressie op dit endpoint. Als die er niet is, volstaat handmatige/impliciete dekking via de `mvn verify`-stap.

## Eindsamenvatting

## Eindsamenvatting SF-1415 — Consolidatie Bearer-token-autorisatie

**Wat is gebouwd**
De Bearer-token-autorisatiecheck die drie keer bijna letterlijk gedupliceerd was (`FactoryApiController.restart()`, `TrackerStoryApiController.authorize()`, `CompletionOperationsController.authorized()`) is samengebracht in één nieuwe gedeelde helper: `nl.vdzon.softwarefactory.config.BearerTokenAuthorizer.isAuthorized(configApi, request)`. Alle drie controllers roepen nu deze ene helper aan; de losse `constantTimeEquals`-implementaties en bijbehorende ongebruikte imports zijn verwijderd. De verouderde docstring in `TrackerStoryApiController.kt` is bijgewerkt zodat die naar de gedeelde helper verwijst in plaats van naar het oude duplicaat-patroon.

**Gedrag**
Functioneel exact hetzelfde gebleven: zelfde env-var (`SF_FACTORY_API_TOKEN` via `ConfigApi.resolvedValues()`), zelfde header-parsing (`Bearer `-prefix strippen, anders lege string), zelfde constante-tijd vergelijking (`MessageDigest.isEqual`), en dezelfde 401-response bij ontbrekend/ongeldig token of ontbrekende env-var. `dashboard-backend`'s eigen `AuthService` (Google-SSO) is niet aangeraakt.

**Belangrijke keuze**
De helper is `internal` (niet public) gemaakt. Dit was nodig omdat twee architectuur-guardrail-tests (`ModuleApiConventionTest` en `ModulithArchitectureTest`) elkaar tegenspreken over waar een publieke helperklasse zou mogen staan; `internal` voldoet aan beide guardrails tegelijk zonder de Modulith-allowlist uit te breiden, en is binnen de ene Maven-module overal bruikbaar.

**Getest**
- Nieuwe unit-tests `BearerTokenAuthorizerTest.kt`: geldig token, ontbrekende/lege env-var, ontbrekende header, header zonder `Bearer`-prefix, token-mismatch (6 tests, groen).
- Bestaande `FactoryApiControllerTest` en `TrackerStoryApiControllerTest` ongewijzigd en nog steeds groen (5/5, 4/4) — bevestigt dat het externe gedrag niet is veranderd.
- Beide architectuur-guardrail-tests groen (9/9).
- `mvn -f softwarefactory/pom.xml test` en `mvn verify` (incl. e2e/Testcontainers) beide BUILD SUCCESS.
- Review en losstaande test-run door reviewer/tester bevestigen onafhankelijk dat de consolidatie correct en compleet is, zonder scope creep.

**Bewust niet gedaan**
- Geen nieuwe test toegevoegd voor `CompletionOperationsController` zelf — dat endpoint had al geen unit-test vóór deze wijziging (conform de aanname in de refined story); de e2e-laag dient hier als vangnet.
- Geen wijzigingen aan `docs/factory/*`-specificaties, omdat dit een pure interne refactor is zonder extern zichtbaar of architectuurrelevant gedrag.
- Een vooraf bestaand, niet-gerelateerd build-probleem (stale lokale `factory-contracts`-jar) is terzijde opgelost door een lokale her-install, zonder productiecode te wijzigen.
