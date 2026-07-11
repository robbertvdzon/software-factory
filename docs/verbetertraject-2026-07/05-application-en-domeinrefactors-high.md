# Plan 05 — Application- en domeinrefactors

## Metadata

| Veld | Waarde |
| --- | --- |
| Plan | 05 |
| Status | `NIET GESTART` |
| Werkpakketten | `ARC-01`, `ARC-02`, `ARC-03`, `ARC-04` |
| Prioriteit / omvang | P2 / viermaal L |
| Aanbevolen model | GPT-5.6 Sol |
| Effort | High |
| Waarom dit niveau | De refactors wijzigen application- en domeingrenzen in centrale flowcode. Gedrag, wirecontracten, state transitions en Modulith-isolatie moeten gelijk blijven terwijl god classes verdwijnen. |
| Prerequisite | Plan 04 volledig `AFGEROND`, gemerged en groen volgens `VOORTGANG.md` |
| Volgend plan | Plan 06 |

Dit plan verandert geen productgedrag. Het maakt bestaande verantwoordelijkheden expliciet,
verwijdert transport-naar-transportkoppeling en splitst brede interfaces en services. Voer het plan
story voor story uit. Een repositorybrede big-bangverplaatsing is verboden.

## Bindende bronnen

Lees vóór de start en opnieuw vóór ieder werkpakket:

- `docs/verbetertraject-2026-07/UITVOERREGELS.md`;
- `docs/verbetertraject-2026-07/VOORTGANG.md`;
- `docs/verbeterplan-onderhoudbaarheid-2026-07.md`, vooral `ARC-01` t/m `ARC-04`;
- de door plan 03 opgeleverde moduleconventie/ADR en architectuurtest (`MOD-01`);
- de door plan 01 opgeleverde centrale mergepolicy (`FIX-01`);
- de door plan 02 opgeleverde getypeerde tracker-not-foundfout (`FIX-06`);
- de door plan 04 opgeleverde completion-inbox en step handlers (`REL-01`).

Lees per werkpakket bovendien alle hieronder genoemde productie- en testbestanden voordat je
wijzigt. Controleer actuele paden; eerdere plannen kunnen classes volgens de moduleconventie hebben
verplaatst. Zoek op type-/methodenaam wanneer een genoemd pad niet meer bestaat.

## Harde uitvoerinvarianten

- Gebruik één Factory-story, branch en PR per werkpakketcode. Combineer nooit twee ARC-codes in één
  story of diff.
- Iedere story begint vanaf de gemergede, groene default branch van de voorgaande story.
- Voeg eerst characterizationtests toe; verplaats pas daarna productiegedrag.
- Behoud HTTP-, websocket-, tracker-, database-, agent-result- en UI-contracten tenzij dit pakket
  expliciet alleen een intern getypeerd contract introduceert met backward-compatibilitytests.
- Mechanische package-/bestandsverplaatsing en inhoudelijke opsplitsing staan nooit in dezelfde
  commit. Maak na iedere mechanische stap de gerichte én volledige gates groen voordat inhoudelijk
  werk begint.
- Na iedere mechanische package-/bestandsverplaatsing geldt `mvn clean verify` als verplichte
  Maven-gate vóór review en opnieuw op de gemergede SHA. Een gewone incrementele `mvn verify` is
  daarvoor geen vervanging.
- Voer in iedere story, ongeacht geraakte paden, onvoorwaardelijk het door `VER-02` vastgelegde
  canonieke volledige repositorygatecommando uit vóór review, na iedere reviewfix en post-merge.
  Een verzameling losse componentcommando's geldt niet als vervanging voor die ene canonieke gate.
- Introduceer geen vervangende facade met dezelfde dependencies, functies en veranderredenen als de
  verwijderde god class.
- Geen enkele falende unit-, integratie-, e2e-, contract-, Flutter- of smoketest mag worden
  genegeerd. “Pre-existing”, flaky, omgevingsgebonden of ongerelateerd is geen uitzondering.
- Geen `@Suppress`, skip, `@Disabled`, quarantine, `|| true`, tijdelijke modulebypass of zwakkere
  assert gebruiken om groen te worden.

## Prerequisites en baseline

1. Controleer dat plan 04 en alle werkpakketten t/m `REL-01` `AFGEROND` zijn in `VOORTGANG.md`.
2. Controleer dat de MOD-01-architectuurtest actief onderdeel is van `mvn verify` en noteer de
   actuele allowlist; iedere story in dit plan moet die verkleinen of gelijk houden, nooit vergroten.
3. Controleer de actuele mergepolicy en tracker-not-foundtypen; dupliceer die niet in nieuwe
   handlers.
4. Voer vóór **iedere** story uit en leg SHA, exitcode, tellingen en qualityscore vast:

   ```bash
   git status --short
   git log -1 --oneline
   mvn clean verify
   ./quality/run.sh
   ```

5. Neem uit de `VER-02`-overdracht het **exacte uitvoerbare canonieke volledige
   repositorygatecommando** over en voer het uit. Leg commando en resultaat vast. Ontbreekt daar een
   concreet commando of is de gate niet groen, dan is de story geblokkeerd; reconstrueer geen
   smallere lokale variant.
6. Maak daarna de story, noteer storykey/branch/PR/status in `VOORTGANG.md` en start niet tevens een
   tweede Factory-pipeline-uitvoerder voor dezelfde story.

## Kopieerbare startopdracht

```text
Voer plan 05 volledig autonoom en strikt sequentieel uit volgens
docs/verbetertraject-2026-07/05-application-en-domeinrefactors-high.md.

Lees vóór iedere wijziging ook volledig:
- docs/verbetertraject-2026-07/UITVOERREGELS.md
- docs/verbetertraject-2026-07/VOORTGANG.md
- docs/verbeterplan-onderhoudbaarheid-2026-07.md
- de actuele MOD-01-moduleconventie en architectuurtest

Controleer eerst dat plan 04 AFGEROND en groen is. Voer ARC-01, ARC-02, ARC-03 en ARC-04 in exact
die volgorde uit. Maak voor ieder werkpakket een afzonderlijke Factory-story, branch en PR; begin
de volgende story pas na merge en post-mergeverificatie van de vorige. Leg vóór iedere refactor
characterizationtests vast, behoud alle externe contracten, voorkom een vervangende god-facade en
vermeng mechanische verplaatsingen niet met inhoudelijke wijzigingen. Werk VOORTGANG.md na iedere
overdracht bij. Laat developer, reviewer en tester alle gerichte en volledige gates uitvoeren en
voer per story onvoorwaardelijk exact het door VER-02 overgedragen canonieke volledige
repositorygatecommando uit. Negeer geen enkele falende test. Push en merge alleen na alle verplichte
groene checks. Stop alleen bij een echte externe blokkade of onomkeerbare productbeslissing.
```

## Canonieke volledige repositorygate

`VER-02` levert één concreet, uitvoerbaar repositorygatecommando op dat Maven, Modulith, Flutter en
de verplichte image-/aggregatorchecks fail-closed samenbrengt. Dat overgedragen commando is vanaf
plan 03 de enige canonieke volledige repositorygate. Iedere ARC-story in dit plan voert exact dat
commando onvoorwaardelijk uit:

1. op de uiteindelijke storycommit vóór review;
2. opnieuw na iedere review- of testerfix;
3. opnieuw op de gemergede default-branch-SHA.

Gerichte tests, `mvn clean verify` en `./quality/run.sh` blijven daarnaast verplicht. Zij vervangen
de canonieke repositorygate niet. Wanneer het concrete commando of een vereiste component ontbreekt,
is dat een blocker en geen reden om de gate zelf smaller te reconstrueren.

## Verplichte dependencyvolgorde — geen big-bang

```text
Plan 04 / REL-01 gemerged
  -> ARC-01 dashboard application-grens
  -> ARC-02 dashboard-use-cases binnen die grens
  -> ARC-03 command- en subtaskhandlers
  -> ARC-04 tracker-capabilities en persistence
  -> planbrede nacontrole
```

`ARC-03` en `ARC-04` zijn inhoudelijk deels onafhankelijk, maar worden in dit autonome traject niet
parallel uitgevoerd: beide wijzigen injecties en trackerconsumenten en parallelle branches zouden
onnodige import-/wiringconflicten veroorzaken. Na iedere merge wordt vanaf de nieuwe default branch
opnieuw gebaselined. Geen agent mag alvast packageverplaatsingen voor een later werkpakket meenemen.

---

## ARC-01 — Introduceer een echte dashboard application-module

**Voorgestelde Factory-storytitel:** `Dashboard application-API ontkoppelt web en bridge`

### Probleem en bewijs

`web` hoort volgens `WebApi` een dunne HTTP-adapter te zijn, maar dashboard-use-cases,
responsemodellen, JDBC-querycode en externe dashboardclients leven er. `BridgeRequestHandler`
importeert concrete `web.services` en `web.models`; om dat compileerbaar te houden is zelfs
`web.services` als `@NamedInterface` geëxporteerd. Daardoor is transport aan transport gekoppeld en
kan de Modulith-test de bedoelde richting niet afdwingen.

Inventariseer minimaal:

- `softwarefactory/.../web/WebApi.kt`;
- `softwarefactory/.../web/services/FactoryDashboardService.kt`;
- `softwarefactory/.../web/services/FactoryOperationsService.kt` en overige dashboardservices;
- `softwarefactory/.../web/models/FactoryDashboardModels.kt` en beide `package-info.java`-bestanden;
- `softwarefactory/.../web/repositories/FactoryDashboardRepository.kt`;
- `softwarefactory/.../bridge/BridgeRequestHandler.kt` en bridgecontracttests;
- `dashboard-backend/.../bridge/BridgeApiController.kt` en `BridgeHub.kt`;
- `factory-common/.../contract/BridgeFrames.kt` en fixtures;
- `ModulithArchitectureTest` plus de MOD-01-test.

### Concrete stappen

1. Leg eerst met characterization-/contracttests de volledige actuele bridgeoperatiecatalogus,
   requestparametertypen, responses, fouten en onbekende-veldcompatibiliteit vast. Gebruik de
   fixtures uit `factory-common`; kopieer geen nieuwe losse waarheid naar docs of tests.
2. Definieer package/module `dashboard` volgens MOD-01:
   - smalle query- en commandports in de module-root;
   - uitsluitend cross-module immutable data classes in `dashboard.models` met
     `@NamedInterface("models")`;
   - implementations, repositories, clients en wiring in interne subpackages;
   - publieke enums/sealed/value types in de door MOD-01 bepaalde `types`-interface, niet tussen
     data classes.
3. Maak de application-API capabilitygericht. Gebruik bijvoorbeeld afzonderlijke interfaces voor
   overview/story/project/nightly/settings queries en story/factory commands; maak geen nieuwe
   universele `DashboardApi` met alle methodes.
4. Verplaats transportonafhankelijke dashboardmodellen en querypersistence mechanisch naar
   `dashboard`; pas imports aan, commit die move apart en maak alle tests groen zonder logica te
   wijzigen.
5. Laat `web`-controllers en `bridge` alleen publieke dashboardports injecteren. Geen adapter mag
   een concrete dashboardservice of intern subpackage importeren.
6. Vervang de grote string-`when(operation)` door een getypeerd command-/queryregister:
   - stabiele operatie-id blijft wire-compatible;
   - iedere operatie heeft een expliciet parametertype en handler;
   - deserialisatie valideert type/verplichte velden centraal;
   - onbekende operatie blijft `UNKNOWN_OPERATION` en onjuiste params blijven `INVALID_PARAMS`;
   - backend en factory delen de contractdefinitie op de huidige contractlocatie. Verplaats de
     Maven-module nog niet; dat is `ARC-06`.
7. Verwijder alle imports `bridge -> web` en daarna de named interface `web.services`. Verwijder
   `web.models` pas wanneer het werkelijk leeg is; laat geen compatibilitykopieën achter.
8. Werk actuele module-, endpoint-, bridge- en technical-specdocumentatie bij. Historische
   storydocs blijven ongewijzigd.

### Acceptatiecriteria

- `bridge` importeert niets uit `web`, ook niet via tests of conveniencehelpers.
- `web` en `bridge` zijn adapters van publieke `dashboard`-applicationports.
- De root en named interfaces van `dashboard` voldoen zonder nieuwe MOD-01-allowlistregels.
- Geen Spring-component, repository, client of service is publiek model/API-detail.
- Alle bestaande operatie-ids, JSON-envelopes, successresponses en foutcodes blijven compatible.
- Elke operatie heeft een expliciet requesttype en één geregistreerde handler; duplicate/missing
  registratie faalt bij startup of in een architectuurtest.
- Onbekende operatie, ontbrekend veld, verkeerd booleantype en onbekende additieve velden zijn
  contractueel getest.
- De named interface `web.services` bestaat niet meer.
- Modulith en volledige e2e-flow blijven groen.

### Gerichte verificatie

```bash
rg -n '^import nl\.vdzon\.softwarefactory\.web' softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/bridge
rg -n 'NamedInterface\("services"\)' softwarefactory/src/main
mvn -pl factory-common,dashboard-backend,softwarefactory -am test
mvn -pl softwarefactory -am -Dtest=ModulithArchitectureTest,BridgeRequestHandlerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
./quality/run.sh
```

### Volledige verificatie

```bash
mvn clean verify
./quality/run.sh
```

Voer daarna onvoorwaardelijk de canonieke volledige repositorygate uit zoals hierboven vastgelegd.

### Buiten scope

- `FactoryDashboardService` nog niet inhoudelijk per use-case splitsen; dat is `ARC-02`.
- Mavenmodules `factory-common`/contracts niet splitsen; dat is `ARC-06`.
- Geen endpoint-, UI-, auth-, cache-, nightly-, deploy- of storygedrag wijzigen.
- Geen andere module-roots migreren.

### Reviewer- en tester-aandachtspunten

- Reviewer: controleer dat de richting werkelijk adapter -> applicationport is en niet alleen een
  hernoemde concrete facade.
- Reviewer: vergelijk alle bridgeoperaties en DTO-velden voor/na en controleer MOD-01-visibility.
- Tester: draai contracttests aan beide bridgezijden en minimaal één echte websocket/REST-flow.
- Tester: forceer onbekende operatie, fout parametertype en offline factory.
- Iedere testfailure blokkeert goedkeuring; niets wordt als pre-existing geaccepteerd.

### Story-overdracht

Merge ARC-01, voer post-merge `mvn clean verify`, de Modulithtests en de canonieke volledige
repositorygate uit en leg de nieuwe publieke
dashboardports en operatiecatalogus in `VOORTGANG.md` vast. ARC-02 start uitsluitend vanaf deze
groene merge-SHA.

---

## ARC-02 — Splits dashboard-use-cases per verantwoordelijkheid

**Voorgestelde Factory-storytitel:** `Dashboard-use-cases zonder god service of facade`

### Probleem en bewijs

De huidige `FactoryDashboardService` telt circa 831 regels, 37 functies en 17 constructor-
dependencies. Zij combineert page assembly, storymutaties, nightly, projects/builds/deploystatus,
downloads, settings, caches en workspaceacties. Alleen verplaatsen naar `dashboard` lost de
single-responsibilityschending niet op.

### Concrete stappen

1. Meet vóór wijziging regels, functies, constructordependencies en relevante Detektbevindingen.
   Voeg characterizationtests toe per publieke use-case en voor cache/error-degradatie.
2. Splits minimaal deze veranderredenen in afzonderlijke applicationservices/use-cases:
   - dashboardoverzicht, stories en my-actions;
   - storydetail, briefing en screenshots;
   - projects, builds en productie-/deploystatus;
   - downloads en releases;
   - nightly queries en nightlycommands;
   - settings/version/configweergave;
   - story lifecyclecommands en storyaanmaak.
3. Gebruik immutable requestobjects voor `createStory`, projectacties en andere lange
   parameterlijsten. Maak defaults op één plaats expliciet en testbaar.
4. Plaats caches bij de query of externe adapter waarop zij betrekking hebben. Injecteer klok en
   executor waar tijd/concurrency getest moet worden; gebruik geen universele cachefacade.
5. Laat bridgehandlers rechtstreeks de passende smalle applicationport/use-case gebruiken. Een
   facade mag alleen pure delegatie bevatten wanneer frameworkwiring dit aantoonbaar vereist en
   moet dan een expliciete verwijderroute in dezelfde story hebben.
6. Verwijder de oude god class volledig wanneer alle consumers zijn gemigreerd. Laat geen typealias,
   tweede facade of test-only kopie achter.
7. Werk module- en technical-specdocumentatie bij en leg de nieuwe ownershipgrenzen vast.

### Acceptatiecriteria

- Iedere nieuwe service heeft één benoembare veranderreden en alleen bijbehorende dependencies.
- De oude `FactoryDashboardService` bestaat niet meer.
- Er is geen vervangende class met vrijwel dezelfde methodes/dependencies.
- Querycaches, timeouts en foutdegradatie gedragen zich gelijk en hebben deterministische tests.
- Story/nightly/settingscommands behouden exact hun bestaande tracker- en responsegedrag.
- De oude `TooManyFunctions`, `LongParameterList` en relevante complexiteitshotspot is verdwenen;
  de totale qualityscore stijgt niet.
- Alle bridge-, repository-, dashboardservice- en e2e-tests blijven groen.

### Gerichte verificatie

```bash
rg -n 'FactoryDashboardService' softwarefactory/src/main softwarefactory/src/test
mvn -pl softwarefactory,dashboard-backend -am test
mvn -pl softwarefactory -am -Dtest='*Dashboard*,*Bridge*' \
  -Dsurefire.failIfNoSpecifiedTests=false test
./quality/run.sh
```

### Volledige verificatie

```bash
mvn clean verify
./quality/run.sh
```

Voer daarna onvoorwaardelijk de canonieke volledige repositorygate uit zoals hierboven vastgelegd.

### Buiten scope

- Geen visuele Flutterwijzigingen of typed Dartmodellen; dat is `UI-01`.
- Geen command-/subtaskpipeline-refactor; dat is `ARC-03`.
- Geen trackerinterface- of persistence-split; dat is `ARC-04`.
- Geen nieuwe cachingtechnologie of distributed cache.

### Reviewer- en tester-aandachtspunten

- Reviewer: teken voor/na de dependencyverdeling en zoek naar een verborgen god facade.
- Reviewer: controleer cache ownership, clock/executorgebruik en foutafvang per externe call.
- Tester: vergelijk responsefixtures en zichtbare dashboardflows voor ieder use-casecluster.
- Tester: test force-refresh, partial external failure, lege data en story/nightlymutaties.
- Geen enkele falende test mag worden genegeerd.

### Story-overdracht

Leg per nieuwe use-case eigenaar, testklasse en dependencies vast in het worklog. Merge en voer
post-merge `mvn clean verify` en de canonieke volledige repositorygate uit. ARC-03 start uitsluitend
vanaf de groene ARC-02-SHA.

---

## ARC-03 — Splits commands en subtaskpipeline in handlers

**Voorgestelde Factory-storytitel:** `Afzonderlijke command- en subtaskhandlers met gedeelde recovery`

### Probleem en bewijs

`ManualCommandService` combineert parsing, processed markers, triggers, alle `FactoryCommand`s,
merge, re-implement en cleanup. `SubtaskExecutionCoordinator` combineert routing voor alle
`SubtaskType`s, recovery, feedbackblokken, caps, auto-approve en chaining. Nieuwe commands of
subtasktypes vergroten daardoor centrale `when`-blokken van ongeveer 500 regels. `AgentDispatcher`
heeft bovendien brede context als losse parameters.

### Concrete stappen

1. Voeg vóór verplaatsen characterizationtests toe voor iedere `FactoryCommand`, trigger,
   processed-markerflow en ieder `SubtaskType`/fasepad, inclusief reject-loopbacks, caps, recovery,
   merge/deploy en chain-einde.
2. Introduceer een immutable `CommandContext` en een smal `FactoryCommandHandler`-contract.
   Registreer exact één handler per `FactoryCommand`; duplicate of ontbrekende registratie faalt
   deterministisch.
3. Laat de overblijvende commandservice uitsluitend comments/instructies parsen, idempotency/
   processed markers bewaken en naar handlers dispatchen.
4. Maak één handler per `SubtaskType`. Deel alleen aantoonbaar identieke mechanics via kleine
   services, bijvoorbeeld:
   - chain advancement;
   - active-phase recovery/timeouts;
   - human/auto-approvebeslissing;
   - feedbackblokschrijven;
   - transitionguards.
5. Houd de centrale mergepolicy uit `FIX-01` de enige merge-entry. Een commandhandler mag
   `mergePullRequest` nooit rechtstreeks aanroepen.
6. Introduceer een immutable `AgentDispatchContext` voor de lange `AgentDispatcher`-aanroep en maak
   invarianten bij constructie expliciet.
7. Centraliseer gemarkeerde feedbackblokken zonder de bestaande markers/tekst of replace-semantiek
   te veranderen.
8. Laat de coordinator na migratie alleen type bepalen, handler selecteren en gemeenschappelijke
   orchestration starten. Verwijder oude branches zodra hun handler groen is; houd geen dubbel pad.
9. Werk pipeline-/module-/technical-specdocumentatie bij.

### Acceptatiecriteria

- Ieder `FactoryCommand` en `SubtaskType` heeft precies één geregistreerde handler.
- Een nieuw command/subtasktype vereist geen businessbranch in een 500-regelige dispatcher.
- `ManualCommandService` parseert/dispatcht alleen; `SubtaskExecutionCoordinator` routeert en
  orchestreert alleen.
- Merge gebruikt uitsluitend de centrale policy; pending/blocked/readygedrag blijft gelijk.
- Recovery, timeout, caps, loopbacks, feedbackmarkers, auto-approve en silentgedrag zijn
  karakterisatiegetest en ongewijzigd.
- `AgentDispatcher` gebruikt een getypeerd contextobject en heeft geen equivalente lange
  parameterlijst elders gekregen.
- Relevante Detekt-hotspots dalen en totale score stijgt niet.
- Bestaande unit-, flow- en e2e-tests blijven groen.

### Gerichte verificatie

```bash
rg -n 'mergePullRequest\(' softwarefactory/src/main factory-common/src/main
mvn -pl softwarefactory -am \
  -Dtest=ManualCommandServiceTest,OrchestratorSubtaskChainTest,OrchestratorSubtaskFlowTest,OrchestratorSubtaskRecoveryTest,MergeSubtaskHandlerTest,DeploySubtaskHandlerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl softwarefactory -am -Dtest='*Pipeline*,*Subtask*,*ManualCommand*' \
  -Dsurefire.failIfNoSpecifiedTests=false test
./quality/run.sh
```

### Volledige verificatie

```bash
mvn clean verify
./quality/run.sh
```

Voer daarna onvoorwaardelijk de canonieke volledige repositorygate uit zoals hierboven vastgelegd.

### Buiten scope

- Geen nieuwe commands, fasen, rollen of subtasktypes toevoegen.
- Geen tracker-capabilities/persistence opsplitsen; dat volgt in `ARC-04`.
- Geen wijziging van merge-, deploy-, retry-, cap- of human-actionbeleid.
- Geen brede packagepluraliteit- of naamcleanup; dat volgt later.

### Reviewer- en tester-aandachtspunten

- Reviewer: controleer één-op-één registratie en dat handlers geen onderlinge cycli krijgen.
- Reviewer: vergelijk alle state transitions en foutteksten met characterizationtests.
- Reviewer: controleer dat gedeelde services mechanics delen en geen nieuwe generieke god service
  vormen.
- Tester: voer alle commandpaden en ieder subtasktype uit, inclusief restart/recovery na actieve
  fase en pending mergecheck.
- Iedere failure gaat terug naar developer; niets wordt genegeerd.

### Story-overdracht

Leg de handlerinventaris en gedeelde mechanics vast. Merge, draai `mvn clean verify` en de canonieke
volledige repositorygate op de default-branch-SHA en start ARC-04 pas daarna.

---

## ARC-04 — Segregeer tracker-capabilities en persistence

**Voorgestelde Factory-storytitel:** `Smalle tracker-capabilities met gescheiden Postgres-opslag`

### Probleem en bewijs

`TrackerApi` combineert issuequeries, mutaties, transitions, comments, parsing, attachments en
processed markers. Veel methodes hebben lege defaults of runtime-`UnsupportedOperationException`,
waardoor ontbrekende capabilities niet compile-time zichtbaar zijn. `PostgresTrackerClient`
combineert dezelfde opslaggebieden plus filesystemattachments, keygeneratie en state-changeevents.
Ook runrepositorymethodes gebruiken lange, foutgevoelige parameterlijsten.

Inventariseer met `rg` iedere productieconsumer en welke methodes zij werkelijk gebruikt. Baseer de
interfaces op consumers, niet op de bestaande brede class.

### Concrete stappen

1. Leg characterization- en Testcontainers-tests vast voor issue lezen/schrijven, transitions,
   comments, processed markers, attachments, keygeneratie, no-opupdates en state-changeevents.
   Bundel de persistence-/restartscenario's in de exact benoemde Failsafe-klasse
   `TrackerCapabilityPersistenceE2eTest`, zodat de gerichte integratiegate aantoonbaar minimaal één
   bedoelde testklasse selecteert.
2. Introduceer smalle publieke capabilities volgens MOD-01, minimaal:
   - `IssueReader`;
   - `IssueWriter`/`IssueLifecyclePort`;
   - `CommentPort`;
   - `AttachmentPort`;
   - `ProcessedCommentPort`.
3. Houd pure comment-/instructieparsing buiten een I/O-port. Geef consumers rechtstreeks een pure
   parser/policy of een toepasselijk domeincontract.
4. Migreer iedere consumer naar uitsluitend benodigde capabilities. Verwijder alle default-no-ops
   en `UnsupportedOperationException`-defaults; een ontbrekende implementatie moet bij compile/wiring
   falen.
5. Splits de interne Postgresimplementatie in issue query/write, comments, attachments en
   keysequence. Deel alleen een kleine schema/Jdbc-supportcomponent; creëer geen brede
   `PostgresTrackerFacade` met alle oude logica.
6. Behoud transacties, events en attachment-filesystemsemantiek. Voeg padvalidatie en atomische
   filesystem/metadata-afhandeling alleen toe waar nodig om bestaande garanties veilig te houden;
   maak hiervan geen algemene storage-rewrite.
7. Vervang lange runrepositorysignatures zoals `updatePullRequest`, `updateWorkspace` en
   `recordStarted` door immutable typed command/updateobjects met constructorinvarianten.
8. Verwijder `TrackerApi` uit productie-injecties en daarna volledig. Een tijdelijke facade mag
   alleen binnen tussencommits bestaan en mag niet in de gemergede eindtoestand achterblijven.
9. Werk module/API-, database- en technical-specdocumentatie bij en verklein de MOD-01-allowlist.

### Acceptatiecriteria

- Geen productieclass injecteert de brede `TrackerApi`.
- Iedere consumer ziet compile-time alleen de benodigde tracker-capabilities.
- Geen capabilitymethode heeft een stille lege/no-opdefault of een “not supported”-default.
- Postgres issue-, comment-, attachment- en keycode hebben gescheiden classes en tests.
- Typed runrepositoryupdates vervangen de genoemde lange parameterlijsten zonder semantiekverlies.
- Tracker-not-found blijft het getypeerde contract uit `FIX-06`.
- Completion-idempotency uit `REL-01`, state-change wake-ups en attachmentdeduplicatie blijven groen.
- Geen SQL-, tracker-, filesystem- of wiregedrag wijzigt ongedocumenteerd.
- Modulith en qualityscore verslechteren niet; relevante class-hotspots dalen.

### Gerichte verificatie

```bash
rg -n 'TrackerApi' softwarefactory/src/main softwarefactory/src/test
mvn -pl softwarefactory -am \
  -Dtest=PostgresTrackerClientTest,TrackerCommentParserTest,AgentRunCompletionServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl softwarefactory -am -Dit.test=TrackerCapabilityPersistenceE2eTest verify
./quality/run.sh
```

Gebruik na hernoemen de nieuwe testklassen en voeg de concrete capability-/repositorytests toe aan
het bewijs; het doel van `rg` is dat productie-injecties nul resultaten geven.

### Volledige verificatie

```bash
mvn clean verify
./quality/run.sh
```

Voer daarna onvoorwaardelijk de canonieke volledige repositorygate uit zoals hierboven vastgelegd.

### Buiten scope

- Geen trackerproductfeature, statusdefinitie, database-engine of attachmentbackend vervangen.
- Geen generieke repositoryframeworklaag invoeren.
- Geen volledige module-rootmigratie van `tracker`/`core`; `MOD-03` volgt later.
- Geen naamcleanup buiten direct geraakte capabilities.

### Reviewer- en tester-aandachtspunten

- Reviewer: controleer capabilitycohesie vanuit iedere consumer en zoek naar casting/service
  locator of brede composite als omweg.
- Reviewer: controleer transacties, SQL-parameters, no-opguards, events en filesystem/DB-consistentie.
- Reviewer: verifieer dat REL-01-idempotencyconstraints en recovery niet zijn verzwakt.
- Tester: draai echte Postgres-tests voor alle capabilities en completion-recovery met screenshots.
- Tester: test ontbrekend issue, duplicate/no-opupdate, commentmarkers en attachment lifecycle.
- Geen enkele falende test mag worden genegeerd.

### Story-overdracht

Leg een capability-consumermatrix, nieuwe typed updateobjects, persistenceclasses en verwijderde
defaults vast. Merge en voer post-merge `mvn clean verify` en de canonieke volledige repositorygate
uit voordat het plan wordt afgerond.

---

## Planbrede verificatie na ARC-04

Voer op de gemergede default branch minimaal uit:

```bash
git status --short
git log -1 --oneline
mvn clean verify
./quality/run.sh
rg -n '^import nl\.vdzon\.softwarefactory\.web' softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/bridge
rg -n 'NamedInterface\("services"\)' softwarefactory/src/main
rg -n 'TrackerApi' softwarefactory/src/main
```

Voer ook onvoorwaardelijk het exacte canonieke volledige repositorygatecommando uit, plus de actuele
documentatie-audit en alle door plan 03 verplichte CI/smokechecks. Wanneer
een commando door verplaatsingen een andere naam heeft, documenteer de equivalente actuele query;
verklein nooit de verificatiescope. Iedere rode test is een blocker.

## Plan-afronding

Plan 05 krijgt pas status `AFGEROND` als:

1. vier afzonderlijke Factory-stories/branches/PR's voor ARC-01 t/m ARC-04 traceerbaar en zonder
   bypass gemerged zijn;
2. ieder volgend werkpakket aantoonbaar vanaf de groene merge-SHA van zijn voorganger begon;
3. reviewer en tester iedere uiteindelijke commit expliciet hebben goedgekeurd;
4. gerichte suites, `mvn clean verify`, de canonieke volledige repositorygate,
   Modulith-/MOD-01-gates, documentatie-audit en qualitycontrole groen zijn;
5. geen enkele falende test als pre-existing, flaky of ongerelateerd is genegeerd;
6. `bridge` niet meer van `web` afhangt, de dashboard-godservice weg is, command/subtaskhandlers
   gescheiden zijn en tracker-capabilities smal zijn;
7. geen tijdelijke facade, allowlistgroei, TODO, skip of dubbel oud/nieuw pad is achtergebleven;
8. actuele module-, bridge-, pipeline-, tracker- en database-docs overeenkomen met de code;
9. `VOORTGANG.md` per story SHA, branch, PR, tests, qualitydelta en post-mergebewijs bevat.

## Overdracht naar plan 06

Leg voor de volgende agent minimaal vast:

- de nieuwe dashboard applicationports, named interfaces en operationhandlercatalogus;
- de uiteindelijke dashboard-use-caseverdeling en cache-eigenaars;
- command- en subtaskhandlerregistraties plus gedeelde recovery/chainservices;
- de tracker capability-consumermatrix en interne persistenceverdeling;
- alle gewijzigde Maven/packagepaden die plan 06 bij imports moet respecteren;
- de actuele MOD-01-allowlist en qualityscore;
- gemergede eind-SHA en volledige groene test-/CI-resultaten.

Plan 06 mag pas starten nadat een nieuwe agent vanaf de actuele default branch `mvn clean verify`,
de canonieke volledige repositorygate, de architectuurgates en qualitycontrole groen heeft bevestigd
en plan 05 in `VOORTGANG.md` `AFGEROND` staat.
