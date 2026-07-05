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
