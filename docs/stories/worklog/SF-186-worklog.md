# SF-186 - Worklog

Story-context bij eerste pickup:
Oorspronkelijke-aanvraag-blok uit promoteRefinedDescription verwijderen

In StoryRefinementCoordinator.promoteRefinedDescription(): verwijder het 'if (original.isNotBlank())'-blok (regel ~163-166) dat '## Oorspronkelijke aanvraag' + de oorspronkelijke description aan newDescription toevoegt; newDescription bevat daarna alleen REFINED_DESCRIPTION_MARKER + het voorstel. Laat de ongebruikte 'original'-variabele (regel ~158) vervallen. Werk de doc-comment (regel ~145) bij: vervang 'De oorspronkelijke aanvraag blijft onderaan bewaard.' door een correcte omschrijving (description bevat na promotie alleen het refiner-voorstel). Pas de bestaande test in OrchestratorServiceTest (rond regel 189-190) aan zodat die toetst dat de gepromote description de marker + het voorstel bevat maar GEEN '## Oorspronkelijke aanvraag' en niet de originele aanvraagtekst. Laat idempotentie, voorstel-extractie en het overslaan-zonder-voorstel ongewijzigd. Voer zelf een review uit op de wijziging.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `StoryRefinementCoordinator.promoteRefinedDescription()`: het `buildString`-blok
  bevat nu alleen nog `REFINED_DESCRIPTION_MARKER` + het refiner-voorstel. De
  `original = current.trim()`-regel en het `if (original.isNotBlank())`-blok dat
  `## Oorspronkelijke aanvraag` + de oorspronkelijke description toevoegde, zijn
  verwijderd. De oude aanvraag blijft beschikbaar via de YouTrack-history.
- Doc-comment bijgewerkt: "De oorspronkelijke aanvraag blijft onderaan bewaard."
  vervangen door een omschrijving die het feitelijke gedrag dekt (na promotie
  alleen het refiner-voorstel; oude aanvraag via tracker-history).
- `OrchestratorServiceTest` (test `refined-approved promotes refiner
  proposed-description into the story description`): de twee asserts die
  `## Oorspronkelijke aanvraag` en `Originele ruwe aanvraag.` verwachtten, zijn
  omgezet naar `assertFalse`, zodat de test het nieuwe gedrag borgt.
- Idempotentie (marker-check), voorstel-extractie en het overslaan-zonder-voorstel
  zijn ongemoeid gelaten (buiten scope).
- Tests gedraaid: `mvn -f softwarefactory/pom.xml test -Dtest=OrchestratorServiceTest`
  → 42 tests groen, BUILD SUCCESS.
- Specs in `docs/factory/` niet aangepast: dit is een intern gedragsdetail van de
  promote-flow dat niet expliciet in de functional/technical-spec staat beschreven.
