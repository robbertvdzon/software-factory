# SF-169 / SF-170 - Worklog

Story-context:
Actieve-thread-koppeling + prefix-detectie implementeren (SF-170 is de development-subtask van SF-169).

Voeg twee methoden toe aan TelegramThreadStore (activeRootSession/setActiveRootSession) en implementeer ze via de bestaande telegram_state-tabel (sleutelpatroon active_root:<chatId>). Voeg in TelegramAssistantService.handle() een private detectPrefix()-functie toe die de eerste regel case-insensitief vergelijkt met de zes vaste prefixen (langste eerst). Pas de thread-bepaling aan: reply → ongewijzigd; forceNew (prefix) → nieuwe UUID; geen prefix → activeRootSession of nieuwe UUID. Update na succesvolle afhandeling de actieve root. Schrijf unit-tests voor prefix-detectie en thread-logica.

Stappenplan:
[x]: read issue and target docs
[x]: TelegramThreadStore: voeg activeRootSession/setActiveRootSession toe aan interface
[x]: JdbcTelegramThreadStore: implementeer via telegram_state (key active_root:<chatId>)
[x]: TelegramAssistantService: detectPrefix() private methode (zes prefixen, langste eerst)
[x]: TelegramAssistantService: determineSession() private methode (reply / forceNew / activeRoot fallback)
[x]: TelegramAssistantService.handle(): gebruik detectPrefix + determineSession, gebruik effectiveTextAfterPrefix
[x]: Na succesvolle afhandeling: setActiveRootSession bijwerken
[x]: helpText() en /new-/reset-/clear-handler aangepast naar nieuw gedrag
[x]: Unit-tests TelegramAssistantServiceTest: noopThreadStore + TrackingThreadStore uitgebreid met nieuwe methoden
[x]: Tests: detectPrefix (alle zes prefixen, case-varianten, geen-match-gevallen, multiline)
[x]: Tests: determineSession (reply-pad, forceNew, activeRoot-fallback, eerste bericht zonder root)
[x]: worklog bijgewerkt

Done / rationale:

**TelegramThreadStore (interface + JdbcTelegramThreadStore)**
Twee nieuwe methoden toegevoegd aan de interface: `activeRootSession(chatId)` en `setActiveRootSession(chatId, sessionId)`. De implementatie gebruikt de bestaande `telegram_state`-tabel met sleutelpatroon `active_root:<chatId>` (zelfde patroon als `updates_offset`).

**TelegramAssistantService**
- `detectPrefix(text)`: controleert de eerste regel case-insensitief op zes vaste prefixen (langste eerst: `nieuwe vraag:`, `new question:`, `iets anders:`, `nieuw:`, `new:`, `story:`). Geeft gestripte tekst terug of null.
- `determineSession(chatId, replyToMessageId, forceNew)`: retourneert `Pair<String, Boolean>` (sessionId, isResume). Reply → bestaande sessie; forceNew → nieuwe UUID; geen prefix/reply → activeRootSession of nieuwe UUID.
- `handle()`: gebruikt `detectPrefix` en `determineSession`, stuurt `effectiveTextAfterPrefix` naar Claude. Na `reply.isError == false`: roept `setActiveRootSession` aan met `actualSid`.
- `helpText()` en `/new`-handler bijgewerkt om het nieuwe gedrag te beschrijven.

**TelegramAssistantServiceTest**
- `noopThreadStore` uitgebreid met de twee nieuwe interface-methoden.
- `TrackingThreadStore` toegevoegd voor tests die state-interacties verifiëren.
- 9 tests voor `detectPrefix`: alle zes prefixen, case-varianten, geen-match-gevallen, alleen-eerste-regel, multiline behoud.
- 4 tests voor `determineSession`: reply-pad, forceNew, activeRoot-fallback, eerste bericht zonder root.

**Specs aangepast:**
Geen aparte docs/factory-bestanden bijgewerkt — het functionele gedrag is volledig beschreven in de codebase-docstrings en deze worklog. De functional-spec.md en technical-spec.md beschrijven de Telegram-integratie op hoog niveau en zijn niet per se verouderd.

## Review-opmerkingen

**[suggestie] Edge case: lege tekst na prefix-stripping**
- Scenario: gebruiker stuurt "nieuw:" zonder verdere inhoud + geen foto.
- Gevolg: `effectiveTextAfterPrefix = ""`, Claude krijgt leeg bericht → foutmelding.
- Advies: voeg validatie toe na `determineSession` (na r82 in handle()) om lege berichten met foto af te wijzen.
