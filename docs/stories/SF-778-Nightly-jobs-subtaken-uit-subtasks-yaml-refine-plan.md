# SF-778 - Nightly-jobs: subtaken uit subtasks.yaml, refine+plan overslaan

## Story

Nightly-jobs: subtaken uit subtasks.yaml, refine+plan overslaan

<!-- refined-by-factory -->

## Scope

Nightly-jobs kunnen hun subtaken voortaan declaratief uit config halen, zodat de AI-refine- en plan-stap worden overgeslagen voor deze statische, elke-nacht-identieke opdrachten.

In scope:
1. **Nieuwe config per nightly-job** in `.factory/nightly/<job>/`, naast de bestaande `job.yaml` en `story.md`:
   - `subtasks.yaml`: een GEORDENDE lijst subtaken (`type` + `title`); de volgorde in het bestand is de uitvoervolgorde.
   - Per AI-subtaak een `<title>.md` met de subtaak-beschrijving. Bestandsnaam = exact de titel + `.md` (titel `Werk documentatie bij` → `Werk documentatie bij.md`).
   - Geldige `type`-waarden: `development, review, test, summary, documentation, merge, deploy, manual-approve`.
   - `subtasks.yaml` is VOLLEDIG LEIDEND: precies die subtaken worden aangemaakt — niet meer, niet minder. De factory voegt géén documentation/merge/deploy/manual-approve automatisch toe.
2. **Lezen + valideren**: `NightlyJobsReader` leest `subtasks.yaml` en de bijbehorende `.md`-bestanden via de bestaande GitHub-contents-aanpak (dezelfde `gh api`/TTL-cache als `job.yaml`/`story.md`).
3. **Gewijzigde flow in `createNightlyStory`**:
   - Job MÉT geldige `subtasks.yaml` → story aanmaken met `description = story.md`, ZONDER refiner/planner; de gedeclareerde subtaken direct materialiseren en de story-fase op `planning-approved` zetten. Géén factory-afgedwongen extra subtaken.
   - Job ZONDER `subtasks.yaml` → huidig gedrag (refine + plan) blijft ongewijzigd (backwards compatible).
4. **Herbruik in `SubtaskPlanMaterializer`**: de spec→createSubtasks-logica wordt (her)gebruikt door zowel het planner-pad (huidig gedrag, MET factory-afgedwongen documentation/merge/deploy/manual-approve) als het nieuwe config-pad (GEEN auto-append; config is leidend). Subtaken erven de AI-supplier van de story (nodig voor de poller); idempotentie keyt op subtaak-titel.
5. **Config voor dit project zelf**: voeg voor de 6 bestaande nightly-jobs van software-factory (`quality`, `adr`, `consistency`, `documentation`, `integration-tests`, `security`) `subtasks.yaml` + de `.md`-bestanden toe. Standaardketen per job: `development → review → test → summary → documentation → merge → deploy` (merge automatisch, deploy rest-restart, zoals dit project nu draait). Baseer de `development`-`.md` op de bestaande `story.md`; review/test/summary/documentation krijgen een korte passende beschrijving.

Buiten scope: wijzigingen aan het planner-/refiner-pad-gedrag voor niet-nightly stories; nieuwe subtaak-types of fases; UI-wijzigingen.

## Acceptance criteria

**Validatie (vóór story-aanmaak):**
1. Bevat een nightly-job een `subtasks.yaml`, dan valideert het nightly-proces vóór het aanmaken van de story:
   - `subtasks.yaml` parseert en bevat minstens 1 subtaak;
   - elk `type` is een geldige waarde uit de toegestane set;
   - subtaak-titels zijn uniek;
   - elke AI-subtaak (`development`/`review`/`test`/`summary`/`documentation`) heeft een bijbehorend `<title>.md`; `merge`/`deploy`/`manual-approve` hoeven er geen;
   - `story.md` bestaat.
2. Bij een validatiefout wordt de job overgeslagen (er wordt GEEN story aangemaakt) en de fout wordt gerapporteerd in de nachtelijke digest (Telegram, 's ochtends), zodat de misconfiguratie zichtbaar is.

**Flow met geldige `subtasks.yaml`:**
3. De story wordt aangemaakt met `description = story.md` (ongewijzigd t.o.v. huidige `story.md`-gebruik).
4. Er wordt géén refiner- en géén planner-run gestart.
5. Exact de in `subtasks.yaml` gedeclareerde subtaken worden gematerialiseerd, in de bestandsvolgorde als uitvoervolgorde; er worden geen extra factory-subtaken (documentation/merge/deploy/manual-approve) automatisch toegevoegd.
6. Elke gematerialiseerde subtaak krijgt zijn `type`, `title` en — voor AI-subtaken — de beschrijving uit het bijbehorende `<title>.md`.
7. Alle subtaken erven de AI-supplier van de story, zodat de poller ze oppikt.
8. De story-fase wordt op `planning-approved` gezet, waarna de keten via het bestaande subtaak-poller-mechanisme wordt uitgevoerd (eerste subtaak start).
9. Materialisatie is idempotent op subtaak-titel: bij herhaalde uitvoering worden geen dubbele subtaken aangemaakt.

**Backwards compatibiliteit:**
10. Een nightly-job ZONDER `subtasks.yaml` behoudt exact het huidige gedrag (refine + plan, met factory-afgedwongen documentation/merge/deploy/manual-approve).
11. Het bestaande planner-pad voor niet-nightly stories blijft ongewijzigd (dezelfde subtaak-keten en volgorde als vandaag).

**Config van dit project:**
12. Elk van de 6 nightly-jobs (`quality`, `adr`, `consistency`, `documentation`, `integration-tests`, `security`) heeft een `subtasks.yaml` met de keten `development → review → test → summary → documentation → merge → deploy` plus de bijbehorende `.md`-bestanden voor de AI-subtaken.
13. De `merge`-subtaak is geconfigureerd als automatische merge en `deploy` als rest-restart, conform de huidige draaiwijze van dit project.

**Kwaliteit:**
14. Bestaande tests blijven groen; nieuwe of gewijzigde logica (reader-validatie, config-pad-materialisatie) is met unit-/integratietests gedekt.

## Aannames

- **Digest-rapportage**: "nachtelijke digest (Telegram, 's ochtends)" verwijst naar het bestaande Telegram-meldingskanaal (`TelegramNotificationService`/`TelegramClient.sendMessage`). Aangenomen wordt dat validatiefouten via dit bestaande kanaal gemeld worden; als er nog geen expliciet "morning digest"-aggregatiepunt bestaat, volstaat een directe Telegram-melding per overgeslagen job. Er wordt geen nieuw digest-framework gebouwd.
- **`planning-approved` triggert development**: door de story-fase op `PLANNING_APPROVED` te zetten start de bestaande statemachine (`StoryRefinementCoordinator`) de eerste subtaak automatisch; er is geen aparte start-trigger nodig.
- **Config-pad slaat manual-approve standaard niet af**: net als vandaag geldt dat nightly-jobs silent/autonoom draaien; het config-pad voegt geen manual-approve toe tenzij die expliciet in `subtasks.yaml` staat.
- **`type`-mapping**: de `type`-waarden in `subtasks.yaml` mappen 1-op-1 op de bestaande `SubtaskType`-enum; er worden geen nieuwe subtaak-types geïntroduceerd.
- **`.md`-koppeling per titel**: de koppeling tussen subtaak en beschrijving verloopt via exacte titel-match op bestandsnaam (`<title>.md`); dit is meteen de reden dat titels uniek moeten zijn.
- **Merge-commit/deploy-gedrag** blijft functioneel identiek aan de huidige `MergeSubtaskHandler`/`DeploySubtaskHandler` (automatische merge + rest-restart); deze story voegt alleen de declaratieve config toe, geen nieuw merge/deploy-gedrag.
- **Reader-caching**: het lezen van `subtasks.yaml`/`.md` gebruikt dezelfde GitHub-contents-aanpak en TTL-cache als de bestaande job-config; er wordt niet uit de lokale checkout gelezen.

<!-- test-feedback:start -->
## Test-feedback
## Testresultaat: `test-rejected`

**Wat klopt:**
- Gerichte unit-suites groen — 79 tests, 0 failures (`NightlyJobsReaderTest` 16, `SubtaskPlanMaterializerTest` 3, `FactoryDashboardServiceTest` 29, `DashboardAuthInterceptorTest` 4, `NightlySchedulerTest` 8, `NightlyPlannerTest` 19).
- Config voor alle 6 nightly-jobs correct: keten development→review→test→summary→documentation→merge→deploy, elke AI-subtaak heeft een exact gelijknamig `<title>.md`, merge/deploy hebben er geen. `manual` wordt terecht geweigerd.

**Blokkerende regressie (AC 14):**
- `ModulithArchitectureTest` faalt op deze branch: *Module 'web' depends on non-exposed type `SubtaskPlanMaterializer` within module 'runtime'*.
- Oorzaak: de nieuwe constructor-injectie van `SubtaskPlanMaterializer` (module `runtime`) in `FactoryDashboardService` (module `web`) schendt de Spring Modulith module-grens.
- **Bewezen regressie**, niet omgeving: de test is **groen op schone `main`** en **rood op de branch** (BUILD FAILURE, 470 run / Errors 1 — uitsluitend deze violation). De oude `modulith-cycle`-baseline-tip is achterhaald.

Terug naar de developer om de `web → runtime`-afhankelijkheid binnen de modulith-regels te brengen (type exposen of via een reeds geëxposeerde poort materialiseren).

Geen code/tests/infra gewijzigd; alleen het worklog bijgewerkt.

{"agent_tips_update":[{"category":"testing","key":"modulith-green-on-main-2026-07","content":"Per 2026-07 is ModulithArchitectureTest GROEN op een schone softwarefactory-main (Tests run 1, Failures 0, Errors 0) — de oude 'modulith-cycle-preexisting-main' baseline (orchestrator->telegram->web cycle) faalt NIET meer. Behandel elke ModulithArchitectureTest-Error dus als potentiële regressie en verifieer altijd tegen schone main (git worktree add /tmp/x main + mvn -pl softwarefactory -am test -Dtest=ModulithArchitectureTest). Typische nieuwe violation: 'Module web depends on non-exposed type ...runtime.services.X within module runtime' door een directe cross-module constructor-injectie."},{"category":"build","key":"softwarefactory-reactor-needs-am","content":"softwarefactory-app hangt af van nl.vdzon:factory-common:0.0.1-SNAPSHOT dat niet in de lokale repo zit. 'mvn -f softwarefactory/pom.xml test' faalt met 'Could not find artifact factory-common'. Draai vanuit repo-root met reactor: 'mvn -pl softwarefactory -am test' (bouwt factory-common mee). e2e/Docker-suite zit in aparte module en draait niet mee, dus de app-module geeft geen Docker-Errors."}]}

{"phase":"test-rejected"}
<!-- test-feedback:end -->

## Eindsamenvatting

Ik heb `.task.md`, de summarizer-instructies en het volledige worklog (SF-778) gelezen. De story is via developer → reviewer → tester rondes tot een groen eindresultaat gekomen. Hieronder de eindsamenvatting voor de PO.

---

## Eindsamenvatting SF-778 — Nightly-jobs: subtaken declaratief uit `subtasks.yaml`

### Wat is gebouwd
Nightly-jobs kunnen hun subtaken voortaan **declaratief uit config** halen, waardoor de AI-refine- en plan-stap worden overgeslagen voor deze statische, elke-nacht-identieke opdrachten.

- **Nieuwe config per job** in `.factory/nightly/<job>/`: een geordende `subtasks.yaml` (`type` + `title`, bestandsvolgorde = uitvoervolgorde) plus per AI-subtaak een gelijknamig `<title>.md` met de beschrijving. `subtasks.yaml` is volledig leidend — precies die subtaken, geen automatische extra's.
- **`NightlyJobsReader`**: leest en valideert `subtasks.yaml` + de `.md`-bestanden via dezelfde `gh`-contents/TTL-cache-aanpak als de bestaande job-config (geen lokale checkout). YAML wordt met `SafeConstructor` geparsed. Bij een fout gooit de reader een `NightlySubtasksConfigException`, waardoor de job als FAILED wordt gemarkeerd en de fout in de bestaande nachtelijke Telegram-digest belandt — er wordt géén story aangemaakt.
- **`SubtaskPlanMaterializer`**: nieuwe publieke `materializeFromSpecs(storyKey, specs)` die exact de meegegeven specs aanmaakt (idempotent op titel, erft de AI-supplier van de parent), zónder auto-append van documentation/merge/deploy/manual-approve. Het bestaande planner-pad (`materializeIfPlanned`) bleef ongewijzigd.
- **`FactoryDashboardService.createNightlyStory`**: met geldige config → story met `description = story.md`, `start=false` (geen refiner/planner), specs direct materialiseren en fase op `PLANNING_APPROVED` (statemachine start de keten). Zonder config → ongewijzigd huidig gedrag (refine + plan). Backwards compatible.
- **Config voor dit project zelf**: alle 6 nightly-jobs (`quality`, `adr`, `consistency`, `documentation`, `integration-tests`, `security`) kregen een `subtasks.yaml` met de keten **development → review → test → summary → documentation → merge → deploy** plus de bijbehorende `.md`-bestanden. Merge = automatisch, deploy = rest-restart (conform de huidige draaiwijze).
- **Specs bijgewerkt**: `functional-spec.md`, `technical-spec.md` en README beschrijven nu het declaratieve config-pad.

### Belangrijkste keuzes
- **Module-grens (Spring Modulith)**: de eerste opzet injecteerde de concrete `SubtaskPlanMaterializer` (module `runtime`) direct in `FactoryDashboardService` (module `web`), wat een module-grensschending gaf. Opgelost met een **geëxposeerde poort** `runtime/SubtaskMaterializationApi` (interface in het runtime-base-package); de materializer implementeert die en `web` injecteert de poort i.p.v. de concrete klasse. Aanroeppunt en gedrag identiek.
- `manual` wordt bewust geweigerd als type (niet in `ALLOWED_TYPES`); alleen de vaste geldige set is toegestaan.
- Koppeling subtaak↔beschrijving via exacte titel-match op bestandsnaam; spaties in `.md`-namen (bv. `Werk documentatie bij.md`) resolven correct omdat `gh api` de padsegmenten encodeert (bevestigd door admin).

### Wat is getest
- **Reader-validatie**: happy path + elke faalconditie (lege lijst, ongeldig type, geweigerde `manual`, dubbele titel, ontbrekend `<title>.md`, niet-parsende YAML, ontbrekende `story.md`).
- **Config-pad-materialisatie**: exact de gedeclareerde specs, geen auto-append, supplier-overerving, idempotentie op titel.
- **Regressie**: `ModulithArchitectureTest` na de poort-fix weer groen (1 run, 0 failures, 0 errors).
- Eindresultaat (reactor `mvn -pl softwarefactory -am test`): **470 tests, 0 failures, 0 errors**; gerichte suites (NightlyJobsReaderTest 16, SubtaskPlanMaterializerTest 3, FactoryDashboardServiceTest 29, DashboardAuthInterceptorTest 4, NightlySchedulerTest 8, NightlyPlannerTest 19) alle groen. Tester-verdict: **tested**.

### Bewust niet gedaan
- Geen nieuw digest-/aggregatieframework: validatiefouten worden via het bestaande Telegram-kanaal gemeld.
- Geen wijzigingen aan het planner-/refiner-gedrag voor niet-nightly stories, geen nieuwe subtaak-types of fases, geen UI-wijzigingen.
- De config-tak van `createNightlyStory` heeft géén eigen service-niveau-integratietest; de onderliggende methodes zijn afzonderlijk gedekt. Als niet-blokkerend [info] laten staan (optionele end-to-end integratietest).
- De 6 project-config-jobs zelf zijn niet apart getest (conform admin-comment); de Docker/e2e-suite draait niet lokaal mee.
