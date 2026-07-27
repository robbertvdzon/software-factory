# SF-1352 - Bootstrap docs/adr/ met sjabloon en eerste ADR

## Story

Bootstrap docs/adr/ met sjabloon en eerste ADR

<!-- refined-by-factory -->

## Scope

Voeg een `docs/adr/` map toe met:

1. **`docs/adr/template.md`** — een kort ADR-sjabloon met de secties `Context`, `Decision` en `Consequences` (plus status/datum-metadata), te gebruiken als basis voor toekomstige ADR's.
2. **`docs/adr/0001-kotlin-spring-backend-flutter-frontend.md`** — een eerste, volledig ingevulde ADR die de reeds gemaakte, impliciete keuze vastlegt voor een Kotlin/Spring-backend (`softwarefactory/`, `dashboard-backend/`) gecombineerd met een Flutter-frontend (`dashboard-frontend/`), zoals informeel beschreven in `docs/factory/technical-spec.md`.

Geen codewijzigingen, geen configuratiewijzigingen, geen gedragswijziging — puur documentatie toevoegen. Geen bestaande bestanden wijzigen buiten het toevoegen van de nieuwe map.

## Acceptance criteria

- `docs/adr/template.md` bestaat en bevat minimaal de secties `## Context`, `## Decision` en `## Consequences`, plus een plek voor status (bv. Proposed/Accepted/Superseded) en datum.
- `docs/adr/0001-kotlin-spring-backend-flutter-frontend.md` bestaat, volgt het sjabloon, en beschrijft:
  - **Context**: waarom een gescheiden backend (Kotlin/Spring, Maven-modules) en frontend (Flutter/Dart, losse Docker-build) bestaan, incl. verwijzing naar `docs/factory/technical-spec.md`.
  - **Decision**: de keuze voor deze combinatie (Kotlin/Spring Boot voor `softwarefactory`/`dashboard-backend`; Flutter voor `dashboard-frontend`).
  - **Consequences**: gevolgen zoals aparte build-toolchains (Maven vs. Docker-Flutter-build), aparte deploy-paden, en de noodzaak beide stacks te onderhouden.
  - Status `Accepted` (het is een reeds gemaakte, bestaande keuze) met een datum.
- Geen enkel ander bestand in de repo wordt gewijzigd; geen code- of gedragswijziging.
- De nightly ADR-naleving-job en audits kunnen na deze story tegen een echt, niet-leeg decision-register toetsen (`docs/adr/` bevat minstens 1 ADR).

## Aannames

- Er wordt geen ADR-index/README in `docs/adr/` verwacht tenzij de developer dat praktisch vindt voor navigatie; dit is geen harde eis van de story.
- Nummering van ADR's start bij `0001`; toekomstige ADR's volgen oplopende nummering.
- De ADR beschrijft de bestaande situatie (retroactief vastleggen), niet een nieuw te nemen besluit — er wordt dus geen alternatieven-afweging ("Options Considered") verwacht, enkel Context/Decision/Consequences zoals expliciet gevraagd in de story.
- Geen wijziging nodig aan `docs/factory/technical-spec.md` zelf; de ADR verwijst ernaar maar dupliceert/vervangt die tekst niet.

## Eindsamenvatting

{"agent_tips_update":[]}
{"phase":"summarized"}
