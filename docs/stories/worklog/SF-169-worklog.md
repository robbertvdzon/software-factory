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

## Review-opmerkingen (SF-170 loopback)

**[blocker opgelost] Lege tekst na prefix-stripping**
- Scenario: gebruiker stuurt "nieuw:" zonder verdere inhoud en zonder foto.
- Fix: validatie toegevoegd in `handle()` na prefix-detectie: `if (effectiveTextAfterPrefix.isEmpty() && photoFileId == null) return`.
- Gedrag: berichten worden stil genegeerd (zie issue comment 7-1280).
- Test toegevoegd: `detectPrefix geeft lege string na prefix zonder verdere inhoud` verifieert dat `detectPrefix("nieuw:")` → `""` retourneert.

## Test-verificatie (SF-171)

**Testresultaten:** 22/22 TelegramAssistantServiceTest-tests groen, 298 totale unit-tests groen.

**AC1: Prefix-detectie werkt** ✅
- `detectPrefix herkent 'nieuw' prefix en strippt hem` → "vraag?"
- `detectPrefix herkent 'nieuwe vraag' prefix` → "test"
- `detectPrefix herkent 'new' prefix` → "iets"
- `detectPrefix herkent 'new question' prefix` → "hallo"
- `detectPrefix herkent 'iets anders' prefix` → "onderwerp"
- `detectPrefix herkent 'story' prefix` → "beschrijving"
- Case-insensitief: NIEUW:, Story:, NEW: werken allemaal
- Geeft null als geen prefix
- Detecteert alleen op eerste regel
- Behoudt resterende regels

**AC2: Fallback naar laatste actieve thread** ✅
- `determineSession volgt reply-keten als replyToMessageId bekend is` → bestaande sessie (isResume=true)
- `determineSession maakt nieuwe UUID als forceNew is true` → nieuwe UUID (isResume=false)
- `determineSession gebruikt actieve root als er geen reply en geen prefix is` → activeRootSession (isResume=true)
- `determineSession maakt nieuwe UUID als geen reply en geen actieve root` → nieuwe UUID (isResume=false)

**AC3: State-persistentie** ✅
- JdbcTelegramThreadStore.setActiveRootSession() slaat op in telegram_state-tabel (UPSERT, last-write-wins)
- JdbcTelegramThreadStore.activeRootSession() leest terug
- Code-implementatie correct in TelegramAssistantService.handle() (regel 127: `threadStore.setActiveRootSession(chatId, actualSid)`)

**AC4: Bestaande gedrag ongewijzigd** ✅
- Replies werken nog steeds (reply-keten bepaling ongewijzigd)
- /new, /reset, /clear commando's geven correcte help-tekst (regel 49-54)
- /help en /start werken
- /stop werkt
- Foto's worden verwerkt
- Empty text checks aanwezig (regel 44, 82)

**Andere testen:** Geen regressies. ModulithArchitectureTest-cycle en FactoryUiDriverLoginTest-fout waren pre-existente, niet veroorzaakt door deze wijzigingen.
