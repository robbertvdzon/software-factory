# SF-1487 - [Audit] Lees SF_ASSISTANT_IMAGE en SF_ASSISTANT_TIMEOUT_SECONDS via ConfigApi in plaats van System.getenv

## Story

[Audit] Lees SF_ASSISTANT_IMAGE en SF_ASSISTANT_TIMEOUT_SECONDS via ConfigApi in plaats van System.getenv

<!-- refined-by-factory -->

## Samenvatting

De Telegram-assistent leest twee instellingen (welk Docker-image hij gebruikt en na
hoeveel seconden een beurt wordt afgekapt) op een andere manier dan de rest van de
factory. Daardoor doen die twee instellingen niets als je ze in het configuratiebestand
zet — ze werken alleen als je ze als echte omgevingsvariabele exporteert, wat in de
praktijk niet gebeurt. De documentatie belooft wel dat het bestand werkt.

Deze story laat beide instellingen via dezelfde configuratie-ingang lopen als alle
andere sleutels, zodat ze doen wat de documentatie zegt. Voor wie niets instelt
verandert er niets: dezelfde standaardwaarden, hetzelfde gedrag.

## Scope

In scope — uitsluitend `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/telegram/clients/ClaudeAssistantClient.kt`:

1. `timeoutSeconds` (regel ~48-51): vervang `System.getenv("SF_ASSISTANT_TIMEOUT_SECONDS")`
   door `configApi.resolvedValues()["SF_ASSISTANT_TIMEOUT_SECONDS"]`, met exact dezelfde
   keten erachter: `?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_SECONDS`.
2. `IMAGE` (regel ~371-372): verplaats uit het private companion object naar een private
   instance-val (bijv. `image`) en lees hem als
   `configApi.resolvedValues()["SF_ASSISTANT_IMAGE"]?.takeIf { it.isNotBlank() } ?: "assistant:local"`.
   Pas de twee gebruiksplekken aan: `dockerCommand()` (regel ~182) en de warn-log in
   `runDocker()` (regel ~235).
3. Verwijder de twee `// TODO(fase 3): via ConfigApi`-commentaren.
4. Voeg een unittest toe die met een stub-`ConfigApi` aantoont dat beide sleutels uit
   `resolvedValues()` worden gelezen én dat de defaults gelden bij een lege map.

Buiten scope:

- `IMAGE_EXTS`, `MODEL`, `DEFAULT_TIMEOUT_SECONDS`, `STOPPED_REPLY` en `DISALLOWED_TOOLS`
  blijven ongewijzigd in het private companion object.
- Alle andere `System.getenv`/`System.getProperty`-aanroepen in de repo (o.a. `localPath()`
  gebruikt bewust `System.getProperty("user.home")`).
- Documentatie: `docs/factory/secrets-local.md` (r121-122), `functional-spec.md` (r256/263)
  en `docs/technical/external-systems.md` beschrijven het beoogde gedrag al correct —
  geen doc-wijziging nodig.
- Geen wijziging aan `ConfigApi`, `SecretsEnvLoader` of `FactorySecrets`.

## Acceptance criteria

1. `ClaudeAssistantClient.kt` bevat geen `System.getenv`-aanroep meer voor
   `SF_ASSISTANT_TIMEOUT_SECONDS` of `SF_ASSISTANT_IMAGE`; beide lopen via
   `configApi.resolvedValues()`.
2. Een waarde die alleen in `secrets.env` / `properties.env` / `properties.default.env`
   staat wordt nu wél gehonoreerd voor beide sleutels.
3. Een echte omgevingsvariabele blijft winnen van de bestandslagen (volgt automatisch uit
   `resolvedValues() = fileValues + environment`); voor een gebruiker die de variabele al
   exporteerde verandert er niets.
4. Defaults ongewijzigd: image `assistant:local`; timeout `DEFAULT_TIMEOUT_SECONDS` (3600)
   bij ontbrekende, blanke, niet-numerieke of ≤ 0 waarde.
5. Blanke image-waarde valt terug op `assistant:local` (`takeIf { it.isNotBlank() }` blijft).
6. De publieke constructorsignatuur van `ClaudeAssistantClient` blijft ongewijzigd, inclusief
   de defaults, zodat `ClaudeAssistantClient(secrets)` blijft compileren — dat patroon wordt
   gebruikt in `BridgeTestFixtures.kt`, `TelegramAssistantServiceTest.kt`,
   `TelegramAssistantFlowTest.kt` (subclass) en `TelegramPollerTest.kt`.
7. Beide `// TODO(fase 3): via ConfigApi`-commentaren zijn weg; de bestaande KDoc/comments
   over de betekenis van de instellingen blijven staan.
8. Nieuwe unittest slaagt en dekt: waarde uit een stub-`ConfigApi` wordt overgenomen voor
   image én timeout, en lege config levert de defaults op.
9. `mvn -f softwarefactory/pom.xml test` slaagt.
10. `mvn verify` (inclusief e2e-laag) slaagt.
11. Geen andere gedragswijziging: geen nieuwe/gewijzigde config-sleutels, geen wijziging aan
    het samengestelde docker-commando behalve de bron van de image-naam.

## Aannames

- Beide waarden worden, net als nu, één keer per instantie bepaald (bij constructie of via
  `by lazy`), niet opnieuw bij elke beurt. Het gedrag "herstart nodig na config-wijziging"
  blijft daarmee gelijk aan vandaag.
- `configApi.resolvedValues()` aanroepen tijdens constructie is veilig: `SecretsEnvLoader`
  parseert ontbrekende bestanden zonder fout en `fileValues` is intern al lazy.
- Testomgeving wordt niet beïnvloed: geen van beide sleutels staat in
  `properties.default.env`, dus bestaande tests zien nog steeds de defaults.
- De klasse is via de kotlin-spring allopen-plugin `open` (ze wordt gesubclassed in
  `TelegramAssistantFlowTest`); het verplaatsen van `IMAGE` naar een private instance-val
  raakt die subclass niet, omdat `FakeClaude` `ask()` volledig overschrijft.
- De unittest mag reflectie gebruiken om de private velden te lezen, conform het bestaande
  patroon in `TelegramAssistantServiceTest.kt`.

## Eindsamenvatting

## Eindsamenvatting SF-1487

**[Audit] SF_ASSISTANT_IMAGE en SF_ASSISTANT_TIMEOUT_SECONDS via ConfigApi**

### Wat is gebouwd
De Telegram-assistent las twee instellingen nog rechtstreeks via `System.getenv`, terwijl de rest van de factory alles via `ConfigApi` leest. Daardoor deden die twee sleutels niets wanneer je ze in `secrets.env` / `properties.env` / `properties.default.env` zette — precies wat de documentatie wél belooft. Beide lopen nu via dezelfde configuratie-ingang.

Concreet in `softwarefactory/.../telegram/clients/ClaudeAssistantClient.kt` (±6 gewijzigde regels):
- `timeoutSeconds` leest nu `configApi.resolvedValues()["SF_ASSISTANT_TIMEOUT_SECONDS"]`, met exact dezelfde validatieketen als voorheen.
- `IMAGE` is uit het private companion object gehaald en vervangen door de private instance-val `image`, gelezen uit `configApi.resolvedValues()["SF_ASSISTANT_IMAGE"]`. De twee gebruiksplekken (`dockerCommand()` en de warn-log in `runDocker()`) zijn meegegaan.
- Beide `// TODO(fase 3): via ConfigApi`-commentaren zijn verwijderd; de verklarende KDoc is behouden en de nieuwe `image`-val heeft eigen documentatie.

### Gemaakte keuzes
- **Instance-val i.p.v. companion object** voor `image`: de waarde hangt nu af van de geïnjecteerde `ConfigApi` en kan dus niet meer statisch zijn.
- **Publieke constructorsignatuur ongewijzigd** (incl. defaults), zodat bestaande call-sites zoals `ClaudeAssistantClient(secrets)` in vier testbestanden blijven compileren.
- **Waarden worden één keer per instantie bepaald**, net als voorheen — een config-wijziging vergt dus nog steeds een herstart. Bewust gelijk gehouden aan het huidige gedrag.
- **Voorrangsvolgorde blijft**: een echte omgevingsvariabele wint nog steeds van de bestandslagen (volgt automatisch uit `resolvedValues() = fileValues + environment`). Voor wie de variabele al exporteerde verandert er niets.
- Reviewer-suggestie om de twee `resolvedValues()`-aanroepen te bundelen in één lokale val is bewust **niet** doorgevoerd: functioneel identiek, buiten de scope van deze audit-story.

### Wat is getest
- Nieuwe unittest `ClaudeAssistantClientConfigTest` (6 tests, groen) met een stub-`ConfigApi`: waarden uit de config worden overgenomen voor image én timeout; een lege map geeft de defaults (`assistant:local` / 3600); blanke image en niet-numerieke/lege/nul/negatieve timeout vallen terug op de default; en het opgebouwde docker-commando bevat de juiste image op de juiste plek.
- Volledige build vanaf repo-root: `mvn clean verify` → **BUILD SUCCESS**, 0 failures / 0 errors over alle modules (factory-contracts 16, factory-common 52, softwarefactory 683 unit + 70 e2e, agentworker 60, dashboard-backend 50).
- Reviewer heeft alle 11 acceptatiecriteria nagelopen en akkoord bevonden; tester heeft dat onafhankelijk statisch én via de groene build geverifieerd. Geen scope creep: de diff bevat alleen het bronbestand, de nieuwe test en het worklog.

### Bewust niet gedaan
- **Geen documentatiewijziging**: `docs/factory/secrets-local.md`, `functional-spec.md` en `docs/technical/external-systems.md` beschreven het beoogde gedrag al correct — de code liep achter op de docs, niet andersom.
- **Geen wijziging** aan `ConfigApi`, `SecretsEnvLoader`, `FactorySecrets` of `properties.default.env`.
- De overige constanten (`IMAGE_EXTS`, `MODEL`, `DEFAULT_TIMEOUT_SECONDS`, `STOPPED_REPLY`, `DISALLOWED_TOOLS`) blijven ongewijzigd in het companion object; andere `System.getenv`/`System.getProperty`-aanroepen in de repo zijn niet aangeraakt.
- **Geen handmatig preview-scenario**: deze story raakt geen UI en de assistent-container vereist Docker + Telegram. Het samengestelde docker-commando is in plaats daarvan afgedekt met reflectietests op `dockerCommand()`.

### Aandachtspunt voor de PO (los van deze story)
De tester zag in één van de volledige builds een **pre-existing flake**: `FactoryApiControllerTest` roept de echte `FactoryProcessService.requestRestart()` aan, die na 600 ms `Runtime.halt(0)` uitvoert en zo de test-JVM hard kan afsluiten. Die test en service staan onveranderd op `main`; geïsoleerde herrun 5/5 groen en de tweede volledige build volledig groen. Aanbeveling voor een aparte story: die test een stub/fake `FactoryProcessControl` laten gebruiken.

```json
```
