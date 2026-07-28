# SF-1415 - Worklog

Story-context bij eerste pickup:
Consolideer Bearer-token-autorisatie naar gedeelde helper

Trek de gedupliceerde Bearer-token-check (env-var ophalen via ConfigApi.resolvedValues()["SF_FACTORY_API_TOKEN"], Authorization-header parsen, MessageDigest.isEqual-vergelijking, 401 bij falen) uit FactoryApiController.restart(), TrackerStoryApiController.authorize() en CompletionOperationsController.authorized() naar één gedeelde helper in de config-package naast ConfigApi. Laat alle drie controllers deze helper gebruiken en verwijder de lokale constantTimeEquals-duplicaten. Behoud het externe 401-gedrag exact. Werk de verouderde docstring-comment in TrackerStoryApiController.kt:140 bij zodat die naar de gedeelde helper verwijst. Voeg unit tests toe voor de nieuwe helper (geldig token, ontbrekend/leeg env-var, ontbrekende/foute header, mismatch). Raak dashboard-backend's AuthService niet aan.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale (SF-1416, developer):
- Nieuwe gedeelde helper `nl.vdzon.softwarefactory.config.BearerTokenAuthorizer` (internal object,
  `softwarefactory/src/main/kotlin/.../config/BearerTokenAuthorizer.kt`) met
  `isAuthorized(configApi, request): Boolean`: leest `SF_FACTORY_API_TOKEN` via
  `ConfigApi.resolvedValues()`, parst de `Authorization`-header (`Bearer `-prefix strippen, anders
  lege string), en vergelijkt constante-tijd via `MessageDigest.isEqual`. Functioneel identiek aan
  de drie oude duplicaten.
  - `internal` (niet public) gekozen omdat `ModuleApiConventionTest` concrete publieke roottypen in
    een module-basepackage verbiedt (alleen `ConfigApi` als publiek contract), terwijl
    `ModulithArchitectureTest` een sub-package als `config.services` weer NIET toestaat als
    afhankelijkheid vanuit `web` (niet in de Modulith-allowlist). `internal` is binnen deze ene
    Maven-module (softwarefactory-app) overal aanroepbaar, dus voldoet aan beide guardrails
    tegelijk zonder de Modulith-allowlist uit te breiden.
- `FactoryApiController.restart()`, `TrackerStoryApiController.authorize()` en
  `CompletionOperationsController.authorized()` gebruiken nu allemaal deze helper; de drie lokale
  `constantTimeEquals`-duplicaten zijn verwijderd (incl. ongebruikte `MessageDigest`/
  `StandardCharsets`-imports).
- Docstring op `TrackerStoryApiController.kt` (klasse-comment + `authorize()`-comment) bijgewerkt om
  naar de gedeelde `BearerTokenAuthorizer` te verwijzen i.p.v. naar het oude duplicaat-patroon in
  `FactoryApiController.restart`.
- Nieuwe unit-tests `config/BearerTokenAuthorizerTest.kt`: geldig token, ontbrekende/lege
  `SF_FACTORY_API_TOKEN`, ontbrekende Authorization-header, header zonder `Bearer `-prefix, token-
  mismatch.
- Bestaande `FactoryApiControllerTest.kt` en `TrackerStoryApiControllerTest.kt` ongewijzigd gelaten
  en beide draaien groen (zelfde autorisatiegedrag/assertions).
- `CompletionOperationsController` had geen bestaande unit-test (conform aanname in de refined
  story); geen nieuwe toegevoegd, e2e-laag (`mvn verify`) dient als vangnet.
- Boyscout-fix (buiten scope van SF-1416 zelf, maar blokkeerde de volledige build): `mvn -f
  softwarefactory/pom.xml test`/`mvn verify` faalden vooraf al met een Kotlin-compilefout
  (`Unresolved reference 'auditReportMarkdown'` in `AuditGatewayAdapter.kt`) door een stale
  geïnstalleerde `factory-contracts`-jar in `~/.m2` (het veld bestaat al langer in
  `factory-contracts/.../AgentResultFile.kt`). Opgelost door `mvn -pl factory-contracts -am install
  -DskipTests` opnieuw te draaien; geen productiecode aangepast voor dit deel.
- Bewijs: `mvn -f softwarefactory/pom.xml test` → BUILD SUCCESS, geen failures/errors. `mvn verify`
  vanaf de repo-root → BUILD SUCCESS voor alle modules (incl. Testcontainers-e2e in
  softwarefactory, ondanks dat een losse `docker info` "NOT available" meldde in deze sandbox —
  bekend uit eerdere runs, gewoon proberen).
- Geen specs in `docs/factory/` aangepast: dit is een pure interne refactor (helper-extractie)
  zonder extern waarneembaar of architectuur-relevant gedrag; er is geen bestaande functional-
  spec/technical-spec/UX-paragraaf die deze drie controllers of hun auth-implementatiedetail
  beschrijft.

Review-notities (SF-1416, reviewer):
- Diff (`git diff main...HEAD`) bevat exact de aangekondigde consolidatie: nieuwe
  `config/BearerTokenAuthorizer.kt` (internal object), alle drie controllers (`FactoryApiController`,
  `TrackerStoryApiController`, `CompletionOperationsController`) roepen `isAuthorized(...)` aan, lokale
  `constantTimeEquals`-duplicaten en ongebruikte imports zijn weg. Token-ophaal (`resolvedValues()["SF_FACTORY_API_TOKEN"]`,
  blank-check → false), header-parsing (`Bearer `-prefix, anders lege string) en `MessageDigest.isEqual`-vergelijking zijn
  functioneel identiek aan de drie oude duplicaten overgenomen — geen gedragswijziging op de 401-paden.
  Docstring op `TrackerStoryApiController.kt` (klasse + `authorize()`) is bijgewerkt en verwijst nu naar de
  gedeelde helper i.p.v. het oude duplicaat-patroon, conform de acceptance criteria.
  `internal`-keuze (i.p.v. public) is een houdbare oplossing voor de tegenstrijdige architectuurguardrails
  (`ModuleApiConventionTest` vs. `ModulithArchitectureTest`) en beide guardrail-tests staan lokaal groen
  (`target/surefire-reports/nl.vdzon.softwarefactory.ModuleApiConventionTest.txt`,
  `...ModulithArchitectureTest.txt`, beide 0 failures/errors).
- Nieuwe `config/BearerTokenAuthorizerTest.kt` dekt de gevraagde gevallen (geldig token, ontbrekende/lege
  env-var, ontbrekende header, header zonder prefix, mismatch) en staat lokaal groen (6/6). Bestaande
  `FactoryApiControllerTest`/`TrackerStoryApiControllerTest` ongewijzigd en groen (5/5, 4/4). Alle 72
  surefire-reports in de workspace tonen 0 failures/errors, geen rode of ontbrekende testen aangetroffen.
- Geen scope creep: `dashboard-backend`'s `AuthService`/Google-SSO-flow is niet aangeraakt. Geen
  `docs/factory/*`-specs raken dit implementatiedetail, dus geen inconsistentie ontstaan. Geen wijziging
  aan `.factory/verification.yaml`, dus geen risico op een fail-open route daar.
- Akkoord: consolidatie is correct, testdekking is toereikend, geen regressie op de drie endpoints.
