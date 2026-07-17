# SF-1045 - agent output

## Story

agent output

<!-- refined-by-factory -->

## Scope

Het detailscherm voor een agent-run (`dashboard-frontend/lib/screens/agent_log_screen.dart`) toont nu elke opgeslagen `agent_events`-regel (kind `docker-stdout`/`docker-stderr`, gevuld door `DockerLogFollower`) als platte tekst in een `Text`-widget, ongeparsed. Elke regel is al een compleet JSON-event (Claude draait met `--output-format stream-json`, Codex met `--json`, beide JSONL — zie `ClaudeStreamParser.kt` en `CodexAiClient.kt:247-269`, die respectievelijk `"type"` en `item.type` al gebruiken voor eigen doeleinden). Een enkel event kan een volledige tool-payload bevatten (bv. de hele inhoud van een ingelezen bestand bij een Read-tool-result). Zo'n regel wordt nu als één blok escaped-JSON getoond naast piepkleine system-events, waardoor het scherm wild ongelijkmatig oogt en niet leesbaar is.

Deze story voegt JSON-bewuste parsing/weergave toe in de Flutter-frontend:

1. **Parse elke regel als JSON** op basis van het bestaande `kind`-veld/de payload-structuur. Claude- en Codex-events hebben een net-iets-ander event-schema; de parser onderscheidt beide.
2. **Toon een korte, leesbare samenvatting per event-type**: assistent-tekst volledig/leesbaar, tool-calls/tool-resultaten als één regel (toolnaam + korte preview), initieel ingeklapt.
3. **Uitklapbaar maken** van ingeklapte tool-payloads, zodat de volledige inhoud alsnog opvraagbaar is (debugging).
4. Regels die niet als geldige JSON te parsen zijn, vallen terug op de huidige ruwe weergave — geen harde crash bij onverwachte content.
5. **Kleurgebruik van het scherm aanpassen**: het detailscherm gebruikt nu een donkere achtergrond (`Colors.black`) met groene/rode monospace-tekst (`Colors.greenAccent`/`Colors.redAccent`). Dit wordt vervangen door de reguliere app-kleuren (`AppTheme.light()` / `SfColors`, zoals gedefinieerd in `dashboard-frontend/lib/main.dart`), zodat het scherm visueel aansluit bij de rest van de applicatie.

Buiten scope: de onderliggende capture (`DockerLogFollower.kt`, `agent_events`-tabel, backend event-formaat) blijft ongewijzigd — dit is puur een presentatiewijziging in de Flutter-frontend. Het al bestaande live-bijwerken tijdens een actieve run (uit SF-1009) blijft ongewijzigd werken; dit voegt alleen betere weergave van de al bestaande data toe.

## Acceptance criteria

- In het agent-detailscherm worden events herkenbaar als afzonderlijke, leesbare items getoond in plaats van als één ononderbroken muur van escaped JSON-tekst.
- Assistent-berichten zijn direct leesbaar (geen JSON-quotes/escape-tekens zichtbaar).
- Tool-calls en tool-resultaten tonen standaard een korte samenvatting (toolnaam + korte preview van de invoer/uitvoer), niet de volledige payload inline.
- Een gebruiker kan een ingeklapt tool-event uitklappen om de volledige, originele inhoud te zien.
- Dit werkt voor zowel Claude- als Codex-agent-runs (beide event-schema's worden herkend).
- Regels die niet als JSON geparsed kunnen worden, blijven zichtbaar (fallback naar ruwe tekst) i.p.v. te verdwijnen of de pagina te laten crashen.
- Live-updates tijdens een actieve run en de volledige log bij afgeronde runs blijven werken zoals nu (geen regressie op bestaande SF-1009-functionaliteit).
- Het agent-detailscherm gebruikt dezelfde kleuren (achtergrond, tekstkleuren, accenten) als de rest van de dashboard-app (`AppTheme`/`SfColors`) in plaats van het huidige donkere scherm met groene letters.

## Aannames

- Parsing/formattering gebeurt client-side (Flutter/Dart) in `agent_log_screen.dart`; er is geen functionele reden om dit server-side (`AgentLogService.kt`) te doen, tenzij de developer een andere reden ziet (bv. performance bij zeer lange logs) — implementatiedetail, geen harde eis.
- "Korte preview" mag een vaste karakterlimiet zijn (bv. eerste ~200 tekens van de tool-invoer/uitvoer); exacte lengte is een implementatiedetail voor de developer.
- Geen wijziging aan wat er gecaptured/opgeslagen wordt — puur hoe het al opgeslagen event getoond wordt.
- Voor de kleurwijziging is "dezelfde kleuren als de rest van de applicatie" gedefinieerd als hergebruik van het bestaande `AppTheme.light()`/`SfColors`-palet (zie `main.dart`); onderscheid tussen stdout/stderr of event-type mag behouden blijven via subtiele varianten binnen dat palet (bv. `SfColors.red`/`redSoft` voor stderr) in plaats van fel groen/rood op zwart.

## Eindsamenvatting

## Eindsamenvatting SF-1045 — JSON-bewuste event-weergave in het agent-detailscherm

**Wat is gebouwd**
Het agent-detailscherm (`dashboard-frontend/lib/screens/agent_log_screen.dart`) toont opgeslagen `agent_events`-regels niet langer als rauwe, escaped JSON-tekst, maar geparsed en leesbaar:

- Nieuwe pure helper `dashboard-frontend/lib/agent_log_event.dart` (`parseAgentLogEvent`) parseert elke regel als JSON en onderscheidt het Claude-schema (`type`: assistant/user/result/system) van het Codex-schema (`item.type` via `turn.completed`/tool-item-types).
- Assistent-tekst wordt volledig leesbaar getoond; tool-calls/tool-resultaten worden standaard ingeklapt als één regel (toolnaam + preview van max 200 tekens), met de volledige pretty-printed payload uitklapbaar door erop te tikken.
- Regels die niet als JSON parsen (of een JSON-array zijn) vallen terug op de bestaande rauwe weergave — geen crash.
- Kleuren van het scherm zijn vervangen: het zwarte scherm met groene/rode monospace-tekst is vervangen door `AppTheme`/`SfColors` (`SfColors.bg`/`ink` voor normale tekst, `SfColors.red`/`redSoft`/`accentSoft` om stderr en ingeklapte tegels te onderscheiden), conform de rest van de app.
- Live-polling en het volledige-log-gedrag uit SF-1009 zijn functioneel ongewijzigd — alleen de renderlaag is aangepast.

**Belangrijkste keuzes**
- Eigen `InkWell`+`Column`-implementatie voor het uitklapbare tegels i.p.v. Flutter's `ExpansionTile`, omdat die laatste in `flutter test` op een Flutter-bug botste (`type 'bool' is not a subtype of type 'double?'` in `ScrollPosition.restoreScrollOffset`), vermoedelijk door een PageStorage-sleutelconflict met de geneste `SelectableText`.
- Expand/collapse-state wordt bijgehouden op index in de geflattende eventlijst; dit is veilig omdat de onderliggende regel-lijst alleen aangroeit (append-only polling).
- Parsing/weergave gebeurt client-side in Flutter, zoals de story als aanname voorschreef; backend (`DockerLogFollower.kt`, `agent_events`-tabel, event-formaat) is niet aangeraakt.

**Getest**
- Nieuwe unit-tests (`agent_log_event_test.dart`, 12 tests) dekken beide schema's en de fallback-paden.
- Uitgebreide widget-test (`agent_log_screen_test.dart`) verifieert leesbare assistent-tekst, standaard ingeklapte tool-call (payload-marker voorbij de previewlimiet niet zichtbaar), uitklappen toont volledige payload, en niet-parsebare regels blijven zichtbaar. Bestaande 4 tests blijven ongewijzigd slagen.
- `flutter analyze`: geen issues. `flutter test`: 37/37 groen.
- Volledig vangnet `mvn verify` (repo-breed, incl. Testcontainers-e2e): BUILD SUCCESS, 0 failures/errors over alle vijf modules, tweemaal gedraaid met identiek resultaat.
- Reviewer en tester hebben de story onafhankelijk beoordeeld en goedgekeurd; geen bugs, regressies of scope-overtredingen gevonden.

**Bewust niet gedaan**
- Geen wijziging aan de backend-capture (`DockerLogFollower.kt`), de `agent_events`-tabel of het backend event-formaat — puur een presentatiewijziging in Flutter, conform de scope-afbakening van de story.

**Documentatie**
`docs/factory/ux/screens/agents.md` is al bijgewerkt met een beschrijving van de nieuwe JSON-bewuste weergave en de themawissel (dit wordt in de volgende subtaak SF-1064 verder nagelopen).
