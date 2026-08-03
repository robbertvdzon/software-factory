# SF-1858 - Story-titel terug in de deployed-Telegram-melding

## Story

Story-titel terug in de deployed-Telegram-melding

<!-- refined-by-factory -->

## Samenvatting

De Telegram-melding "Story is deployed" laat op dit moment alleen het storynummer zien, niet de titel. Daardoor is niet te zien of een story uit het nachtelijke auditproces komt of zelf is aangemaakt — dat verschil is juist aan de titel te herkennen.

Na deze wijziging staat de titel achter het storynummer op dezelfde regel. Heeft een story geen titel, dan blijft alleen het nummer staan zonder rare leestekens. Erg lange titels worden ingekort zodat het bericht netjes blijft. De rest van het bericht verandert niet.

## Scope

In `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/telegram/services/TelegramResultNotifyPoller.kt`:

- `send()` (~r164): de eerste entry van `blocks` wordt opgebouwd uit key én titel (`story.summary`) in plaats van alleen de key.
- Nieuwe private helper + lengte-constante in het bestaande `companion object` (in lijn met `SUMMARY_LIMIT`), zodat de kopregel-opbouw puur functioneel en los testbaar is.

In `softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/telegram/TelegramResultNotifyPollerTest.kt`:

- De vier bestaande asserties op de kopregel (r354, r378, r396, r411) bijwerken naar de nieuwe vorm.
- Nieuwe tests voor de randgevallen: lege titel en te lange titel.

Buiten scope:

- De "✅ Klaar"-eindmelding met subtaaklijst uit `TelegramNotificationService`.
- De inhoud/bronnen van de functionele samenvatting (`functionalSummary()`, `deploySummaryBlock()`, `descriptionSummary()`) en de URL-regel.
- De pollcondities (`notify_mode`, deploy-bevestiging, opgeef-timeout, idempotentie via `TelegramStore`).

## Acceptance criteria

1. Bij een story met een niet-lege titel luidt de kopregel van de deployed-melding exact:
   `🚀 Story <KEY>: <TITEL> is deployed!`
   Voorbeeld: `🚀 Story SF-1234: nightly: code-kwaliteit is deployed!`
2. Is de titel leeg of alleen whitespace, dan luidt de kopregel exact `🚀 Story <KEY> is deployed!` — dus zonder dubbele punt en zonder dubbele/lossen spatie.
3. Een titel langer dan de vastgestelde limiet wordt afgekapt op die limiet met `…` als afsluiting; de kopregel blijft daarmee begrensd van lengte.
4. Alles ná de kopregel is ongewijzigd: de functionele samenvatting en de eventuele URL blijven dezelfde blokken, in dezelfde volgorde, gescheiden door een lege regel.
5. Wanneer er niet gemeld wordt (geen bevestiging, verkeerde `notify_mode`, al gemeld, opgeef-timeout) verandert er niets aan het bestaande gedrag.
6. `TelegramResultNotifyPollerTest` asserteert op de titel in de kopregel: de vier bestaande kopregel-asserties zijn bijgewerkt, en er zijn tests voor de lege titel (AC 2) en de afgekapte titel (AC 3).
7. `mvn -pl softwarefactory test` is groen en de Detekt-/quality-gate (`./quality/run.sh`) meldt geen nieuwe blokkerende bevindingen.

## Aannames

- Titel-bron is `TrackerIssue.summary`; dat veld is non-null `String` en al gevuld in de `findWorkIssues`-resultaten die de poller gebruikt, dus er is geen extra tracker-call nodig. "Ontbrekende titel" betekent in de praktijk dus een lege of whitespace-only string.
- Afkaplimiet voor de titel: **120 tekens**, geteld over de titel alleen (niet over de hele kopregel), met `…` erachter als er is afgekapt. Vastgelegd als benoemde constante in het `companion object`, naast `SUMMARY_LIMIT`. Titels tot en met 120 tekens komen ongewijzigd door zonder `…`.
- Het bericht wordt als platte tekst verstuurd (`TelegramClient.sendMessage` zet bewust geen `parse_mode`), dus de titel wordt niet ge-escaped of anderszins bewerkt; eventuele Markdown-tekens in de titel blijven letterlijk staan.
- Nieuwe regels in de titel worden niet apart genormaliseerd; story-titels zijn in de tracker eenregelig.
- Geen documentatie-drift: geen enkel bestand in `docs/factory/` beschrijft de exacte tekst van deze melding, dus er hoeft geen doc bijgewerkt te worden.

## Eindsamenvatting

Ik heb alles wat ik nodig heb: het worklog bevat de developer-, reviewer- en tester-bijdragen, en de diff bevestigt de omvang.

## Eindsamenvatting SF-1858 — Story-titel terug in de deployed-Telegram-melding

### Wat is gebouwd
De kopregel van de "Story is deployed"-melding in Telegram toont nu ook de story-titel achter het storynummer:

- Met titel: `🚀 Story SF-1234: nightly: code-kwaliteit is deployed!`
- Zonder titel (leeg/whitespace): `🚀 Story SF-1234 is deployed!` — zonder dubbele punt en zonder losse spatie.
- Titel langer dan 120 tekens: afgekapt op 120 tekens met `…` erachter.

Alles ná de kopregel (functionele samenvatting, eventuele URL, volgorde en lege regels) is ongewijzigd, net als alle voorwaarden waaronder er wél of niet gemeld wordt.

### Gemaakte keuzes
- De kopregel wordt opgebouwd door één nieuwe, pure helper `headline(key, summary)` in het bestaande `companion object` van `TelegramResultNotifyPoller`, naast `SUMMARY_LIMIT`/`summarySectionOf()` — los testbaar, in lijn met het huispatroon.
- Afkaplimiet vastgelegd als benoemde constante `TITLE_LIMIT = 120`, geteld over de titel alleen; titels t/m 120 tekens komen ongewijzigd door zonder `…`.
- Geen escaping van de titel: het bericht gaat bewust als platte tekst weg (geen `parse_mode`), dus eventuele Markdown-tekens blijven letterlijk staan.
- Afwijking van de refined story: de aanname "geen documentatie-drift" bleek onjuist — vier documenten noemden de oude kopregel letterlijk. Die zijn meteen bijgewerkt (`docs/factory/technical-spec.md`, `docs/factory/functional-spec.md`, `docs/technical/scheduled-jobs.md`, `docs/technical/modules.md`).

### Wat is getest
- `TelegramResultNotifyPollerTest`: vier bestaande kopregel-asserties bijgewerkt, drie tests toegevoegd (whitespace-only titel, 130 tekens → afgekapt, precies 120 tekens → ongewijzigd). 18 tests groen.
- Volledige module: 719 tests, 0 failures/errors. `mvn clean verify` vanaf repo-root: BUILD SUCCESS.
- Kwaliteitspoort `./quality/run.sh`: `ok: true`, geen nieuwe blokkerende bevindingen. `tools/audit-documentation`: PASS.
- Tester heeft het gedrag daarnaast onafhankelijk waargenomen op de gecompileerde helper, inclusief het exacte voorbeeld uit de acceptatiecriteria en het `null`-geval (valt veilig terug op alleen de key).

### Bewust niet gedaan
- De "✅ Klaar"-eindmelding met subtaaklijst is niet aangeraakt.
- Inhoud en bronnen van de functionele samenvatting en de URL-regel zijn ongewijzigd.
- De pollcondities (notify-modus, deploy-bevestiging, opgeef-timeout, idempotentie) zijn ongemoeid.
- Geen screenshots: dit is een tekstwijziging in een Telegram-bericht zonder UI-oppervlak.

### Openstaande observaties (geen blockers)
1. Valt de afkapgrens precies op een spatie, dan staat er " …" vóór het beletselteken (cosmetisch; een `trimEnd()` zou dat afronden).
2. Valt de knip midden in een emoji (surrogate-paar) op positie 120, dan toont Telegram een vervangingsteken. Dit is hetzelfde `.take(LIMIT)`-patroon als bij de bestaande samenvattingslimiet, dus pre-existing en alleen relevant bij extreem lange emoji-titels.

<!-- deploy-summary:start -->
De melding dat een story live staat, laat nu naast het storynummer ook de titel zien, zodat je in één oogopslag ziet om welke story het gaat. Heeft een story geen titel, dan blijft alleen het nummer staan. Erg lange titels worden ingekort zodat het bericht overzichtelijk blijft.
<!-- deploy-summary:end -->
