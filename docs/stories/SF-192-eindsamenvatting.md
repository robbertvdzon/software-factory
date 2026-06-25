# SF-192 - Eindsamenvatting

## Story

Eindsamenvatting

## Eindsamenvatting

## Eindsamenvatting — SF-192: Handmatige approve-poort vóór merge

**Wat is gebouwd**

Een verplichte handmatige goedkeur-poort als vaste, niet-AI subtaak die automatisch tussen de laatste AI-subtaak (summary) en de merge-subtaak wordt geplaatst. De poort staat per project default AAN en is per project uit te zetten via `projects.yaml` (`manualApprove: false`). Goedkeuren en afkeuren lopen uniform via het bestaande `@factory:command`-mechanisme — zowel vanuit het dashboard als vanuit Telegram. Afkeuren reset de hele story-keten en geeft de afkeurreden mee aan de volgende ronde.

**Belangrijkste onderdelen**
- **Enum & tracker**: nieuw subtask-type `MANUAL_APPROVE` (`manual-approve`) en drie fases — `manual-approve-needed` (wachten), `manually-approved` (terminaal), `manually-not-approved` (transient → reset). Alle nieuwe waarden geregistreerd in de YouTrack-client zodat aanmaken/zetten niet faalt.
- **Config**: per-project `manualApproveFor()` in `ProjectRepoResolver` (default aan; alleen expliciete `false` zet uit).
- **Materialisatie**: in `AgentRunCompletionService` wordt conditioneel en idempotent één manual-approve-subtaak tussen plannerspecs en merge/deploy aangemaakt.
- **Coördinator**: `SubtaskExecutionCoordinator` handelt de fase-overgangen af (start→needed, wachten, approved→`advanceSubtaskChain`, not-approved→volledige story-reset).
- **Commando's**: `approve`/`reject` in `ManualCommandService`, met afkeurreden in een herhaalbaar te overschrijven, gemarkeerd blok in de story-description. No-op buiten de poort.
- **Dashboard**: nieuwe command-based `approveRejectCommandCard` met feedbackveld; `manual-approve-needed` telt als wacht-op-mens.
- **Telegram**: melding bij wachten-op-mens; reply vertaalt instemmend woord → approve, andere tekst → reject-met-reden, via dezelfde commando's.
- **Reset bij afkeuren**: alle subtaken (incl. de poort zelf) fase-leeg + State-lane terug naar todo, eerste subtaak op `start`. Idempotent, geen herstart-loop.

**Gemaakte keuzes**
- Afkeurreden via een optioneel `comment`-veld op het bestaande command-endpoint (geen nieuw commando-argument), zodat bestaande commando's ongewijzigd blijven.
- Feedback in een marker-blok (`<!-- manual-approve-feedback:start/end -->`) dat bij een nieuwe afkeuring wordt vervangen i.p.v. gestapeld; developer/reviewer/tester lezen de description al in.
- Reset hergebruikt de bestaande todo-lane en `advanceSubtaskChain`-guards.

**Getest**
- `mvn -f softwarefactory/pom.xml test`: 162 tests, 0 failures. De enige error (`ModulithArchitectureTest`) is op een schone `main`-worktree gereproduceerd → **pre-existing**, geen regressie.
- Dekking voor: config aan/uit, plaatsing + idempotentie in de keten, de drie fase-overgangen, en de reset (alle subtaken todo + reden in description). Bestaande keten-asserties en e2e-seed bijgewerkt; `sample`-project heeft de poort uit zodat de full-chain e2e ongewijzigd doorloopt.
- Specs (`functional-spec.md`, `technical-spec.md`) en `projects.yaml.example` bijgewerkt.

**Bewust niet gedaan**
- Geen wijzigingen aan de bestaande approve/reject-flow van andere subtaaktypes of aan merge/deploy-gedrag (buiten scope).
- Geen server-side validatie dat een reject altijd een reden bevat — bij een lege reden wordt "(geen reden opgegeven)" gebruikt (conform aannames).
- Geen dedicated unittest voor de Telegram vrije-tekst→commando-vertaling (reviewer-suggestie); de logica hergebruikt de bestaande `isApproval` en is laag-risico.

Alle 20 acceptatiecriteria zijn geïmplementeerd; review en test zijn akkoord, geen blockers.
