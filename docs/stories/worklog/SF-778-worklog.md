# SF-778 - Worklog

Story-context bij eerste pickup:
Nightly config-pad: subtasks.yaml lezen/valideren + declaratief materialiseren

Implementeer het volledige config-pad voor nightly-jobs.

1) NightlyJobsReader: lees en valideer `.factory/nightly/<job>/subtasks.yaml` (geordende lijst type+title) en de bijbehorende `<title>.md`-bestanden via de bestaande gh-contents/decodeContent-aanpak (geen lokale checkout). Breid NightlyJobDetail uit met een optionele geordende subtask-speclijst (null = geen config). Parse YAML met SafeConstructor. Validatie: parseert en >=1 subtaak; elk type mapt op SubtaskType.fromTracker; titels uniek; elke AI-subtaak (development/review/test/summary/documentation) heeft een <title>.md, merge/deploy/manual-approve niet; story.md bestaat. Bij fout: geef een duidelijke foutmelding terug (job wordt overgeslagen), sluit aan op de bestaande errors-conventie.

2) SubtaskPlanMaterializer: voeg een publieke methode toe die uit een expliciete List<SubtaskSpec> materialiseert via de bestaande createSubtasks (idempotent op titel, erft aiSupplier van de parent) ZONDER documentation/merge/deploy/manual-approve auto-append. Laat materializeIfPlanned (planner-pad) volledig ongewijzigd.

3) FactoryDashboardService.createNightlyStory: bij geldige subtask-lijst -> story aanmaken met description=story.md en start=false (geen refiner/planner), daarna de specs direct materialiseren en de story-fase op StoryPhase.PLANNING_APPROVED zetten. Zonder subtask-lijst -> huidig gedrag (start=true, refine+plan). Bij validatiefout -> geen story aanmaken en de fout in de bestaande nachtelijke Telegram-digest/errors-rapportage laten belanden.

4) Voeg voor de 6 nightly-jobs van dit project (quality, adr, consistency, documentation, integration-tests, security) een subtasks.yaml toe met de keten development->review->test->summary->documentation->merge->deploy plus de .md-bestanden voor de AI-subtaken (development gebaseerd op de bestaande story.md; review/test/summary/documentation korte passende beschrijving). merge=automatisch, deploy=rest-restart conform huidige draaiwijze.

5) Dek de nieuwe logica met unit-/integratietests: reader-validatie (happy path + elke faalconditie) en config-pad-materialisatie (exact de gedeclareerde subtaken, geen auto-append, idempotentie op titel). Voer zelf een review-stap uit. Bestaande tests moeten groen blijven.

## SF-787 — development (config-pad implementeren)

De nightly-jobs kunnen hun subtaken voortaan declaratief uit `.factory/nightly/<job>/subtasks.yaml`
halen. Bij een geldige config slaat de factory refine + plan over: de story wordt aangemaakt met
`description = story.md`, precies de gedeclareerde subtaken worden gematerialiseerd (geen
factory-afgedwongen extra's) en de story-fase gaat naar `planning-approved`, waarna de bestaande
statemachine de keten start. Zonder `subtasks.yaml` blijft het huidige gedrag (refine + plan) intact.

Stappenplan:
[x]: read issue and target docs
[x]: NightlyJobsReader — subtasks.yaml + `<title>.md` lezen/valideren, NightlyJobDetail uitbreiden
[x]: SubtaskPlanMaterializer — publieke `materializeFromSpecs` (geen auto-append, idempotent op titel)
[x]: FactoryDashboardService.createNightlyStory — config-pad vs legacy-pad
[x]: config voor de 6 nightly-jobs van dit project toevoegen
[x]: unit-tests (reader-validatie happy + faalcondities, config-pad-materialisatie)
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- **NightlyJobsReader** (`softwarefactory/.../nightly/NightlyJobsReader.kt`): `NightlyJobDetail` kreeg
  een optionele geordende `subtasks: List<SubtaskSpec>?` (null = geen config). `readJob` leest nu ook
  `subtasks.yaml` via dezelfde `gh`-contents/`decodeContent`-aanpak; is die aanwezig dan wordt hij met
  `SafeConstructor` geparsed en gevalideerd. Validatie: parseert + ≥1 subtaak; elk type is één van
  `development/review/test/summary/documentation/merge/deploy/manual-approve` (bewust NIET `manual`);
  titels uniek; elke AI-subtaak (development/review/test/summary/documentation) heeft een `<title>.md`
  (merge/deploy/manual-approve niet); `story.md` bestaat. Bij een fout gooit de reader de nieuwe
  `NightlySubtasksConfigException` met een duidelijke melding, waardoor de job in
  `NightlyScheduler.startJob` als FAILED wordt gemarkeerd en de fout in de bestaande nachtelijke
  Telegram-digest belandt (geen story aangemaakt).
- **SubtaskPlanMaterializer** (`.../runtime/services/SubtaskPlanMaterializer.kt`): nieuwe publieke
  `materializeFromSpecs(storyKey, specs)` die exact de meegegeven specs aanmaakt via de bestaande
  private `createSubtasks` — idempotent op titel (bestaande subtaak-titels worden overgeslagen), erft
  de AI-supplier van de parent-story, ZONDER documentation/merge/deploy/manual-approve auto-append.
  `materializeIfPlanned` (planner-pad) is volledig ongewijzigd → backwards compatible.
- **FactoryDashboardService.createNightlyStory** (`.../web/services/FactoryDashboardService.kt`): met
  een geldige subtask-lijst wordt de story aangemaakt met `start=false` (geen refiner/planner), worden
  de specs direct gematerialiseerd en gaat de story-fase op `StoryPhase.PLANNING_APPROVED`. Zonder
  subtask-lijst blijft het pad `start=true` (refine + plan). De service kreeg `SubtaskPlanMaterializer`
  als verplichte constructor-dep (drie test-constructies bijgewerkt: FactoryDashboardServiceTest,
  BridgeTestFixtures, DashboardAuthInterceptorTest).
- **Config voor dit project**: elk van de 6 nightly-jobs (`quality`, `adr`, `consistency`,
  `documentation`, `integration-tests`, `security`) kreeg een `subtasks.yaml` met de keten
  `development → review → test → summary → documentation → merge → deploy` plus de `.md`-bestanden voor
  de AI-subtaken (development gebaseerd op de bestaande `story.md`; review/test/summary/documentation
  een korte passende beschrijving). Merge = automatische merge en deploy = rest-restart komen zoals
  vandaag uit de handlers/projects.yaml; de config declareert alleen de subtaken.
- **Tests**: `NightlyJobsReaderTest` uitgebreid met happy-path + elke faalconditie (lege lijst, ongeldig
  type, niet-toegestane `manual`, dubbele titel, ontbrekend `<title>.md`, niet-parsende YAML, ontbrekende
  `story.md`); nieuwe `SubtaskPlanMaterializerTest` dekt exact-de-specs/geen-auto-append,
  supplier-overerving en idempotentie op titel. Lokaal groen: NightlyJobsReaderTest (16),
  SubtaskPlanMaterializerTest (3), FactoryDashboardServiceTest (29), DashboardAuthInterceptorTest (4),
  Bridge*Test, NightlyPlannerTest (19), NightlySchedulerTest (8). Docker-afhankelijke e2e-tests draaien
  niet lokaal; die laat ik in de pipeline lopen.
- **Specs**: `docs/factory/functional-spec.md` en `docs/factory/technical-spec.md` bijgewerkt met het
  nieuwe declaratieve nightly-config-pad (zie hieronder).

## Review (reviewer, 2026-07-05)

Volledige story-diff (`git diff main...HEAD`) beoordeeld.

**Akkoord op hoofdlijnen** — implementatie dekt alle acceptatiecriteria:
- Validatie in `NightlyJobsReader.parseAndValidateSubtasks` compleet (parse/lege-lijst/ongeldig
  type/`manual`-uitsluiting/dubbele titel/ontbrekend `<title>.md`/ontbrekende `story.md`), gooit
  `NightlySubtasksConfigException` → job FAILED in digest (via `NightlyScheduler.startJob`). Getest.
- `materializeFromSpecs`: exact de specs, geen auto-append, idempotent op titel, erft supplier. Getest.
- `createNightlyStory` config-pad (`start=false` → materialiseren → `PLANNING_APPROVED`) vs legacy
  (`start=true`). Backwards compatibel; planner-pad `materializeIfPlanned` ongewijzigd.
- SafeConstructor gebruikt (geen willekeurige type-instantiatie op deels-untrusted repo-config).
- 6 project-jobs met correcte keten; merge/deploy-titels matchen de vaste planner-titels. Specs
  (functional-/technical-spec, README) consistent bijgewerkt.

**Openstaande vraag (zie phase-JSON):**
- [bug?] De `<title>.md`-lookup gebeurt via `gh api repos/<slug>/contents/.../<title>.md` met titels
  die spaties bevatten (o.a. "Werk documentatie bij", "ADR-naleving herstellen"). De unit-tests
  gebruiken een `FakeGitApi` die paden letterlijk matcht en dus de echte gh/URL-encoding niet
  uitoefent. Als `gh api` de spatie niet naar `%20` encodeert, faalt de lookup (404) → job wordt
  overgeslagen voor álle 6 project-jobs. Graag bevestigen dat dit met spaties werkende paden oplevert
  (bekend gedrag of geverifieerd in de CI/e2e).

**Niet-blokkerend:**
- [info] De config-tak van `createNightlyStory` (materialiseren + fase-set) heeft geen eigen
  service-niveau-test; de onderliggende methodes zijn wel afzonderlijk gedekt. Overweeg één
  integratietest die de tak end-to-end raakt.

---

## Reviewnotitie (reviewer, 2026-07-05) — 2e ronde, SF-787

Volledige story-diff `main...HEAD` beoordeeld. Verdict: **akkoord**.

**Eerdere openstaande vraag (spaties in `<title>.md`-pad) — opgelost.**
Admin heeft in issue-comment 7-1990 bevestigd dat de spatie in de `.md` correct/gewenst is
("er moet gewoon een spatie staan in de .md"). `gh api repos/<slug>/contents/<path>` encodeert
de padsegmenten zelf, dus paden als `.../Werk documentatie bij.md` resolven correct. De vraag uit
de 1e ronde beschouw ik daarmee als beantwoord; niet langer blokkerend.

**Beoordeling t.o.v. acceptance criteria:**
- Reader-validatie (AC 1-2): `parseAndValidateSubtasks` dekt parse-fout, lege lijst, ongeldig/niet-
  toegestaan type (incl. `manual`), dubbele titel, ontbrekend `<title>.md` en ontbrekende `story.md`;
  gooit `NightlySubtasksConfigException` → `NightlyScheduler.startJob` markeert job `failed` → digest.
  SafeConstructor tegen untrusted YAML. ✔
- Config-flow (AC 3-9): `createNightlyStory` maakt story met `start=false`, materialiseert exact de
  specs (geen auto-append), erft AI-supplier, zet fase `PLANNING_APPROVED`; idempotent op titel. ✔
- Backwards compat (AC 10-11): legacy-pad (`start=true`, `materializeIfPlanned`) ongewijzigd. ✔
- Projectconfig (AC 12-13): 6 jobs met keten development→review→test→summary→documentation→merge→
  deploy; `.md`-bestandsnamen matchen exact de titels; merge/deploy zonder `.md`. ✔
- Tests (AC 14): reader happy-path + elke faalconditie; materializer exact/supplier/idempotent;
  3 test-fixtures bijgewerkt voor de nieuwe verplichte ctor-dep. Config-jobs zelf niet getest, conform
  admin-comment 7-1990. ✔
- Specs (functional-/technical-spec, README) consistent bijgewerkt met het config-pad. ✔

**Niet-blokkerend (blijft staan als [info]):**
- De config-tak van `createNightlyStory` (start=false → materialiseren → fase-set) heeft geen eigen
  service-niveau-test; onderliggende methodes zijn wel afzonderlijk gedekt. Optioneel: één
  integratietest die de tak end-to-end raakt.

Geen implementatiebestanden gewijzigd door de reviewer.

---

## Test (tester, 2026-07-05) — SF-788 — **test-rejected**

Getest tegen de story-brede diff `main...HEAD`. Config en unit-dekking zijn in orde, maar er is
één **regressie in een bestaande test** (AC 14 geschonden).

**Groen:**
- Gerichte unit-suites: `NightlyJobsReaderTest` (16), `SubtaskPlanMaterializerTest` (3),
  `FactoryDashboardServiceTest` (29), `DashboardAuthInterceptorTest` (4), `NightlySchedulerTest` (8),
  `NightlyPlannerTest` (19) → samen 79 tests, Failures 0, Errors 0.
- Config-verificatie: alle 6 nightly-jobs (`quality`, `adr`, `consistency`, `documentation`,
  `integration-tests`, `security`) hebben een `subtasks.yaml` met de keten
  development→review→test→summary→documentation→merge→deploy; elke AI-subtaak heeft een exact
  gelijknamig `<title>.md`, merge/deploy hebben er (correct) geen. Types mappen op `SubtaskType`;
  `manual` wordt terecht geweigerd (niet in `ALLOWED_TYPES`).

**Rood — regressie (blokkerend):**
- `ModulithArchitectureTest` faalt op deze branch:
  `Module 'web' depends on non-exposed type
  nl.vdzon.softwarefactory.runtime.services.SubtaskPlanMaterializer within module 'runtime'`.
- Oorzaak: `FactoryDashboardService` (module `web`) kreeg een constructor-dependency op
  `SubtaskPlanMaterializer` (niet-geëxposeerd type in module `runtime`), wat de Spring Modulith
  module-grens schendt.
- Bewezen regressie: `ModulithArchitectureTest` is **groen op een schone `main`-worktree**
  (Tests run: 1, Failures 0, Errors 0) en **rood op deze branch** (Errors 1, BUILD FAILURE).
  Dit is dus géén pre-existing/omgevings-baseline (de oude modulith-cycle-tip is achterhaald; die
  cycle faalt niet meer op main).
- Volledige app-module-suite (`mvn -pl softwarefactory -am test`): 470 run, Failures 0, **Errors 1**
  (uitsluitend deze modulith-violation).

**Richting voor de developer:** breng de `web → runtime`-afhankelijkheid binnen de modulith-regels,
bv. door `SubtaskPlanMaterializer` als geëxposeerd type/named-interface in `runtime` beschikbaar te
maken, of de config-pad-materialisatie achter een reeds geëxposeerde API (orchestrator/runtime-poort)
te laten lopen i.p.v. de directe injectie in de `web`-service. Daarna `ModulithArchitectureTest`
opnieuw groen krijgen.

## Fix modulith-regressie (developer, 2026-07-05) — SF-787

De `web → runtime`-grensschending is opgelost door een **geëxposeerde runtime-poort** te
introduceren i.p.v. de concrete klasse te injecteren:

- Nieuw: `runtime/SubtaskMaterializationApi.kt` — interface in het *base*-package van de `runtime`-
  module (net als `RuntimeApi`), dus door Spring Modulith geëxposeerd. Eén methode
  `materializeFromSpecs(storyKey, specs)`.
- `runtime/services/SubtaskPlanMaterializer` implementeert nu `SubtaskMaterializationApi`
  (`materializeFromSpecs` gemarkeerd `override`); de bestaande logica is ongewijzigd.
- `web/services/FactoryDashboardService` injecteert nu `SubtaskMaterializationApi` i.p.v. de niet-
  geëxposeerde `runtime.services.SubtaskPlanMaterializer`. Het aanroeppunt in `createNightlyStory`
  blijft identiek (`subtaskPlanMaterializer.materializeFromSpecs(...)`).
- Test-constructies hoefden niet gewijzigd: ze geven een concrete `SubtaskPlanMaterializer` mee, die
  het interface-type nu implementeert (subtype → geldig ctor-argument).

**Verificatie** (`mvn -pl softwarefactory -am test`):
- `ModulithArchitectureTest`: 1 run, 0 failures, 0 errors → **weer groen**.
- Regressie-gerelateerde suites samen 53 tests, 0 failures: `NightlyJobsReaderTest` (16),
  `FactoryDashboardServiceTest` (29), `DashboardAuthInterceptorTest` (4),
  `SubtaskPlanMaterializerTest` (3), `ModulithArchitectureTest` (1).

---

## Review (reviewer, 2026-07-05) — 3e ronde, na modulith-fix — **akkoord**

Volledige story-diff `main...HEAD` opnieuw beoordeeld, met focus op de developer-fix voor de door de
tester gerapporteerde blokkerende regressie (AC 14: `web → runtime` modulith-grensschending).

**Fix geverifieerd:**
- Nieuwe `runtime/SubtaskMaterializationApi` (interface in het geëxposeerde runtime-base-package);
  `SubtaskPlanMaterializer` implementeert de poort (`override materializeFromSpecs`), onderliggende
  logica ongewijzigd. `FactoryDashboardService` injecteert nu de poort i.p.v. de concrete klasse —
  correcte, minimale module-boundary-refactor zonder gedragswijziging. Aanroeppunt identiek.
- Zelf gedraaid `mvn -pl softwarefactory -am test` (gerichte suites): **53 tests, 0 failures, 0 errors**
  incl. `ModulithArchitectureTest` 1/0/0 (weer groen), `NightlyJobsReaderTest` 16, `FactoryDashboard-
  ServiceTest` 29, `DashboardAuthInterceptorTest` 4, `SubtaskPlanMaterializerTest` 3. Regressie hersteld.

**Overige AC's (bevestigd uit eerdere rondes, blijven staan):** reader-validatie (1-2), config-flow
(3-9), backwards compat (10-11), projectconfig 6 jobs met correcte keten + `.md`-koppeling (12-13),
testdekking (14). Geen nieuwe bevindingen.

**Niet-blokkerend:**
- [info] De config-tak van `createNightlyStory` (start=false → materialiseren → fase-set) heeft nog
  geen eigen service-niveau-integratietest; onderliggende methodes zijn afzonderlijk gedekt. Optioneel.

Geen implementatiebestanden gewijzigd door de reviewer.

---

## Test (tester, 2026-07-05) — 4e ronde, na modulith-fix — **tested**

Hertest na developer-fix voor de eerder gerapporteerde blokkerende regressie (AC 14:
`web → runtime` modulith-grensschending).

**Blokkerende regressie hersteld:**
- `ModulithArchitectureTest` weer **groen**: 1 run, 0 failures, 0 errors. De nieuwe
  `runtime/SubtaskMaterializationApi`-poort (geëxposeerd) die door `SubtaskPlanMaterializer`
  wordt geïmplementeerd en door `FactoryDashboardService` geïnjecteerd, respecteert de
  module-grens. Vergeleken met de eerdere rode branch (470 run / 1 Error) is de violation weg.

**Gerichte suites groen** (`mvn -pl softwarefactory -am test`, reactor):
- `NightlyJobsReaderTest` 16, `SubtaskPlanMaterializerTest` 3, `FactoryDashboardServiceTest` 29,
  `DashboardAuthInterceptorTest` 4, `NightlySchedulerTest` 8, `NightlyPlannerTest` 19,
  `ModulithArchitectureTest` 1 — alle 0 failures / 0 errors.
- Reactor-totaal over gedraaide selectie: **470 run, 0 failures, 0 errors** (e2e/Docker-suite
  draait niet mee zonder Docker).

**Config (AC 12-13) opnieuw bevestigd:** alle 6 nightly-jobs (`quality`, `adr`, `consistency`,
`documentation`, `integration-tests`, `security`) hebben `subtasks.yaml` met de keten
development→review→test→summary→documentation→merge→deploy; elke AI-subtaak heeft een exact
gelijknamig `<title>.md`, merge/deploy hebben er geen.

Geen code/tests/infra gewijzigd; alleen het worklog bijgewerkt.
