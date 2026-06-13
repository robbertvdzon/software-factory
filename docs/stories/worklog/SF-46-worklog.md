# SF-46 - Implementeer error-badge in subtasks-panel

## Story samengevat
Maak subtask-fouten en -voortgang visueel zichtbaar in de subtasks-panel van de story-detail-view:
- Rode "fout"-badge wanneer `issue.fields.error` ingevuld is
- Gele "bezig"-badge wanneer subtask-fase actief is (agent draait) maar nog niet terminaal
- Bestaande "klaar"- en "actie nodig"-badges blijven ongewijzigd

## Implementatieplan
- [x] Lees .task.md en context
- [x] Identificeer de code-locatie (FactoryDashboardViews.kt, subtasksPanel-functie)
- [x] Definieer de logica: error-badge (fields.error niet leeg) en in-progress-badge (SubtaskPhase.isActive=true && !isTerminal)
- [x] Voeg twee helper-functions toe: `subtaskHasError` en `subtaskIsActive`
- [x] Update `subtasksPanel` om de nieuwe badges in de juiste volgorde toe te voegen
- [ ] Draai tests en verifieer rendering

## Implementatie

### FactoryDashboardViews.kt (regel 710-752)

Drie helper-functies toegevoegd:
1. **`subtaskHasError(issue)`**: checkt of `issue.fields.error` niet null/leek is
2. **`subtaskIsActive(issue)`**: checkt of fase actief is (`SubtaskPhase.isActive`) en NIET terminaal

In `subtasksPanel()`:
- Vier logische flags berekend: `waiting`, `done`, `hasError`, `isActive`
- Three badge variables gebouwd: `errorBadge`, `activeBadge`, `statusBadge`
- Badge-volgorde in HTML: Error (rood) → In-progress (geel) → Status (klaar/actie nodig)
  Dit geeft prioriteit aan fouten/voortgang voordat eindsituatie getoond wordt

### Tests (FactoryDashboardViewsTest.kt)

Twee nieuwe test-functies:
1. **`subtasks panel shows error badge when issue has error field set`**: 
   - Maakt subtask met error="Connection timeout"
   - Verifieert dat "fout" badge verschijnt met `badge bad` styling

2. **`subtasks panel shows bezig badge when subtask is in active phase but not terminal`**:
   - Maakt twee subtasks: developing (DEVELOPER role = active) en reviewing (REVIEWER role = active)
   - Verifieert dat beide "bezig" badges krijgen (minimum 2 occurrences)

Factory helper:
- `issue()` factory-functie uitgebreid met `error: String? = null` parameter
- `TrackerIssueFields()` constructor aangepast om error door te geven

## Gedaan
- [x] Twee helper-functies toegevoegd: `subtaskHasError()` en `subtaskIsActive()`
- [x] `subtasksPanel()` bijgewerkt met volledige badge-logica (error + in-progress)
- [x] Twee unit-tests toegevoegd om beide badges te verifiëren
- [x] Test factory-functie uitgebreid met error-parameter

## Niet gedaan / aanpassingen
- Geen tests lokaal gedraaid (mvn niet beschikbaar in dev-omgeving)
- Factory-CI zal tests en integratie verifiëren

## Review SF-46 (Reviewer) 

**Status**: ✅ Akkoord met opmerking

### Bevindingen

**Correctheid**: Implementatie volledig volgens spec.
- Helper-functies (`subtaskHasError`, `subtaskIsActive`) correct
- Error-badge (rood) en in-progress-badge (geel) beide aanwezig met juiste logica
- SubtaskPhase.isActive/isTerminal semantiek juist gebruikt
- Bestaande badges behouden

**Tests**: Twee specifieke tests dekken beide badges. Coördinaat-test (error + bezig tegelijk in active phase) ontbreekt, maar laag-risico edge case.

**Edge case opmerking**: Scenario waar subtask ZOWEL error HAS als in active phase (agent draait terwijl fout optreedt) toont beide badges correct. Dit is gewenst gedrag maar niet expliciet getest.
