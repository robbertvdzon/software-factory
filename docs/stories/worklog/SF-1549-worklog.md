# SF-1549 - Worklog

## Story in eigen woorden (subtaak SF-1615)

In elke e2e-testrun draaide op de achtergrond een Telegram-poller die zonder pauze rondjes maakte:
`RecordingTelegramClient` overschreef `getUpdates` niet, dus viel de poller-thread terug op de
productie-implementatie die zonder bot-token direct een lege lijst teruggeeft. Resultaat: een loop
zonder blokkade die onafgebroken `telegram_state`-queries op de Testcontainers-Postgres afvuurde.
De testdubbel moet zich netjes gedragen (kort blokkeren) en ook `sendPhoto` vastleggen i.p.v. een
echte upload te proberen.

Daarnaast leggen we de meldingen-as (SF-1261, as 3) e2e vast: bij `meldingen=geen` krijgt de
gebruiker écht geen enkel bericht, mét als enige uitzondering het vraagbericht — zonder dat bericht
zou een `*-with-questions`-wachtstand eindeloos stilstaan.

Alleen testcode; geen productiegedrag gewijzigd.

## Stappenplan

[x]: read issue and target docs
[x]: implement requested changes (a) RecordingTelegramClient + (b) SpecScenarioCoverageE2eTest
[x]: run relevant tests / volledig vangnet (`mvn verify`)
[x]: update story-log with results
[x]: loopback review SF-1615: `mvn verify` afgedraaid en uitkomst + suite-looptijd vóór/na vastgelegd
[x]: loopback review SF-1615 (suggestie): unittests op de dubbel (`getUpdates`/`sendPhoto`/`reset`)

## Wat is er gedaan en waarom

### (a) `E2eTestConfig.kt` — `RecordingTelegramClient` compleet

- `getUpdates(offset, timeoutSeconds)` overschreven: doet een `poll(200 ms)` op een altijd lege
  `LinkedBlockingQueue` en geeft daarna een lege lijst terug. Dat is exact het patroon van
  `TelegramPollerTest.ScriptedTelegramClient` (blokkerende dubbel) en neemt het spinnen weg: één
  poll-ronde van de `TelegramPoller`-thread kost nu minimaal ~200 ms i.p.v. ~0 ms. Een
  `InterruptedException` propageert bewust door — `TelegramPoller.loop` breekt daarop af, wat het
  gewenste `@PreDestroy`-shutdownpad is.
- `sendPhoto(chatId, file, caption)` overschreven: legt de verzending in-memory vast in de nieuwe
  synchronized lijst `photos` (`SentPhoto(chatId, fileName, caption)`) en geeft `true` terug.
- `reset()` leegt nu `messages`, `photos`, de teller én de (lege) queue, zodat
  `E2eTestBase.resetSharedState` beide registraties meeneemt.
- Overrides zonder defaultwaarden op de parameters (Kotlin-eis bij override).

### (b) `SpecScenarioCoverageE2eTest.kt` — meldingen-as vastgelegd

- Bestaande silent-test (`NotifyMode=geen`, vragen uit) assert nu ná `awaitAllAiSubtasksApproved`
  dat er geen enkel Telegram-bericht over déze story of haar subtaken is verstuurd
  (`assertNoTelegramMessagesFor`).
- Nieuwe test `een vraag komt altijd door ook als meldingen uit staan`: unieke story-key `-240`,
  `QuestionsAllowed=true` + `NotifyMode=geen` + `refinerAsksQuestion=true`, gedreven via de échte
  orchestrator-poll (story aanmaken + velden zetten + wachten op `refined-with-questions`).
  `TelegramNotificationService` wordt nergens rechtstreeks aangeroepen. Assertie: precies één
  bericht met de issue-regel `"<key>: <summary>"` en dat bericht draagt de QUESTION-kop
  `❓ De Software Factory heeft een vraag`.
- De velden worden bewust vóór `Story Phase=start` gezet: pas met een fase wordt de story door de
  orchestrator/notify-poll opgepakt, dus `meldingen=geen` geldt gegarandeerd vanaf de eerste poll.
- Beide asserties zijn gescoped op de eigen story-/subtaak-keys (word-boundary-regex tegen
  prefix-verwarring zoals `SF-200` vs `SF-2001`), niet op de globale lijstlengte — `telegram.messages`
  is gedeelde JVM-state. Voor het uitlezen is een `telegramMessages()`-snapshot onder de
  monitor van de `synchronizedList` toegevoegd (voorkomt `ConcurrentModificationException` bij
  gelijktijdige schrijvers vanuit de poller-threads).

### Specs

Geen `docs/factory/`-spec aangepast: het beschreven gedrag (meldingen-as en de vraag-uitzondering)
staat al in `functional-spec.md` (r44-50, r78-80); deze story pint het alleen met tests vast en
wijzigt geen productiegedrag.

## Bewijs

Zie "Loopback na review SF-1615" hieronder: `mvn verify` is afgedraaid en groen (BUILD SUCCESS,
exit 0, 0 failures / 0 errors), inclusief de gemeten suite-looptijd vóór/na.

`git diff main --stat` raakt uitsluitend `softwarefactory/src/test/` + dit worklog; geen
productiecode (AC8).

## Review SF-1615 (reviewer, 2026-07-31)

Uitkomst: **review-rejected** — inhoudelijk is de code akkoord, maar het testbewijs ontbreekt.

- [blocker] De sectie "Bewijs" laat `mvn verify` als placeholder staan ("zie hieronder") zonder
  uitkomst; issue-comment 2066 bevestigt dat de run bij afronden nog liep. AC9 (volledige
  `mvn verify` groen) is daarmee niet aantoonbaar. Ook de gevraagde suite-looptijd vóór/na
  (AC9, tweede helft) staat nergens genoteerd. Docker ontbreekt in de reviewomgeving, dus de
  reviewer kan dit niet zelf compenseren.
- [suggestie] `sendPhoto`/`photos` (AC2) en de lege-na-reset-eis (AC3) worden door geen enkele
  test aangeraakt; overweeg een kleine assertie zodat de dubbel-registratie niet stil kan
  verrotten.
- [info] Statisch gecontroleerd in de reviewomgeving: `mvn -pl factory-common,softwarefactory -am
  test-compile` is groen (overrides compileren, `TelegramClient` is open via kotlin-spring).
- [info] Overige AC's (1, 4-8) zien er correct uit: 200 ms poll neemt het spinnen weg,
  `InterruptedException` propageert naar `TelegramPoller.loop`; beide berichtasserties zijn op de
  eigen story-/subtaak-keys gescoped; de nieuwe test drijft alles via de echte orchestrator-poll;
  de diff raakt alleen `src/test/` + dit worklog; specs in `docs/factory/` blijven consistent.

## Loopback na review SF-1615 (developer, 2026-07-31)

Alleen de twee blockers uit de review + de suggestie afgehandeld; aan de al goedgekeurde
implementatie is niets veranderd.

### 1. Nieuwe tests op de dubbel zelf (reviewsuggestie AC2/AC3)

Nieuw bestand `softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/RecordingTelegramClientTest.kt`
(3 tests, geen Spring/Testcontainers nodig; draait via failsafe mee omdat het `e2e`-package in
`softwarefactory/pom.xml` uit surefire is gefilterd):

- `getUpdates` blokkeert >= 100 ms en geeft een lege lijst terug (AC1).
- `sendPhoto` legt `SentPhoto(chatId, fileName, caption)` vast en geeft `true` terug (AC2).
- `reset()` leegt zowel `messages` als `photos` (AC3).

Deze drie tests zijn aantoonbaar regressiegevoelig: tijdens de baseline-meting hieronder liepen ze
tegen de *oude* `RecordingTelegramClient` van `main` aan en faalden ze precies zoals bedoeld
(`getUpdates blokkeerde maar 0ms; verwacht >= 100ms`, `sendPhoto` gaf `false`, `photos` bestond niet).
Dat is meteen het directe bewijs voor AC1: op `main` kostte een poll-ronde 0 ms (spinnen), nu >= 200 ms.

### 2. Volledig vangnet: `mvn verify` vanaf de repo-root — GROEN

Docker was in deze run wél bruikbaar: de `docker`-CLI ontbreekt, maar `/var/run/docker.sock` is
bereikbaar en Testcontainers praat rechtstreeks met de daemon (Docker Desktop 4.43.0, engine 28.3.0).

```
[INFO] Reactor Summary for software-factory-root 0.0.1-SNAPSHOT:
[INFO] software-factory-root .............................. SUCCESS [  0.001 s]
[INFO] factory-contracts .................................. SUCCESS [  9.678 s]
[INFO] factory-common ..................................... SUCCESS [ 11.961 s]
[INFO] softwarefactory .................................... SUCCESS [04:17 min]
[INFO] agentworker ........................................ SUCCESS [  5.270 s]
[INFO] softwarefactory-dashboard-backend .................. SUCCESS [  9.393 s]
[INFO] BUILD SUCCESS
[INFO] Total time:  04:54 min
```

- Exitcode 0. Testtotalen per module: 16 / 52 / 683 (surefire softwarefactory) / 74 (failsafe
  softwarefactory) / 60 / 50 = **935 tests, 0 failures, 0 errors, 0 skipped**.
- `tools/audit-documentation`: `documentation-audit/v1: PASS` (exit 0).

### 3. Suite-looptijd vóór/na (AC9, tweede helft)

Zelfde commando, zelfde machine, direct na elkaar: `mvn -pl softwarefactory verify -Dsurefire.skip=true`.
"Vóór" = de e2e-testmap teruggezet naar `main` (dus zonder de blokkerende `getUpdates`).

| meting | wall clock | som failsafe-klassetijden | failsafe-tests |
| --- | --- | --- | --- |
| vóór (`main`) | 4m14,7s | 147,0 s | 73 = 70 van `main` + de 3 nieuwe dubbel-tests, die daar rood liepen (zie punt 1) |
| na (deze branch) | 4m38,2s | 159,0 s | 74 = 71 (70 + de nieuwe vraag-e2e) + de 3 dubbel-tests, alle groen |

Conclusie: **geen significante verslechtering.** Het verschil van ~12 s zit in (a) ~2,4 s echt nieuw
testwerk (de nieuwe vraag-e2e-test + de 3 dubbel-tests incl. hun eigen JVM-fork; elke testklasse
forkt apart, `reuseForks=false`) en (b) run-to-run-ruis van de awaitility-gedreven e2e's, die per
klasse enkele seconden schommelen (`ManualApproveGateE2eTest` 12,2 → 15,8 s en `FullRefineToDevelop`
12,9 → 16,4 s zijn allebei ongewijzigde tests). De 200 ms-blokkade zit in een aparte poller-thread en
staat niet op het kritieke pad van een test; hij haalt juist de continue `telegram_state`-queries van
de gedeelde Testcontainers-Postgres af.

### 4. Pre-existente rode repo-gates (niet van deze story)

`tools/verify-repository` bevat twee stappen die *niet* in `.factory/verification.yaml` staan en die
al op `main` rood zijn — deze story wijzigt geen productiecode, dus ze zijn onmogelijk hierdoor
veroorzaakt en niet met een test-only diff te repareren:

- `./quality/run.sh` (ratchet): alle `new`-findings staan in `src/main`-bestanden die deze branch niet
  aanraakt (o.a. `AuditGatewayAdapter.kt`, `TelegramAuditQuestionService.kt` — `ReturnCount`). Geen
  enkele finding wijst naar `src/test`.
- `tools/generate-module-dependencies --check`: drift in `docs/technical/module-dependencies.md`
  (bekend van SF-1609). Hergenereren zou een productiedocbestand in de diff trekken en botst met AC8
  ("alleen `src/test/` gewijzigd"), dus bewust niet gedaan — dit hoort in een eigen story.

De gate die de factory-harness deterministisch herdraait (`repository-maven-verify` +
`repository-documentation-audit` uit `.factory/verification.yaml`) is groen.
