# SF-1718 - [Audit] E2e: manual-approve-poort echt vastleggen (verouderde assertie + afgedreven reset)

## Story

[Audit] E2e: manual-approve-poort echt vastleggen (verouderde assertie + afgedreven reset)

<!-- refined-by-factory -->

## Samenvatting

De end-to-end test die bewaakt dat de factory bij een handmatige goedkeurpoort écht op een mens
wacht, controleert dat op dit moment niet echt: hij kijkt naar een verouderde momentopname en
zou ook groen blijven als de poort zou stoppen met blokkeren. Die controle wordt hersteld zodat
de test de veiligheidspoort daadwerkelijk bewaakt.

Daarnaast wordt de testklasse gelijkgetrokken met alle andere end-to-end tests door dezelfde
gedeelde basis te gebruiken. Nu heeft hij een eigen, onvolledige opschoning tussen tests,
waardoor testen elkaar kunnen beïnvloeden en de test op toeval leunt in plaats van op een
mechanisme. Er verandert niets aan de werking van het product; alleen testcode wordt aangepast.

## Scope

Uitsluitend `softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/ManualApproveGateE2eTest.kt`.
Geen productiecode, geen wijziging aan `E2eTestBase`, `AwaitDsl` of `TrackerTestState`.

**(a) Verouderde momentopname in de assertie**

- In `manual-approve poort goedgekeurd zet de keten door naar de merge-subtaak` leest r104
  `enforcedChild(story, "merge")`; dat object is een onveranderlijke snapshot (via
  `TrackerTestState.childrenOf` → `PostgresTrackerClient.subtasksOf`), gemaakt vóór het wachten
  op r107, op een moment dat de fase per constructie `null` is.
- De assertie op r109-113 (`merge.fields.subtaskPhase`) moet vers uit de state lezen, precies
  zoals de deploy-check op r125-129 dat al doet: `state.issue(merge.key)?.fields?.subtaskPhase`.
- De `merge`-/`deploy`-vals blijven bestaan om de subtaak-keys op te halen; alleen de
  fase-uitlezing wordt vers.
- De eerste test (r43-77) is gecontroleerd: die gebruikt de snapshot alleen voor `gate.key`
  (immutable) en heeft geen assertie op een snapshot-veld. Daar is geen inhoudelijke correctie
  nodig; zie Aannames.

**(b) Erven van `E2eTestBase`**

- `class ManualApproveGateE2eTest : E2eTestBase()`; de eigen `@SpringBootTest`- en
  `@Import(E2eTestConfig::class)`-annotaties (r28-30) en de bijbehorende imports vervallen.
- De eigen `state`/`runtime`-vals (r32-33) en de eigen `resetSharedState` (r35-40) vervallen;
  de basisklasse reset óók de Telegram-dubbel en ruimt verouderde story-workspaces op.
- De gedupliceerde helpers `dispatchCount` en `awaitDispatchCount` (r142-149) vervallen ten
  gunste van de identieke helpers in de basisklasse.
- Timeouts blijven functioneel gelijk aan vandaag (120 s): gebruik `awaiter(Duration.ofSeconds(120))`
  in plaats van `AwaitDsl(state, Duration.ofSeconds(120))`, en geef bij
  `awaitDispatchCount(...)` de timeout expliciet mee (basisklasse-default is 60 s).
- De twee inline `Awaitility.await("manual-approve-subtaak aangemaakt onder ...")`-blokken
  (r64-67, r99-102) hebben geen equivalent in de basisklasse en blijven zoals ze zijn, inclusief
  hun 120 s-timeout.
- De klasse-KDoc en de bestaande verklarende commentaren blijven behouden; de comment bij
  `dispatchCount` die naar `E2eTestBase` verwees vervalt samen met de helper.

## Acceptance criteria

1. `ManualApproveGateE2eTest` erft van `E2eTestBase` en bevat zelf geen `@SpringBootTest`,
   geen `@Import(E2eTestConfig::class)`, geen eigen `resetSharedState`, geen eigen
   `state`/`runtime`-property en geen eigen `dispatchCount`/`awaitDispatchCount`.
2. De merge-assertie in de tweede test leest de fase vers uit de tracker-state
   (`state.issue(merge.key)?.fields?.subtaskPhase`) in plaats van uit het eerder opgehaalde
   `merge`-object.
3. Geen enkel bestand buiten `ManualApproveGateE2eTest.kt` is gewijzigd; `git diff --stat` toont
   precies dat ene testbestand.
4. Alle awaits in deze klasse hanteren nog steeds 120 s: de `awaiter(...)`-aanroepen en de
   `awaitDispatchCount(...)`-aanroep geven die duur expliciet mee.
5. `mvn verify` (met Docker) draait groen; in het bijzonder slagen beide tests van deze klasse
   onder failsafe.
6. Aangetoond is dat de herstelde assertie kán falen: met een tijdelijk verwachte waarde
   `"manual-approve-needed"` in plaats van `null` wordt de test rood. Deze tijdelijke wijziging
   wordt teruggedraaid en komt niet in de commit; het waargenomen faalresultaat (foutmelding)
   wordt in de werklog/PR-beschrijving vastgelegd.

## Aannames

- De eerste test heeft geen verouderde-momentopname-assertie; "op dezelfde manier aanpassen"
  betekent daar dus alleen: dezelfde 120 s-timeouts expliciet meegeven bij het overstappen op de
  basisklasse-helpers. Wordt tijdens implementatie alsnog een snapshot-afhankelijke assertie
  gevonden, dan gaat die op dezelfde vorm als in de tweede test.
- Het gedrag onder test verandert niet: dat de merge in de e2e-harness op een fout uitloopt
  (`awaitErrorContains(merge.key, "automatische merge")`) blijft de bestaande, verwachte uitkomst.
- Het reduceren van de basis-timeout van 120 s naar 60 s is expliciet niét de bedoeling; de test
  mag niet strenger worden dan vandaag.
- De extra Telegram-reset en workspace-opruiming uit `E2eTestBase.resetSharedState` zijn veilig
  voor deze twee tests: ze gebruiken unieke story-keys (`SP-300`, `SP-310`) en doen geen
  asserties op Telegram-berichten.
- `mvn verify` vereist Docker (Testcontainers-Postgres). Is Docker in de uitvoeromgeving niet
  beschikbaar, dan is dat een blokkade die gemeld moet worden, niet een reden om de story zonder
  volledige verify af te ronden.

## Eindsamenvatting

## Eindsamenvatting SF-1718 — E2e: manual-approve-poort echt vastleggen

**Wat was het probleem**
De end-to-end test die bewaakt dat de factory bij een handmatige goedkeurpoort écht op een mens wacht, controleerde dat niet werkelijk. De assertie las de subtaak-fase uit een momentopname die al vóór het wachten was opgehaald en dus per definitie leeg was — de test zou dus ook groen blijven als de poort zou stoppen met blokkeren. Daarnaast had de testklasse een eigen, onvolledige opschoning tussen tests in plaats van de gedeelde basis die alle andere e2e-tests gebruiken.

**Wat is gebouwd**
Eén bestand gewijzigd: `softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/ManualApproveGateE2eTest.kt` (7 regels toegevoegd, 32 verwijderd).

- De merge-assertie leest de fase nu vers uit de tracker-state (`state.issue(merge.key)?.fields?.subtaskPhase`), in exact dezelfde vorm als de al bestaande deploy-check. De test kan daarmee daadwerkelijk falen als de poort niet meer blokkeert.
- De klasse erft nu van `E2eTestBase`. Vervallen: eigen `@SpringBootTest`/`@Import`, eigen `state`/`runtime`, eigen reset tussen tests, en de gedupliceerde `dispatchCount`/`awaitDispatchCount`-helpers. De basisklasse reset óók de Telegram-dubbel en ruimt verouderde story-workspaces op, dus de opschoning is nu vollediger en gelijk aan de rest van de suite.

**Gemaakte keuzes**
- Timeouts bewust functioneel gelijk gehouden op 120 s: de basisklasse-default is 60 s, dus die duur wordt overal expliciet meegegeven. De test is niet strenger geworden dan hij was.
- De twee inline `Awaitility.await(...)`-blokken hebben geen equivalent in de basisklasse en zijn ongewijzigd gelaten.
- De `merge`-/`deploy`-objecten blijven bestaan, maar worden alleen nog voor hun (onveranderlijke) subtaak-key gebruikt.
- De eerste test bleek geen snapshot-afhankelijke assertie te hebben (gebruikte de snapshot alleen voor `gate.key`); daar was geen inhoudelijke correctie nodig.

**Wat is getest**
- Volledige `mvn clean verify` met Docker/Testcontainers: BUILD SUCCESS, 0 failures / 0 errors. Beide tests van deze klasse slagen onder failsafe (`tests="2" failures="0" errors="0" flakes="0"`).
- Onafhankelijke hercontrole door de tester met een gerichte e2e-run: opnieuw groen, geen flakes; in dezelfde run liepen alle unittests mee (contracts 16 / common 52 / softwarefactory 687, alle groen).
- Falsifieerbaarheid aangetoond: met een tijdelijk verwachte waarde `"manual-approve-needed"` in plaats van `null` werd de test rood met `expected: <manual-approve-needed> but was: <null>`. Die tijdelijke wijziging is teruggedraaid en zit niet in de commit — bewijs dat de herstelde assertie echt bewaakt wat hij hoort te bewaken.
- Alle zes acceptatiecriteria zijn door reviewer én tester afgevinkt; beiden hebben goedgekeurd.

**Bewust niet gedaan**
- Geen productiecode aangepast; geen wijziging aan `E2eTestBase`, `AwaitDsl` of `TrackerTestState`. Het gedrag van het product verandert niet — dit is puur herstel van de testdekking.
- Geen spec-update: `docs/factory/development.md` beschrijft de `E2eTestBase`-basis al; deze story brengt de klasse dáármee in lijn.
- Geen browser-/UI-verificatie: er was geen preview-omgeving beschikbaar en de story is test-only.
- Openstaand punt voor later (buiten scope gelaten): de klasse gebruikt nog `FactoryUiDriver(state)` waar de rest van de suite `loginUi()` uit de basisklasse gebruikt. Functioneel identiek; mee te nemen bij een volgende opruimronde.
