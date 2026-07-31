# SF-1549 - [Audit] E2e: stop de spinnende Telegram-poller en leg de meldingen-as vast (vraag gaat altijd door)

## Story

[Audit] E2e: stop de spinnende Telegram-poller en leg de meldingen-as vast (vraag gaat altijd door)

<!-- refined-by-factory -->

## Samenvatting

In elke end-to-end testrun draait op de achtergrond een Telegram-poller die zonder pauze
databasequeries afvuurt. Dat kost onnodig veel machinecapaciteit en maakt de testsuite
instabieler, terwijl niemand er iets aan heeft. We laten de nep-Telegramclient in de tests
zich netjes gedragen, zodat die tolzucht verdwijnt.

Daarnaast leggen we twee afspraken over meldingen vast in tests: bij "meldingen: geen" krijgt
de gebruiker echt geen enkel bericht, en de belangrijke uitzondering daarop — een vraag van de
factory komt altijd door — wordt nu ook aantoonbaar bewezen. Zonder dat vraagbericht zou de
keten eindeloos stilstaan zonder dat iemand het merkt.

Alleen testcode verandert; het gedrag van de factory zelf blijft ongewijzigd.

## Scope

Uitsluitend testcode in `softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/`.
Geen productiecode, geen migraties, geen docs-wijziging vereist.

(a) `E2eTestConfig.kt` — `RecordingTelegramClient` (r157-186) compleet maken:

- `getUpdates(offset: Long?, timeoutSeconds: Int)` overschrijven zodat de aanroep **kort
  blokkeert** (bijv. `poll` op een lege `BlockingQueue` met korte timeout, minimaal ~100 ms)
  en daarna een lege lijst teruggeeft. Patroon volgt `TelegramPollerTest.ScriptedTelegramClient`
  (r205-218). Een `InterruptedException` mag gewoon doorpropageren: `TelegramPoller.loop`
  (r76-78) breekt daarop af, wat het gewenste shutdown-pad is via `@PreDestroy`.
- `sendPhoto(chatId: String, file: Path, caption: String?)` overschrijven zodat de verstuurde
  foto in-memory wordt vastgelegd (bijv. een synchronized lijst met chatId + bestandsnaam of
  bytes + caption) en `true` teruggeeft.
- Beide nieuwe registraties worden in `reset()` geleegd, zodat `E2eTestBase.resetSharedState`
  ze meeneemt.
- Overrides zonder defaultwaarden op de parameters (Kotlin-eis bij override).

(b) `SpecScenarioCoverageE2eTest.kt` — meldingen-as (SF-1261, as 3) vastleggen:

- In de bestaande test op r25 e.v. (`silent story doorloopt de keten autonoom…`, zet
  `NotifyMode=geen` op r40) de ontbrekende assertie toevoegen dat er voor déze story geen
  enkel Telegram-bericht verstuurd is.
- Nieuwe test toevoegen voor de spec-uitzondering (`functional-spec.md` r44-50 en r78-80):
  story met `QuestionsAllowed=true` **en** `NotifyMode=geen`, `runtime.script.refinerAsksQuestion
  = true`, unieke story-key, gedreven via de echte orchestrator-poll (dus géén directe
  `TelegramNotificationService`-aanroep). Assertie: precies één Telegram-bericht voor deze
  story, en dat is het vraagbericht.
- Gebruik de bestaande `telegram`-accessor uit `E2eTestBase.kt:27`.

Buiten scope: aanpassen van `TelegramPoller`, `TelegramClient`, `TelegramNotificationService`
of enige andere productieklasse; een screenshot-e2e-test (SF-206) zelf.

## Acceptance criteria

1. `RecordingTelegramClient.getUpdates` is overschreven en spint niet: één poll-ronde van de
   `TelegramPoller`-thread kost minimaal ~100 ms, waardoor de e2e-test-JVM niet langer
   ongelimiteerd `SELECT value FROM <schema>.telegram_state WHERE key=?` uitvoert.
2. `RecordingTelegramClient.sendPhoto` is overschreven, legt de verstuurde foto in-memory vast
   en geeft `true` terug; de opname is via de dubbel uitleesbaar en wordt door `reset()` geleegd.
3. Bestaande gedeelde reset blijft correct: na `E2eTestBase.resetSharedState` zijn zowel
   `messages` als de nieuwe foto-registratie leeg.
4. De bestaande silent-test in `SpecScenarioCoverageE2eTest` assert dat er voor die story
   (`NotifyMode=geen`, vragen uit) geen enkel Telegram-bericht is verstuurd.
5. Er is een nieuwe e2e-test die bewijst dat een story met `QuestionsAllowed=true` én
   `NotifyMode=geen`, waarvan de refiner een vraag stelt, precies één Telegram-bericht oplevert
   en dat dit het vraagbericht is (herkenbaar aan de QUESTION-kop uit
   `TelegramNotificationService`, bijv. "❓ De Software Factory heeft een vraag").
6. Die nieuwe test drijft de story via de echte orchestrator-poll (story aanmaken + velden
   zetten + wachten op de `*-with-questions`-fase); nergens wordt `TelegramNotificationService`
   rechtstreeks aangeroepen.
7. Beide berichten-asserties zijn gescoped op de eigen story-/subtaak-keys van de test (niet op
   de globale lijstlengte), zodat naloop van eerdere tests in dezelfde JVM ze niet kan laten
   flaken.
8. Alleen bestanden onder `src/test/` zijn gewijzigd; `git diff` raakt geen productiecode.
9. Een volledige `mvn verify` (Docker vereist) slaagt; alle bestaande e2e-tests slagen
   ongewijzigd. De looptijd van de suite wordt vóór/na genoteerd voor zover meetbaar en in het
   worklog vermeld (geen harde drempel; een significante verslechtering is wél een blocker).

## Aannames

- `getUpdates` mag ná de korte blokkade altijd een lege lijst teruggeven: geen enkele bestaande
  e2e-test voert inkomende Telegram-updates op, en de poller-logica zelf is al gedekt door
  `TelegramPollerTest`.
- De concrete blokkeertijd is een implementatiedetail; als richtwaarde volstaat een korte poll
  in de orde van 100-500 ms — lang genoeg om het spinnen weg te nemen, kort genoeg om
  test-shutdown niet te vertragen.
- De vraag-uitzondering is al geïmplementeerd in productie
  (`TelegramNotificationService.suppressedByNotifyMode` geeft `false` voor
  `NotifyCategory.QUESTION`); de nieuwe test pint bestaand gedrag vast en zou meteen groen
  moeten zijn. Faalt hij toch, dan is dat een echte productiebug en een aparte story — deze
  story wijzigt geen productiecode.
- De nieuwe test hoeft de vraag niet te beantwoorden; de story mag na de assertie in de
  `refined-with-questions`-wachtstand blijven staan, zoals andere e2e-tests met een unieke
  story-key ook doen.
- `QuestionsAllowed` staat default AAN; de test zet het veld toch expliciet, zodat de bedoeling
  in de test zelfstandig leesbaar is.
- `NotifyMode` wordt gezet met de tracker-waarde `"geen"` (`NotifyMode.NONE.trackerValue`),
  zoals de bestaande tests doen.

## Eindsamenvatting

## Eindsamenvatting SF-1549 — E2e: stop de spinnende Telegram-poller en leg de meldingen-as vast

**Wat er is gebouwd**

Twee dingen, allebei uitsluitend in testcode (`softwarefactory/src/test/.../e2e/`):

1. **De spinnende poller is gestopt.** De nep-Telegramclient in de e2e-tests (`RecordingTelegramClient`) overschreef `getUpdates` niet, waardoor de achtergrond-poller zonder pauze rondjes maakte en onafgebroken database-queries op de test-Postgres afvuurde. De dubbel blokkeert nu 200 ms per poll-ronde (net als een echte long-poll) en geeft daarna een lege lijst terug. Ook `sendPhoto` is overschreven: die legt de "verstuurde" foto in-memory vast (chat, bestandsnaam, caption) in plaats van een echte upload te proberen. Beide registraties worden door de gedeelde `reset()` tussen tests geleegd.
2. **De meldingen-as is met tests vastgelegd.** De bestaande silent-story-test bewijst nu dat bij `meldingen=geen` géén enkel Telegram-bericht over die story of haar subtaken uitgaat. Daarnaast is er een nieuwe test die de belangrijke uitzondering vastpint: een story met vragen aan én meldingen uit, waarvan de refiner een vraag stelt, levert precies één bericht op — en dat is het vraagbericht ("❓ De Software Factory heeft een vraag"). Zonder dat bericht zou de keten stilstaan zonder dat iemand het merkt.

**Gemaakte keuzes**

- De nieuwe vraag-test wordt gedreven via de échte orchestrator-poll (story aanmaken, velden zetten, wachten op `refined-with-questions`); de notificatieservice wordt nergens rechtstreeks aangeroepen, zodat de test het echte keten-gedrag meet.
- Beide berichten-asserties zijn gescoped op de eigen story- en subtaak-keys (met word-boundary-matching, zodat `SF-200` en `SF-2001` niet door elkaar lopen), niet op de globale lijstlengte. Daarmee kan naloop van eerdere tests in dezelfde JVM ze niet laten flaken.
- Na review is er een klein extra testbestand bijgekomen (`RecordingTelegramClientTest`) dat de dubbel zelf afdekt — blokkeerduur, foto-registratie en reset — zodat die registratie niet stil kan verrotten. Deze tests faalden aantoonbaar tegen de oude situatie op `main`, wat meteen het bewijs is dat de poller daar écht spinde (0 ms per ronde).
- De wachttijd op het vraagbericht is verruimd van 15 naar 60 seconden, omdat een koude of belaste CI-container trager is dan de ontwikkelsandbox. De assertie zelf is niet versoepeld.

**Wat is getest**

- Volledige `mvn verify` vanaf de repo-root, met exact het harness-commando: BUILD SUCCESS, 935 tests, 0 failures / 0 errors / 0 skipped. De documentatie-audit is PASS.
- Suite-looptijd vóór/na gemeten op dezelfde machine: ~4m15 tegenover ~4m38. Het verschil is grotendeels nieuw testwerk plus run-to-run-ruis; geen significante verslechtering. De 200 ms-blokkade zit in een aparte thread en staat niet op het kritieke pad van tests.
- De tester heeft daarnaast gericht de gewijzigde testklassen gedraaid (8/8 groen) en de bedrading gecontroleerd: de dubbel wordt daadwerkelijk door de poller gebruikt.
- Eén eerdere, niet-reproduceerbare afwijzing door de automatische gate kwam in latere runs niet terug (herhaald groen over meerdere volledige runs).

**Bewust niet gedaan**

- Geen productiecode aangeraakt — de diff blijft binnen `src/test/` plus het worklog. Het gedrag van de factory zelf is ongewijzigd; deze story pint bestaand gedrag vast.
- Geen documentatie-wijziging: de meldingen-as en de vraag-uitzondering stonden al in de functionele spec.
- Geen screenshot-e2e-test gebouwd (dat is een aparte story); `sendPhoto` is alleen voorbereid.
- Twee repo-brede kwaliteitsgates staan al op `main` rood (een code-ratchet en drift in een module-afhankelijkhedendocument). Die zijn niet meegenomen: ze zijn niet door deze story veroorzaakt en repareren zou de diff buiten testcode trekken. Aanbeveling: een aparte opruimstory.

**Aandachtspunt voor jou als PO:** geen. De story raakt geen gebruikersgedrag; de winst is een rustigere, stabielere en goedkopere testsuite plus hard bewijs dat een vraag van de factory altijd doorkomt.
