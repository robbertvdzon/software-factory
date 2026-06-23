# SF-164 / SF-165 - Worklog

Story-context bij eerste pickup:
Implementeer auto-approve suppression en auto-start

Drie codedelen in één PR:

1. **FactoryDashboardService.awaitsHuman()**: voeg autoApprove-check toe. Bij autoApprove=true: retourneer false voor approval-fasen (REFINED, PLANNED, DEVELOPED/dev-type, REVIEWED, TESTED, SUMMARIZED); altijd true voor *-with-questions en AWAITING_HUMAN.

2. **TelegramNotificationService**: geef autoApprove door als parameter aan classifyStory() en classifySubtask(). Bij autoApprove=true: retourneer null i.p.v. een APPROVAL NotifyEvent. DONE- en QUESTION-events altijd doorlaten.

3. **StoryRefinementCoordinator.processStoryRefinement()**: voeg bij de PLANNING_APPROVED-tak een autoApprove-check toe. Implementeer private autoStartDevelopment(issue): haal subtaken op; als al een subtaak een fase heeft → Skipped("development-already-started") (idempotent); anders zet eerste subtaak op SubtaskPhase.START en story op StoryPhase.IN_PROGRESS → Recovered.

Stappenplan:
- [x] Lees task.md en bestaande bronbestanden
- [x] Implementeer awaitsHuman autoApprove-check (FactoryDashboardService)
- [x] Implementeer classify autoApprove-suppressie (TelegramNotificationService)
- [x] Implementeer autoStartDevelopment (StoryRefinementCoordinator)
- [x] Schrijf unit tests voor awaitsHuman met autoApprove=true/false
- [x] Schrijf unit tests voor autoStartDevelopment idempotentie
- [x] Werk worklog bij

## Gedaan

### FactoryDashboardService.awaitsHuman()
- Methode van `private` naar `internal` gemaakt zodat tests er direct bij kunnen.
- Bij `autoApprove=true`: retourneert `false` voor REFINED, PLANNED (story), REVIEWED, TESTED, SUMMARIZED, DEVELOPED/dev-type (subtask).
- Altijd `true` voor `*-with-questions`-fasen en `AWAITING_HUMAN` ongeacht autoApprove.
- Dit filtert de kaarten in de myActions-view indirect uit (de view rendert alleen issues die `awaitsHuman()` passeren).

### TelegramNotificationService.classify()
- `classify()` leest `issue.fields.autoApprove` en geeft het door aan `classifyStory()` en `classifySubtask()`.
- Bij `autoApprove=true`: approval-events (REFINED, PLANNED, REVIEWED, TESTED, SUMMARIZED, DEVELOPED/dev) leveren `null` op (geen melding).
- QUESTION-events (`*-with-questions`) en DONE-events gaan altijd door, ongeacht autoApprove.
- `buildMessage()` is niet gewijzigd.

### StoryRefinementCoordinator
- Import `SubtaskPhase` toegevoegd.
- `PLANNING_APPROVED`-tak: bij `autoApprove=true` roept `autoStartDevelopment(issue)` aan.
- `autoStartDevelopment()`: haalt subtaken op via `issueTrackerClient.subtasksOf()`. Als al een subtaak een niet-lege fase heeft → `Skipped("development-already-started")` (idempotent). Anders: zet eerste subtaak op `SubtaskPhase.START` en story op `StoryPhase.IN_PROGRESS` → `Recovered`.

### Tests
- `FactoryDashboardServiceTest`: 8 nieuwe tests voor `awaitsHuman()` met autoApprove=true/false voor story (REFINED, PLANNED_WITH_QUESTIONS) en subtask (REVIEWED, DEVELOPED/dev, REVIEWED_WITH_QUESTIONS).
- `StoryRefinementCoordinatorAutoStartTest` (nieuw bestand): 3 tests voor autoStartDevelopment — autoApprove=true start subtaak, idempotentie bij al gestarte subtaak, autoApprove=false slaat auto-start over.

## Niet gedaan / aangepast

- `FactoryDashboardViews.kt` en `FactoryDashboardModels.kt`: niet gewijzigd. De view-filtering werkt via `awaitsHuman()` in de service, niet in de view zelf.
- Geen wijziging in story-fasen, issue-fields of database-schema (conform story-aanname).
- Bestaande "Start developing"-knop blijft aanwezig (redundant bij autoApprove=true, conform story-aanname).

## Test — 2026-06-23

**FOUT GEVONDEN**: Compilatie mislukt in `StoryRefinementCoordinatorAutoStartTest.kt:182`:
```
Class 'StoryRefinementCoordinatorAutoStartTest.FakeTracker' is not abstract and does not implement abstract member:
fun getIssue(issueKey: String): TrackerIssue
```

De `FakeTracker` klasse (regel 182) implementeert `YouTrackApi` maar voegt `getIssue()` niet toe. Deze methode is een abstract member van de interface en **verplicht** (geen default implementatie).

Status: **TEST-REJECTED** — fout in test-code, terug naar developer.

## Fix — 2026-06-23 (developer-loopback)

`getIssue()` toegevoegd aan `FakeTracker` in `StoryRefinementCoordinatorAutoStartTest.kt`.
Zoekt in de geconfigureerde subtask-lijst; gooit `NoSuchElementException` als de key niet gevonden is.
Hiermee compileert de testklasse correct en zijn alle drie de auto-start-tests uitvoerbaar.

Status: **FIX TOEGEPAST** — gereed voor hertest.
