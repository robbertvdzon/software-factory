# SF-587 - nightly: ADR-naleving herstellen

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
- Er is aantoonbaar gezocht naar ADR's (minimaal `docs/adr/` plus een repo-brede zoekactie op bestandsnaam én op decision-record-structuur); de bevinding (aanwezig/afwezig en welke) staat in `docs/stories/worklog/SF-587-worklog.md`, inclusief de doorzochte locaties en gehanteerde uitsluitingen.
- Geen ADR's aanwezig → geen code- of gedragswijziging; story wordt succesvol afgerond (geen error).
- ADR's aanwezig en gevolgd → geen wijzigingen; bevinding vastgelegd in het worklog.
- ADR's aanwezig met veilig herstelbare afwijking → code in lijn gebracht zonder gedragswijziging; per ADR kort vastgelegd wat is aangepast.
- ADR's aanwezig met afwijking die alleen via gedrags-/functiewijziging herstelbaar of onduidelijk is → géén codewijziging; story gaat in error met ADR en afwijking benoemd.
- Functioneel gedrag is in alle gevallen exact gelijk gebleven.
- Alle bestaande tests slagen onveranderd (regressiesignaal `mvn -f softwarefactory/pom.xml test`: 0 failures; pre-existing/omgevingsgebonden Docker-e2e- en bekende infra-errors blokkeren niet).

## Aannames
- De repo bevat op dit moment geen ADR's: `docs/adr/` ontbreekt en de enige `*adr*`-treffers zijn de nightly-jobdefinitie zelf en eerdere ADR-naleving-stories/worklogs (SF-436, SF-372, SF-503); de verwachte uitkomst van deze run is daarom een no-op met een worklog-notitie.
- "Veilig herstelbaar" = puur structureel/conventioneel: geen wijziging in observeerbaar gedrag, API's of testuitkomsten.
- Het bijwerken van uitsluitend het worklog (zonder code-/gedragswijziging) telt als geldige, succesvolle afronding.
- De silent-nightly-regel prevaleert: bij twijfel over een benodigde gedragswijziging → error in plaats van wachten op een mens.
- De spec dekt expliciet ook het toekomstige geval dat er later wél ADR's worden toegevoegd; een volgende run doorloopt dan alsnog de overige beslis-takken.

## Eindsamenvatting

## Eindsamenvatting — SF-587: nightly ADR-naleving herstellen

**Resultaat: geldige no-op (succesvol afgerond, geen error).**

### Wat is gebouwd / gewijzigd
Niets aan code, tests of infrastructuur. De enige wijziging is een notitie in het worklog (`docs/stories/worklog/SF-587-worklog.md`). Functioneel gedrag is per definitie ongewijzigd.

### Wat is onderzocht en welke keuze is gemaakt
De story controleert of de codebase de vastgelegde Architecture Decision Records (ADR's) nog volgt. Er is langs **drie sporen** aantoonbaar gezocht:

1. **Vaste locatie** `docs/adr/` → bestaat niet.
2. **Bestandsnaam** `*adr*` (repo-breed, case-insensitive) → alleen treffers in expliciet uitgesloten bronnen.
3. **Inhoudsstructuur** (ADR-headings Context/Decision/Consequences + "decision record" in `*.md`) → geen enkel bestand met een ADR-besluitstructuur.

Toegepaste uitsluitingen conform spec: `.factory/nightly/adr/` (de nightly-jobdefinitie zelf), `.task.md`, en de eerdere ADR-naleving-stories/worklogs in `docs/stories/` (SF-436, SF-372, SF-503 — projecthistorie, geen besluitregister).

**Conclusie:** er zijn geen ADR's aanwezig. Gekozen beslis-tak: *geen ADR's → no-op zonder code- of gedragswijziging*. Dit bevestigt de aanname in `.task.md` en de uitkomst van eerdere ADR-runs.

### Wat is getest
- `git diff main...HEAD` → uitsluitend de worklog-wijziging; geen code/test/infra geraakt.
- De 3-sporen-zoekactie is door de tester (SF-595) onafhankelijk gereproduceerd met dezelfde uitkomst.
- Geen `mvn test` nodig omdat geen code/tests zijn geraakt; het regressiesignaal blijft groen. **Tester-oordeel: tested.**

### Wat bewust niet is gedaan
- Geen codewijzigingen, geen aanpassingen aan `docs/factory/`-specs (er is niets gewijzigd dat ze zouden weerspiegelen).
- Story is **niet** in error gezet: de error-tak geldt alleen bij een aanwezige afwijking die gedragswijziging vereist of onduidelijk is — die situatie deed zich niet voor.
- De spec dekt ook het toekomstige geval dat er wél ADR's worden toegevoegd; een volgende run doorloopt dan alsnog de overige beslis-takken.

Alle acceptance criteria zijn gehaald.
