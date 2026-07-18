# SF-1084 - scrollbar in agents

## Story

scrollbar in agents

<!-- refined-by-factory -->

## Scope

In `dashboard-frontend/lib/screens/agent_log_screen.dart` (het log-detailscherm van een agent-run, geopend vanaf het Agents-scherm):

- Voeg een zichtbare scrollbar toe aan de log-lijst (`ListView.builder` met `_scrollController`), zodat de gebruiker visueel ziet dat er meer content is en waar hij zich in de log bevindt.
- Repareer het auto-scrollgedrag: nu roept `_load()` bij elke poll (elke 3s, voor actieve runs) onvoorwaardelijk `_scrollToEnd()` aan, waardoor de lijst terugspringt naar beneden zodra er nieuwe regels bijkomen — ook als de gebruiker net omhoog heeft gescrold om oudere regels te lezen. Na de fix geldt:
  - Auto-scroll-naar-beneden gebeurt alleen bij het eerste laden van de log, en bij volgende polls alleen als de gebruiker op dat moment al (ongeveer) onderaan zat.
  - Als de gebruiker omhoog heeft gescrold (niet aan de onderkant), blijft de scrollpositie behouden wanneer nieuwe regels binnenkomen; er wordt niet automatisch teruggesprongen.

Geen wijzigingen aan backend, API, of andere schermen; alleen dit ene Flutter-scherm.

## Acceptance criteria

- De loglijst toont een scrollbar (bijv. via een `Scrollbar`-widget om de `ListView`) wanneer de content langer is dan het zichtbare gebied.
- Bij het openen van het scherm scrollt de log automatisch naar de onderkant (bestaand gedrag blijft behouden voor de initiële load).
- Voor een actieve run (`widget.active == true`, periodieke polling elke 3s): als de gebruiker aan de onderkant van de lijst staat wanneer nieuwe regels binnenkomen, scrollt de lijst automatisch mee naar beneden.
- Voor een actieve run: als de gebruiker omhoog heeft gescrold (niet aan de onderkant) wanneer nieuwe regels binnenkomen, blijft de scrollpositie ongewijzigd — geen automatische sprong naar beneden.
- Bestaand gedrag voor afgeronde runs (eenmalig laden, geen polling) blijft ongewijzigd.
- Geen regressie op de bestaande expand/collapse-functionaliteit van log-regels (`_eventTile`).

## Aannames

- "Scrollbar" betekent een zichtbaar scrollbar-element in de UI (Flutter `Scrollbar`-widget), niet enkel de al aanwezige scrollbare `ListView` zonder visuele indicator.
- "Onderaan staan" wordt bepaald met een kleine tolerantie rond `maxScrollExtent` (bijv. binnen enkele tientallen pixels), zodat kleine renderingsverschillen niet leiden tot onterecht wel/niet auto-scrollen.
- Dit betreft alleen `agent_log_screen.dart`; andere schermen met logs/lijsten vallen buiten scope tenzij later gemeld.

## Eindsamenvatting

Alle context is voldoende. Hieronder de eindsamenvatting voor de PO.

## Eindsamenvatting SF-1084 — Scrollbar in agents (log-detailscherm)

**Wat is gebouwd**
In `dashboard-frontend/lib/screens/agent_log_screen.dart` (het log-detailscherm van een agent-run):
- De `ListView.builder` met de log-regels is gewrapt in een zichtbare `Scrollbar`-widget (`thumbVisibility: true`), zodat de gebruiker nu visueel ziet dat er meer content is en waar hij zich in de log bevindt.
- Het auto-scrollgedrag is gerepareerd: voorheen sprong de lijst bij elke poll (elke 3s voor actieve runs) onvoorwaardelijk terug naar beneden, ook als de gebruiker net omhoog had gescrold. Nu wordt vóór het verwerken van nieuwe regels bepaald of de gebruiker al (ongeveer) onderaan stond (nieuwe helper `_isNearBottom()`, tolerantie 40px t.o.v. `maxScrollExtent`). Auto-scroll-naar-beneden gebeurt alleen bij de allereerste load of als de gebruiker daarvóór al onderaan stond; bij omhoogscrollen blijft de positie behouden.

**Gemaakte keuzes**
- Tolerantie van 40px gekozen voor "onderaan staan" om kleine renderingsverschillen niet als "niet onderaan" te laten tellen (conform de aanname uit de scope).
- Geen wijzigingen aan `_eventTile`/expand-collapse, het pollinterval (3s), of het gedrag voor afgeronde runs (eenmalige load, geen polling) — bewust binnen scope gehouden.
- Geen wijzigingen aan backend, API of andere schermen; geen aanpassingen aan `docs/factory/` specs, omdat dit een UI-only bugfix/verbetering is op reeds gedocumenteerd gedrag.

**Wat is getest**
- 2 nieuwe gerichte Flutter-widgettests in `agent_log_screen_test.dart`: (1) scrollpositie blijft behouden bij nieuwe regels als gebruiker omhoog heeft gescrold, (2) lijst scrollt automatisch mee als gebruiker al onderaan stond.
- `flutter analyze`: geen issues.
- `flutter test` (volledige suite): 44/44 groen, inclusief de bestaande expand/collapse-regressietest (geen regressie).
- `mvn verify` (repo-root): BUILD SUCCESS, 0 failures/errors.
- Onafhankelijk herhaald door reviewer (gerichte analyze) en tester (volledige analyze + testsuite + code-review tegen acceptatiecriteria): beide akkoord, geen bugs gevonden.
- Geen preview-omgeving beschikbaar voor deze repo; verificatie is gedaan via lokale Flutter-toolchain en statische code-review.

**Bewust niet gedaan**
- Geen wijzigingen buiten `agent_log_screen.dart` en de bijbehorende test (geen ander scherm met logs/lijsten aangepast, conform scope-afbakening).
