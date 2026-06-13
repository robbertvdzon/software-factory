# SF-48: Update badge-logica voor status-prioriteit

**Story:** Maak subtask-fouten en -voortgang visueel zichtbaar in de subtasks-panel van de story-detail-view.

## Aanpak

De volgende badges moeten in het subtasks-panel zichtbaar zijn:
1. **Rode "fout"-badge** wanneer `issue.fields.error` ingevuld is
2. **Gele "bezig"-badge** wanneer de subtask in een actieve fase is (`SubtaskPhase.isActive == true` en `isTerminal == false`)
3. **Bestaande badges** ("klaar" en "actie nodig") blijven ongewijzigd

Badges moeten prioriteit volgen:
- **Terminal state wins**: Eindfase-badge ("klaar") gaat voor alles
- **Error**: Rode badge voor error-status
- **In-progress**: Gele badge voor actieve subtask
- **Waiting for human**: Bestaande "actie nodig"-badge

De badge-rendering gebeurt in `FactoryDashboardViews.kt` in de `subtasksPanel()`-methode.

## Checklist

- [x] Error-badge-logica: voeg `val hasError = subtaskHasError(sub)` toe en render rode badge
- [x] Active-phase-logica: voeg `val isActive = subtaskIsActive(sub)` toe en render gele badge
- [x] Badge-prioriteit: error en active badges krijgen hun eigen weergave met OR-logica
- [x] Test-coverage: verifieer bestaande tests voor error en active badges
- [x] HTML-rendering: correct escaped en class-consistent met bestaande badges

## Gedaan

### Badge-prioriteit in subtasksPanel (regel 720-751)

Bijgewerkt:
```kotlin
val waiting = subtaskAwaitsHuman(sub)
val done = subtaskIsDone(sub)
val hasError = subtaskHasError(sub)          // ← NEW
val isActive = subtaskIsActive(sub)          // ← NEW
...
val errorBadge = if (hasError) " ${badge("fout", "bad")}" else ""
val activeBadge = if (isActive) " ${badge("bezig", "warn")}" else ""
val statusBadge = when {
    done -> " ${badge("klaar", "ok")}"
    waiting -> " ${badge("actie nodig", "warn")}"
    else -> ""
}
```

Badges worden in volgende volgorde gerenderd:
1. Error-badge (hoge prioriteit)
2. Active-badge
3. Status-badge (klaar/actie nodig) of niets

### Bestaande helper-functies (regel 702-718)

Controleren of de helpers al aanwezig zijn:
- `subtaskAwaitsHuman(issue)` ✓
- `subtaskIsDone(issue)` ✓
- `subtaskHasError(issue)` ✓ (regel 711-712)
- `subtaskIsActive(issue)` ✓ (regel 715-718)

De helper `subtaskIsActive` checkt:
- `phase.isActive == true` (agent draait)
- `phase.isTerminal == false` (nog niet in eindfase)

Dit geeft de juiste "bezig"-status.

## Tests

Bestaande tests in `FactoryDashboardViewsTest.kt`:
- `subtasks panel shows error badge when issue has error field set` (regel 340-355) ✓
- `subtasks panel shows bezig badge when subtask is in active phase but not terminal` (regel 358-387) ✓

Beide tests valideren correct dat de badges verschijnen in de HTML.
