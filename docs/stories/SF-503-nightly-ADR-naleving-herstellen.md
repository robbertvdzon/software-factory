# SF-503 - nightly: ADR-naleving herstellen

## Story

nightly: ADR-naleving herstellen

<!-- refined-by-factory -->

## Scope
Controleer of de codebase de vastgelegde Architecture Decision Records (ADR's) nog volgt en herstel afwijkingen waar dat **zonder enige gedragswijziging** kan.

- Zoek aantoonbaar naar ADR's langs meerdere sporen: minimaal de vaste locatie `docs/adr/`, een repo-brede zoekactie op bestandsnaam (`*adr*`) én een zoekactie op inhoudsstructuur (ADR-/"decision record"-headings zoals Context/Decision/Consequences in `*.md`).
- Sluit als ADR-bron expliciet uit: `.factory/nightly/adr/` (de nightly-jobdefinitie van déze story), `.task.md` (de taakomschrijving) en de story-/worklog-bestanden in `docs/stories/` over ADR-naleving zelf (projecthistorie, geen besluitregister).
- Geen ADR's aanwezig → rond af als geldige no-op zonder code- of gedragswijziging; leg de bevinding en de doorzochte locaties vast in het worklog.
- ADR's aanwezig en gevolgd → geen wijziging nodig; bevinding vastleggen in het worklog.
- ADR's aanwezig met een veilig, puur structureel/conventioneel herstelbare afwijking (geen observeerbare gedragsverandering) → code in lijn brengen en per ADR kort vastleggen wat is aangepast.
- ADR's aanwezig met een afwijking die alleen via een functionele/gedragswijziging te herstellen is, of die inhoudelijk onduidelijk is → géén codewijziging; zet de story in **error** met vermelding van de betreffende ADR en wat afwijkt (silent-nightly: geen interactie afwachten).

## Acceptance criteria
- Er is aantoonbaar gezocht naar ADR's (minimaal `docs/adr/` plus een repo-brede zoekactie op bestandsnaam én op decision-record-structuur); de bevinding (aanwezig/afwezig en welke) staat in `docs/stories/worklog/SF-503-worklog.md`, inclusief de doorzochte locaties en gehanteerde uitsluitingen.
- Geen ADR's aanwezig → geen code- of gedragswijziging; story wordt succesvol afgerond (geen error).
- ADR's aanwezig en gevolgd → geen wijzigingen; bevinding vastgelegd in het worklog.
- ADR's aanwezig met veilig herstelbare afwijking → code in lijn gebracht zonder gedragswijziging; per ADR kort vastgelegd wat is aangepast.
- ADR's aanwezig met afwijking die alleen via gedrags-/functiewijziging herstelbaar of onduidelijk is → géén codewijziging; story gaat in error met ADR en afwijking benoemd.
- Functioneel gedrag is in alle gevallen exact gelijk gebleven.
- Alle bestaande tests slagen onveranderd (regressiesignaal `mvn -f softwarefactory/pom.xml test`: 0 failures; pre-existing/omgevingsgebonden Docker-e2e- en bekende infra-errors blokkeren niet).

## Aannames
- De repo bevat op dit moment geen ADR's: `docs/adr/` ontbreekt en de enige `*adr*`-treffers zijn de nightly-jobdefinitie zelf en eerdere ADR-naleving-stories/worklogs (SF-436, SF-372); de verwachte uitkomst van deze run is daarom een no-op met een worklog-notitie.
- "Veilig herstelbaar" = puur structureel/conventioneel: geen wijziging in observeerbaar gedrag, API's of testuitkomsten.
- Het bijwerken van uitsluitend het worklog (zonder code-/gedragswijziging) telt als geldige, succesvolle afronding.
- De silent-nightly-regel prevaleert: bij twijfel over een benodigde gedragswijziging → error in plaats van wachten op een mens.
- De spec dekt expliciet ook het toekomstige geval dat er later wél ADR's worden toegevoegd; een volgende run doorloopt dan alsnog de overige beslis-takken.

## Eindsamenvatting

Ik heb de volledige context. De story SF-503 is een nightly ADR-naleving-controle die als no-op is afgerond. Hier de eindsamenvatting voor de PO.

---

## Eindsamenvatting — SF-503: nightly: ADR-naleving herstellen

**Uitkomst: geldige no-op.** De codebase is gecontroleerd op naleving van vastgelegde Architecture Decision Records (ADR's). Er zijn geen ADR's in de repo aanwezig, dus er was niets te herstellen en er is bewust geen code gewijzigd.

### Wat is gebouwd / uitgevoerd
- Geen code- of configuratiewijziging. De enige aanpassing is een notitie in het worklog `docs/stories/worklog/SF-503-worklog.md` (109 regels toegevoegd) waarin de zoekactie en bevinding aantoonbaar zijn vastgelegd.
- ADR-zoekactie langs drie sporen uitgevoerd:
  1. **Vaste locatie** `docs/adr/` → bestaat niet.
  2. **Repo-brede zoek op bestandsnaam** (`*adr*`) → alleen treffers in uitgesloten bronnen.
  3. **Decision-record-structuur** (Context/Decision/Consequences-headings in `*.md`) → geen enkel bestand met die ADR-structuur.

### Keuzes en onderbouwing
- Volgens de spec expliciet uitgesloten als ADR-bron: `.factory/nightly/adr/` (jobdefinitie van deze story), `.task.md` (taakomschrijving) en de eerdere ADR-stories/worklogs SF-436 en SF-372 (projecthistorie, geen besluitregister).
- Na deze uitsluitingen blijven **0 echte ADR's** over → beslis-tak "geen ADR's → no-op" toegepast.
- De error-tak (silent-nightly) is niet getriggerd: die geldt alleen bij aanwezige ADR's met een afwijking die enkel via gedragswijziging herstelbaar of onduidelijk is.

### Wat is getest
- Tester (SF-511) heeft de zoekactie onafhankelijk gereproduceerd en bevestigd dat de diff uitsluitend het worklog raakt — geen broncode-, config- of testwijzigingen.
- Geen `mvn test` gedraaid: bij nul code/test-delta is er geen regressierisico. Functioneel gedrag is per definitie exact gelijk gebleven.

### Bewust niet gedaan
- Geen code aangepast (geen ADR's om naar te conformeren).
- Geen nieuwe tests toegevoegd (geen nieuw of gewijzigd gedrag om af te dekken).

### Aandachtspunt voor de PO
Dit is de derde keer (na SF-436 en SF-372) dat deze nightly-controle als no-op eindigt omdat de repo nog geen ADR-register kent. Zodra er wél ADR's worden toegevoegd in `docs/adr/`, doorloopt een volgende run alsnog de overige beslis-takken.
