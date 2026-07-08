# SF-853 - creanup temporary folders

## Story

creanup temporary folders

<!-- refined-by-factory -->

## Scope

Voeg een tijd-gebaseerd opruimmechanisme toe voor tijdelijke `work/`-mappen die de factory zelf aanmaakt tijdens runtime, als achtervang bovenop de bestaande event-gedreven cleanup (die alleen bij succesvolle run-completion of expliciete purge/merge draait en dus weesmappen achterlaat bij crashes, killed processes of afgebroken flows).

Betreft de volgende bekende locaties onder `work/` (gitignored, zie `AgentWorkspaceFactory`/`AgentWorkspace.kt`, `StoryWorkspaceService.kt`, `AssistantWorkspaceService.kt`, `ClaudeAssistantClient.kt`):
- `work/agent-workspaces/<story>-<role>-<random>/` (per agent-run tempdir)
- `work/stories/<storyKey>/repo` (per-story checkout, gemarkeerd met `.factory-story-workspace`)
- `work/assistant-checkouts/<naam>/repo`
- `work/assistant/<chatId>/<sessionId>/{in,out}`

Buiten scope: `attachments/`, `logs/`, `qualityrun/`, `target/` — deze worden niet door de Kotlin-runtime zelf als "agent work"-map aangemaakt/beheerd en vallen buiten de expliciete probleemomschrijving ("work folder, folders die agents gebruiken").

Implementatie sluit aan bij het bestaande pollerpatroon (vgl. `AgentResultFileCompletionPoller`, `CostMonitorPoller`): een nieuwe `@Scheduled` component die periodiek de bovenstaande roots scant en top-level entries verwijdert waarvan de laatste-wijzigingstijd (mtime, meest recente bestand binnen de map) ouder is dan een configureerbare retentieperiode.

## Acceptance criteria

- Er is een nieuwe scheduled cleanup-component die de vier bovenstaande `work/`-subroots scant en top-level mappen ouder dan de retentieperiode volledig (recursief) verwijdert.
- Default retentieperiode is 7 dagen (1 week), configureerbaar via een `SF_*`-env var (bv. `SF_WORK_CLEANUP_RETENTION_DAYS`), analoog aan bestaande `SF_AGENT_WORKSPACE_CLEANUP_ENABLED`/`SF_AGENT_WORKSPACE_PRESERVE_ON_FAILURE`.
- Cleanup is uit te zetten via een aparte enable/disable env var (default enabled), zodat gedrag in tests/lokaal ongewijzigd blijft tenzij expliciet aangezet.
- Mappen die jonger zijn dan de retentiedrempel worden nooit aangeraakt, ook niet als de bijbehorende story-run nog actief is (voorkomt race condition met lopende agent-runs/checkouts).
- Verwijderde mappen worden gelogd (pad + berekende leeftijd) zodat opruimacties traceerbaar zijn.
- Unit tests dekken minimaal: map ouder dan drempel wordt verwijderd; map jonger dan drempel blijft staan; cleanup uitgeschakeld via config = no-op; alle vier subroots worden gescand.
- Bestaande event-gedreven cleanup (`AgentWorkspaceCleaner`, `StoryWorkspaceService.cleanup`) blijft ongewijzigd functioneren; de nieuwe scheduler is een aanvullende achtervang, geen vervanging.
- `attachments/`, `logs/`, `qualityrun/`, `target/` worden niet door deze story aangeraakt.

## Aannames

- Scope is beperkt tot de vier `work/`-subroots die al door de Kotlin-runtime worden aangemaakt; `attachments/`/`logs/`/`qualityrun/`/`target/` vallen buiten deze story omdat de issue-omschrijving expliciet spreekt over "work folder, folders die agents gebruiken".
- "Een week oud" wordt geïnterpreteerd als 7 dagen sinds laatste wijziging (mtime) van de map/inhoud, niet als aanmaakdatum (die niet betrouwbaar bijgehouden wordt in bestaande mapnamen).
- Retentieperiode en enable/disable zijn configureerbaar via env vars volgens het bestaande `SF_*`-conventiepatroon, met veilige defaults (7 dagen, enabled) zodat bestaand gedrag in productie niet verrast.
- Er is geen aparte "is deze map nog in gebruik door een actieve run"-check nodig anders dan de leeftijdsdrempel zelf, omdat een actieve run per definitie recent wordt aangeraakt (mtime wordt bijgewerkt) en dus binnen de retentieperiode blijft.

## Eindsamenvatting

## Eindsamenvatting SF-853: cleanup van tijdelijke work/-mappen

**Gebouwd**

Een nieuwe scheduled achtervang-cleanup voor tijdelijke `work/`-mappen die de factory tijdens runtime aanmaakt, als vangnet bovenop de bestaande event-gedreven cleanup (die weesmappen achterlaat bij crashes of afgebroken flows):

- `WorkCleanupSettings` / `WorkCleanupConfiguration` — leest `SF_WORK_CLEANUP_ENABLED` (default `true`) en `SF_WORK_CLEANUP_RETENTION_DAYS` (default `7`) via de bestaande `ConfigApi.resolvedValues()`-conventie.
- `WorkCleanupPoller` — een `@Scheduled` component (elk uur) dat vier subroots scant: `work/agent-workspaces/`, `work/stories/`, `work/assistant-checkouts/` (plat) en `work/assistant/<chatId>/<sessionId>/{in,out}` (twee niveaus diep). Per top-level entry wordt de meest recente mtime bepaald; is die ouder dan de retentiedrempel, dan wordt de map recursief verwijderd, met padvalidatie tegen verwijdering buiten de scan-root en logging van pad + leeftijd per verwijdering.
- `properties.default.env` en `docs/factory/technical-spec.md` / `secrets-local.md` zijn bijgewerkt met de nieuwe env-vars en componentbeschrijving.

**Keuzes**

- Implementatie volgt exact het bestaande patroon van `AgentWorkspaceCleanupSettings`/`FileSystemAgentWorkspaceCleaner`, zodat er geen nieuwe conventies zijn geïntroduceerd.
- Bij `assistant/<chatId>/<sessionId>/{in,out}` worden alleen de `in`/`out`-submappen zelf verwijderd, niet de (dan lege) sessie-/chat-oudermappen — bewuste keuze conform de letterlijke scope-omschrijving.
- Bestaande event-gedreven cleaners (`AgentWorkspaceCleaner`, `StoryWorkspaceService.cleanup`) zijn ongewijzigd gelaten; dit is puur een aanvullende achtervang.
- `attachments/`, `logs/`, `qualityrun/`, `target/` zijn buiten scope gehouden, conform de issue-omschrijving.

**Getest**

- Nieuwe unit tests (`WorkCleanupPollerTest`, JUnit 5 met `@TempDir`) dekken alle vier vereiste scenario's: map ouder dan drempel wordt verwijderd (in alle vier subroots), map jonger blijft staan, cleanup disabled = no-op, en een gemengd scenario dat bevestigt dat alle vier subroots daadwerkelijk gescand worden.
- Volledige testsuite (452 tests): 0 failures, 32 pre-existing errors — allemaal Testcontainers/Docker- of screenshot-afhankelijke tests die niet gerelateerd zijn aan deze wijziging (geen Docker beschikbaar in de test-omgeving), geen regressie t.o.v. de developer-run.
- Reviewer gaf akkoord zonder blockers; wel een niet-blokkerende suggestie om de `age(...)`-call ook binnen `runCatching` te wrappen zodat een racy verdwenen entry niet de rest van de poll-cyclus voor die subroot afbreekt.

**Bewust niet gedaan**

- Geen wijziging aan bestaande event-gedreven cleanup-logica.
- Geen opruiming van `attachments/`, `logs/`, `qualityrun/` of `target/`.
- Geen "is deze map nog actief in gebruik"-check los van de mtime-drempel; een actieve run wordt geacht binnen de retentieperiode te blijven vanwege recente bestandswijzigingen.
- De reviewer-suggestie over `runCatching` rond de stat-call is niet doorgevoerd (geen blocker, mogelijk vervolgwerk).
