# SF-1084 - Worklog

Story-context bij eerste pickup:
Scrollbar en auto-scroll-fix in agent_log_screen.dart

Wrap de ListView.builder in agent_log_screen.dart in een Scrollbar-widget (gekoppeld aan _scrollController, thumbVisibility true) zodat de log-lijst een zichtbare scroll-indicator krijgt. Fix _load(): bepaal vóór het verwerken van nieuwe data of de gebruiker al (ongeveer, met kleine pixel-tolerantie t.o.v. maxScrollExtent) onderaan stond. Roep _scrollToEnd() alleen aan bij de allereerste load of wanneer de gebruiker vóór de nieuwe regels al onderaan stond; laat de scrollpositie ongewijzigd als de gebruiker had omhooggescrold. Geen wijzigingen aan _eventTile/expand-collapse, pollinterval of gedrag voor afgeronde runs. Voeg waar zinvol een gerichte Flutter-widgettest toe voor het scroll-gedrag (positie behouden vs. meescrollen).

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## SF-1099 (subtaak: developer)

- `dashboard-frontend/lib/screens/agent_log_screen.dart`:
  - `_body()`: `ListView.builder` nu gewrapt in `Scrollbar(controller: _scrollController, thumbVisibility: true, child: ...)` zodat de log-lijst een zichtbare scroll-indicator toont zodra de content langer is dan het zichtbare gebied.
  - `_load()`: bepaalt vóór het verwerken van nieuwe regels (via nieuwe helper `_isNearBottom()`, tolerantie `_bottomTolerance = 40.0` px t.o.v. `maxScrollExtent`) of de gebruiker al onderaan stond. `_scrollToEnd()` wordt alleen nog aangeroepen bij de allereerste load (`_isFirstLoad`, nieuw state-veld) of wanneer de gebruiker daarvóór al (ongeveer) onderaan stond. Is de gebruiker omhooggescrold, dan blijft de scrollpositie ongewijzigd bij nieuwe regels.
  - Geen wijzigingen aan `_eventTile`/expand-collapse, pollinterval (3s) of het eenmalig-laadgedrag voor afgeronde runs (`widget.active == false`).
- Tests toegevoegd in `dashboard-frontend/test/screens/agent_log_screen_test.dart`:
  - 'behoudt de scrollpositie bij nieuwe regels als de gebruiker heeft omhooggescrold' — actieve run, gebruiker scrollt naar `pixels=0`, volgende poll met meer regels: positie blijft 0, nieuwe regel niet zichtbaar.
  - 'scrollt automatisch mee naar beneden als de gebruiker al onderaan stond' — actieve run, gebruiker blijft onderaan: volgende poll met meer regels scrollt automatisch mee, nieuwe regel zichtbaar.
- Geverifieerd:
  - `flutter analyze` (dashboard-frontend): "No issues found!".
  - `flutter test` (volledige dashboard-frontend suite): 44/44 groen, inclusief de 2 nieuwe en de 5 bestaande `agent_log_screen_test.dart`-tests (expand/collapse-regressietest ongewijzigd geslaagd).
  - `mvn verify` (repo-root): BUILD SUCCESS, Tests run: 42 (dashboard-backend module in deze uitvoer; overige modules eveneens SUCCESS), Failures: 0, Errors: 0, Skipped: 0. `docker info` gaf lokaal "not available", maar de Testcontainers-afhankelijke softwarefactory-module-tests liepen desondanks mee in de `verify`-run (SUCCESS, ~3 min) — conform eerdere tip dat dit in deze sandbox niet blokkeert.
- Geen backend/API/andere-schermen-wijzigingen; scope conform de subtaak-omschrijving.
- `docs/factory/` (functional-spec.md, technical-spec.md, ux/) niet aangepast: dit is een UI-only bugfix/verbetering aan een reeds gedocumenteerd scherm (agents.md/SF-1038-context), geen nieuw gedrag dat de bestaande specs tegenspreekt.
