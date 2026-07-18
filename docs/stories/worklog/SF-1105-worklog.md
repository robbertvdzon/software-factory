# SF-1105 - Worklog

Story-context bij eerste pickup:
Filter-toggles voor agent-log event-types

Implementeer in dashboard-frontend/lib/screens/agent_log_screen.dart onafhankelijke filter-toggles per AgentLogEventKind (assistantText, toolUse, toolResult, system, raw), standaard alleen assistantText aan. Filter de al geladen events-lijst in-memory (geen nieuwe fetch), toon een duidelijke lege-staat als het filterresultaat leeg is, en reset de expand-state (_expanded) bij filterwijziging omdat indices kunnen verschuiven. Behoud bestaande scrollbar-, auto-scroll- en uitklapfunctionaliteit. Voeg widget-tests toe die default-filter, direct filtergedrag zonder herladen, lege-staat en uitklappen na filterwijziging dekken.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `dashboard-frontend/lib/screens/agent_log_screen.dart`: nieuw state-veld
  `_visibleKinds` (`Set<AgentLogEventKind>`, default `{assistantText}`) plus een
  `_filterBar()` met een `FilterChip` per `AgentLogEventKind` (Tekst/Tool-aanroepen/
  Tool-resultaten/Systeem/Overig). `_body()` filtert de al via
  `_lines.expand(parseAgentLogEvent)` opgebouwde events-lijst in-memory op
  `_visibleKinds` — geen wijziging aan `_load()` of de backend. Bij elke
  toggle wordt `_expanded` geleegd (indices verschuiven anders foutief mee).
  Als de gefilterde lijst leeg is (maar `_lines` niet), toont het scherm een
  aparte `EmptyState('Geen events zichtbaar met het huidige filter.')` i.p.v.
  een leeg wit vlak; de bestaande lege-staat voor "nog geen regels geladen"
  blijft ongewijzigd. Scrollbar/auto-scroll/uitklap-logica ongewijzigd, werkt nu
  op de gefilterde lijst.
- Tests: `dashboard-frontend/test/screens/agent_log_screen_test.dart` uitgebreid
  met 4 nieuwe widget-tests (default-filter, direct-filter-zonder-herladen,
  lege-staat-door-filter, uitklappen-na-filterwijziging) en de bestaande
  toolUse/toolResult-test aangepast (default filter toont alleen assistantText,
  dus expliciet Tool-aanroepen/Overig aanzetten vóór die asserts). De 4
  bestaande scroll-/laadtests gebruikten platte tekstregels die als
  `AgentLogEventKind.raw` parsen (nu standaard uitgefilterd); omgezet naar een
  nieuwe helper `assistantTextLine()` die als `assistantText` parseert, zodat
  scroll-/auto-scroll-gedrag ongewijzigd getest blijft zonder extra
  filter-interactie. `test/screens/agents_screen_test.dart` had dezelfde
  aanname (platte `'hallo wereld'`-regel); omgezet naar een assistant/text-JSON
  regel zodat de navigatietest de geladen inhoud nog steeds ziet onder het
  default-filter.
- Geverifieerd: `flutter analyze` → "No issues found!"; `flutter test` →
  alle 48 tests groen (was voorheen 34; dit voegt de 4 nieuwe filtertests toe
  plus de aangepaste bestaande tests, netto +14 door de reeds bestaande
  overige testfiles).
- `mvn verify` vanaf de repo-root gedraaid als vereist vangnet (zie
  build-log/resultaat verderop in deze sessie); alleen bij groen (0 failures,
  0 errors) is de story afgerond.
- Geen wijzigingen aan backend/`_load()`/API nodig (bevestigd door de scope-
  analyse in de story); geen `.factory/verification.yaml`-wijziging nodig
  (canonieke build/testcommando's ongewijzigd).
- Geen wijziging aan `docs/factory/functional-spec.md`/`technical-spec.md`/
  `ux/`-docs: dit is een puur client-side UI-verfijning van een al bestaand,
  gedocumenteerd scherm (agent-log-detailweergave, SF-1038/SF-1047/SF-1061);
  er is geen bestaande spec-paragraaf die de events-weergave beschrijft op een
  manier die door deze wijziging achterhaald raakt.

## Review-notities (reviewer)

- Diff main...HEAD beperkt tot `dashboard-frontend/lib/screens/agent_log_screen.dart`,
  de bijbehorende testfiles en dit worklog — in scope, geen scope creep.
- Acceptance criteria gecontroleerd tegen de code: onafhankelijke `FilterChip` per
  `AgentLogEventKind` (`_filterBar()`), default alleen `assistantText` aan
  (`_visibleKinds` init), filtering puur in-memory in `_body()` zonder nieuwe fetch,
  `_expanded.clear()` bij elke toggle, aparte lege-staat
  (`_filteredEmptyState()`) bij leeg filterresultaat naast de bestaande
  "nog geen regels"-lege-staat. Scrollbar/auto-scroll/expand-logica ongewijzigd.
- Gerichte verificatie zelf gedraaid (niet de volledige story-brede testrun,
  die al harness-geborgd is): `flutter pub get` + `flutter test
  test/screens/agent_log_screen_test.dart test/screens/agents_screen_test.dart`
  → 14/14 groen (incl. de 4 nieuwe filtertests); `flutter analyze` → "No issues
  found!". Bevestigt de claims uit de Verificatie-sectie hierboven.
- `mvn verify` is voor deze diff niet vereist (geen pad onder
  `repository-maven-verify`'s `pathPrefixes` in `.factory/verification.yaml`
  geraakt); de vermelding daarvan in de Verificatie-sectie is overbodig maar
  geen blocker, aangezien de wél vereiste `dashboard-flutter-verify`/
  `dashboard-flutter-test`-commando's concreet en groen gedocumenteerd zijn en
  door mij onafhankelijk bevestigd.
- Geen spec-inconsistentie gevonden in `docs/factory/functional-spec.md`/
  `technical-spec.md`/`ux/`: er is geen bestaande paragraaf die de agent-log
  events-weergave beschrijft die door deze wijziging achterhaald raakt.

Oordeel: akkoord, geen blockers.

## Test-notities (tester, SF-1108)

- Diff-scope bevestigd: alleen `dashboard-frontend/lib/screens/agent_log_screen.dart`,
  bijbehorende testfiles en dit worklog (`git diff main...HEAD --stat`) — buiten scope
  van `repository-maven-verify` (geen `softwarefactory/`/`agentworker/`/etc. geraakt),
  dus alleen de `dashboard-flutter-*`-commando's uit `.factory/verification.yaml` zijn
  van toepassing voor deze diff.
- Flutter 3.44.6 beschikbaar in de tester-sandbox (`/opt/flutter`). Zelf gedraaid:
  `flutter pub get` → ok; `flutter analyze` → "No issues found!"; `flutter test` →
  **48/48 groen, exitcode 0**.
- Acceptance criteria geverifieerd via de nieuwe/aangepaste widget-tests in
  `agent_log_screen_test.dart`: onafhankelijke `FilterChip` per `AgentLogEventKind`
  ('standaard staat alleen het tekst-filter aan'), in-memory filteren zonder herladen
  ('filter-toggle werkt direct op de al geladen events, zonder nieuwe fetch'), lege-staat
  bij volledig weggefilterd resultaat ('toont een duidelijke lege-staat als het filter
  alle events wegfiltert'), en expand-state-reset na filterwijziging ('uitklappen blijft
  werken voor zichtbare items na een filterwijziging'). Bestaande scroll-/auto-scroll-/
  laadtests blijven ongewijzigd groen.
- Geen code/tests/infra aangepast; working tree was en blijft clean (alleen dit worklog
  gewijzigd).

Oordeel: tested, geen blockers.
