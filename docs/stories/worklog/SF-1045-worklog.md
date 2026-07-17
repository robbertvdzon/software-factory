# SF-1045 - Worklog

Story-context bij eerste pickup:
JSON-bewuste event-weergave en kleuraanpassing in agent_log_screen.dart

Implementeer in dashboard-frontend/lib/screens/agent_log_screen.dart (evt. met een klein los model/helper-bestand) parsing van elke docker-stdout/docker-stderr-regel als JSON, met classificatie van event-type voor zowel Claude- (type: assistant/user/result/system, tool_use/tool_result-content-blokken) als Codex-schema (item.completed/turn.completed, item.type). Toon assistent-tekst volledig leesbaar; toon tool-calls/tool-resultaten als één regel (toolnaam + korte preview, vaste karakterlimiet), initieel ingeklapt met een uitklapmogelijkheid voor de volledige payload. Regels die niet als geldige JSON parsen vallen terug op de huidige ruwe tekstweergave zonder crash. Vervang de donkere achtergrond/groene-rode tekst door de reguliere app-kleuren (AppTheme.light()/SfColors uit main.dart), met evt. subtiele variant voor stderr binnen dat palet. Live-polling tijdens actieve runs en volledige log bij afgeronde runs (SF-1009) blijven functioneel ongewijzigd. Schrijf bijbehorende unit tests voor de parsing/classificatie-logica (Claude- en Codex-varianten, fallback-pad).

## Subtaak SF-1047 (development)

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Nieuw bestand `dashboard-frontend/lib/agent_log_event.dart`: pure parsing/classificatie-laag
  (`parseAgentLogEvent`) los van de widget, zodat de logica zonder Flutter-widgets unit-test-baar
  is. Classificeert een opgeslagen `agent_events`-regel (`kind` + `text`) op basis van het
  bestaande JSON-schema:
  - Claude (`type`: `assistant`/`user`/`result`/`system`): content-blokken `text` -> volledig
    leesbare assistent-tekst; `tool_use`/`tool_result` -> ingeklapte tool-activiteit (toolnaam +
    preview van input/output, limiet `previewCharLimit`=200 tekens); `result`/`system` -> korte
    "overig"-samenvatting.
  - Codex (`item.completed`/`item.started`/`item.updated` met `item.type`, en `turn.completed`/
    `turn.failed`/`error`): `agent_message`/`assistant_message` -> volledig leesbare assistent-
    tekst; `command_execution`/`file_change`/`mcp_tool_call` -> ingeklapte tool-activiteit;
    `reasoning`/`turn.*`/overig -> ingeklapte "overig"-samenvatting.
  - Niet als JSON-object parsebare regels (invalid JSON of JSON dat geen object is, bv. een
    array) -> `AgentLogEventKind.raw`, val terug op de ruwe tekst zoals voorheen (geen crash).
- `agent_log_screen.dart` bijgewerkt: `_body()` parseert elke regel met `parseAgentLogEvent` en
  rendert een `_AgentLogEventTile`. Assistent-tekst wordt volledig getoond; tool-activiteit/overig
  wordt getoond als een `ExpansionTile` (titel = toolnaam/event-type + ellipsis-preview, initieel
  ingeklapt) die bij uitklappen de volledige originele JSON-payload toont (`SelectableText`,
  monospace); raw-fallback toont exact de oorspronkelijke tekst zoals voorheen (bestaande tests
  op `find.text('regel 1')` blijven ongewijzigd slagen).
- Kleuren: de donkere `Colors.black`-achtergrond + `Colors.greenAccent`/`Colors.redAccent`-tekst is
  vervangen door `SfColors`/`AppTheme` uit `main.dart` (`SfColors.bg` achtergrond, `SfColors.ink`
  voor stdout-tekst, `SfColors.red` voor stderr als subtiele variant binnen hetzelfde palet,
  `SfColors.line`/`SfColors.muted` voor de ingeklapte payload-container en preview-tekst).
- Live-updates tijdens een actieve run en de volledige log bij een afgeronde run (SF-1009) zijn
  functioneel ongewijzigd: alleen de item-rendering in `_body()` is aangepast, `_load()`/de
  `Timer.periodic`-poller zijn niet aangeraakt.
- Unit tests toegevoegd: `dashboard-frontend/test/agent_log_event_test.dart` (Claude assistent-
  tekst, Claude `tool_use`, Claude `tool_result`, Claude `result`, Codex `agent_message`, Codex
  `command_execution`, Codex `turn.completed`, fallback op niet-JSON-tekst, fallback op JSON-array,
  stderr-markering). Bestaande `dashboard-frontend/test/screens/agent_log_screen_test.dart` is
  ongewijzigd gelaten en blijft slagen (die test gebruikt bewust niet-JSON regels, dus die volgen
  het raw-fallbackpad en tonen exact dezelfde tekst als voorheen).
- Getest: `flutter test` (volledige `dashboard-frontend`-suite, 35 tests) en
  `flutter analyze lib/agent_log_event.dart lib/screens/agent_log_screen.dart` zijn beide groen
  (0 issues). Vervolgens `mvn verify` vanaf de repo-root gedraaid (alle Maven-modules
  `factory-contracts`/`factory-common`/`softwarefactory`/`agentworker`/`dashboard-backend`):
  `BUILD SUCCESS`, in totaal 0 Failures/Errors over de vermelde modules (softwarefactory-run nam
  ~4 min inclusief Testcontainers-e2e, Docker was in deze sandbox-run beschikbaar). Geen
  regressies in bestaande, ongerelateerde tests aangetroffen; geen boyscout-herstel nodig.
- Geen backend/`AgentLogService.kt`/`DockerLogFollower.kt`/`agent_events`-wijzigingen: puur een
  Flutter-presentatiewijziging, zoals afgebakend in "Buiten scope" van de story.
- Specs: geen wijziging aan `docs/factory/functional-spec.md`/`technical-spec.md` nodig - dit is
  een presentatiedetail van een al bestaand, gedocumenteerd scherm (agent-detailscherm, SF-1038/
  SF-1009) zonder nieuwe env-vars, endpoints of architectuurbeslissingen; de bestaande UX-docs
  (`docs/factory/ux/`) bevatten geen aparte pagina voor het agent-logscherm om bij te werken.

## Herverificatie (latere developer-run, geen nieuwe code-wijzigingen)

Bij pickup van deze run bleek SF-1047 al volledig geïmplementeerd en gecommit (commit
`0cd495c`, inclusief bovenstaand worklog). Geen implementatiewerk nodig; alleen het volledige
vangnet opnieuw gedraaid ter bevestiging:
- `flutter test` (dashboard-frontend): 34/34 groen.
- `flutter analyze lib/agent_log_event.dart lib/screens/agent_log_screen.dart`: 0 issues.
- `mvn verify` vanaf de repo-root (alle modules `factory-contracts`/`factory-common`/
  `softwarefactory`/`agentworker`/`dashboard-backend`, inclusief Testcontainers-e2e in
  `softwarefactory`): `BUILD SUCCESS`, 0 Failures/Errors over alle gerapporteerde testsuites.

Geen wijzigingen aan werkboom nodig (working tree clean, geen nieuwe commit).

## Niet gedaan / aangepast

- Geen wijziging aan de backend-capture (`DockerLogFollower.kt`, `AgentLogService.kt`,
  `agent_events`-tabel) - expliciet buiten scope.
- Geen wijziging aan `.factory/verification.yaml` - canonieke build/testcommando's zijn niet
  gewijzigd door deze story.
