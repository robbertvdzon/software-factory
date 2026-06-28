# SF-565 - nightly: Security: kwetsbaarheden vinden en oplossen

## Story

nightly: Security: kwetsbaarheden vinden en oplossen

<!-- refined-by-factory -->

## Scope
Voer een security-review uit op de code in deze repository en verhelp gevonden problemen, mits de fix het functionele gedrag exact ongewijzigd laat.

Te onderzoeken patronen (niet uitputtend):
- Injectie (SQL/command/template) en onveilige stringinterpolatie in queries of shell-aanroepen.
- Ontbrekende of zwakke in- en uitvoervalidatie (inclusief padtraversal, deserialisatie, untrusted input richting externe systemen).
- Onveilige defaults (te ruime permissies, debug/verbose standen aan, permissieve CORS/TLS, voorspelbare of zwakke crypto/randomness).
- Gelekte secrets in code, configuratie, logging of testfixtures; secrets die niet-geredigeerd worden gelogd.
- Ontbrekende of onjuiste authenticatie/autorisatie op endpoints of bevoorrechte acties.
- Verouderde of bekend-kwetsbare dependencies (Maven-modules `softwarefactory`, `agentworker`, `dashboard-backend`; Flutter `dashboard-frontend`).

Aanpak:
- Veilige, gedragsneutrale fixes worden direct doorgevoerd in de code.
- Per doorgevoerde fix wordt in `docs/stories/worklog/SF-565-worklog.md` vastgelegd: wat het probleem was, waar, en waarom de fix gedragsneutraal is.
- Bevindingen die niet veilig op te lossen zijn (gedragsverandering, grote refactor, risicovolle dependency-upgrade) worden niet zelf doorgevoerd, maar gedocumenteerd en geëscaleerd via de error-route zodat een mens ze oppakt.

Buiten scope:
- Wijzigingen die functioneel gedrag veranderen.
- Aanpassen van integratie-/e2e-tests om een fix groen te krijgen.
- Het aanmaken van nieuwe features of het herstructureren van architectuur.

## Acceptance criteria
- De code is op de bovengenoemde security-patronen nagelopen; het resultaat (gevonden issues, fixes, en geëscaleerde/niet-opgeloste punten) is gedocumenteerd in `docs/stories/worklog/SF-565-worklog.md`.
- Elke doorgevoerde codewijziging is gedragsneutraal: het waarneembare functionele gedrag van de applicatie blijft exact gelijk.
- Alle bestaande tests slagen onveranderd: `mvn -f softwarefactory/pom.xml test`, `mvn -f agentworker/pom.xml test` en `mvn -f dashboard-backend/pom.xml test` (of `mvn test` vanaf de root).
- Er zijn geen integratie-/e2e-tests gewijzigd. Als een fix alleen groen te krijgen is door een integratietest aan te passen, wordt de fix niet doorgevoerd maar geëscaleerd via de error-route.
- Wijzigingen volgen de repo-conventies: geen project-interne wildcard-imports, `SF_`-prefix voor nieuwe config, secrets nooit ongeredigeerd gelogd, en stdlib-guards (`require`/`requireNotNull`/`error`) waar passend.
- Worden er geen veilig-oplosbare problemen gevonden, dan is een no-op met onderbouwing in het worklog (welke gebieden/patronen zijn bekeken) een acceptabele uitkomst.
- Risicovolle of gedragsveranderende bevindingen worden niet stilzwijgend doorgevoerd, maar met concrete beschrijving geëscaleerd zodat een mens ze kan beoordelen.

## Aannames
- "Functioneel gedrag exact gelijk" wordt geïnterpreteerd op waarneembaar gedragsniveau (API-responses, opgeslagen data, externe effecten); puur interne, niet-waarneembare hardening (bijv. parametrized queries i.p.v. stringconcatenatie met dezelfde uitkomst, het redigeren van een gelekte secret in logging) telt als gedragsneutraal en mag.
- Het verhelpen van een gelekt secret omvat ook het roteren-signaal: de fix verwijdert/redigeert het secret in de code en markeert in het worklog dat rotatie door een mens nodig kan zijn; de agent roteert zelf geen externe credentials.
- Een dependency-upgrade geldt als "veilig" alleen wanneer het een patch/minor betreft zonder API- of gedragswijziging én alle tests groen blijven; majors of upgrades met breaking changes worden geëscaleerd.
- De drie Maven-modules en de Flutter-frontend vormen samen de te reviewen codebase; de e2e-/pipeline-tests onder `softwarefactory/src/test/.../e2e` gelden als integratietests die niet gewijzigd mogen worden.
- "Ga in error" verwijst naar de factory-error-route (story op Error zetten met een duidelijke, mens-leesbare beschrijving), niet naar het stilletjes overslaan van de bevinding.
</aannames>

## Eindsamenvatting

## Eindsamenvatting — SF-565: nightly Security-review

**Wat is gebouwd / verholpen**
Een story-brede security-review over de vier codebases (`softwarefactory`, `agentworker`, `dashboard-backend`, `dashboard-frontend`) op injectie, in-/uitvoervalidatie, onveilige defaults, gelekte secrets, auth/authz en kwetsbare dependencies.

Eén concrete kwetsbaarheid gevonden en gedragsneutraal opgelost: **onveilige YAML-deserialisatie (RCE-risico)**. `Yaml().load(...)` met de default SnakeYAML-constructor stond instantiatie van willekeurige Java-typen toe via YAML-tags (`!!some.java.Type`), wat via een gadget-payload tot remote code execution kon leiden. Beide parsers gebruiken nu `Yaml(SafeConstructor(LoaderOptions()))`:
- `ProjectRepoResolver.kt` (`projects.yaml`)
- `NightlyJobsReader.kt` (`job.yaml`, deels untrusted: gelezen uit geconfigureerde project-repo's — het meest relevante pad)

**Gemaakte keuzes**
- Fix is bewust beperkt tot het afsluiten van type-instantiatie; geldige config levert exact dezelfde objecten op → gedragsneutraal.
- Overige onderzochte gebieden bleken al afdoende gehard en vereisten geen actie: SQL-queries gebruiken placeholders; `ProcessBuilder` gebruikt list-vorm (geen shell); git-token via env-var i.p.v. script; TLS valideert (geen trust-all); cookies `HttpOnly`/`Secure`/`SameSite=Lax` met constant-time auth-vergelijking; secrets worden geredigeerd gelogd; dashboard-HTML escapet dynamische data via `String.e()`.
- Geen speculatieve dependency-bumps zonder concrete CVE (Spring Boot 3.5.14 / Kotlin 2.1.21 zijn recent) — valt buiten "veilig + gedragsneutraal".

**Wat is getest**
- Nieuwe unit-test weigert een `ScriptEngineManager`-gadget-payload en levert een lege resolver.
- Doelgerichte tests: `ProjectRepoResolverTest` + `ProjectRepoResolverMergeDeployTest` → 18 run, 0 failures.
- Volledige module-suite `softwarefactory`: 425 run, **0 failures**. De 25 errors zijn pre-existing omgevingsfouten (Docker/Testcontainers e2e-package niet beschikbaar in de testomgeving) en raken de gewijzigde code niet.

**Bewust niet gedaan**
- Geen integratie-/e2e-tests gewijzigd.
- Geen functioneel gedrag aangepast, geen nieuwe config/env-vars, geen architectuur-refactor.
- Geen escalaties: er zijn geen risicovolle of gedragsveranderende bevindingen aangetroffen die via de error-route opgepakt moeten worden.

**Impact voor de PO:** een reëel RCE-deserialisatiepad is gedicht zonder enige waarneembare gedragsverandering; de resterende codebase was al goed gehard.
