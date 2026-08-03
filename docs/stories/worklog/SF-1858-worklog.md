# SF-1858 - Worklog

Story-context bij eerste pickup:
Story-titel in kopregel deployed-melding + tests

In softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/telegram/services/TelegramResultNotifyPoller.kt: vervang de eerste entry van 'blocks' in send() (~r164) door een nieuwe pure private helper die de kopregel bouwt uit story.key en story.summary. Gedrag: niet-lege titel -> '🚀 Story <KEY>: <TITEL> is deployed!'; lege of whitespace-only titel -> '🚀 Story <KEY> is deployed!' zonder dubbele punt en zonder dubbele/losse spatie; titel langer dan de limiet wordt afgekapt op die limiet met '…' erachter, titels t/m de limiet blijven ongewijzigd zonder '…'. Leg de limiet (120 tekens, geteld over de titel alleen) vast als benoemde const in het bestaande companion object, naast SUMMARY_LIMIT, volgens het huispatroon const + .take(LIMIT). Alles na de kopregel blijft ongewijzigd: functionele samenvatting en eventuele URL, zelfde blokken, zelfde volgorde, join met \n\n. Raak de pollcondities (notify_mode, deploy-bevestiging, opgeef-timeout, idempotentie via TelegramStore) niet aan. Niet escapen: TelegramClient.sendMessage zet bewust geen parse_mode, het bericht is platte tekst. In softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/telegram/TelegramResultNotifyPollerTest.kt: werk de VIER bestaande kopregel-asserties bij (r354, r378, r396, r411 - allemaal via helper story() met summary="Een story"), en voeg tests toe voor de randgevallen lege/whitespace-titel en te lange (afgekapte) titel. Sluit af met een eigen review van de diff en zorg dat 'mvn -pl softwarefactory test' groen is en ./quality/run.sh geen nieuwe blokkerende bevindingen geeft.

In eigen woorden: de deployed-melding in Telegram noemde alleen het storynummer. Daardoor zie je
niet of een story uit het nachtelijke auditproces komt of zelf is aangemaakt — dat verschil zit juist
in de titel. De titel komt nu achter de key op dezelfde kopregel; zonder titel blijft alleen de key
staan en een erg lange titel wordt afgekapt zodat het bericht netjes blijft.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `TelegramResultNotifyPoller.send()` bouwt de eerste block-entry nu via de nieuwe pure helper
  `headline(key, summary)` in het bestaande `companion object`, naast `SUMMARY_LIMIT`/
  `summarySectionOf()`. De helper is één expressie (`trim` → `takeIf { isNotBlank() }` → afkappen →
  format), zodat detekt's `ReturnCount`-limiet niet in beeld komt. Nieuwe const `TITLE_LIMIT = 120`,
  geteld over de titel alleen; boven die lengte `take(120) + "…"`, t/m 120 tekens ongewijzigd zonder
  `…`. Geen escaping: het bericht gaat als platte tekst weg (`TelegramClient` zet geen `parse_mode`).
- De rest van `send()` is ongemoeid: functionele samenvatting en eventuele URL blijven dezelfde
  blokken in dezelfde volgorde, `joinToString("\n\n")`. Pollcondities (notify_mode, deploy-
  bevestiging, opgeef-timeout, idempotentie via `TelegramStore`) zijn niet aangeraakt.
- Tests (`TelegramResultNotifyPollerTest`): de helper `story()` heeft een `summary`-parameter met
  default `"Een story"` gekregen; de vier bestaande kopregel-asserties verwachten nu
  `🚀 Story SF-1: Een story is deployed!`. Drie tests toegevoegd: whitespace-only titel (kop zonder
  dubbele punt en zonder losse spatie), titel van 130 tekens (afgekapt op 120 + `…`) en titel van
  precies 120 tekens (ongewijzigd, geen `…` in het bericht). 18 tests groen in deze klasse.
- Documentatie bijgewerkt (de aanname "geen documentatie-drift" uit de refined story klopte niet —
  vier bestanden noemden de exacte kopregel): `docs/factory/technical-spec.md` (berichtopbouw),
  `docs/factory/functional-spec.md` (wat er in het bericht staat), `docs/technical/scheduled-jobs.md`
  (§6 result-notify-poller) en `docs/technical/modules.md` (telegram-module). Overal de nieuwe kop
  plus het lege-titel- en afkapgedrag.

Bewijs vangnet (03-08-2026, branch ai/SF-1858):
- `mvn -B --no-transfer-progress clean verify` vanaf repo-root: BUILD SUCCESS, exit 0, 0 failures /
  0 errors (softwarefactory 3m45, totaal 4m18).
- `./quality/run.sh`: `ok: true`, `new: []`, findingCount 752 — geen nieuwe blokkerende bevindingen.
- `tools/audit-documentation`: PASS. `tools/generate-module-dependencies --check`: actueel.
