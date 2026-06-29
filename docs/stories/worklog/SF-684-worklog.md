# SF-684 - Worklog

Story-context bij eerste pickup:
Behavior-neutrale consistentie-opschoning gladstrijken

Inventariseer per Maven-module (softwarefactory, agentworker, dashboard-backend) afwijkingen t.o.v. een aantoonbaar dominante norm en trek ze gelijk, ZONDER gedragsverandering. In scope: (a) project-interne wildcard-imports (import nl.vdzon.softwarefactory.<pkg>.*) vervangen door expliciete per-type imports conform development.md; (b) ruwe throw IllegalStateException(...) / ?: throw IllegalArgumentException(...) vervangen door stdlib-helpers error(...)/require(...)/requireNotNull(...){...}, met behoud van domeinexcepties (YouTrackApiException, GitHubClientException, ResponseStatusException) en met respect voor de module-relatieve uitzondering: in dashboard-backend is ?: throw juist de norm en blijft ongemoeid; (c) lokale afwijkingen in naamgeving/logging/error-handling/API-conventies convergeren naar het patroon dat binnen dezelfde module al overheerst. Regels: geen nieuwe normen introduceren; alleen wijzigen waar een duidelijke meerderheid bestaat; geen @Suppress/detekt:disable toevoegen; integratie-/e2e-tests (softwarefactory/src/test/.../e2e/) NIET aanpassen - moet dat wel om groen te krijgen, dan is er gedragsverandering en gaat de story in error. Bij elke twijfel of een wijziging observeerbaar gedrag/output/API/DB-state/timing/side effects raakt: wijziging niet doen. Een (bijna) lege diff met enkel een worklog-notitie is een geldige uitkomst. Draai lokaal de relevante unit-/module-tests en quality/run.sh en vergelijk qualityrun/quality-score.json (mag niet stijgen). Werk docs/stories/worklog/SF-684-worklog.md bij met welke norm op welke plekken is gladgestreken en de testuitkomst. Voer een eigen review-stap uit op de diff.

Stappenplan:
[x]: read issue and target docs
[x]: inventariseer per module de in-scope normen (imports, error-handling, naming, logging)
[x]: implement requested changes (waar een aantoonbaar dominante-norm-afwijking veilig is)
[x]: run relevant tests
[x]: update story-log with results

## Bevindingen van de inventarisatie (2026-06-29)

Per Maven-module gezocht naar afwijkingen t.o.v. de in `docs/factory/development.md`
verankerde normen. Uitkomst: de codebase is in alle in-scope categorieën **al
consistent** met de dominante norm. Geen veilige, gedragsneutrale wijziging gevonden.

### (a) Project-interne wildcard-imports
- `grep -rn "^import .*\.\*$"` over `*/src/main`: **0 treffers** in alle drie de
  modules. Er bestaan geen wildcard-imports (project-intern noch overig) om te
  vervangen. De norm (expliciete per-type imports) is al overal nageleefd.

### (b) State-/null-guards
- `throw IllegalStateException(...)`: **0 treffers** in alle modules (al
  geconvergeerd naar `error(...)` / `check(...)`; vgl. SF-517/SF-636).
- `?: throw IllegalArgumentException(...)`: alleen in **dashboard-backend**
  (`DashboardController.kt`, 4×). Dat is de module-relatieve uitzondering waar
  `?: throw` juist de norm is (0× requireNotNull in die module) — bewust
  **ongemoeid** gelaten conform AC2.
- softwarefactory gebruikt al consistent `requireNotNull` (19×) / `require` (19×)
  / `error`/`check` voor generieke guards.
- De enige overige `throw IllegalArgumentException` in softwarefactory zit in
  `FactoryDashboardService.kt:621` binnen een `getOrElse { ... }` van een
  `runCatching` (HH:MM-parse). Dat is geen null-/state-guard en geen `?: throw`;
  omzetten naar `error(...)` zou het exception-type wijzigen
  (IllegalArgument → IllegalState) en dus gedrag/contract raken → **niet gedaan**.
- agentworker: alle `throw`/`?: throw` zijn **domeinexcepties**
  (`GitHubClientException`, `MissingTrackerFieldException`, `GitCommandException`,
  `PreviewCleanupException`, `PreviewWaitException`) — expliciet te behouden →
  **ongemoeid**.

### (c) Naamgeving / logging / error-handling / API-conventies
- Logger-declaraties softwarefactory: 31× `LoggerFactory.getLogger(javaClass)` +
  1× `getLogger(ProjectRepoResolver::class.java)`. Die ene afwijking staat in een
  `companion object`, waar `javaClass` de Companion-klasse zou opleveren; dit is
  een **bewuste** keuze, geen inconsistentie → ongemoeid.
- Logger-variabelenaam: 32× consistent `private val logger = ...`. Geen afwijking.
- `println`/`System.out` in main: in softwarefactory alleen `DockerAgentRuntime`
  (3×, bewuste `[DOCKER]`-stdout-tracing); in agentworker is `println` juist de
  module-norm (geen SLF4J-loggers in agentworker main). Omzetten naar een logger
  zou observeerbare output/side effects veranderen → buiten scope, niet gedaan.

## Conclusie

Een veilige, gedragsneutrale consistentie-wijziging die naar een **aantoonbaar
reeds dominante** norm trekt, is niet aanwezig: eerdere nightly-/opschoonstories
(o.a. SF-517 voor error-handling, SF-636 voor detekt-wins, SF-650) hebben de
in-scope normen al gladgestreken. Conform de story-aanname ("Een (bijna) lege diff
met enkel een worklog-notitie is een geldige uitkomst") is dit een veilige
**no-op nightly-run**: geen code-wijziging, alleen deze worklog-notitie.

Geen `@Suppress`/`detekt:disable` toegevoegd; geen integratie-/e2e-test aangeraakt;
geen API-/env-var-/DB-/serialisatie-contract geraakt. De detekt-kwaliteitsmeetlat
(`quality/run.sh` → `qualityrun/quality-score.json`) kan per definitie niet
verslechteren omdat er geen code-wijziging is (AC6).

## Testuitkomst

Geen code-wijziging, dus build/tests zijn ongewijzigd t.o.v. `main`. Ter controle
de agentworker-suite gedraaid:

- `mvn -f agentworker/pom.xml test` → **exit code 0 (BUILD SUCCESS)**, suite groen.

Bekende, niet door deze story veroorzaakte main-failures blijven van toepassing op
de softwarefactory-suite (`ModulithArchitectureTest` module-cycle;
`AgentResultFileCompletionPollerTest` forked-VM-crash onder de volledige run,
slaagt in isolatie); die zijn niet door deze story geïntroduceerd en niet geraakt.
