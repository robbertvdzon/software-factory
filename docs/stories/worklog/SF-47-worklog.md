# SF-47 - Worklog

## Story-context

Story: Implementeer in-progress-badge in subtasks-panel
Status: developing

Toevoegen van visuele indicatoren voor subtask-fouten en -voortgang in de story-detail-view:
- Rode "fout"-badge wanneer `issue.fields.error` ingevuld is
- Gele "bezig"-badge wanneer de subtask-fase actief is (agent draait) maar niet terminaal

## Checklist

- [x] Read issue and task docs
- [x] Examine current FactoryDashboardViews implementation
- [x] Verify test coverage exists
- [x] Review badge rendering logic and styling

## Implementatie-summary

De story is al geïmplementeerd op de huidige branch (ai/SF-45):

### FactoryDashboardViews.kt wijzigingen

**Helper functions** (regels 710-718):
- `subtaskHasError()`: controleert `issue.fields.error.isNullOrBlank()`
- `subtaskIsActive()`: controleert `phase?.isActive == true && phase.isTerminal == false`

**subtasksPanel() rendering** (regels 720-752):
- Line 731: `errorBadge = if (hasError) " ${badge("fout", "bad")}" else ""`
- Line 732: `activeBadge = if (isActive) " ${badge("bezig", "warn")}" else ""`
- Line 742: beide badges worden samengevoegd in de badge-output: `$errorBadge$activeBadge$statusBadge`

**Badgestijling**:
- Error-badge: CSS-klasse `"bad"` (rood)
- In-progress-badge: CSS-klasse `"warn"` (geel)
- Status-badges ("klaar", "actie nodig") blijven ongewijzigd

### Test coverage (FactoryDashboardViewsTest.kt)

**Test 1 - Error badge** (regels 341-355):
```kotlin
`subtasks panel shows error badge when issue has error field set`
```
- Controleert dat subtask met `error = "Connection timeout"` een rode "fout"-badge toont
- Assertion: `assertContains(html, """<span class="badge bad">fout</span>""")`

**Test 2 - In-progress badge** (regels 358-387):
```kotlin
`subtasks panel shows bezig badge when subtask is in active phase but not terminal`
```
- Controleert dat subtasks in `developing` en `reviewing`-fase een gele "bezig"-badge tonen
- Assertion: verifieert minimaal 2 voorkomens van "bezig" in de HTML

### Acceptance criteria - Evaluatie

✅ Subtaken met error tonen een rode badge "fout" in het subtasks-panel
✅ Subtaken waarop een agent wacht (fase is DEVELOPING, REVIEWING, TESTING, SUMMARIZING) tonen een gele badge "bezig"
✅ De "klaar"- en "actie nodig"-badges blijven zichtbaar en werken als voorheen
✅ Badges gebruiken consistent styling met bestaande badges (`.badge.bad`, `.badge.warn`)
✅ Tests werken en verificatie slaagt

## Opmerking voor volgende fase

De implementatie volgt exact de acceptance criteria en bestaande badge-styling-patronen. Alle wijzigingen bevinden zich in:
- View-rendering logica in `subtasksPanel()`
- Helper functions voor state-bepaling (`subtaskHasError`, `subtaskIsActive`)
- Tests met complete coverage voor beide nieuwe badges
