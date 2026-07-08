# SF-853 - Worklog

Story-context bij eerste pickup:
Scheduled achtervang-cleanup voor tijdelijke work/-mappen

Implementeer WorkCleanupSettings (env-var configuratie: SF_WORK_CLEANUP_ENABLED default true, SF_WORK_CLEANUP_RETENTION_DAYS default 7), analoog aan AgentWorkspaceCleanupSettings. Implementeer een @Component + @Scheduled poller/service die de vier subroots scant (work/agent-workspaces/, work/stories/, work/assistant-checkouts/, work/assistant/<chatId>/<sessionId>/{in,out}), per top-level entry de meest recente mtime bepaalt, en entries ouder dan de retentiedrempel recursief verwijdert (Files.walk + reverse-order deleteIfExists, met padvalidatie binnen de subroot). Log elke verwijdering (pad + berekende leeftijd) via slf4j. Registreer settings via een @Configuration Bean die ConfigApi.resolvedValues() gebruikt. Bestaande event-gedreven cleaners (AgentWorkspaceCleaner, StoryWorkspaceService.cleanup) blijven ongewijzigd. Schrijf unit tests (JUnit 5, @TempDir, mtime via Files.setLastModifiedTime of Clock-abstractie) die dekken: map ouder dan drempel wordt verwijderd; map jonger dan drempel blijft staan; cleanup uitgeschakeld via config = no-op; alle vier subroots worden gescand.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Nieuw bestand `softwarefactory/.../runtime/workspaces/WorkCleanupSettings.kt`: data class
  `WorkCleanupSettings(enabled, retentionDays)` met `fromEnvironment(...)` (leest
  `SF_WORK_CLEANUP_ENABLED` default `true` en `SF_WORK_CLEANUP_RETENTION_DAYS` default `7`),
  plus `@Configuration WorkCleanupConfiguration` die de bean bouwt via
  `ConfigApi.resolvedValues()` — exact dezelfde conventie als
  `AgentWorkspaceCleanupSettings`/`AgentWorkspaceCleanupConfiguration`.
- Nieuw bestand `.../runtime/workspaces/WorkCleanupPoller.kt`: `@Component` met
  `@Scheduled(fixedDelayString = "\${softwarefactory.work-cleanup-poll-ms:3600000}")` (elk uur,
  analoog aan `AgentResultFileCompletionPoller`). `cleanupOnce()` scant de vier gevraagde
  subroots onder `work/` (root via `AgentWorkspaceFactory.projectRoot()`, zelfde als de
  bestaande workspace-code):
  - `work/agent-workspaces/`, `work/stories/` en `work/assistant-checkouts/` — plat gescand,
    elke top-level map is een kandidaat.
  - `work/assistant/<chatId>/<sessionId>/{in,out}` — twee niveaus diep gescand (chat- dan
    sessiemap), waarbij de `in`/`out`-submappen zelf de te-verwijderen eenheden zijn (zo staat
    in de scope-omschrijving).
  Per kandidaat wordt de meest recente mtime van alle bestanden erin bepaald (`Files.walk` +
  max op `Files.getLastModifiedTime`); is die ouder dan `retentionDays`, dan wordt de map
  recursief verwijderd (`Files.walk` + reverse-order `deleteIfExists`, zelfde patroon als
  `FileSystemAgentWorkspaceCleaner`/`StoryWorkspaceService.cleanup`). Elke verwijdering wordt
  gelogd (pad + berekende leeftijd). Padvalidatie (`require(entry.startsWith(root))`) voorkomt
  dat er ooit buiten de scan-root verwijderd wordt.
- `settings.enabled=false` maakt `poll()` een no-op (geen scan, geen logging).
- Geen wijzigingen aan `AgentWorkspaceCleaner`/`StoryWorkspaceService.cleanup` — die
  event-gedreven cleaners blijven ongewijzigd; dit is puur een aanvullende achtervang.
- `properties.default.env`: nieuwe sectie `SF_WORK_CLEANUP_ENABLED=true` /
  `SF_WORK_CLEANUP_RETENTION_DAYS=7` toegevoegd, direct na de bestaande
  agent-workspace-cleanup-sectie.
- Unit tests: `softwarefactory/src/test/kotlin/.../runtime/workspaces/WorkCleanupPollerTest.kt`
  (JUnit 5, `@TempDir`, `Clock.fixed(...)` + `Files.setLastModifiedTime`) dekt: verwijdering van
  mappen ouder dan de drempel in alle vier subroots in één keer; mappen jonger dan de drempel
  blijven staan; `enabled=false` is een complete no-op; gemengd scenario waarbij per subroot
  zowel een verlopen als een niet-verlopen entry aanwezig is (bevestigt dat alle vier roots
  daadwerkelijk gescand worden, niet slechts de eerste).
- Build/tests: `mvn -pl factory-common -am install -DskipTests` (nodig voor lokale
  `factory-common`-dependency) daarna `cd softwarefactory && mvn test
  -Dtest='!ModulithArchitectureTest,!AgentResultFileCompletionPollerTest'` → 452 tests, 0
  failures, 32 pre-existing errors (allemaal Testcontainers/Docker-afhankelijke e2e-/repo-tests;
  geen Docker beschikbaar in deze omgeving, niet gerelateerd aan deze wijziging). De nieuwe
  `WorkCleanupPollerTest` draait volledig groen.
- Docs: `docs/factory/technical-spec.md` bijgewerkt met de nieuwe scheduled cleanup-component
  en de twee nieuwe `SF_`-env-vars, naast de bestaande beschrijving van
  `AgentWorkspaceCleaner`.
