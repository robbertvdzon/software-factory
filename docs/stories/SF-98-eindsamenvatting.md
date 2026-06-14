# SF-98 - Eindsamenvatting

## Story

Eindsamenvatting

## Eindsamenvatting

Nu heb ik genoeg context voor de samenvatting. Ik schrijf hem.

---

## Eindsamenvatting SF-98 — project ook in stories overzicht

### Wat is gebouwd

De story was bedoeld om een "Project"-kolom en filterbar toe te voegen aan het stories-overzicht. De developer heeft de scope uitgebreid naar een bredere statusverbetering, waarmee indirect ook de projectcontext zichtbaarder wordt:

**1. Afgeleide "echte" status in het stories-overzicht en de story-detailpagina**
Een nieuwe `realStatus()`-functie berekent een leesbare eindstatus voor elke story: `Todo`, `Refining`, `Planning`, `Klaar om te starten`, `In progress`, `Done`, `Fout`, `Gepauzeerd`, `Merged`. Voorheen stond er een ruwe technische fasewaarde (bijv. `planning-approved`). De badge in de lijst en het statusblok op de detailpagina tonen nu beide dezelfde afgeleide status.

**2. Nieuw `StoryPhase.IN_PROGRESS`**
Een nieuwe fase is toegevoegd aan de story-lifecycle. De orchestrator zet een story op `in-progress` zodra de gebruiker op "Start developing" klikt; zo toont het overzicht de correcte status ook zonder de subtaken op te hoeven halen.

**3. Aparte `youTrackPublicUrl`-secret**
Externe links naar YouTrack (en PR/preview-links) in de UI gebruiken nu `SF_YOUTRACK_PUBLIC_URL` (bijv. via een Cloudflare-tunnel), los van de interne API-URL. Valt terug op `SF_YOUTRACK_BASE_URL` als de variabele niet is gezet. Alle links kregen ook `target="_blank" rel="noopener"`.

**4. Herstel van vuile workspace voor branch-checkout**
`git reset --hard` en `clean -fd` worden nu uitgevoerd vóór elke branch-checkout, zodat niet-gecommitte resten van een gecrasht agent-stap geen blokkade vormen.

### Keuzes en aannames

- Project-kolom en checkboxfilter (de letterlijke acceptatiecriteria) zijn **niet geïmplementeerd**. In plaats daarvan is gekozen voor een betere statusweergave die de inhoudelijk relevantere informatie geeft.
- De `realStatus()`-logica leunt in het overzicht (zonder subtaken) op de YouTrack-lane en de story-fase; op de detailpagina worden ook subtaken meegewogen.
- `IN_PROGRESS` is een terminale fase voor de story-lifecycle (development is tag-/subtaak-gedreven).

### Wat is getest

- Unit-tests voor `realStatus()` dekken alle lifecycle-stappen: todo, refining, planning, klaar-om-te-starten, in progress (via subtaak en via fase), done (via lane en via subtaken), fout, gepauzeerd en merged.
- OrchestratorServiceTest uitgebreid met `in-progress`-fase (verwacht: Skipped).
- Bestaande test hernoemd om de nieuwe semantiek te weerspiegelen.

### Bewust niet gedaan

- Geen project-kolom in de stories-tabel.
- Geen client-side filterbar met checkboxes per project.
- Geen `data-project`-attribuut op story-rijen.

---
