# SF-671 - nightly: ADR-naleving herstellen

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
- ADR's aanwezig met een afwijking die alleen via een functionele/gedragswijziging te herstellen is, of die inhoudelijk onduidelijk is → géён codewijziging; zet de story in **error** met vermelding van de betreffende ADR en wat afwijkt (silent-nightly: geen interactie afwachten).

## Acceptance criteria
- Er is aantoonbaar gezocht naar ADR's (minimaal `docs/adr/` plus een repo-brede zoekactie op bestandsnaam én op decision-record-structuur); de bevinding (aanwezig/afwezig en welke) staat in `docs/stories/worklog/SF-671-worklog.md`, inclusief de doorzochte locaties en gehanteerde uitsluitingen.
- Geen ADR's aanwezig → geen code- of gedragswijziging; story wordt succesvol afgerond (geen error).
- ADR's aanwezig en gevolgd → geen wijzigingen; bevinding vastgelegd in het worklog.
- ADR's aanwezig met veilig herstelbare afwijking → code in lijn gebracht zonder gedragswijziging; per ADR kort vastgelegd wat is aangepast.
- ADR's aanwezig met afwijking die alleen via gedrags-/functiewijziging herstelbaar of onduidelijk is → géén codewijziging; story gaat in error met ADR en afwijking benoemd.
- Functioneel gedrag is in alle gevallen exact gelijk gebleven.
- Alle bestaande tests slagen onveranderd (regressiesignaal `mvn -f softwarefactory/pom.xml test`: 0 failures; pre-existing/omgevingsgebonden Docker-e2e- en bekende infra-errors blokkeren niet).

## Aannames
- De repo bevat op dit moment geen ADR's: `docs/adr/` ontbreekt en de enige `*adr*`-treffers zijn de nightly-jobdefinitie zelf en eerdere ADR-naleving-stories/worklogs (SF-372, SF-436, SF-503, SF-587); de verwachte uitkomst van deze run is daarom een no-op met een worklog-notitie.
- "Veilig herstelbaar" = puur structureel/conventioneel: geen wijziging in observeerbaar gedrag, API's of testuitkomsten.
- Het bijwerken van uitsluitend het worklog (zonder code-/gedragswijziging) telt als geldige, succesvolle afronding.
- De silent-nightly-regel prevaleert: bij twijfel over een benodigde gedragswijziging → error in plaats van wachten op een mens.
- De spec dekt expliciet ook het toekomstige geval dat er later wél ADR's worden toegevoegd; een volgende run doorloopt dan alsnog de overige beslis-takken.

## Eindsamenvatting

## Eindsamenvatting — SF-671: nightly: ADR-naleving herstellen

**Wat is gebouwd / gedaan**
Dit is een nightly-controlestory: nagaan of de codebase nog voldoet aan vastgelegde Architecture Decision Records (ADR's) en afwijkingen herstellen die **zonder enige gedragswijziging** te corrigeren zijn. De uitkomst is een **geldige no-op**: er zijn geen ADR's in de repository, dus er is bewust géén code-, API- of gedragswijziging aangebracht. De enige wijziging in de PR is het bijgewerkte worklog (`docs/stories/worklog/SF-671-worklog.md`) met het zoekbewijs.

**Welke keuzes zijn gemaakt**
- ADR's zijn langs **drie sporen** gezocht: vaste locatie `docs/adr/`, repo-brede bestandsnaamzoek (`*adr*`) en een structuurzoek op decision-record-koppen (Context/Decision/Consequences) in `*.md`.
- Bekende niet-ADR-bronnen zijn expliciet **uitgesloten**: de nightly-jobdefinitie `.factory/nightly/adr/`, `.task.md`, en de eerdere ADR-naleving-stories/worklogs (SF-372/436/503/587).
- Resultaat: na uitsluiting blijven er langs alle drie de sporen **nul echte ADR's** over → beslisboom-tak (a): geen ADR's → no-op, bevinding vastgelegd.

**Wat is getest**
- Reviewer (SF-678) heeft de drie-sporen zoekactie onafhankelijk herhaald en bevestigd; diff bevat alleen het worklog → **akkoord**.
- Tester (SF-679) heeft de zoekactie gereproduceerd, bevestigd dat `git diff` uitsluitend het worklog raakt (geen code/tests/infra) → **akkoord — tested**.
- Omdat er geen productiecode is gewijzigd, is er geen nieuw regressiesignaal; `mvn test` is voor deze worklog-only no-op niet vereist.

**Wat bewust niet is gedaan**
- Geen code-, API- of gedragswijziging (er waren geen ADR's om tegen te toetsen).
- Geen aanpassing van `docs/factory/`-specs (geen functionele/technische wijziging).
- Geen error-afhandeling nodig: de afwezigheid van een besluitregister is een verwachte, geldige uitkomst (geen tak (c)/(d)).
