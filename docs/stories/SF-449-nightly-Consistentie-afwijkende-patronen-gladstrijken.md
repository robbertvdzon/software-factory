# SF-449 - nightly: Consistentie: afwijkende patronen gladstrijken

## Story

nightly: Consistentie: afwijkende patronen gladstrijken

<!-- refined-by-factory -->

## Scope

Onderhoudsstory (nightly): spoor inconsistente patronen op in de Kotlin-code van de factory en breng ze in lijn met de heersende norm in de codebase, zónder functioneel gedrag te wijzigen.

In scope:
- Afwijkende naamgeving (klassen, functies, variabelen, packages) t.o.v. de overheersende stijl.
- Afwijkende structuur/indeling van vergelijkbare componenten (bijv. handlers, services, repositories).
- Afwijkende error-handling, logging en API/HTTP-conventies waar elders een duidelijke norm geldt.
- Plekken waar hetzelfde probleem op meerdere manieren is opgelost: gelijktrekken naar één patroon.
- Naleving van expliciete repo-conventies uit `docs/factory/development.md` (o.a. expliciete imports, géén project-interne wildcard-imports, `SF_`-prefix voor nieuwe config, secrets nooit ongeredigeerd gelogd).

Buiten scope:
- Nieuwe features of gedragswijzigingen.
- Wijzigingen aan integratietests/e2e-tests (`softwarefactory/src/test/.../e2e`).
- Wijzigingen aan de Flutter-frontend tenzij puur cosmetisch-consistent en zonder gedragsrisico.
- Grootschalige herarchitectuur; houd de wijziging klein en review-baar.

## Acceptance criteria

- Het functionele gedrag is exact gelijk gebleven; uitsluitend vorm/structuur/consistentie is aangepast.
- Alle bestaande tests slagen (`mvn -f softwarefactory/pom.xml test`, en waar geraakt `agentworker`/`dashboard-backend`).
- Geen enkele integratietest/e2e-test is gewijzigd. Als groen krijgen vereist dat een integratietest wordt aangepast, is dat per definitie een gedragswijziging → ga in error i.p.v. de test te wijzigen.
- Aangepaste plekken volgen aantoonbaar de bestaande norm elders in de codebase (de gekozen "norm" is het patroon dat in de meerderheid van vergelijkbare plekken al gebruikt wordt).
- Repo-conventies uit `development.md` worden niet geschonden door de wijziging (geen nieuwe wildcard-imports, geen ongeredigeerde secrets in logging, `SF_`-prefix gehandhaafd).
- De detekt-/kwaliteitsmeetlat (`quality/run.sh`) is niet verslechterd: het aantal findings + suppressies daalt of blijft gelijk; consistentie wordt niet bereikt door findings weg te `@Suppress`-en.
- Bij twijfel of een wijziging gedrag verandert, wordt die wijziging niet doorgevoerd (of de story gaat in error met concrete reden).
- `docs/stories/worklog/SF-449-worklog.md` is bijgewerkt met welke inconsistenties zijn gevonden en hoe ze zijn gelijkgetrokken (of, indien niets veilig te doen was, een onderbouwde no-op-notitie).

## Aannames

- "Norm" = het patroon dat in de meerderheid van vergelijkbare gevallen al gebruikt wordt; bestaat er geen duidelijke meerderheidsnorm, dan wordt die plek met rust gelaten.
- Een lege of vrijwel-lege diff (geen veilige consistentie-winst gevonden) is een acceptabele uitkomst; de story faalt daar niet op, mits de worklog dit motiveert.
- De wijziging is bewust klein en gericht gehouden zodat een reviewer per hunk kan vaststellen dat gedrag gelijk blijft.
- Primaire focus ligt op de `softwarefactory`-module (orchestrator + ingebouwd dashboard); andere modules alleen waar een evidente, risicoloze inconsistentie zit.
- De drie Maven-modules worden onafhankelijk gebouwd; alleen modules die daadwerkelijk geraakt zijn hoeven her-getest te worden, naast de standaard `softwarefactory`-testrun.

## Eindsamenvatting

Ik heb `.task.md`, het worklog en de git-historie gelezen. Hier de eindsamenvatting voor de PO.

---

## Eindsamenvatting SF-449 — Consistentie: afwijkende patronen gladstrijken (nightly)

**Doel:** inconsistente patronen in de Kotlin-code van de factory opsporen en in lijn brengen met de heersende norm, strikt zónder functioneel gedrag te wijzigen.

### Wat is gebouwd
Een bewust kleine, gedrag-neutrale consistentie-refactor. Drie ruwe `throw IllegalStateException("...")`-aanroepen zijn gelijkgetrokken naar de codebase-norm `error("...")`:

- `StoryWorkspaceService.kt` — faal-vangnet bij achtergebleven merge-conflictmarkers.
- `DashboardController.kt` (2×) — IntelliJ-open timeout en IntelliJ-open faalt.

`error(msg)` gooit per Kotlin-stdlib exact een `IllegalStateException(msg)`, dus exceptietype én melding blijven byte-identiek: puur vorm.

### Gemaakte keuzes (en wat bewust níét is gedaan)
- **`?: throw IllegalArgumentException(...)` niet omgezet** — `error()` zou IAE→ISE veranderen, een semantische wijziging. Bij twijfel niet aangeraakt.
- **Domeinspecifieke excepties** (`YouTrackApiException`, `GitHubClientException`, `MissingTrackerFieldException`, `ResponseStatusException`) bewust ongemoeid gelaten.
- **String-templates vs `+`-concatenatie** met rust gelaten: resterende `+`-gevallen zijn idiomatisch (regex/multiline), omzetten geeft ruis zonder detekt-winst.
- **Geen wijzigingen** nodig aan wildcard-imports, logger-declaraties, naamgeving of HTTP/`ResponseEntity`-conventies — die volgen de norm al.
- Geen e2e-/integratietests gewijzigd; geen `@Suppress` gebruikt om de detekt-meetlat te "halen".

### Wat is getest (door developer én onafhankelijk door tester)
- `StoryWorkspaceServiceTest` → 4 tests, 0 failures (assert nog steeds `IllegalStateException` → bevestigt neutraliteit).
- `dashboard-backend` volledige suite → 13 tests, 0 failures, BUILD SUCCESS.
- `softwarefactory` volledige suite → 416 tests, **0 failures**, 18 errors. Alle 18 zijn pre-existing omgevingsfouten (ontbrekende Docker/Testcontainers in deze omgeving) en raken de gewijzigde bestanden niet → geen regressie.
- Detekt-meetlat kan door deze wijziging alleen gelijk blijven of verbeteren.

### Resultaat
Acceptance criteria gehaald: gedrag exact gelijk, diff klein en per-hunk reviewbaar (2 `.kt`-bestanden + worklog), bestaande tests groen, geen e2e-test geraakt, conventies uit `development.md` niet geschonden. Klaar voor documentatie (SF-453) en merge (SF-454).
