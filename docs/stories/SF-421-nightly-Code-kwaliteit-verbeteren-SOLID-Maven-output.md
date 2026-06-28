# SF-421 - nightly: Code-kwaliteit verbeteren (SOLID, Maven-output)

## Story

nightly: Code-kwaliteit verbeteren (SOLID, Maven-output)

<!-- refined-by-factory -->

## Scope

Puur kwaliteits-/refactorwerk aan de bestaande code, met **exact gelijkblijvend functioneel gedrag**. Doel is leesbaarheid en onderhoudbaarheid verhogen en bouwruis wegwerken, zonder de werking te veranderen.

In scope:
- **SOLID & leesbaarheid** in de Maven-modules (`softwarefactory`, `agentworker`, `dashboard-backend`):
  verbeter naamgeving, verwijder dode code, hef duplicatie op en splits te lange functies/klassen op. Volg de bestaande conventies (Kotlin/JDK 21, expliciete imports, geen project-interne wildcard-imports, `SF_`-prefix voor config, secrets nooit ongeredigeerd loggen).
- **Maven-output opschonen**: los compiler-/build-warnings en deprecations op die bij `mvn test` zichtbaar zijn (bv. deprecated API's vervangen door hun aanbevolen alternatief, ongebruikte/dubbele dependencies), zolang dit het gedrag niet verandert.
- Begrens de wijziging tot een **gerichte, samenhangende set refactors** (stories klein en testbaar houden). Het is uitdrukkelijk geen volledige herstructurering van de codebase.

Buiten scope:
- Functionele wijzigingen, nieuwe features, API-/contract-wijzigingen, DB-migraties of gedrag van endpoints/CLI.
- Aanpassen van integratietests (e2e/pipeline) of het wijzigen van bestaand testgedrag om een refactor groen te krijgen.
- De Flutter `dashboard-frontend` (staat los van de Maven-build) — alleen meenemen als er triviale, gedrag-neutrale opschoning nodig is; bij twijfel niet.

## Acceptance criteria

1. Alle bestaande tests slagen onveranderd: `mvn test` (root, over de drie modules) is groen.
2. De integratie-/e2e-tests in `softwarefactory/src/test/kotlin/.../e2e/` zijn **niet gewijzigd**. Moest een integratietest aangepast worden om groen te worden, dan is dat een gedragswijziging → ga in error (met vermelding van de test en het afwijkende gedrag) in plaats van de test te wijzigen.
3. Functioneel gedrag is aantoonbaar onveranderd: publieke API's, endpoints, CLI-gedrag, config-keys (`SF_*`), logging-semantiek en DB-schema/migraties blijven gelijk.
4. De `mvn test`-output bevat na de wijziging **minder build-/compiler-warnings en deprecations** dan ervoor; opgeloste warnings zijn niet weggemoffeld met onnodige `@Suppress`/`@SuppressWarnings`.
5. De detekt-kwaliteitsscore (`quality/run.sh` → `qualityrun/quality-score.json`) is **niet verslechterd** t.o.v. de uitgangssituatie; idealiter verbeterd. Suppressies zijn niet toegevoegd om bevindingen te verbergen (tellen mee in de score).
6. Bestaande conventies blijven gerespecteerd (expliciete imports, geen project-interne wildcards, `SF_`-prefix, geredigeerde secrets).
7. `docs/stories/worklog/SF-421-worklog.md` is bijgewerkt met wat is gerefactord en de onderbouwing waarom het gedrag-neutraal is.

## Aannames

- "Gedrag blijft exact hetzelfde" wordt geverifieerd via de bestaande (ongewijzigde) testsuite als waarheidsbron; bij twijfel of een wijziging gedrag verandert, wordt de wijziging niet doorgevoerd, of gaat de developer in error.
- De refactor is bewust begrensd tot een behapbare set van de meest renderende verbeteringen (bv. de detekt-hotspots uit `qualityrun/latest.md`), niet de hele codebase in één story.
- "Warnings en deprecations uit de Maven-output" betreft de output van `mvn test`/build over de drie Maven-modules; pre-existing warnings uit third-party plugins die niet veilig oplosbaar zijn zonder gedrags-/versiewijziging mogen blijven staan en worden in de worklog benoemd.
- De Flutter-frontend valt buiten de Maven-build en wordt standaard niet aangeraakt.
- Geen nieuwe dependencies, geen versie-upgrades van frameworks/plugins die gedrag kunnen wijzigen; dependency-opschoning beperkt zich tot ongebruikte/dubbele entries.

## Eindsamenvatting

Ik heb `.task.md`, het worklog (`docs/stories/worklog/SF-421-worklog.md`) met de developer-, reviewer- en tester-secties, en de factory-regels gelezen. Hieronder de eindsamenvatting voor de PO.

---

## SF-421 — Code-kwaliteit verbeteren (SOLID, Maven-output)

**Type:** nachtelijke kwaliteitsstory — gerichte, gedrag-neutrale refactor. Geen functionele wijzigingen.

### Wat is gebouwd
Een begrensde opschoning van de drie Maven-modules (`softwarefactory`, `agentworker`, `dashboard-backend`), gericht op de meest renderende detekt-hotspots en compiler-warnings:

- **Exceptions idiomatischer:** `throw IllegalStateException(msg)` → `error(msg)` (5×), `throw IllegalArgumentException` → `require(...)` (1×), en een generieke `RuntimeException` → `error(...)`. Zelfde exception-type en -boodschap, dus identiek gedrag.
- **Naamgeving:** privé-constanten `STATE_TODO/IN_PROGRESS/DONE` → camelCase (lane-waarden "Open"/"In Progress"/"Done" ongewijzigd); ongebruikte catch-binding hernoemd.
- **Dode code weg:** ongebruikte `logger`-velden en een ongebruikte `settings`-constructorinjectie in `CostMonitorPoller` (de `@Scheduled`-SpEL gebruikt de Spring-bean, niet het veld).
- **Bestandshernoemingen** (pure `git mv`, 100% similarity) zodat bestandsnaam de top-level klasse weerspiegelt; klassennamen ongewijzigd.
- **`MayBeConst`:** `val FAVICON` → `const val`.
- **Locale-fix:** `String.format("%.2f", usd)` → `String.format(Locale.US, ...)` in `NightlyDigest.formatCost`, zodat de "$1.23"-output deterministisch is ongeacht JVM-default-locale.
- **Maven-warnings:** 2 Kotlin "named arguments"-warnings in `FactoryDashboardServiceTest` opgelost door override-parameternamen aan de supertype-namen gelijk te trekken.

### Belangrijkste keuzes
- **Bewust begrensd:** grote tellers (MaxLineLength, MagicNumber, ReturnCount) zijn buiten scope gehouden — die vergen bredere herstructurering.
- **`AiRouting.claudeBucket(role)`** niet aangepast: `role` hoort bij de publieke routing-API; weghalen zou een API-wijziging zijn. Niet weggemoffeld met `@Suppress`.
- **Geen** `@Suppress`/`@SuppressWarnings` toegevoegd; geen nieuwe dependencies of versie-upgrades.

### Wat is getest
- **AC1:** `mvn test` op de geraakte pakketten → softwarefactory **130 tests** (0 failures/errors), agentworker **34 tests** (0/0), beide `BUILD SUCCESS`.
- **AC4:** `mvn clean test-compile` (3 modules) → **0 warnings** op de branch vs. **2** op `main`. Reductie bevestigd.
- **AC5:** detekt-score (`quality/run.sh`) **518 → 498** (−20 bevindingen, **0 suppressies** in beide). Verbeterd, niets verborgen.
- **AC2:** e2e-/integratietests onaangeroerd — alleen 1 nieuwe unit-test (`NightlyDigestTest`, borgt de Locale-fix) en 1 warning-fix in een mock.
- Reviewer (SF-422) en tester (SF-423): **akkoord / tested**, geen blockers of regressies.

### Bewust niet gedaan
- Geen functionele wijzigingen, nieuwe features of API-/contract-/DB-wijzigingen.
- Geen wijziging aan e2e-tests of bestaand testgedrag.
- Flutter `dashboard-frontend` niet aangeraakt (valt buiten de Maven-build).
- Volledige softwarefactory-fork niet end-to-end gedraaid wegens bekende pre-existing tail-VM-crash en Docker-afhankelijke e2e in deze Docker-loze omgeving; de geraakte code valt volledig binnen de wél gedraaide pakketten.

### Aandachtspunt voor de PO
De volledige test-fork en de Docker-e2e draaien alleen in CI. De bevestiging dat álles groen is, hangt af van de CI-run op deze branch vóór merge (SF-426).

---
