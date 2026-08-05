# SF-1959 - Hotfix-modus voor stories: snelle wijziging zonder refine/plan/review/documentation

## Story

Hotfix-modus voor stories: snelle wijziging zonder refine/plan/review/documentation

<!-- refined-by-factory -->

## Samenvatting

Kleine, niet-kritische wijzigingen (een timeout ophogen, een kleurtje aanpassen) moeten nu
de hele ontwikkelstraat door: refinen, plannen, reviewen, testen, documenteren en pas dan
mergen. Dat duurt lang en levert voor zulke klusjes weinig op.

Deze story voegt een "Hotfix"-vinkje toe dat je bij het aanmaken van een story zet. Staat het
aan, dan slaat de factory al die stappen over en doet één AI-stap het werk: code aanpassen,
de bestaande tests draaien en de wijziging aanbieden. Daarna lopen de normale merge en deploy
gewoon door, inclusief de gebruikelijke CI-controle. Zijn de tests rood, dan wordt er niets
gemerged.

## Scope

**In scope**

1. **Story-veld `Hotfix`** — nieuw boolean veld naast de bestaande assen
   QuestionsAllowed/ApprovalMode/NotifyMode:
   - `TrackerField.HOTFIX("Hotfix")` in `factory-common/.../core/TrackerField.kt`;
   - kolom `hotfix BOOLEAN NOT NULL DEFAULT false` op `${schema}.issues` (volgende vrije
     migratienummer is **V33**; bestaande rijen worden niet aangeraakt);
   - meenemen in `PostgresTrackerClient` (`ISSUE_COLUMNS`, `mapRow`, `columnFor`/
     `columnForLifecycleField`, `columnValue`) en in `TrackerIssueFields` + `applying(...)`
     — beide exhaustieve `when`-blokken dwingen dit af;
   - `TrackerCapabilities.createStory(...)` krijgt een `hotfix: Boolean = false`-parameter die
     in de INSERT meegaat (net als `questionsAllowed`).
2. **Aanmaakroutes** — het veld is te zetten bij het aanmaken van een story via:
   `POST /api/tracker/stories` (`CreateTrackerStoryRequest`), `tools/sf-story create --hotfix`,
   bridge-operatie `story.create` + `POST /api/v1/stories` (dashboard-backend), en de
   aanmaakdialoog in `dashboard-frontend/lib/screens/stories_screen.dart`.
   `AuditGatewayAdapter.proposeStoryIfAny` blijft expliciet op `hotfix = false`.
3. **Nieuw `SubtaskType.HOTFIX("hotfix")`** met een kale, reviewerloze pipeline:
   `start → developing → (developed-with-questions ↔ development-questions-answered) →
   developed → hotfix-approved`. `hotfix-approved` is een nieuwe `SubtaskPhase` en moet in
   `SubtaskPhase.isTerminal` staan, anders zet `advanceSubtaskChain` de keten nooit door.
   De handler-map in `SubtaskExecutionCoordinator` (met zijn `require(keys == entries)`-check)
   krijgt een `hotfixSubtask`-handler.
4. **Routing bij `Story Phase = start`** — in `StoryRefinementCoordinator` takt de `START`-tak
   af: bij `hotfix = true` wordt géén REFINER gedispatcht, maar wordt exact de speclijst
   `[HOTFIX, MERGE, DEPLOY]` gematerialiseerd via het exact-list-pad
   (`SubtaskMaterializationApi.materializeFromSpecs`, dat bewust niets auto-toevoegt), de eerste
   subtaak op `start` gezet en de story op `in-progress`. `start-next` blijft ongewijzigd: de
   per-repo wachtrij-promotor zet de story eerst op `start`.
   Let op de Spring-Modulith-grens: `pipeline` mag `runtime` vandaag níet zien
   (`pipeline/package-info.java`). Dit mag opgelost worden door `runtime` aan de
   `allowedDependencies` toe te voegen (geen cyclus: `runtime` kent `pipeline` niet) óf door de
   subtaken rechtstreeks via `TrackerCapabilities.createSubtask` aan te maken — de planner kiest.
5. **Geen extra ketenstappen bij hotfix** — er ontstaan géén review-, test-, summary-,
   documentation- of manual-approve-subtaken, ongeacht `ApprovalMode`.
6. **Dashboard-UI** — "Hotfix"-schakelaar in de aanmaakdialoog en een eigen tak
   (`case 'hotfix'`) in `dashboard-frontend/lib/phase_stepper.dart`, zodat een hotfix-subtaak
   niet in de grijze default-tak valt.
7. **Documentatie** — `docs/factory/functional-spec.md` (nieuwe paragraaf bij de story-assen),
   `docs/factory/technical-spec.md` (veld, kolom, migratie, subtaaktype/fase) en
   `docs/factory/ux/screens/stories.md` bijwerken.

**Expliciet buiten scope**

- Rechtstreeks naar `main` committeren zonder branch/PR. Bewust: de bestaande
  `MergeSubtaskHandler` en `DEPLOY`-subtaak blijven volledig ongewijzigd, ten koste van
  ~6-7 minuten CI-wachttijd. Een snellere variant is eventueel een vervolgstory.
- Elke vorm van diff-grootte-guard of hotfix-specifieke risicocontrole.
- Een apart/goedkoper model of effort-niveau voor hotfix-runs.
- Hotfix-specifieke notify-logica (`NotifyMode` volstaat).
- Het `Hotfix`-veld achteraf wijzigen op een bestaande story.

## Acceptance criteria

1. Een story kan bij het aanmaken op hotfix worden gezet via het dashboard, `sf-story create
   --hotfix`, `POST /api/tracker/stories` en de bridge-operatie `story.create`. Zonder expliciete
   waarde is een story géén hotfix.
2. Een bestaande story of een auditvoorstel wordt door deze wijziging nooit een hotfix.
3. Zodra een hotfix-story op `start` komt, draait er **geen** refiner- en **geen** planner-run:
   de story blijft nooit in `refining`/`planning` en gaat direct naar `in-progress`.
4. Direct na die overgang bestaan er onder de story precies drie subtaken, in deze volgorde:
   één `hotfix`, één `merge`, één `deploy`. Er is geen subtaak van type `review`, `test`,
   `summary`, `documentation` of `manual-approve` — ook niet wanneer de story op
   `ApprovalMode = alleen-manual-poort` of `elke-stap` staat.
5. De hotfix-subtaak wordt uitgevoerd door de DEVELOPER-rol en doorloopt
   `developing → developed → hotfix-approved`. Er draait geen reviewer op deze subtaak
   (nul reviewer-runs voor de story).
6. Zijn de bestaande projecttests rood na de wijziging, dan bereikt de subtaak nooit
   `hotfix-approved` en worden de merge- en deploy-subtaak nooit gestart. De rode uitkomst is
   deterministisch (harness-geverifieerd bewijs, geen AI-oordeel) en de diagnose is als
   `[FACTORY VERIFICATION]` terug te vinden bij de subtaak.
7. Na een groene hotfix-subtaak lopen de bestaande merge- en deploy-subtaken volledig
   ongewijzigd: merge alleen op groene CI op de actuele PR-head, deploy inclusief de bestaande
   `/api/version`-herstartverificatie. Daarna gaan subtaken en story naar Done.
8. Een hotfix-story met `QuestionsAllowed = aan` kan nog steeds een vraag stellen
   (`developed-with-questions`) en na beantwoording verder; met `QuestionsAllowed = uit` wordt
   dat zoals altijd een `[CLARIFICATION]`-error.
9. `NotifyMode = als-klaar-en-gedeployed` levert bij een hotfix-story precies één melding na de
   daadwerkelijke deploy — dezelfde code als bij een gewone story.
10. Een niet-hotfix-story doorloopt exact de bestaande keten
    `development → review → test → summary → documentation → [manual-approve] → merge → deploy`;
    geen bestaande e2e-/unittest verandert van verwacht gedrag.
11. Er is een e2e-test die de hotfix-flow van `start` tot en met deploy afdekt en expliciet
    assert dat er géén review-/test-/summary-/documentation-/manual-approve-subtaak ontstaat.
12. `mvn verify` vanaf de repo-root is groen (0 failures, 0 errors) en `flutter analyze` in
    `dashboard-frontend` is schoon.

## Aannames

- **Terminale fase.** Er komt een eigen `SubtaskPhase.HOTFIX_APPROVED("hotfix-approved")` die aan
  `isTerminal` wordt toegevoegd. `development-approved` wordt bewust *niet* terminaal gemaakt: dat
  zou het bestaande DEVELOPMENT-type (developer → reviewer in dezelfde subtaak) breken.
- **Geen mens-poort bij hotfix.** `ApprovalMode` wordt binnen de hotfix-keten volledig genegeerd:
  de overgang `developed → hotfix-approved` gaat onvoorwaardelijk door en de manual-approve-poort
  wordt nooit aangemaakt. Dat is het hele punt van hotfix.
- **Testpoort is hergebruik, geen nieuwbouw.** De deterministische poort bestaat al voor de
  DEVELOPER-rol (`AgentCli` → `TesterVerificationRunner` op `.factory/verification.yaml`, met
  diff-scoping via `pathPrefixes`). Rood levert `development-rejected` op; de hotfix-handler
  hergebruikt daarvoor de bestaande developer-loopback met de bestaande cap
  (`AI_MAX_DEVELOPER_LOOPBACKS`). Bij het bereiken van de cap komt de subtaak in `Error` en wordt
  er nooit gemerged of gedeployed.
- **Committen/pushen/PR is bestaand gedrag.** De factory (niet de agent) commit, pusht en opent de
  PR op de normale story-branch voor de DEVELOPER-rol; hiervoor is geen nieuwe git-code nodig.
- **Prompt blijft ongewijzigd.** De hotfix-subtaak gebruikt `AgentRole.DEVELOPER` met de bestaande
  `docs/factory/agents/developer.md`-instructies; er komt geen aparte hotfix-prompt. De developer
  houdt dus ook bij een hotfix zijn worklog bij.
- **Vaste subtaaktitel.** De hotfix-spec krijgt een stabiele titel (bv. "Hotfix uitvoeren"), omdat
  de materialisatie idempotent op titel werkt; de story-description is de opdracht.
- **Geen DB-constraint-werk voor het subtaaktype.** `subtask_type` en `subtask_phase` zijn vrije
  TEXT-kolommen zonder CHECK; alleen het `Hotfix`-veld vraagt een migratie (V33).
- **Testsupport meenemen.** `TrackerTestState.fieldFor` moet `"Hotfix"` kennen (anders `error(...)`),
  en de letterlijke typelijsten in `AwaitDsl.NON_AI_SUBTASK_TYPES` en
  `E2eTestBase.ENFORCED_SUBTASK_TYPES` moeten kloppend blijven voor de hotfix-keten.
- **`materializeFromSpecs` krijgt zijn eerste productiecaller terug.** De methode bestaat nog (het
  nightly-pad is verwijderd) en voegt bewust niets toe; merge en deploy staan daarom expliciet in
  de hotfix-speclijst.

## Eindsamenvatting

Ik heb `.task.md`, de story-diff en het volledige worklog (SF-1960/1961/1962) gelezen. Alles wat ik nodig had staat daar; ik heb niets gewijzigd.

---

# SF-1959 — Hotfix-modus voor stories

## Wat is er gebouwd

**1. Story-veld `Hotfix` (SF-1960)**
Een vierde story-as naast QuestionsAllowed/ApprovalMode/NotifyMode. Nieuw tracker-veld `Hotfix`, databasekolom via migratie **V33** (`NOT NULL DEFAULT false`, bestaande rijen worden niet aangeraakt) en volledige doorvoer in de tracker-client. Het veld is te zetten via alle vier de aanmaakroutes: het dashboard (schakelaar in de aanmaakdialoog), `sf-story create --hotfix`, de REST-API en de bridge-operatie `story.create`. Zonder expliciete waarde is een story géén hotfix; auditvoorstellen staan expliciet op `hotfix = false`.

**2. De hotfix-keten (SF-1961)**
Nieuw subtaaktype `hotfix` en een nieuwe terminale fase `hotfix-approved`. Komt een hotfix-story op `start`, dan wordt géén refiner en géén planner gedispatcht; in plaats daarvan worden exact drie subtaken gematerialiseerd — `hotfix`, `merge`, `deploy` — en gaat de story direct naar `in-progress`. De hotfix-subtaak is één DEVELOPER-run zonder reviewer en zonder mens-poort. Merge en deploy zijn volledig ongewijzigd. Het dashboard heeft een eigen stepper-tak voor hotfix-subtaken.

## Belangrijkste keuzes

- **Eigen terminale fase `hotfix-approved`** in plaats van `development-approved` terminaal maken — dat laatste zou de bestaande development-keten (developer → reviewer binnen dezelfde subtaak) breken.
- **ApprovalMode wordt binnen de hotfix-keten genegeerd**: `developed → hotfix-approved` gaat onvoorwaardelijk door, ook bij "elke stap". Dat is het punt van hotfix.
- **Fail-safe default**: bij een onbekende of lege waarde valt het veld terug op `false` (anders dan QuestionsAllowed, dat op `true` terugvalt) — een story mag nooit stilzwijgend een hotfix worden.
- **Testpoort is hergebruik, geen nieuwbouw**: de deterministische verificatie hangt aan de DEVELOPER-rol, niet aan het subtaaktype, dus de hotfix-subtaak krijgt de bestaande poort en de bestaande loopback-cap gratis. Rood → nooit `hotfix-approved`, dus nooit merge of deploy.
- **Modulith-grens**: `runtime` is aan de toegestane dependencies van `pipeline` toegevoegd (cyclusvrij) i.p.v. de subtaken buiten de bestaande materialisatie om aan te maken.

## Wat is getest

Naast de eigen unittests op veld-roundtrip, aanmaakroutes, routing, handler-gedrag en de Flutter-schermen is er een **volledige e2e-test** die de hotfix-flow van `start` t/m deploy draait op een echte Spring-app met Postgres, en expliciet assert dat er géén review-, test-, summary-, documentation- of manual-approve-subtaak ontstaat (nul runs van die rollen, nul `refining`/`planning`-fases). Ook gedekt: vragen aan/uit, en rode projecttests (subtaak blijft hangen, merge en deploy starten niet).

Vangnet groen op 05-08-2026: `mvn clean verify` vanaf de repo-root **BUILD SUCCESS**, 0 failures / 0 errors (839 unittests + e2e, Flyway tot en met V33 op een verse DB); `flutter analyze` schoon; 140 Flutter-tests groen; documentatie-audit PASS. Documentatie (functionele spec, technische spec, UX-scherm) is bijgewerkt.

## Bewust niet gedaan

- Geen directe commit naar `main` zonder branch/PR — merge en deploy blijven ongewijzigd, inclusief de gebruikelijke ~6-7 minuten CI-wachttijd. Een snellere variant kan een vervolgstory zijn.
- Geen diff-grootte-guard of hotfix-specifieke risicocontrole.
- Geen apart/goedkoper model of eigen prompt voor hotfix-runs.
- Het `Hotfix`-veld kan niet achteraf op een bestaande story worden gewijzigd.

## Openstaande suggestie (geen blocker)

De reviewer merkte op dat de subtaaktitels "Merge story-branch" / "Deploy naar productie" nu op twee plekken als literal staan; een hernoeming kan daar stil wegdriften. Functioneel onschadelijk, kandidaat voor opruimen.

<!-- deploy-summary:start -->
Je kunt bij het aanmaken van een story voortaan aangeven dat het om een hotfix gaat. De factory slaat dan het verfijnen, plannen, reviewen en documenteren over en past de wijziging in één stap aan, waarna hij zoals altijd wordt gecontroleerd en uitgerold. Gaan de bestaande controles mis, dan wordt er niets uitgerold.
<!-- deploy-summary:end -->
