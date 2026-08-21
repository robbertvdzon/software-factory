# SF-2256 - [Audit] Valideer de Product Factory-API met ResponseStatusException in plaats van require, en haal de validatie uit createStory

## Story

[Audit] Valideer de Product Factory-API met ResponseStatusException in plaats van require, en haal de validatie uit createStory

<!-- refined-by-factory -->

## Scope

`dashboard-backend/src/main/kotlin/nl/vdzon/softwarefactory/dashboard/bridge/ProductFactoryIntegrationApi.kt` valideert zijn invoer met negen `require(...)`-regels plus een ruwe `throw IllegalArgumentException`. In `dashboard-backend` vangt niets die exceptie op (nul `@ControllerAdvice`/`@ExceptionHandler` in de hele repo), dus een ongeldig verzoek komt terug als HTTP 500. Een fout die via de factory terugkomt met `INVALID_PARAMS` geeft wél 400 (`statusFor()`, `:189`). Voor de machineclient is dat functioneel verschil: 5xx betekent "probeer opnieuw", terwijl een verzoek met bijvoorbeeld een verkeerde `deliveryMode` bij elke poging opnieuw faalt.

Binnen scope:

1. Vervang in dit bestand de negen `require(...)` (`:77`, `:78`, `:79`, `:80`, `:81`, `:144`, `:147`, `:148`, `:152`) en de `throw` op `:155` door `throw ResponseStatusException(HttpStatus.BAD_REQUEST, "<dezelfde reden>")`. De meldingsteksten gaan letterlijk ongewijzigd mee.
2. Haal het validatieblok `:77-81` uit `createStory` en het blok rond `:144-155` uit `answer`, en zet elk in een eigen private validatiefunctie die als eerste stap na `authorize(...)` wordt aangeroepen. Voor de `answer`-route moet de gekozen operatie (`story.setStoryPhase` / `subtask.setPhase`) beschikbaar blijven; kies zelf of de validatiefunctie die operatie teruggeeft of dat de `when` blijft staan en alleen de checks eruit gaan.
3. Voeg minstens twee tests toe aan `ProductFactoryIntegrationApiTest.kt` die een ongeldig verzoek POSTen (minimaal `deliveryMode: "bogus"` en een lege titel) en op `status().isBadRequest` asserteren.

Buiten scope (bewust):

- `error(...)` op `:120` ("Software Factory gaf geen story key terug") blijft ongewijzigd; dat is een echte serverfout.
- Een `@RestControllerAdvice` toevoegen. Dat zou ook `BridgeApiController` raken, dat geen `require` gebruikt, en maakt de wijziging breder dan nodig.
- De ratchet-bevindingen in `AgentPromptContracts.kt` en `AgentRunCompletionService.kt` — aparte afweging.
- Documentatie: `docs/factory/development.md:131-133` benoemt de norm al als module-relatief en noemt `ResponseStatusException` expliciet als domeinexceptie die zijn betekenis houdt. Er is geen doc-drift.

## Acceptance criteria

1. In `ProductFactoryIntegrationApi.kt` staat geen `require(` en geen `throw IllegalArgumentException` meer; alle tien de gevallen gooien `ResponseStatusException(HttpStatus.BAD_REQUEST, ...)` met exact de bestaande meldingstekst.
2. Een POST op `/api/integrations/v1/stories` met een geldig token en een ongeldig veld (`deliveryMode: "bogus"`, lege titel, ongeldige productslug, ongeldige commit-SHA, ontbrekende of ongeldige `Idempotency-Key`) levert HTTP 400, niet 500.
3. Een POST op `/api/integrations/v1/answers`-route met een leeg antwoord, een onbekende `targetType`, een niet-passende `targetKey` of een onbekende fase levert HTTP 400, niet 500.
4. De validatie van `createStory` staat niet meer inline in `createStory` maar in een aparte validatiefunctie die direct na `authorize(...)` wordt aangeroepen; hetzelfde geldt voor de validatie in `answer`.
5. Het gedrag op het geldige pad is onveranderd: statuscodes, responsebodies, idempotentiegedrag en de doorgestuurde bridge-parameters blijven gelijk. De bestaande tests in `ProductFactoryIntegrationApiTest.kt` (401/201/200) slagen ongewijzigd.
6. `ProductFactoryIntegrationApiTest.kt` bevat minstens twee nieuwe tests voor ongeldige invoer die op `status().isBadRequest` asserteren.
7. `mvn -B --no-transfer-progress -pl dashboard-backend -am test` is groen.
8. `./quality/run.sh` is gedraaid en de `CyclomaticComplexMethod`-bevinding op `ProductFactoryIntegrationApi.kt:70 createStory` staat niet meer in de nieuwe bevindingen. Er is geen enkele nieuwe detekt-bevinding op dit bestand bijgekomen — in het bijzonder geen `TooManyFunctions` op `ProductFactoryIntegrationApi`. De verwachting is dat het aantal nieuwe bevindingen van drie naar twee gaat; die resterende twee (`AgentPromptContracts.kt`, `AgentRunCompletionService.kt`) vallen buiten deze story, dus een volledig groene ratchet is géén acceptatiecriterium. Neem de uitkomst van de run op in het worklog.
9. `docs/stories/worklog/SF-2256-worklog.md` is bijgewerkt tijdens de implementatie, conform `docs/factory/development.md`.

## Aannames

- **Extractie zonder nieuwe detekt-bevinding.** De klasse `ProductFactoryIntegrationApi` heeft nu tien methoden (`status`, `createStory`, `story`, `answer`, `existingStory`, `marker`, `authorize`, `dispatch`, `respond`, `statusFor`). Detekt draait met `buildUponDefaultConfig` en `quality/detekt.yml` overschrijft `TooManyFunctions` niet, dus de standaarddrempel van 11 functies per klasse geldt. Twee extra *members* brengen de klasse op twaalf en ruilen de complexiteitsbevinding in voor een `TooManyFunctions`-bevinding — die rule staat óók in `blockingRules` van de ratchet. De aanname is daarom dat de validatiefuncties als **top-level private functies in hetzelfde bestand** worden geschreven, naar het bestaande precedent van `private fun BridgeHub.safeDispatch(...)` onderaan het bestand (top-level telt tegen `thresholdInFiles`, waar nu pas één functie staat). Een andere oplossing die criterium 8 haalt is ook goed; het criterium is de meting, niet de vorm.
- **Testopzet volstaat.** `ProductFactoryIntegrationApiTest.kt` gebruikt `MockMvcBuilders.standaloneSetup(...)`, dat de `ResponseStatusExceptionResolver` registreert. `status().isBadRequest` werkt daar zonder extra Spring-configuratie; de bestaande `mvc(responder)`-helper en `request()`-template kunnen hergebruikt worden. Een test voor ongeldige invoer hoeft de bridge-responder niet te raken, want de validatie slaat toe vóór elke dispatch.
- **Geen strengere of soepelere validatie.** De set gecontroleerde condities blijft exact gelijk. Concreet: de `subtask`-tak controleert nu géén `targetKey` tegen het pad (alleen de `story`-tak doet dat) en die asymmetrie blijft zoals hij is; dit is een statuscode-story, geen validatie-uitbreiding.
- **Responsebody bij 400.** Het formaat van de foutbody dat Spring bij een `ResponseStatusException` produceert wordt niet vastgelegd; alleen de statuscode 400 en het behoud van de meldingstekst in de exceptie zijn onderdeel van de acceptatiecriteria.
- Geen wijzigingen buiten `ProductFactoryIntegrationApi.kt`, `ProductFactoryIntegrationApiTest.kt` en het worklog.

## Eindsamenvatting

## Eindsamenvatting SF-2256

**Wat is er gebouwd**

De Product Factory-integratie-API (`ProductFactoryIntegrationApi.kt`) valideerde zijn invoer met negen `require(...)`-regels en één ruwe `throw IllegalArgumentException`. Omdat `dashboard-backend` nergens een `@ControllerAdvice` heeft, kwam een ongeldig verzoek terug als **HTTP 500**. Voor de machineclient betekende dat "probeer opnieuw", terwijl bijvoorbeeld een verkeerde `deliveryMode` bij elke poging opnieuw faalt. Alle tien de gevallen gooien nu `ResponseStatusException(HttpStatus.BAD_REQUEST, ...)` met **exact dezelfde meldingstekst**, dus HTTP 400.

**Gemaakte keuzes**

- Validatie is uit `createStory` en `answer` gehaald naar twee **top-level private functies** in hetzelfde bestand: `validateCreateStory(...)` (geeft de genormaliseerde idempotentiesleutel terug) en `validateAnswer(...)` (geeft de bridge-operatie terug, waardoor de `when` uit `answer` verdween). Beide worden als eerste stap ná `authorize(...)` aangeroepen. Top-level in plaats van members, omdat de klasse met tien memberfuncties vlak onder detekt's `TooManyFunctions`-drempel (11) zat.
- Om detekt's `ThrowsCount` (max 2 per functie) niet te triggeren lopen de gevallen via twee kleine helpers `badRequest(message): Nothing` en `badRequestUnless(condition, message)`.
- `STORY_ANSWER_PHASES` / `SUBTASK_ANSWER_PHASES` zijn meeverhuisd van het private companion object naar file-private top-level vals; het lege companion object is verwijderd.
- De **set gecontroleerde condities is exact gelijk gebleven**, inclusief de bestaande asymmetrie dat de `subtask`-tak de `targetKey` niet tegen het pad controleert.

**Wat is getest**

- Vier nieuwe tests in `ProductFactoryIntegrationApiTest.kt` dekken elf 400-cases (o.a. `deliveryMode: "bogus"`, lege titel, ontbrekende/te korte `Idempotency-Key`, ongeldige productslug, ongeldige commit-SHA, en vijf cases op de answers-route). De responder gooit daarin bewust een fout, wat bewijst dat validatie vóór elke bridge-dispatch toeslaat.
- `mvn -pl dashboard-backend -am test` → BUILD SUCCESS, 74 tests, 0 failures. Volledige `clean verify` vanaf root → alle vijf modules groen.
- **Gedragsproef op de echt draaiende app** (geen MockMvc): alle zes stories-cases en alle vijf answers-cases geven 400 met onveranderde tekst. Negatieve controle op `main`: dezelfde verzoeken geven daar 500 — de wijziging is dus hard aangetoond, niet vacuüm-groen. Geldig pad ongewijzigd (503 `FACTORY_OFFLINE` bij ontbrekende bridge, 401 zonder token).
- `./quality/run.sh`: `findingCount` 777 → 775, `resolved: 5`, `newSuppressions: []`. De `CyclomaticComplexMethod`-bevinding op `createStory` is weg en er is **geen enkele nieuwe bevinding** op dit bestand (geen `TooManyFunctions`).

**Bewust niet gedaan**

- Geen `@RestControllerAdvice` toegevoegd (zou ook `BridgeApiController` raken en de wijziging breder maken dan nodig).
- `error(...)` bij een ontbrekende story key blijft staan — dat is een echte serverfout (500 is correct).
- De twee resterende ratchet-bevindingen (`AgentPromptContracts.kt`, `AgentRunCompletionService.kt`) zijn pre-existent op `main` en vallen buiten deze story; een volledig groene ratchet was geen acceptatiecriterium.
- Geen documentatiewijziging: `docs/factory/development.md` noemt `ResponseStatusException` al expliciet als domeinexceptie — geen doc-drift.
- Geen screenshots: dit is een machine-tot-machine JSON-API zonder UI.

<!-- deploy-summary:start -->
Als een aanvraag aan het systeem verkeerd is ingevuld, kreeg je eerder een melding die leek te zeggen dat er iets aan onze kant misging. Vanaf nu krijg je meteen een duidelijke melding dat de aanvraag zelf niet klopt, met dezelfde uitleg over wat er mis is. Zo weet je dat het geen zin heeft om het nog eens te proberen zonder de aanvraag aan te passen.
<!-- deploy-summary:end -->
