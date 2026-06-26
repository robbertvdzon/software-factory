# SF-186 - Eindsamenvatting

## Story

Eindsamenvatting

## Eindsamenvatting

Ik heb alle context. Hier is de eindsamenvatting voor de PO.

---

## Eindsamenvatting — SF-186: Oude beschrijving niet meer onder gepromote description plakken

### Wat is gebouwd
Bij het approven van een refined story plakte de factory tot nu toe de oorspronkelijke ruwe aanvraag onder een kopje `## Oorspronkelijke aanvraag` onderaan de gepromote story-description. Dat gedrag is verwijderd: na promotie bevat de description **alleen nog het refiner-voorstel** (de `REFINED_DESCRIPTION_MARKER` gevolgd door het voorgestelde description-blok). De oorspronkelijke aanvraag blijft beschikbaar via de YouTrack/tracker-history.

### Concrete wijzigingen
- **`StoryRefinementCoordinator.promoteRefinedDescription()`** — het `if (original.isNotBlank())`-blok dat `## Oorspronkelijke aanvraag` + de oude description toevoegde is verwijderd, evenals de nu overbodige `original`-variabele. De `buildString` bevat enkel nog marker + voorstel.
- **Doc-comment** bijgewerkt: de regel "De oorspronkelijke aanvraag blijft onderaan bewaard." is vervangen door een correcte omschrijving (na promotie alleen het refiner-voorstel; oude aanvraag via tracker-history).
- **`OrchestratorServiceTest`** — de twee asserts die juist het kopje en de originele tekst verwachtten, zijn omgezet naar `assertFalse`, zodat de test borgt dat die tekst er niét meer in staat.

### Gemaakte keuzes
- De oorspronkelijke aanvraag wordt nergens anders bewaard; de tracker-history geldt als voldoende bron (conform issue-aanname).
- Geen migratie/opschoning voor stories die eerder al mét het blok zijn gepromoot — alleen de promote-stap zelf is aangepast.
- Idempotentie (marker-check), voorstel-extractie en het overslaan-zonder-voorstel zijn bewust ongemoeid gelaten (buiten scope).

### Wat is getest
- `OrchestratorServiceTest` gericht gedraaid: **42 tests groen, BUILD SUCCESS**.
- Volledige suite (tester, SF-188): **158 tests**. De enige failure is `ModulithArchitectureTest` (architectuur-cycle orchestrator → telegram → web → orchestrator). Deze is geverifieerd **pre-existing** door dezelfde test op een schone `main`-worktree te draaien — identieke failure, dus geen regressie van deze branch.

### Acceptance criteria
Alle AC's gehaald: gepromote description bevat marker + voorstel zonder `## Oorspronkelijke aanvraag`; idempotentie werkt; geen-voorstel laat description ongewijzigd; doc-comment klopt; tests aangepast en groen.

### Bewust niet gedaan
- Geen migratie van reeds eerder gepromote stories.
- Geen wijziging aan overige promote-flow-logica of aan de specs in `docs/factory/` (intern gedragsdetail, niet in de functional/technical-spec beschreven).
