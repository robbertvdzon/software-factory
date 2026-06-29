# SF-684 - nightly: Consistentie: afwijkende patronen gladstrijken

## Story

nightly: Consistentie: afwijkende patronen gladstrijken

<!-- refined-by-factory -->

## Scope

Behavior-neutrale consistentie-opschoning van de software-factory codebase: zoek plekken die afwijken van een reeds dominante norm in dezelfde module en breng ze in lijn. Het functionele gedrag blijft **exact** gelijk.

In scope (alleen waar een aantoonbaar dominante norm bestaat en de wijziging puur cosmetisch/structureel is):
- **Naamgeving**: afwijkende class-/functie-/variabele-/constant-namen of bestandsnamen tegenover de heersende stijl in dezelfde module.
- **Imports**: project-interne wildcard-imports (`import nl.vdzon.softwarefactory.<pkg>.*`) vervangen door expliciete per-type imports (conform `development.md`).
- **State-/null-guards**: ruwe `throw IllegalStateException(...)` / `... ?: throw IllegalArgumentException(...)` vervangen door de Kotlin-stdlib helpers `error(...)` / `require(...)` / `requireNotNull(...) { ... }`, met behoud van domeinspecifieke excepties (`YouTrackApiException`, `GitHubClientException`, `ResponseStatusException`). **Let op de module-relatieve uitzondering**: binnen `dashboard-backend` is `?: throw` juist de norm en blijft ongemoeid.
- **Error-handling / logging / API-conventies**: lokale afwijkingen gelijktrekken naar het patroon dat elders in dezelfde module al overheerst (bv. logging-stijl, exception-mapping, response-vorm).
- **Dezelfde oplossing op meerdere manieren**: waar één probleem inconsistent is opgelost, convergeren naar de meest voorkomende variant zonder gedragsverandering.

Expliciet buiten scope:
- Elke wijziging die observeerbaar gedrag, output, API-contract, DB-state, timing of side effects raakt.
- Aanpassen van integratietests/e2e-tests (`softwarefactory/src/test/.../e2e/`); die zijn het vangnet.
- Wijzigingen aan de Flutter `dashboard-frontend` zijn niet vereist (mag overgeslagen).
- Brede architectuur-refactors, nieuwe abstracties of het toevoegen/verwijderen van features.
- "Normen" die slechts één keer voorkomen: zonder duidelijke meerderheid geen wijziging.

## Acceptance criteria

1. De PR bevat uitsluitend behavior-neutrale consistentie-wijzigingen; geen functionele gedragsverandering.
2. Elke wijziging trekt code naar een **aantoonbaar reeds dominante** norm binnen dezelfde module (geen nieuwe norm introduceren); de module-relatieve uitzondering voor `dashboard-backend` (`?: throw`) wordt gerespecteerd.
3. Geen enkele project-interne wildcard-import meer geïntroduceerd; bestaande gevonden wildcard-imports binnen de aangeraakte scope zijn vervangen door expliciete imports.
4. Alle bestaande tests blijven slagen: `mvn -f softwarefactory/pom.xml test`, `mvn -f agentworker/pom.xml test`, `mvn -f dashboard-backend/pom.xml test` (of `mvn test` vanaf root) zijn groen.
5. Er is **geen** integratie-/e2e-test gewijzigd. Als een wijziging alleen groen te krijgen is door een integratietest aan te passen, wordt die wijziging teruggedraaid en gaat de story in **error** (gedragsverandering gedetecteerd).
6. De detekt-kwaliteitsmeetlat (`quality/run.sh`) verslechtert niet: het aantal findings + suppressies (`qualityrun/quality-score.json`) blijft gelijk of daalt; consistentie wordt niet via nieuwe `@Suppress`/`detekt:disable` weggezwegen.
7. `docs/stories/worklog/SF-684-worklog.md` is bijgewerkt met wat is gladgestreken (welke norm, welke plekken) en de testuitkomst.
8. Bij twijfel of een wijziging gedrag verandert: de wijziging wordt niet gedaan (of de story gaat in error) — niet "voor de zekerheid toch".

## Aannames

- Dit is een terugkerende nightly-onderhoudsstory; een **kleine, gerichte** opschoning is voldoende — volledigheid over de hele repo is geen eis. Liever weinig, zeker-veilige wijzigingen dan een brede risicovolle sweep.
- "Norm" = het patroon dat binnen dezelfde Maven-module/`web`-package het vaakst voorkomt; de conventies in `docs/factory/development.md` (expliciete imports, `error/require/requireNotNull`, `SF_`-prefix, geredigeerde secrets) gelden als gezaghebbende ankers.
- Het is acceptabel dat de PR weinig of zelfs nul code-wijzigingen bevat als er geen veilige, dominante-norm-afwijkingen worden gevonden; in dat geval volstaat een worklog-notitie (vergelijkbaar met een veilige no-op nightly-run).
- Pure formattering die door geen enkele bestaande norm/tooling wordt afgedwongen wordt niet massaal aangeraakt (vermijdt ruis-diffs zonder norm-grond).
- Geen wijzigingen aan publieke API-vormen, env-var-namen, DB-migraties of serialisatie-contracten, omdat die observeerbaar gedrag raken.

## Eindsamenvatting

Ik heb `.task.md`, het worklog (`SF-684-worklog.md`) en de branch-diff bekeken. De branch bevat één commit met uitsluitend een worklog-notitie — geen code-wijzigingen. Hieronder de eindsamenvatting voor de PO.

---

## Eindsamenvatting — SF-684: nightly Consistentie — afwijkende patronen gladstrijken

### Wat is gebouwd
Dit was een terugkerende **nightly-onderhoudsstory** met als doel behavior-neutrale consistentie-opschoning: code die afwijkt van een reeds dominante norm in dezelfde module gelijktrekken, zónder functionele gedragsverandering.

**Uitkomst: een veilige no-op nightly-run.** Na een gerichte inventarisatie per Maven-module (`softwarefactory`, `agentworker`, `dashboard-backend`) bleek de codebase in alle in-scope categorieën al consistent met de dominante norm. Er is geen veilige, gedragsneutrale wijziging doorgevoerd. De PR bevat alleen een worklog-notitie (1 bestand, +80 regels).

### Bevindingen van de inventarisatie
- **Wildcard-imports** — 0 project-interne wildcard-imports gevonden in alle drie modules; norm (expliciete imports) is al overal nageleefd.
- **State-/null-guards** — 0× `throw IllegalStateException`. De `?: throw` in `dashboard-backend` (`DashboardController.kt`, 4×) is daar juist de module-norm en is bewust ongemoeid gelaten (AC2). `softwarefactory` gebruikt al consistent `require`/`requireNotNull`/`error`/`check`. `agentworker`-excepties zijn allemaal te behouden domeinexcepties.
- **Naamgeving / logging / API-conventies** — logger-declaraties (31× `javaClass`), logger-variabelenamen (32×) en `println`-gebruik zijn al consistent of vallen onder een bewuste, module-relatieve uitzondering.

### Belangrijkste keuzes
- **Bewust niets gewijzigd** waar geen aantoonbaar dominante-norm-afwijking bestaat — conform de story-aanname dat een (bijna) lege diff met enkel een worklog een geldige uitkomst is.
- Twee randgevallen bewust **niet** aangeraakt omdat ze gedrag/contract zouden raken: de `IllegalArgumentException` in `FactoryDashboardService.kt:621` (HH:MM-parse binnen `runCatching` — omzetten zou het exception-type wijzigen), en `println` in `agentworker`/`DockerAgentRuntime` (observeerbare output/side effects).
- Eerdere opschoonstories (o.a. SF-517 error-handling, SF-636 detekt-wins, SF-650) hadden de in-scope normen al gladgestreken.

### Wat is getest
- `mvn -f agentworker/pom.xml test` → **BUILD SUCCESS** (exit 0), suite groen.
- Geen code-wijziging, dus build/tests overigens ongewijzigd t.o.v. `main`.
- Geen integratie-/e2e-test aangeraakt; geen `@Suppress`/`detekt:disable` toegevoegd; geen API-/env-var-/DB-/serialisatie-contract geraakt. De detekt-kwaliteitsmeetlat kan per definitie niet verslechteren (geen code-diff, AC6).
- Bekende, niet door deze story veroorzaakte main-failures op de `softwarefactory`-suite (`ModulithArchitectureTest` module-cycle; `AgentResultFileCompletionPollerTest` forked-VM-crash) blijven van toepassing en zijn niet geraakt.

### Wat bewust niet is gedaan
- Geen brede repo-sweep, geen architectuur-refactors, geen Flutter `dashboard-frontend`-wijzigingen.
- Geen wijzigingen die observeerbaar gedrag, output, API-contract, DB-state, timing of side effects raken.
