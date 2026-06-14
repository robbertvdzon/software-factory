# SF-52 - Worklog

Story-context bij eerste pickup:
In de briefing wil ik ook de antwoorden zien die ik op de refiner geef

<!-- refined-by-factory -->

## Scope
In de briefing-view (`/stories/{key}/briefing`) moeten ook de antwoorden van gebruikers op agent-vragen zichtbaar zijn, niet alleen de agent-samenvattingen. Dit omvat:
- YouTrack-comments die door gebruikers zijn geplaatst als antwoord op refiner-, planner-, en ander agent-vragen
- Chronologische integratie met agent-run-kaarten
- Visuele onderscheiding tussen agent-runs en menselijke-comment-kaarten

## Acceptance criteria
- Gebruiker-comments worden in de briefing getoond als aparte kaarten
- Comments en agent-runs verschijnen in chronologische volgorde (op timestamp)
- Menselijke comments tonen duidelijk: timestamp, autor-naam, antwoord-tekst
- De briefing werkt zowel voor story-level comments als subtask-level comments
- Bestaande tests slagen; nieuwe tests dekken menselijke-comment-rendering

## Aannames
- Menselijke comments die als antwoord op vragen dienen, kunnen direct uit `issue.comments` gelezen worden
- Er is geen onderscheid nodig tussen "agent-comment" en "user-comment" in de briefing — ze worden chronologisch samengevoegd
- De visuele stijl mag eenvoudig blijven (geen complexe UI-veranderingen)
- Subtaak-comments verschijnen in een subtaak-briefing; story-comments in een story-briefing

## Oorspronkelijke aanvraag

Als de refiner vragen heeft en ik antwoord gegeven heb dan zie ik die antwoorden wel in de log van de story, maar niet in de briefing. Die wil ik daar ook bij zien staan.
En ook voor de vragen die bij de subtaak gevraagd zijn door de verschillende agents

Stappenplan:
[x]: read issue and target docs
[x]: extend briefing data model to include non-agent comments
[x]: adapt briefing view to render user comments alongside agent-runs
[x]: add tests for comment rendering
[x]: SF-94 review-fixes (test logic correction)

## Implementation details

### Models (FactoryDashboardModels.kt)
- Added sealed interface `UiBriefingItem` with timestamp
- `UiBriefingAgentRun`: wraps UiAgentRun, timestamp = startedAt
- `UiBriefingUserComment`: holds id, authorName, body, created timestamp

### View (FactoryDashboardViews.kt)
- `briefing()` function now:
  1. Collects agent-runs (story-level or subtask-level based on view context)
  2. Filters non-agent comments from issue.comments (where `!comment.isAgentComment && comment.created != null`)
  3. For story-briefing: also collects user-comments from all subtasks
  4. Combines into `sortedItems` sorted by timestamp
  5. Renders each item with appropriate HTML card
- Agent-run cards: existing layout with role, source-badge, outcome, timestamps
- User-comment cards: "USR" tile, "Gebruiker" source-badge (green), author name, timestamp, comment body

### CSS (sf-ui.css)
- Added `.brief-source.user` styling (green color scheme)
- Added `.brief-card.user-comment` styling (light bg, green tile)

### Tests (FactoryDashboardViewsTest.kt)
- Test 1: story-briefing with mixed agent-runs and user-comments in chronological order
- Test 2: subtask-briefing shows only subtask-level comments, not parent story comments

## Notes
- Agent-comments are filtered out via `TrackerCommentParser.isAgentComment()` which checks for `[ROLE]` prefix
- User-comment timestamps preserve creation order (no synthetic timestamps needed)
- Story-briefing includes subtask comments (same as agent-runs behavior)
- Subtask-briefing shows only that subtask's comments (via `page.parentKey != null` check)
- Comments without timestamps are filtered out (null-safe: `comment.created != null`)
- Null author names display as "Anoniem" (fall-through with elvis-operator)

## Review Notes (2026-06-14)

✓ Data model: sealed interface pattern correct, timestamps consistent.
✓ View logic: story vs subtask mode works, comment filtering correct (isAgentComment check).
✓ Chronological merge: sortedBy timestamp, includes subtask-comments in story-briefing.
✓ HTML: user-comment cards with proper escaping, source badge, author fallback.
✓ CSS: green styling for user-comment cards aligns with agent-run cards.

[blocker] Test 1 fix (2026-06-14T09:15:00Z):
- Probleem: test fixture voegde `[DEVELOPER]` comments toe, maar deze worden gefilterd als agent-comments.
- Fix: gebruiksgevallen simplified: alleen non-agent-comments (geen [ROLE] prefixes), twee user-comments met vier items (agent→user→agent→user).
- Verifieer: chronologische volgorde via indexing van unieke strings ("Analyse voltooid", "Ik kies", "Vragen gesteld", "Akkoord").

[blocker] Test 2 fix (2026-06-14T09:15:00Z):
- Probleem: test claimde filtering maar bevatte geen parent-story comments om die filtering te testen.
- Fix: voeg parent-story comment toe via `page.subtasks` en verifieer via `assertFalse(html.contains(...))` dat deze NIET gerendered wordt.
- Verifieer: subtask-comment WEL present, parent-story comment absent, filtering werkt via `if (!isSubtask)`.

[info] Issue vraag over "[USER] prefix" en database-opslag:
- Implementatie gebruikt geen [USER]-prefix
- Geen onderscheid tussen human user vs system-account
- Comments lezen uit YouTrack, niet uit database
- Issue vraag #1 ("zouden antwoorden ook in de database kunnen"): buiten scope, niet geadresseerd

## Review (SF-94, 2026-06-14T10:25:00Z)

✓ **Models**: UiBriefingItem sealed interface correct, timestamps konsistent met agentRun.startedAt en comment.created.
✓ **View-logica**: isSubtask-check werkt correct; subtask-briefing toont alleen subtask-level comments (via `if (!isSubtask)` filter).
✓ **Chronologische merge**: sortedBy timestamp integreert agent-runs en user-comments correct.
✓ **HTML/escaping**: user-comment kaarten hebben proper HTML-escaping (`.e()` calls), source-badge, author-fallback naar "Anoniem".
✓ **CSS**: `.brief-source.user` (groen) en `.brief-card.user-comment` styling consistent met agent-run kaarten.
✓ **Test 1**: fixture herschreven naar pure user-comments (geen [ROLE] prefixes), vier items in chronologische orde, `indexOf()` verificatie.
✓ **Test 2**: logic correct, maar fixture bevat onnodig element (`subtasks = listOf(parentStoryIssue)`).

[suggestie] Test 2 fixture vereenvoudigen:
- `subtasks = listOf(parentStoryIssue)` is niet nodig: filtering via `if (!isSubtask)` gebeurt NIET op subtasks-list inhoud.
- Mag blijven (test pass toch), maar het is misleidend commentaar ("to test filtering").
- Aanbeveling: `subtasks = emptyList()` (fixture is van subtask-perspectief, geen parent issue nodig).

Reviewstatus: **AKKOORD** — implementatie is coherent, testdekking adequate, geen scope-creep.

## Testing (SF-95, 2026-06-14T08:00:00Z)

### Test-resultaten
**FactoryDashboardViewsTest:** 25/25 tests slagen ✓
- 23 bestaande tests (inclusief gerepareerde volgorde-test)
- 2 nieuwe tests voor user-comment rendering:
  - `briefing renders user comments chronologically with agent-runs`
  - `subtask briefing shows only subtask comments, not story comments`

**Issue gevonden en opgelost:**
- Bestaande test `briefing renders newest runs first with readable outcomes and role iterations` faalde omdat nieuwe user-comment-integratie de volgorde veranderde van `sortedBy()` (oldest first) naar `sortedByDescending()` (newest first) voor consistency met originele agent-run-volgorde.
- **Fix:** Beide nieuwe tests aangepast om reverse-chronological order (newest first) te verwachten, consistent met bestaande UX.

### Coverage
- ✅ User-comments verschijnen als aparte kaarten met groene styling
- ✅ Filtering werkt correct: agent-comments gefilterd via `isAgentComment` check
- ✅ Subtask-briefing toont alleen subtask-comments, niet parent-story-comments
- ✅ Reverse chronologische volgorde (nieuwste eerst) voor alle items
- ✅ HTML-escaping van user-comment-tekst
- ✅ Fallback naar "Anoniem" voor comments zonder author-naam

### Omgeving-notitie
E2E-tests (PipelineFlowsE2eTest, FactoryUiDriverLoginTest, etc.) slagen niet in deze omgeving (11 errors) vanwege afwezigheid Docker daemon (testcontainers kunnen niet instantiëren). Dit is omgeving, geen code-bug. Alle relevante unit-tests slagen.
