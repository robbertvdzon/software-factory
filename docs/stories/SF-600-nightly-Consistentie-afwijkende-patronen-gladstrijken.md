# SF-600 - nightly: Consistentie: afwijkende patronen gladstrijken

## Story

nightly: Consistentie: afwijkende patronen gladstrijken

<!-- refined-by-factory -->

## Scope

Strijk afwijkende code-patronen in de Maven-modules (`softwarefactory`, `agentworker`, `dashboard-backend`) glad zodat ze in lijn komen met de bestaande, dominante norm in de codebase. Het gaat **uitsluitend** om consistentiewerk; functioneel gedrag blijft exact gelijk.

In scope (afwijkingen t.o.v. de codebase-norm):
- **Naamgeving**: functies, variabelen, classes en bestanden die afwijken van het omringende patroon gelijktrekken.
- **Structuur**: vergelijkbare logica die op meerdere manieren is opgelost naar één consistente vorm brengen.
- **Error-handling**: ruwe `throw IllegalStateException(...)` / `... ?: throw IllegalArgumentException(...)` vervangen door de Kotlin-stdlib helpers `error(...)` / `require(...)` / `requireNotNull(...) { ... }`, conform `docs/factory/development.md`. Domeinspecifieke excepties (`YouTrackApiException`, `GitHubClientException`, `ResponseStatusException`) blijven ongemoeid. Deze norm is **module-relatief**: binnen `dashboard-backend` is `?: throw` juist de dominante variant en blijft die staan.
- **Imports**: project-interne wildcard-imports (`import nl.vdzon.softwarefactory.<pkg>.*`) vervangen door expliciete per-type imports.
- **Logging**: afwijkende logging-stijl/-niveaus gelijktrekken met het omringende patroon; secrets blijven geredigeerd.
- **API-/configuratie-conventies**: nieuwe/bestaande config volgt de `SF_`-prefix; afwijkende API-conventies gelijktrekken.

Buiten scope:
- Functionele wijzigingen, nieuwe features, gedragsveranderingen.
- Wijzigingen aan integratietests/e2e-tests (`softwarefactory/src/test/.../e2e`).
- Wegzwijgen van problemen via `@Suppress`/`detekt:disable` om groen te worden.
- De Flutter `dashboard-frontend/` (eigen toolchain, los van de Maven-build) — alleen meenemen als een afwijking triviaal en gedragsneutraal is.

## Acceptance criteria

- Geïdentificeerde inconsistenties zijn gladgestreken richting de dominante codebase-norm; gelijksoortige problemen die verschillend waren opgelost zijn gelijkgetrokken.
- Het externe/functionele gedrag van de applicatie is onveranderd: geen wijziging in publieke API's, story-/subtask-flows, persistente data of zichtbaar gedrag.
- Alle bestaande tests slagen (`mvn test`, of per module `mvn -f softwarefactory/pom.xml test`, `mvn -f agentworker/pom.xml test`, `mvn -f dashboard-backend/pom.xml test`).
- Integratietests/e2e-tests zijn **niet** gewijzigd. Als een wijziging alleen groen te krijgen is door een integratietest aan te passen, dan verandert die wijziging gedrag → de wijziging wordt teruggedraaid of de run gaat in error.
- Geen nieuwe `@Suppress`/`@SuppressWarnings`/`detekt:disable`/`ktlint-disable`-onderdrukkingen toegevoegd om findings weg te zwijgen.
- `docs/stories/worklog/SF-600-worklog.md` is bijgewerkt met de gevonden inconsistenties, de doorgevoerde wijzigingen en de testresultaten.
- Bij twijfel of een wijziging gedrag verandert: de wijziging wordt niet gedaan; als er geen veilige consistentiewinst overblijft, is een minimale of lege wijziging met onderbouwing in de worklog acceptabel.

## Aannames

- De "norm" is de dominante variant in de omringende code en de conventies in `docs/factory/development.md`; bij meerdere bestaande varianten wint de meest voorkomende per module.
- De error-handling- en import-conventies zijn module-relatief: `softwarefactory`/`agentworker` volgen `error()`/`require()`, `dashboard-backend` behoudt `?: throw`.
- Detekt (`quality/run.sh`, score in `qualityrun/quality-score.json`) mag als optioneel hulpmiddel dienen om kandidaten te vinden, maar is geen acceptance-eis voor deze story.
- Een gerichte, kleine set wijzigingen (klein en testbaar gehouden) is acceptabel; volledigheid over de hele codebase wordt niet vereist zolang de doorgevoerde wijzigingen correct en gedragsneutraal zijn.
- Worden geen veilige, gedragsneutrale consistentieverbeteringen gevonden, dan is dat een geldige uitkomst, mits onderbouwd in de worklog.

## Eindsamenvatting

Ik heb `.task.md` en het worklog gelezen. Hieronder de eindsamenvatting voor de PO.

---

## Eindsamenvatting — SF-600: Consistentie — afwijkende patronen gladstrijken

### Wat is gebouwd
Een kleine, gerichte en **gedragsneutrale** consistentie-refactor in de Maven-module `softwarefactory`. Concreet zijn inline volledig-gekwalificeerde typenamen vervangen door expliciete per-type imports, conform de dominante codebase-norm (`docs/factory/development.md` / `technical-spec.md`):

1. **`web/models/FactoryDashboardModels.kt`** — `nl.vdzon.softwarefactory.nightly.NightlySettings` en `List<...NightlyJob>` vervangen door expliciete imports `NightlySettings` / `NightlyJob` + simpele typenamen.
2. **`web/services/FactoryDashboardService.kt`** — twee inline-FQN-aanroepen van `NightlyTime.parseHhMm(...)` rechtgetrokken via een expliciete `import ... NightlyTime`.

### Gemaakte keuzes
- **Module-relatieve norm gerespecteerd**: `dashboard-backend` behoudt bewust zijn eigen `?: throw`-patroon (daar is dat de dominante variant).
- **Bewust ongemoeid gelaten**: domein-excepties (`YouTrackApiException`, `GitHubClientException`, `ResponseStatusException`); KDoc-links in `[...Type]`-vorm (gladstrijken zou de links breken); de `throw IllegalArgumentException` in `saveNightlySettings` (bewuste exceptie-transformatie binnen `runCatching{}.getOrElse{}`, geen booleaanse conditie → `require()` past niet).
- **Klein gehouden**: de scope-categorieën error-handling en wildcard-imports leverden geen afwijkingen op in de main-code (eerdere gevallen waren al door SF-517 omgezet), dus de wijziging bleef beperkt tot drie import-correcties — een geldige, onderbouwde uitkomst volgens de story.

### Wat is getest
- `mvn -f softwarefactory/pom.xml test-compile` → succes.
- Unit-tests `NightlyTimeTest, FactoryDashboardServiceTest, FactoryDashboardViewsTest` → **70 tests, 0 failures, 0 errors, BUILD SUCCESS**.
- Tester-verificatie: diff omvat uitsluitend de twee genoemde bestanden + worklog; identieke types/bytecode; geen logica-/signatuurwijziging; alle types resolven correct naar `nl.vdzon.softwarefactory.nightly`. **Goedgekeurd.**

### Wat bewust niet is gedaan
- Geen functionele wijzigingen, geen nieuwe features.
- Geen wijzigingen aan integratie-/e2e-tests.
- Geen nieuwe `@Suppress`/`detekt:disable`/`ktlint-disable`-onderdrukkingen.
- Geen nieuwe unit-tests toegevoegd (puur import-refactoring; bestaande tests dekken de geraakte classes).

---
