# SF-2256 - Worklog

Story-context bij eerste pickup:
ResponseStatusException-validatie en extractie in ProductFactoryIntegrationApi

In dashboard-backend/src/main/kotlin/nl/vdzon/softwarefactory/dashboard/bridge/ProductFactoryIntegrationApi.kt: vervang de negen require(...) (:77, :78, :79, :80, :81, :144, :147, :148, :152) en de throw IllegalArgumentException op :155 door throw ResponseStatusException(HttpStatus.BAD_REQUEST, "<exact dezelfde meldingstekst>"). Haal het validatieblok :77-81 uit createStory en het blok :144-155 uit answer, elk naar een eigen validatiefunctie die direct na authorize(...) wordt aangeroepen; voor answer mag die functie meteen de operatie (story.setStoryPhase / subtask.setPhase) teruggeven zodat de when uit answer verdwijnt. LET OP detekt: de klasse heeft nu 10 memberfuncties en TooManyFunctions (drempel 11) staat in blockingRules, dus schrijf de validatiefuncties als top-level private functies onderaan hetzelfde bestand, naar het precedent van 'private fun BridgeHub.safeDispatch'. STORY_ANSWER_PHASES/SUBTASK_ANSWER_PHASES staan in een private companion object en zijn vanaf top-level niet zichtbaar; verplaats ze mee naar file-private top-level vals. Wijzig de set gecontroleerde condities niet (ook de bestaande asymmetrie dat de subtask-tak geen targetKey tegen het pad checkt blijft). Laat error(...) op :120 ongemoeid, voeg GEEN @RestControllerAdvice toe en raak geen andere bestanden aan. Voeg in ProductFactoryIntegrationApiTest.kt minstens twee tests toe die met geldig token een ongeldig verzoek POSTen (minimaal deliveryMode: "bogus" en een lege titel) en op status().isBadRequest asserteren; bij voorkeur ook een 400-case op de answers-route (onbekende targetType of leeg antwoord). Hergebruik de bestaande mvc(responder)-helper en request()-template; standaloneSetup registreert de ResponseStatusExceptionResolver. Draai daarna 'mvn -B --no-transfer-progress -pl dashboard-backend -am test' en './quality/run.sh' en noteer beide uitkomsten in docs/stories/worklog/SF-2256-worklog.md; de CyclomaticComplexMethod-bevinding op createStory moet weg zijn zonder nieuwe bevinding op dit bestand. Sluit af met een eigen review-stap over de diff.

Story in eigen woorden:
De Product Factory-integratie-API valideerde zijn invoer met `require(...)`. Er is nergens in
`dashboard-backend` een `@ControllerAdvice`, dus een ongeldig verzoek kwam terug als HTTP 500 —
voor een machineclient het signaal "probeer opnieuw", terwijl een verzoek met bv. een verkeerde
`deliveryMode` bij elke poging opnieuw faalt. Alle tien de validatiegevallen moeten daarom
`ResponseStatusException(HttpStatus.BAD_REQUEST, ...)` gooien met exact dezelfde meldingstekst, en
de validatieblokken gaan uit `createStory`/`answer` naar aparte functies.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `ProductFactoryIntegrationApi.kt`: de negen `require(...)` en de `throw IllegalArgumentException`
  vervangen door 400-fouten. Om te voorkomen dat detekt's `ThrowsCount` (max 2 per functie) een
  nieuwe bevinding oplevert, lopen alle gevallen via twee kleine top-level helpers
  `badRequest(message): Nothing` en `badRequestUnless(condition, message)`; elke melding gooit dus
  nog steeds `ResponseStatusException(HttpStatus.BAD_REQUEST, "<ongewijzigde tekst>")`.
- Validatie geëxtraheerd naar top-level private functies `validateCreateStory(...)` (geeft de
  genormaliseerde idempotentiesleutel terug) en `validateAnswer(...)` (geeft de bridge-operatie
  terug, zodat de `when` uit `answer` verdwijnt). Beide worden als eerste stap ná `authorize(...)`
  aangeroepen. Top-level i.p.v. members, want de klasse zat met tien memberfuncties vlak onder de
  `TooManyFunctions`-drempel van 11. `STORY_ANSWER_PHASES`/`SUBTASK_ANSWER_PHASES` zijn daarom mee
  verhuisd van het private companion object naar file-private top-level vals (companion daardoor
  leeg en verwijderd).
- De set gecontroleerde condities is exact gelijk gebleven, inclusief de bestaande asymmetrie dat
  de `subtask`-tak de `targetKey` niet tegen het pad controleert. `error(...)` op het ontbreken van
  een story key blijft staan (echte serverfout); geen `@RestControllerAdvice` toegevoegd.
- `ProductFactoryIntegrationApiTest.kt`: vier tests toegevoegd voor 400-gedrag —
  `deliveryMode: "bogus"`, lege titel, een gecombineerde test voor ontbrekende/te korte
  `Idempotency-Key` + ongeldige productslug + ongeldige commit-SHA, en vijf 400-cases op de
  answers-route (leeg antwoord, onbekende `targetType`, niet-passende `targetKey`, onbekende fase
  voor story en voor subtask). De responder gooit in die tests bewust `error(...)`, zodat bewezen
  is dat de validatie vóór elke bridge-dispatch toeslaat. De `request()`-helper heeft nu
  default-parameters gekregen zodat de bestaande aanroepen ongewijzigd blijven.

Bewijs (21-08-2026):
- `mvn -B --no-transfer-progress -pl dashboard-backend -am test` → BUILD SUCCESS,
  Tests run: 74, Failures: 0, Errors: 0 (waarvan 10 in `ProductFactoryIntegrationApiTest`).
- Volledig vangnet `mvn -B --no-transfer-progress clean verify` vanaf de root → BUILD SUCCESS,
  alle vijf modules groen (softwarefactory-e2e incl. Testcontainers, 4:42 min).
- `tools/audit-documentation` → `documentation-audit/v1: PASS`.
- `./quality/run.sh` → `findingCount` van 777 naar 775, `resolved: 5`, `newSuppressions: []`.
  De `CyclomaticComplexMethod`-bevinding op `ProductFactoryIntegrationApi.kt createStory` staat
  niet meer in `new`; er is géén nieuwe bevinding op dit bestand bijgekomen (alleen de
  pre-existente `WildcardImport` en `MaxLineLength` staan er nog). De `new`-lijst is van drie naar
  twee gegaan: `AgentPromptContracts.kt` (TooManyFunctions) en `AgentRunCompletionService.kt`
  (LargeClass) — beide pre-existent op main en buiten deze story. `ok:false` van de ratchet komt
  dus volledig van die twee.

Specs: geen `docs/factory/`-aanpassing nodig. `docs/factory/development.md:128-133` noemt
`ResponseStatusException` al expliciet als domeinexceptie die zijn betekenis houdt en merkt op dat
de `require`-norm module-relatief is; er is geen doc-drift.

Review (21-08-2026, SF-2257):
- Volledige story-diff (`git diff main...HEAD`) beoordeeld: drie bestanden, geen scope creep.
- AC1 geverifieerd: `grep 'require(\|IllegalArgumentException'` op het bestand geeft nul treffers;
  alle tien meldingsteksten zijn letterlijk gelijk aan de oude `require`-lambda's.
- AC4/AC5: validatie staat direct na `authorize(...)`, de conditieset en de volgorde van de checks
  zijn identiek aan de oude inline-blokken (incl. de asymmetrie in de `subtask`-tak); het geldige
  pad, de idempotentiesleutel en de bridge-params zijn ongewijzigd.
- AC7: harness-bewijs `[FACTORY VERIFICATION EVIDENCE]` — `repository-maven-verify` passed op tree
  `69aa492f4fa089d24b366a3e3a3802ad099fbe38`, gelijk aan de tree van commit `0d24efe`.
- AC8 zelf nagedraaid met `./quality/run.sh`: `findingCount: 775`, `new` = alleen
  `AgentPromptContracts.kt` en `AgentRunCompletionService.kt`, `newSuppressions: []`, `resolved: 5`.
  Geen `CyclomaticComplexMethod` en geen `TooManyFunctions` op dit bestand.
- Akkoord, geen blockers.
