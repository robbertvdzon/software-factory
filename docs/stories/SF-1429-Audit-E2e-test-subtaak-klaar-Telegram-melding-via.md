# SF-1429 - [Audit] E2e-test: subtaak-klaar Telegram-melding via de echte pijplijn (NotifyMode = na-elke-stap)

## Story

[Audit] E2e-test: subtaak-klaar Telegram-melding via de echte pijplijn (NotifyMode = na-elke-stap)

<!-- refined-by-factory -->

## Samenvatting
Er bestaat nog geen end-to-end-test die aantoont dat een gebruiker écht een Telegram-bericht krijgt zodra een subtaak klaar is, wanneer die gebruiker "na elke stap" meldingen wil. Deze story voegt zo'n test toe: hij draait de hele echte Software Factory-pijplijn (inclusief de achtergrond-klok die alles aanstuurt) en checkt dat er precies één Telegram-bericht met de juiste inhoud verstuurd wordt op het moment dat de subtaak klaar is. Er verandert niets aan het gedrag van de applicatie zelf, alleen aan de testdekking.

## Scope
- Nieuw e2e-testbestand in `softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/` (bv. `TelegramSubtaskDoneE2eTest.kt`), gebaseerd op `E2eTestBase`.
- In `E2eTestConfig`: een `@Primary` test-only `TelegramClient`-bean toevoegen die berichten in-memory vastlegt i.p.v. echt naar Telegram te versturen. Implementatie volgt exact het bestaande patroon uit `TelegramNotificationServiceTest.RecordingTelegramClient` (subclass van `TelegramClient`, `enabled=true`, `defaultChatId` vast, `sendMessage` legt de tekst vast en geeft een oplopend message-id terug).
- De test:
  - Maakt een story aan (analoog aan `E2eTestBase.createStory`) en zet het `NotifyMode`-veld op `"na-elke-stap"` (`NotifyMode.EVERY_STEP.trackerValue`), met auto-approve aan zodat de keten vanzelf doorloopt.
  - Laat de story via de echte pipeline (echte Spring-app, Testcontainers-Postgres, echte `OrchestratorPoller`-`@Scheduled`-poll, geen directe service-aanroep) een subtaak tot terminale fase (bv. `development-approved`) laten doorlopen.
  - Wacht (via `Awaitility`/bestaande `AwaitDsl`) tot de test-Telegram-client precies één bericht heeft ontvangen.
  - Verifieert de inhoud van dat bericht: bevat de subtaak-key en -titel, en het "✅ Klaar"-patroon zoals gebouwd door `TelegramNotificationService.notifySubtaskDone`/`buildMessage`.
- Puur testcode: geen wijziging aan productiecode (`TelegramNotificationService`, `OrchestratorPoller`, `TelegramClient`, etc.).

## Acceptance criteria
1. Er is een nieuw e2e-testbestand in `softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/`, gebaseerd op `E2eTestBase`, dat de echte Spring-app opstart (zoals de bestaande e2e-tests in die map).
2. `E2eTestConfig` bevat een `@Primary` test-only `TelegramClient`-testdouble (subclass-patroon, consistent met `TelegramNotificationServiceTest.RecordingTelegramClient`) die verzonden berichten in-memory bijhoudt in plaats van echte HTTP-calls te doen.
3. De nieuwe test zet op een story `NotifyMode = "na-elke-stap"` en drijft een subtaak tot een terminale fase via de echte orchestrator-poll/scheduling (geen directe aanroep van `TelegramNotificationService.notifyPending()` of vergelijkbare service-methoden vanuit de test).
4. Zodra de subtaak terminaal is, ontvangt de test-Telegram-client **precies één** bericht (niet nul, niet meerdere) voor die gebeurtenis.
5. Het ontvangen bericht bevat de verwachte inhoud: minstens de subtaak-key, de subtaak-titel/summary en het "klaar"-patroon (header `✅ Klaar`, consistent met `TelegramNotificationService.buildMessage`/`notifySubtaskDone`).
6. De volledige testsuite (`mvn verify` / bestaand test-commando uit `docs/factory/development.md`) blijft slagen; geen bestaande test wordt gebroken.
7. Er is geen wijziging in productiecode buiten `softwarefactory/src/test/...` (test-only scope, zoals expliciet gevraagd).

## Aannames
- De test gebruikt een aparte, unieke story-key (conform het bestaande patroon in andere e2e-tests) zodat hij niet met andere tests in dezelfde test-JVM interfereert.
- "Precies één Telegram-melding" wordt geïnterpreteerd als: precies één bericht voor de terminale subtaak-gebeurtenis binnen de scope van deze ene story/test-run (niet een globale telling over de hele testsuite).
- Als terminale subtaak-fase wordt een bestaand, eenvoudig te bereiken eindpunt gebruikt (bv. een development-subtaak die via `development-approved` terminaal wordt), tenzij de developer een beter passend scenario kiest binnen dezelfde acceptatiecriteria.
- De nieuwe `@Primary` `TelegramClient`-testbean in `E2eTestConfig` beïnvloedt alleen deze en toekomstige e2e-tests die er gebruik van maken; bestaande e2e-tests die Telegram niet toetsen blijven ongewijzigd gedrag vertonen (Telegram stond daarvoor effectief uit doordat er geen enabled test-client was).

## Eindsamenvatting

Voldoende context verzameld. Ik schrijf nu de eindsamenvatting voor de PO.

## Eindsamenvatting SF-1429

**Wat is gebouwd**
Er is een nieuwe end-to-end-test toegevoegd (`TelegramSubtaskDoneE2eTest.kt`) die aantoont dat een gebruiker écht een Telegram-melding krijgt zodra een subtaak klaar is, bij `NotifyMode = "na-elke-stap"`. De test draait de volledige echte pijplijn (Spring-app, Testcontainers-Postgres, de echte `OrchestratorPoller`-scheduling) zonder enige service rechtstreeks aan te roepen. Daarnaast is in `E2eTestConfig` een `@Primary` test-only `TelegramClient`-testdouble (`RecordingTelegramClient`) toegevoegd die berichten in-memory vastlegt in plaats van echt te versturen, exact volgens het bestaande patroon uit `TelegramNotificationServiceTest`. `E2eTestBase` kreeg een kleine accessor om deze testdouble te kunnen bevragen.

**Belangrijkste keuzes**
- De test plant bewust maar één AI-subtaak (`review`) en drijft die door tot `review-approved`, omdat bleek dat `DEVELOPMENT_APPROVED` géén terminale fase is (levert geen eigen Telegram-melding op) terwijl `REVIEW_APPROVED` dat wel is.
- Ontdekt werd dat zodra `review-approved` bereikt is, de pijplijn binnen enkele honderden milliseconden doorschakelt naar volgende afsluiters (documentation/merge/deploy), die hun eigen Telegram-berichten versturen. De "precies één bericht"-assertie is daarom gescoped op de specifieke issue-regel van de review-subtaak, in lijn met de aanname in de story dat het om precies één bericht per gebeurtenis gaat, niet een globale telling over de hele testrun.
- Er is bewust geen productiecode aangeraakt; scope bleef beperkt tot testbestanden.

**Wat is getest**
- Gerichte run van de nieuwe test: groen (1 test, 0 fails/errors).
- Volledige `mvn verify` vanaf de repo-root: BUILD SUCCESS over alle modules, geen regressies in bestaande (e2e-)tests.
- Reviewer heeft de diff, het subclass-patroon en de terminale-fase-logica geverifieerd en akkoord gegeven zonder blockers.
- Tester heeft dezelfde gerichte en volledige verify-run herhaald, logs gecontroleerd op het correcte verloop (echte poller, geen directe service-aanroep, correcte melding) en akkoord gegeven.

**Bewust niet gedaan**
- Geen wijziging aan productiecode of aan `.factory/verification.yaml`/functionele documentatie, aangezien er geen nieuw applicatiegedrag is — puur testdekking toegevoegd.
