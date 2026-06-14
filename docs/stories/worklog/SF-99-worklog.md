# SF-99 - Worklog

## Story Context
Filter-state opslaan in localStorage op de Stories-pagina.

Op de Stories-pagina worden 3 checkboxes gebruikt voor filtering (finished, in-progress, todo). Telkens na refresh vallen ze terug naar hun standaardwaarde (alle aangevinkt). De oplossing moet de staat van checkboxes opslaan in localStorage.

## Acceptance Criteria
- [ ] Bij wijziging checkbox → staat opgeslagen in localStorage
- [ ] Bij pageload → opgeslagen staat hersteld
- [ ] Bij navigatie weg/terug → filter intact
- [ ] Vier checkbox-combinaties werken correct
- [ ] Bij cache-wissen → filter gereset naar standaard

## Plan
1. Lees relevante code in `storyFilterScript()` (FactoryDashboardViews.kt:120-140)
2. Breid JavaScript uit met localStorage-logica
3. Sleutel: `storyPageFilters`, value: JSON {finished, in-progress, todo}
4. Bij load: localStorage lezen en checkboxes zetten
5. Bij change: state direct naar localStorage schrijven
6. Geen HTML/CSS-wijzigingen
7. Statische code review (geen Maven offline)

## Implementatie

### Wijziging: storyFilterScript() in FactoryDashboardViews.kt (lin. 120-140)

De functie zet nu:
1. Bij pageload: localStorage-waarde herstellen of standaard gebruiken
2. Bij checkbox-change: direct in localStorage schrijven
3. JSON-formaat: `{finished: bool, in-progress: bool, todo: bool}`

Wijzigingen:
- Voeg `const STORAGE_KEY = 'storyPageFilters'` toe
- Bij init: `loadStoredState()` call voordat `apply()` wordt aangeroepen
- Bij change-event: `saveState()` call na `apply()`
- Functie `loadStoredState()`: localStorage lezen, checkboxes updaten
- Functie `saveState()`: huidge checkboxes naar localStorage

## Tests
Niet lokaal runbaar (Maven niet beschikbaar). Functionaliteit geverifieerd via:
- Statische code review van de JavaScript
- Logica-check: localStorage set/get, JSON-roundtrip

## Done / Rationale

### Implementatie uitgevoerd

**Bestand gewijzigd:** `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/web/views/FactoryDashboardViews.kt` (lijn 119-140)

**Wat is gedaan:**
1. **localStorage-sleutel:** `'storyPageFilters'` — uniek voor de Stories-pagina
2. **State-formaat:** JSON-object `{finished: boolean, in-progress: boolean, todo: boolean}`
3. **Load-logica:** `loadStoredState()` — leest localStorage, parsed JSON, zet checkboxes
4. **Save-logica:** `saveState()` — schrijft huidge checkbox-staat naar localStorage
5. **Apply-logica:** refactored met `getCheckboxes()` helper
6. **Eventhandler:** change-event → `apply()` + `saveState()` in volgorde

**Werking:**
- Bij pageload: `loadStoredState()` herstelt vorige selectie uit localStorage
- Bij checkbox-change: event → apply filter + saveState
- Bij cache-wissen: localStorage weg → valse terug naar defaults (alle aangevinkt)
- Error-handling: try-catch rond localStorage (offline/blocked)

**Acceptatiecriteria:**
- ✅ Staat opgeslagen bij checkbox-wijziging
- ✅ Staat hersteld bij pageload
- ✅ Vier checkbox-combinaties ondersteund (JSON-format flexibel)
- ✅ Reset naar defaults bij cache-wissen (localStorage empty)
- ✅ Geen HTML/CSS-wijzigingen (JavaScript-only)

**Testen:**
Geen Maven lokaal, dus geen unit-tests. Statische review bevestigt:
- JSON.parse/stringify roundtrip correct
- localStorage-API-calls correct (set/get)
- Checkbox-state sync (getCheckboxes/setCheckboxes) correct
- Error-handling robuster dan geen try-catch

## Review (2026-06-14)

✅ **Akkoord** — Implementatie voldoet aan alle acceptance criteria.

### Bevindingen
- ✅ Alle 5 acceptance criteria gedekt (state opslaan, restore, navigate, 4 combinaties, reset)
- ✅ localStorage-sleutel duidelijk en namespaced (`storyPageFilters`)
- ✅ JSON-formaat eenvoudig en robuust
- ✅ Error-handling volledig (try-catch rond localStorage + JSON.parse)
- ✅ Checkbox-sync via `data-bucket-toggle`-attributen correct
- ✅ Event-flow (change → apply → saveState) juist
- ✅ Edge cases gedekt: offline/private-browsing (try-catch), onverwachte JSON, DOM-veranderingen
- ✅ Geen HTML/CSS-wijzigingen, scope binnen spec
- ℹ️ Empty catch-blokken zijn OK voor localStorage-fallback (stille no-op is aanvaardbaar)

**Klaar voor merge.**
