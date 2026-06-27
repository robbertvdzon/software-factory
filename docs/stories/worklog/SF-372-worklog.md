# SF-372 - Worklog

Story-context bij eerste pickup:
ADR-naleving controleren en veilig herstellen

Zoek aantoonbaar naar ADR's (minimaal docs/adr/ plus repo-brede zoek in docs/ op ADR-/decision-record-structuur; sluit .factory/nightly/adr/ uit als ADR-bron). Volg de beslisboom: (a) geen ADR's -> geen code-/gedragswijziging, leg in docs/stories/worklog/SF-372-worklog.md vast dat er geen ADR's zijn om tegen te toetsen en welke locaties zijn doorzocht; (b) ADR's aanwezig en gevolgd -> geen wijziging, bevinding in worklog; (c) ADR met veilig puur structureel/conventioneel herstelbare afwijking -> code in lijn brengen met behoud van exact hetzelfde functionele gedrag en per ADR kort vastleggen wat is aangepast; (d) ADR met afwijking die alleen via gedrags-/functiewijziging te herstellen is of inhoudelijk onduidelijk is -> géén codewijziging, zet de story in error met de betreffende ADR en wat afwijkt (silent-nightly, geen interactie afwachten). Functioneel gedrag blijft exact gelijk en bestaande tests blijven onveranderd slagen. Voeg waar relevant tests toe als onderdeel van dit ontwikkelwerk.

## Stappenplan
- [x]: read issue and target docs
- [x]: aantoonbaar zoeken naar ADR's (docs/adr/ + repo-brede zoek)
- [x]: beslisboom toepassen
- [x]: update story-log met resultaten

## Uitvoer van de ADR-zoekactie

Doorzochte locaties (allemaal aantoonbaar gecontroleerd):

1. **`docs/adr/`** — bestaat niet. Er is geen dedicated ADR-directory in de repo.
2. **Repo-brede zoek naar ADR-bestandsnamen** (`find . -iname '*adr*'`) — enige
   treffer is `.factory/nightly/adr/` (job.yaml + story.md). Dit is de
   nightly-job-definitie van deze story zelf en is conform de spec **expliciet
   uitgesloten** als ADR-bron.
3. **Repo-brede zoek in alle `*.md` op ADR-/decision-record-structuur**
   (`grep -rilE 'architecture decision record|decision record|^#+ *ADR[ -]|ADR-[0-9]'`)
   over de hele repo, met uitsluiting van `.git` en `.factory/nightly/adr/`.
   Enige treffer is `.task.md` (de story-omschrijving zelf, die het woord "ADR"
   bevat). Dit is geen ADR maar de taakdefinitie en telt dus niet mee.
4. **Aanvullende mappen gecontroleerd**: `docs/factory/`, `docs/stories/`,
   `docs/technical/`, `specs/` (specs.md, refactor-plan.md,
   integration-test-plan.md), `quality/`, `README.md`, `runbook.md`. Geen van
   deze bevat een Architecture Decision Record of decision-record-structuur.

## Bevinding (beslisboom: tak (a))

**Er zijn géén ADR's aanwezig in de repo om de code tegen te toetsen.** De enige
locatie met "adr" in de naam (`.factory/nightly/adr/`) is conform de spec
uitgesloten als ADR-bron; de overige treffer (`.task.md`) is de taakomschrijving
zelf.

Conform de beslisboom (tak a) betekent dit:

- **Geen code- of gedragswijziging.** Er is niets om tegen te toetsen of in lijn
  te brengen.
- **Geen story in error.** Afwezigheid van ADR's is een geldige, succesvolle
  no-op (zie acceptance criteria en aannames in de story).
- **Geen nieuwe tests** toegevoegd: er is geen gedragswijziging, dus er is geen
  nieuw gedrag dat getest moet worden. Bestaande tests blijven onveranderd.

Functioneel gedrag van de codebase is exact gelijk gebleven (er is niets aan de
code gewijzigd).

## Gewijzigde specs in docs/factory/
Geen. Er is geen functionele of code-wijziging, dus de functional-spec,
technical-spec en UX-docs blijven onveranderd accuraat.

## Testverificatie (SF-380, tester)

Onafhankelijk geverifieerd op branch `ai/SF-372`:

- **ADR-zoekactie herhaald en bevestigd**: `docs/adr/` bestaat niet; `find -iname
  '*adr*'` geeft enkel `.factory/nightly/adr` (conform spec uitgesloten); een
  repo-brede grep op `architecture decision record|decision record|ADR-[0-9]` in
  alle `*.md` levert alleen `.task.md` en dit worklog op — geen echte ADR's.
  Ook `docs/technical/` (overview/modules/endpoints/scheduled-jobs/external-systems)
  bevat geen decision records. Beslisboom-tak (a) is dus correct toegepast.
- **Diff t.o.v. main**: uitsluitend dit worklog gewijzigd; geen code-/infra-/
  testwijzigingen. Een functionele regressie is daardoor per definitie uitgesloten.
- **Testsuite** (`mvn -f softwarefactory/pom.xml -Dsurefire.runOrder=alphabetical
  test`): `Tests run: 390, Failures: 0, Errors: 14`. Alle 14 errors zijn
  pre-existing/omgevingsgebonden (11 Docker-e2e, NightlyRepositoriesTest +
  FactoryDashboardRepositoryScreenshotTest via Testcontainers/Docker,
  ModulithArchitectureTest pre-existing cycle), plus de bekende forked-VM
  tail-crash (YouTrackClientTest). **Failures: 0** is het relevante regressiesignaal.

Conclusie: story-doel (ADR-naleving) gehaald als geldige no-op; gedrag exact
gelijk gebleven; bestaande tests slagen onveranderd. **Test geslaagd.**
