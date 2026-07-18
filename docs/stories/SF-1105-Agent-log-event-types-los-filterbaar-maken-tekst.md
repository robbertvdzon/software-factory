# SF-1105 - Agent-log: event-types los filterbaar maken (tekst/tools/systeem)

## Story

Agent-log: event-types los filterbaar maken (tekst/tools/systeem)

<!-- refined-by-factory -->

## Scope
Op het agent-log-detailscherm (`dashboard-frontend/lib/screens/agent_log_screen.dart`) worden nu alle events door elkaar getoond: platte agent-tekst (`AgentLogEventKind.assistantText`), tool-aanroepen (`toolUse`), tool-resultaten (`toolResult`), systeem-/thinking-events (`system`) en niet-parsbare rauwe regels (`raw`) — zie `dashboard-frontend/lib/agent_log_event.dart`. Voeg filter-toggles toe waarmee elk type los aan/uit gezet kan worden, zodat de gebruiker zelf kiest tussen "alleen de gewone tekst" en "ook laten zien welke tools hij aanroept".

De parsing gebeurt al client-side op de reeds geladen `_lines`-lijst (`_lines.expand(parseAgentLogEvent)` in `_body()`), dus filteren op `AgentLogEventKind` kan puur in-memory op de resulterende `events`-lijst, zonder wijziging aan de backend of aan `_load()`.

## Acceptance criteria
- Op het agent-log-scherm staan filter-toggles voor elk `AgentLogEventKind` (`assistantText`, `toolUse`, `toolResult`, `system`, `raw`), onafhankelijk van elkaar aan/uit te zetten.
- Standaard staat alleen `assistantText` aan; de overige types staan standaard uit.
- Het aan-/uitzetten van een filter werkt direct op de al geladen events-lijst (geen nieuwe fetch nodig).
- Bestaande functionaliteit (scrollbar, auto-scroll-gedrag, uitklappen van tool-aanroep-/resultaat-kaartjes) blijft ongewijzigd werken voor de types die getoond worden.
- Als door de gekozen filterstand geen enkel event zichtbaar is, toont het scherm een duidelijke lege-staat (geen onbedoeld leeg wit vlak).
- De filterstand hoeft niet bewaard te blijven tussen scherm-bezoeken (elke keer opnieuw terug naar de default: alleen `assistantText`) — tenzij bewaren met de gekozen aanpak vanzelf meekomt; geen harde eis.

## Aannames
- Filter-UI mag een eenvoudige rij toggle-chips/checkboxes zijn (bv. in de AppBar of direct boven de lijst); geen specifiek visueel ontwerp vereist.
- De JSON-tips-marker (`agent_tips_update`) die de agent aan het eind van zijn antwoordtekst schrijft, wordt niet apart gefilterd — die telt mee als `assistantText` en blijft dus zichtbaar zodra dat filter aanstaat (bekende, geaccepteerde beperking).
- De expand/collapse-state (`_expanded`, een `Set<int>` van indices in de events-lijst) mag bij het wijzigen van een filter gereset worden, omdat de index van een event kan verschuiven wanneer de zichtbare lijst verandert; dit is geen regressie zolang uitklappen zelf blijft werken voor zichtbare items.

## Eindsamenvatting

Duidelijk beeld van de story. Ik schrijf nu de eindsamenvatting.

## Eindsamenvatting SF-1105 — Filter-toggles voor agent-log event-types

**Wat is gebouwd**
Op het agent-log-detailscherm (`dashboard-frontend/lib/screens/agent_log_screen.dart`) is een filterbalk toegevoegd met een onafhankelijke `FilterChip` per event-type (Tekst / Tool-aanroepen / Tool-resultaten / Systeem / Overig). Standaard staat alleen "Tekst" (`assistantText`) aan, zoals geëist. Het filteren gebeurt volledig in-memory op de al geladen events-lijst — er wordt geen nieuwe data opgehaald bij het wisselen van een filter. Bij het aan/uitzetten van een filter wordt de uitklap-state (`_expanded`) geleegd, omdat event-indices kunnen verschuiven wanneer de zichtbare lijst verandert. Als het gekozen filter geen enkel event overlaat, toont het scherm een expliciete lege-staat ("Geen events zichtbaar met het huidige filter.") in plaats van een leeg vlak — los van de bestaande lege-staat voor "nog geen regels geladen". Bestaande scrollbar-, auto-scroll- en uitklapfunctionaliteit is ongewijzigd en werkt nu op de gefilterde lijst.

**Keuzes**
- Eenvoudige `FilterChip`-rij boven de lijst, geen aparte modal of instellingenscherm (conform de aanname dat visueel ontwerp vrij was).
- Filterstand wordt niet bewaard tussen scherm-bezoeken — elke keer terug naar de default (alleen Tekst), zoals expliciet toegestaan in de story.
- Geen backend- of API-wijzigingen; scope bleef beperkt tot het detailscherm.

**Getest**
- `flutter analyze`: geen issues.
- `flutter test`: 48/48 groen (4 nieuwe widget-tests voor default-filter, direct filtergedrag zonder herladen, lege-staat, en uitklappen na filterwijziging; bestaande tests aangepast waar ze impliciet op het oude "alles zichtbaar"-gedrag leunden).
- Reviewer en tester hebben de test- en analyzerun onafhankelijk herhaald met hetzelfde groene resultaat; geen blockers gevonden in beide fases.
- `mvn verify` is voor deze diff niet van toepassing (geraakt pad valt buiten de Maven-verificatiescope).

**Bewust niet gedaan**
- Geen wijziging aan functional-spec/technical-spec/ux-documentatie, omdat er geen bestaande paragraaf is die door deze wijziging achterhaald raakt.
- Filterstand niet persistent gemaakt tussen bezoeken (expliciet geen harde eis).
