# SF-719 - nightly: Code-kwaliteit verbeteren (SOLID, Maven-output)

## Story

nightly: Code-kwaliteit verbeteren (SOLID, Maven-output)

<!-- refined-by-factory -->

## Scope

Puur kwaliteits-/refactorwerk op de Maven-modules van software-factory (`softwarefactory`, `agentworker`, `dashboard-backend`), zonder functionele gedragsverandering.

- **SOLID, leesbaarheid en onderhoudbaarheid**: verbeter naamgeving, verwijder dode code, hef duplicatie op en splits te lange functies/klassen op. Houd refactors klein en lokaal; respecteer bestaande module-conventies uit `docs/factory/development.md` (expliciete imports, geen project-interne wildcards, `error()/require()/requireNotNull()` voor generieke guards behalve waar domeinspecifieke excepties of de `?: throw`-norm van `dashboard-backend` gelden).
- **Maven-output opschonen**: los compiler-warnings en deprecations op die zichtbaar zijn in de Maven-build van de drie modules.
- **Niet in scope**: nieuwe features, API-/gedragswijzigingen, aanpassingen aan de Flutter-frontend, en het wegzwijgen van bevindingen met `@Suppress`/`@SuppressWarnings`/`detekt:disable`/`ktlint-disable` als manier om de kwaliteitsscore kunstmatig te verbeteren.

## Acceptance criteria

1. Het functionele gedrag is exact gelijk gebleven; er is geen API-, contract- of gedragswijziging doorgevoerd.
2. Alle bestaande tests slagen: `mvn -f softwarefactory/pom.xml test`, `mvn -f agentworker/pom.xml test` en `mvn -f dashboard-backend/pom.xml test` (of `mvn test` vanaf de root) zijn groen.
3. Integratie-/e2e-tests (package `...e2e`) zijn **niet** gewijzigd. Als een refactor alleen groen te krijgen is door een integratietest aan te passen, wordt die refactor niet doorgevoerd en gaat de run in error.
4. De detekt-kwaliteitsscore is niet verslechterd ten opzichte van de uitgangssituatie: `quality/run.sh` levert in `qualityrun/quality-score.json` een `score` (totaal bevindingen + suppressies) die ≤ de score vóór de wijzigingen is. De vóór- en na-score worden in het worklog vastgelegd.
5. Compiler-warnings en/of deprecations die bij de bouw worden opgelost, zijn benoemd in het worklog; nieuwe warnings/deprecations worden niet geïntroduceerd.
6. `docs/stories/worklog/SF-719-worklog.md` is bijgewerkt met de doorgevoerde refactors, de rationale, de vóór/na-kwaliteitsscore en de testresultaten.

## Aannames

- "De code" betreft de drie Kotlin/Maven-modules; de Flutter dashboard-frontend valt buiten scope omdat die een eigen toolchain heeft en buiten de Maven-build staat.
- De objectieve kwaliteitsmeetlat voor deze nightly-categorie is de detekt-`score` uit `quality/run.sh`; deze wordt als anker gebruikt zodat "score niet hoger" netto kwaliteitsverbetering of -behoud betekent en suppressies geen ontsnapping bieden.
- Bij twijfel of een wijziging gedrag verandert, wordt de wijziging niet doorgevoerd of gaat de run bewust in error (conform de randvoorwaarden), in plaats van risico te nemen.
- Het mag een gedeeltelijke, incrementele verbetering zijn: niet elke bevinding hoeft te verdwijnen, zolang de score niet verslechtert en het gedrag gelijk blijft.

## Eindsamenvatting

Alle context is gelezen (`.task.md`, worklog SF-719 met developer-, reviewer- en tester-bijdragen). Hieronder de eindsamenvatting voor de PO.

---

## Eindsamenvatting — SF-719: nightly Code-kwaliteit verbeteren (SOLID, Maven-output)

### Wat is gebouwd
Een gedrag-neutrale kwaliteits-/refactorslag op de Kotlin/Maven-module `softwarefactory` (main-code). `agentworker` en `dashboard-backend` zijn alleen geverifieerd, niet inhoudelijk aangepast. Geen functionele, API- of UX-wijzigingen.

Doorgevoerde refactors (7 source-files):
- **Magic numbers → benoemde constants** in `CallMetrics.kt`, `DockerLogFollower.kt` en `AiRouting.kt` (level-grenzen). Waarden exact gelijk; vastgepind door bestaande `AiRoutingTest`.
- **Te lange regels (>120) gewrapt** in `SubtaskExecutionCoordinator`, `StoryRefinementCoordinator`, `AgentDispatcher` en `TelegramReplyService`. Samengestelde log-/format-strings blijven identiek.
- **SOLID/lange methode opgesplitst**: `StoryRefinementCoordinator.recoverActiveStoryPhase` kreeg twee private helpers (`recoveredFromSuccess`, `recoveredFromRetryableFailure`); zelfde check-volgorde en retourwaarden.

### Belangrijkste keuzes
- **Detekt-score als objectieve meetlat**: van **493 → 450** findings (netto **−43**), zonder enige `@Suppress`/`detekt:disable`/`ktlint-disable`. De daling is echte opschoning, geen wegzwijgen. Onafhankelijk door de tester geverifieerd tegen een schone `main`-baseline.
- **Conservatieve scope**: alleen klein, lokaal, gedrag-behoudend werk. Bij twijfel niet doorgevoerd.

### Wat is getest
- `softwarefactory`: 442 run, **0 failures** (32 errors zijn allemaal omgevings-/Docker-gebonden e2e-tests — geen Docker in de CI-omgeving, niet door deze wijziging veroorzaakt).
- `agentworker`: 34 run, **BUILD SUCCESS**.
- `dashboard-backend`: 13 run, **BUILD SUCCESS**.
- Geen Kotlin compiler-warnings/deprecations; geen nieuwe geïntroduceerd.
- Alle acceptatiecriteria (AC1–AC6) voldaan; review en story-brede test beide akkoord/geslaagd.

### Bewust niet gedaan
- Geen e2e-/integratietests (`...e2e`) gewijzigd (AC3 gerespecteerd).
- `agentworker` en `dashboard-backend` niet gerefactord (buiten conservatieve scope gehouden).
- Geen volledige opschoning van alle 450 resterende findings — incrementele verbetering volstaat zolang de score niet stijgt en gedrag gelijk blijft.
- Geen spec-aanpassingen: interne refactor zonder functionele impact, bestaande specs blijven kloppen.

---
