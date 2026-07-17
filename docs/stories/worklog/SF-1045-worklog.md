# SF-1045 - Worklog

Story-context bij eerste pickup:
JSON-bewuste event-parsing en weergave in agent_log_screen.dart, incl. tests

Parse elke agent_events-regel als JSON (Claude/Codex-schema's onderscheiden via bestaande 'type'/'item.type'-velden), toon assistent-tekst volledig leesbaar, tool-calls/tool-resultaten als ingeklapte samenvatting (toolnaam + korte preview, uitklapbaar naar volledige payload), val terug op ruwe tekstweergave bij niet-parsebare regels, en vervang de zwarte/groene/rode kleuren door AppTheme/SfColors. Live-polling en volledige-log-gedrag (SF-1009) blijven ongewijzigd. Breid dashboard-frontend/test/screens/agent_log_screen_test.dart uit met tests voor assistent-tekst, tool-call/tool-resultaat (ingeklapt/uitgeklapt) en niet-parsebare fallback-regels; bestaande tests moeten blijven slagen.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Nieuwe pure helper `dashboard-frontend/lib/agent_log_event.dart`
  (`parseAgentLogEvent`) parseert elke opgeslagen `agent_events`-regel
  (`kind`/`text`) als JSON, los van widgets zodat hij zonder flutter_test-
  widget-harnas unit-testbaar is. Claude-schema herkend via `type` in
  `{assistant, user, result, system}` (content-blokken `text`/`tool_use`/
  `tool_result`); alle andere `type`-waarden (o.a. `item.completed`/
  `turn.completed` met `item.type`) worden als Codex-schema behandeld.
  Niet-JSON of JSON die geen object is (bv. een array) → `AgentLogEventKind.raw`,
  valt terug op de ruwe tekst.
  - `assistantText`: volledige, leesbare tekst (geen JSON-quotes/escapes).
  - `toolUse`/`toolResult`: ingeklapte samenvatting (naam + preview, max 200
    tekens, whitespace genormaliseerd), met de volledige originele payload
    (pretty-printed JSON) beschikbaar via `detail` voor het uitklappen.
  - `system`: kleine samenvatting (bv. `Systeem: init`), ook uitklapbaar naar
    de ruwe regel.
- `dashboard-frontend/lib/screens/agent_log_screen.dart` bijgewerkt:
  - `_body()` "flattened" nu alle events van alle regels (één regel kan
    meerdere content-blokken/events bevatten) via `parseAgentLogEvent` i.p.v.
    één rauwe `Text` per regel.
  - Niet-uitklapbare events (assistentText/raw) tonen gewoon de samenvatting;
    uitklapbare events (toolUse/toolResult/system) zijn een eigen tegel met
    expand/collapse-icoon en state bijgehouden in `_expanded` (Set<int> op
    index in de geflattende lijst). Bewust een eigen `InkWell`+`Column`
    i.p.v. `ExpansionTile`: `ExpansionTile` (met een expliciete
    `PageStorageKey` per index) botste in `flutter test` op een Flutter-bug
    (`type 'bool' is not a subtype of type 'double?'` in
    `ScrollPosition.restoreScrollOffset`) doordat de geneste `SelectableText`
    dezelfde PageStorage-bucket/sleutel als de ExpansionTile-eigen
    expand-state leek te delen — met eigen state-beheer treedt dat niet op.
  - Kleuren vervangen: `Colors.black`/`Colors.greenAccent`/`Colors.redAccent`
    → `SfColors.bg`/`SfColors.ink` (normale tekst) en `SfColors.red` +
    `SfColors.redSoft`/`SfColors.accentSoft` (stderr resp. achtergrond van
    ingeklapte tegels), conform `AppTheme`/`SfColors` in `main.dart`.
- Tests:
  - Nieuw `dashboard-frontend/test/agent_log_event_test.dart`: dekt de pure
    parser voor fallback (niet-JSON, JSON-array), Claude-schema (assistant-
    tekst, tool_use ingeklapt+detail, tool_result ingeklapt+detail, result-
    event, system-event) en Codex-schema (agent_message, command_execution,
    command_execution_output, turn.completed).
  - `dashboard-frontend/test/screens/agent_log_screen_test.dart` uitgebreid
    met een widget-test die assistent-tekst leesbaar toont, een tool-call
    standaard ingeklapt toont (payload met een lange "geheime marker"-waarde
    voorbij de 200-tekens-previewgrens is dan niet zichtbaar), een
    niet-parsebare regel gewoon als ruwe tekst laat zien, en na tikken op de
    tegel de volledige payload (incl. de marker) uitklapt. Bestaande 4 tests
    in dat bestand ongewijzigd gebleven en blijven slagen (ze gebruiken
    niet-JSON regel-tekst zoals `'regel 1'`, die via de nieuwe parser
    ongewijzigd als `raw` met dezelfde tekst gerenderd wordt).
- `docs/factory/ux/screens/agents.md` bijgewerkt met een korte beschrijving
  van de JSON-bewuste weergave en de thema-wissel, zodat de UX-doc de huidige
  implementatie weerspiegelt.
- Buiten scope gelaten (conform de story): `DockerLogFollower.kt`, de
  `agent_events`-tabel en het backend event-formaat zijn niet aangeraakt —
  dit is puur een Flutter-presentatiewijziging.
- Getest: `flutter analyze` (dashboard-frontend) → "No issues found!".
  `flutter test` (dashboard-frontend, `--concurrency=1` om de compacte
  standaard-reporter niet regelnamen te laten hergroeperen) → 37/37 groen,
  exitcode 0 (met het standaard, parallelle `flutter test` is de teller in de
  compacte reporter-uitvoer identiek, 37/37, alleen worden dan niet alle
  "loading <file>"-regels los getoond — geen echte weglating, geverifieerd
  met `-r expanded`/`--concurrency=1`).
- `mvn verify` (repo-root, deze story raakt alleen `dashboard-frontend/`, geen
  Kotlin/Maven-wijzigingen, maar vangnet conform `docs/factory/development.md`
  toch volledig gedraaid, incl. de Testcontainers-e2e-tests in
  `softwarefactory`, Docker was hier beschikbaar) → `BUILD SUCCESS`, alle vijf
  modules SUCCESS, `dashboard-backend` `Tests run: 40, Failures: 0, Errors: 0,
  Skipped: 0`, totale tijd 3:33 min, exitcode 0.

## Review (SF-1061, reviewer)

- Diff (main...HEAD, commit 7c50f3e) beoordeeld: `dashboard-frontend/lib/agent_log_event.dart` (nieuw),
  `dashboard-frontend/lib/screens/agent_log_screen.dart`, tests, `docs/factory/ux/screens/agents.md`.
- Correctheid: `parseAgentLogEvent` onderscheidt Claude (`type` in assistant/user/result/system)
  en Codex (`item.completed`/`turn.completed` met `item.type`) correct; niet-JSON en JSON-arrays
  vallen terug op `raw` i.p.v. te crashen. Kleuren correct vervangen door `SfColors`/`AppTheme`
  (geverifieerd tegen `main.dart`: `bg`/`ink`/`red`/`redSoft`/`accentSoft` bestaan).
  `_expanded`-state op index in de geflattende eventlijst is veilig omdat `_lines` alleen
  aangroeit (append-only polling, SF-1009-gedrag ongewijzigd) — geen indexverschuiving.
- Tests: nieuwe `agent_log_event_test.dart` (12 tests, Claude+Codex+fallback-paden) en uitgebreide
  widget-test in `agent_log_screen_test.dart` (assistent-tekst leesbaar, tool-call ingeklapt met
  payload-marker voorbij previewlimiet niet zichtbaar, uitklappen toont volledige payload,
  niet-parsebare regel blijft zichtbaar) dekken de acceptatiecriteria. Bestaande 4 tests
  ongewijzigd/consistent (niet-JSON regelteksten blijven via `raw`-pad identiek gerenderd).
- Specs: `docs/factory/ux/screens/agents.md` is bijgewerkt en consistent met de implementatie.
  Geen wijziging aan `.factory/verification.yaml` (canonieke commando's ongewijzigd, geen
  shell-string/fail-open-risico).
- Scope: backend (`DockerLogFollower.kt`, `agent_events`, event-formaat) bewust ongewijzigd,
  conform story. Geen scope creep gevonden.
- Geen blockers/bugs gevonden. Akkoord.

## Test (SF-1062, tester)

- Story-brede verificatie op branch `ai/SF-1045` (commit `d569bc0`), diff `main...HEAD`
  ongewijzigd t.o.v. reviewer-diff (alleen `agent_log_event.dart`, `agent_log_screen.dart`,
  bijbehorende tests, `docs/factory/ux/screens/agents.md`, worklog).
- Flutter-toolchain was dit keer wél beschikbaar in de tester-sandbox (Flutter 3.44.6 stable,
  aarch64/linux, `/opt/flutter`) — in afwijking van eerdere agent-tip
  `environment/flutter-arm64-unavailable`; die tip is dus stale voor deze host/toolchain-versie.
  `flutter pub get` (dashboard-frontend) → ok. `flutter analyze` → "No issues found!".
  `flutter test --concurrency=1` → 37/37 groen, exitcode 0, incl. de nieuwe
  `agent_log_event_test.dart` (12 tests: fallback/raw, Claude assistant/tool_use/tool_result/
  result/system, Codex agent_message/command_execution/command_execution_output/turn.completed)
  en de uitgebreide widget-test in `agent_log_screen_test.dart` (assistent-tekst leesbaar,
  tool-call standaard ingeklapt met payload-marker buiten previewlimiet niet zichtbaar,
  uitklappen toont volledige payload, niet-parsebare regel blijft zichtbaar).
- Statische controle van de implementatie tegen de acceptatiecriteria: `parseAgentLogEvent`
  (`agent_log_event.dart`) onderscheidt Claude- (`type` assistant/user/result/system) en
  Codex-schema (`item.type` via `turn.completed`/tool-item-types) correct, valt terug op
  `AgentLogEventKind.raw` bij niet-JSON of JSON-array i.p.v. te crashen; `agent_log_screen.dart`
  toont ingeklapte tool-call/tool-resultaat-tegels (naam + max-200-tekens-preview) die uitklapbaar
  zijn naar de volledige pretty-printed payload; kleuren zijn volledig vervangen door
  `SfColors.bg`/`SfColors.ink`/`SfColors.red`/`SfColors.redSoft`/`SfColors.accentSoft`
  (geen `Colors.black`/`greenAccent`/`redAccent` meer in dit bestand); live-polling/full-log-pad
  (SF-1009) is ongewijzigd, alleen de render-laag is aangepast.
- Volledig vangnet: `mvn -B --no-transfer-progress clean verify` vanaf de repo-root (Docker-socket
  beschikbaar, dus inclusief Testcontainers-e2e) → `BUILD SUCCESS`, alle vijf modules SUCCESS
  (`factory-contracts`, `factory-common`, `softwarefactory`, `agentworker`,
  `softwarefactory-dashboard-backend`), 0 Failures / 0 Errors over de volle breedte incl.
  `softwarefactory` (15+36+498+69 = 618 tests, waaronder alle e2e/Testcontainers-klassen en
  `ModulithArchitectureTest`), `agentworker` (48 tests, incl. de eerder als flaky gerapporteerde
  `TesterVerificationRunnerTest` — nu 6/6 groen, geen herhaling van de eerdere
  subreaper-zombie-flake) en `dashboard-backend` (40 tests). Totale tijd 3:32–3:40 min,
  exitcode 0. Herdraaid (tweede keer zonder `clean`) met identiek resultaat naar
  `/tmp/mvn_verify_full.log` t.b.v. volledige reactor-inspectie.
- Geen scope-overtreding: `DockerLogFollower.kt`/`agent_events`/backend event-formaat
  onaangeraakt, conform story.
- Oordeel: alle acceptatiecriteria van SF-1045 voldaan, geen bugs of regressies gevonden.
