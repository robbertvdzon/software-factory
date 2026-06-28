# SF-436 - nightly: ADR-naleving herstellen

## Story

nightly: ADR-naleving herstellen

<!-- refined-by-factory -->

## Scope
Controleer of de codebase de vastgelegde Architecture Decision Records (ADR's) nog volgt en herstel afwijkingen waar dat **zonder enige gedragswijziging** kan.

- Zoek aantoonbaar naar ADR's op de gangbare plekken: minimaal `docs/adr/`, plus een repo-brede zoekactie (op bestandsnaam én op ADR-/"decision record"-structuur in `*.md`).
- Sluit `.factory/nightly/adr/` (de nightly-jobdefinitie van déze story) en `.task.md` (de taakomschrijving) uit als ADR-bron.
- Geen ADR's aanwezig → rond af als geldige no-op zonder code- of gedragswijziging; leg de bevinding en doorzochte locaties vast in het worklog.
- ADR's aanwezig en gevolgd → geen wijziging nodig; bevinding vastleggen in het worklog.
- ADR's aanwezig met een veilig, puur structureel/conventioneel herstelbare afwijking (geen observeerbare gedragsverandering) → code in lijn brengen en per ADR kort vastleggen wat is aangepast.
- ADR's aanwezig met een afwijking die alleen via een functionele/gedragswijziging te herstellen is, of die inhoudelijk onduidelijk is → géén codewijziging; zet de story in **error** met vermelding van de betreffende ADR en wat afwijkt (silent-nightly: geen interactie afwachten).

## Acceptance criteria
- Er is aantoonbaar gezocht naar ADR's (minimaal `docs/adr/` plus een repo-brede zoekactie op bestandsnaam en op decision-record-structuur); de bevinding (aanwezig/afwezig en welke) staat in `docs/stories/worklog/SF-436-worklog.md`, inclusief de doorzochte locaties.
- Geen ADR's aanwezig → geen code- of gedragswijziging; story wordt succesvol afgerond (geen error).
- ADR's aanwezig en gevolgd → geen wijzigingen; bevinding vastgelegd in het worklog.
- ADR's aanwezig met veilig herstelbare afwijking → code in lijn gebracht zonder gedragswijziging; per ADR kort vastgelegd wat is aangepast.
- ADR's aanwezig met afwijking die alleen via gedrags-/functiewijziging herstelbaar of onduidelijk is → géén codewijziging; story gaat in error met ADR en afwijking benoemd.
- Functioneel gedrag is in alle gevallen exact gelijk gebleven.
- Alle bestaande tests slagen onveranderd (regressiesignaal `mvn test`: 0 failures; pre-existing/omgevingsgebonden Docker-e2e- en bekende infra-errors blokkeren niet).

## Aannames
- De repo bevat op dit moment geen ADR's (`docs/adr/` ontbreekt; enige `*adr*`-treffer is de nightly-job zelf); de verwachte uitkomst van deze run is daarom een no-op met een worklog-notitie.
- "Veilig herstelbaar" = puur structureel/conventioneel: geen wijziging in observeerbaar gedrag, API's of testuitkomsten.
- Het bijwerken van uitsluitend het worklog (zonder code-/gedragswijziging) telt als geldige, succesvolle afronding.
- De silent-nightly-regel prevaleert: bij twijfel over een benodigde gedragswijziging → error in plaats van wachten op een mens.
- De spec dekt expliciet ook het toekomstige geval dat er later wél ADR's worden toegevoegd.

## Eindsamenvatting

Ik heb `.task.md`, de summarizer-instructies en het volledige worklog van SF-436 (developer SF-443, reviewer, tester SF-444) gelezen. De story is een nightly ADR-nalevingscontrole die als no-op is afgerond. Hier is de eindsamenvatting voor de PO.

## Eindsamenvatting — SF-436: nightly ADR-naleving herstellen

**Wat is gebouwd / uitgevoerd**
Dit was een nachtelijke controle of de codebase de vastgelegde Architecture Decision Records (ADR's) nog volgt, met herstel van afwijkingen alléén waar dat zonder enige gedragswijziging kan. Er is een aantoonbare ADR-zoekactie uitgevoerd langs drie sporen:
1. **Vaste locatie** `docs/adr/` → bestaat niet.
2. **Bestandsnaam** (`find -iname '*adr*'`) → enkel `docs/stories/SF-372-*` en `.factory/nightly/adr/`.
3. **Inhoudsstructuur** (grep op ADR/decision-record-headings in `*.md`) → geen enkel bestand met een klassieke ADR-structuur (Context + Decision + Consequences).

**Gemaakte keuzes**
- Per spec uitgesloten als ADR-bron: `.factory/nightly/adr/` (de nightly-jobdefinitie van déze story) en `.task.md` (de taakomschrijving).
- De resterende treffers (SF-372 story + worklog) zijn beoordeeld als projecthistorie *óver* ADR-naleving, geen besluitregister.
- Conclusie: **geen ADR's aanwezig → spec-tak (a): geldige no-op.** Geen code- of gedragswijziging; enige output is de worklog-notitie met doorzochte locaties, treffers, uitsluitingen en onderbouwing.

**Wat is getest**
- Reviewer (SF-443) en tester (SF-444) hebben de zoekactie onafhankelijk gereproduceerd op `ai/SF-436` met identieke uitkomst.
- `git diff --name-only main...HEAD` raakt uitsluitend `docs/stories/worklog/SF-436-worklog.md`; geen productiecode, tests of specs gewijzigd.
- Docs-only: geen `mvn test`-regressiesignaal nodig; functioneel gedrag per definitie exact gelijk, bestaande tests onveranderd. Geen preview-omgeving / UI-wijziging, dus geen browsertest. Tester-oordeel: **pass**.

**Bewust niet gedaan**
- Geen codewijzigingen, geen refactors, geen spec-aanpassingen — er was geen ADR om tegen te toetsen.
- Geen error-tak: er is geen onduidelijke of alleen-via-gedragswijziging herstelbare afwijking gevonden.
- Toekomstvast: zodra er wél een `docs/adr/` of vergelijkbaar besluitregister wordt toegevoegd, doorloopt een volgende run alsnog spec-takken (b)/(c)/(d).
