# Plan 09 — Cleanup en eindverificatie

| Metadata | Waarde |
| --- | --- |
| Status | `AFGEROND` |
| Werkpakket | `CLN-01`, gevolgd door trajectbrede eindverificatie |
| Aanbevolen model | GPT-5.6 Sol |
| Effort | Light |
| Waarom dit niveau | Alle architectuur-, contract- en gatekeuzes moeten in plannen 01 tot en met 08 al zijn genomen en gemerged. Dit plan voert alleen afgebakende mechanische cleanup uit en controleert daarna de bestaande eindcriteria. Het maakt nadrukkelijk geen nieuwe architectuurkeuzes. |
| Verplichte voorganger | Plan 08 volledig `AFGEROND` en gemerged |
| Opvolger | Geen; dit is het sluitplan van het traject |
| Bronnen | [Bronplan](../verbeterplan-onderhoudbaarheid-2026-07.md), [uitvoerregels](UITVOERREGELS.md), [voortgang](VOORTGANG.md) |

## Doel en harde begrenzing

Dit plan heeft twee fasen:

1. één laatste, mechanische Factory-story `CLN-01` voor namen, dode bestanden en aantoonbaar
   ongebruikte parameters;
2. na merge een eindcontrole op de gemergede default-branch-SHA tegen **alle** criteria van het
   volledige verbetertraject.

De eindcontrole is geen gelegenheid om nieuw ontwerpwerk in de cleanup-PR te stoppen. Ontbreekt een
eerder beloofde applicationgrens, recovery-eigenschap, gate of deploymentreparatie, dan is de
verantwoordelijke eerdere story niet af. Zet plan 09 op `GEBLOKKEERD`, heropen of maak daar een
expliciete blokkerende story voor en hervat plan 09 pas nadat die correctie afzonderlijk gemerged en
groen is.

## Prerequisites

Begin niet voordat alle onderstaande punten aantoonbaar waar zijn:

- [ ] In [VOORTGANG.md](VOORTGANG.md) staan plannen 01 tot en met 08 op `AFGEROND`.
- [ ] Alle 24 voorafgaande administratieve werkpakketten en alle afzonderlijke `MOD-03`-
      modulestories zijn gemerged en post-merge groen.
- [ ] Plan 08 heeft het exacte qualitycommando, baselinebewijs, architectuurcommando,
      dependency-matrix en reproduceerbare diagram overgedragen.
- [ ] De repository-aggregator uit `VER-02` is verplicht en groen op de actuele default branch.
- [ ] De `MOD-01`-migratieallowlist is repositorybreed exact leeg; iedere resterende regel blokkeert.
- [ ] Het permanente `ARC-07` composition-root-boundaryregister is versioned, exact, groen en niet
      gegroeid. Het is geen overtredingsallowlist en blijft bestaan.
- [ ] De auditbaseline [`baselines/quality-cc7cac2.json`](baselines/quality-cc7cac2.json), de
      actuele ratchetbaseline en de bijgewerkte per-hotspotmatrix in `VOORTGANG.md` zijn aanwezig.
      De suppressiebaseline is exact de bekende één of veilig nul en is nooit gegroeid.
- [ ] Er bestaan geen open blokkerende stories, production compatibilityfacades,
      compatibilityshims, testskips of geaccepteerde rode checks. Een verwijderstory maakt een
      achtergebleven facade niet acceptabel en houdt het traject `GEBLOKKEERD`.
- [ ] De worktree is schoon en de actuele default branch is lokaal bijgewerkt.
- [ ] De canonieke volledige repositorygate, inclusief `mvn clean verify`, Flutter, alle
      image-/runtime-smokes, documentatie-audit en externe aggregator, is groen vóór de
      cleanupwijziging.

Een ontbrekende prerequisite is geen cleanupbevinding maar een blokkade. Leg story/eigenaar en
concrete eerstvolgende actie vast in `VOORTGANG.md`.

## Kopieerbare startopdracht

```text
Voer plan 09 volledig autonoom uit volgens
docs/verbetertraject-2026-07/09-cleanup-en-eindverificatie-light.md.

Lees vóór iedere wijziging ook volledig:
- docs/verbetertraject-2026-07/UITVOERREGELS.md
- docs/verbetertraject-2026-07/VOORTGANG.md
- docs/verbeterplan-onderhoudbaarheid-2026-07.md
- alle overdrachtsgegevens van plannen 01 tot en met 08

Controleer eerst dat plan 08 en alle eerdere plannen werkelijk gemerged en groen zijn. Maak daarna
precies één Factory-story, branch en PR voor CLN-01. Voer alleen de in plan 09 opgesomde mechanische
naam-, dode-code- en ongebruikte-parametercleanup uit. Maak geen functionele wijziging en geen
nieuwe architectuurkeuze. Als een cleanupstap alleen via nieuw ontwerp kan, stop die stap, registreer
de ontbrekende eerdere story als blokkade en houd CLN-01 zuiver.

Compileer na iedere mechanische stap, controleer renames met git diff --find-renames en draai de
gerichte en volledige gates. Na iedere package-, bestands- of symboolmove is `mvn clean verify`
verplicht voordat de volgende stap begint. Merge uitsluitend na developer-, reviewer-, tester- en
CI-akkoord.
Voer daarna op de gemergede default-branch-SHA de volledige eindverificatie en ieder criterium uit
dit plan uit. Geen enkele falende test mag worden genegeerd, ook niet als deze pre-existing,
omgevingsgebonden, flaky of ongerelateerd lijkt. Een ontbrekende, skipped, cancelled, pending of
rode check is niet groen.

Accepteer geen production compatibilityfacade of shim, ook niet met een vervolgstory. Zo'n story
houdt plan 09 en het traject GEBLOKKEERD totdat de facade afzonderlijk is verwijderd, gemerged en
volledig groen geverifieerd.

Werk VOORTGANG.md volledig bij met stories, PR's, commits, exacte commando's, tellingen, externe
runlinks en de eind-SHA. Rond het traject alleen af wanneer ieder eindcriterium aantoonbaar bewijs
heeft en geen blokkade of uitzondering openstaat.
```

## Verplichte volgorde

1. Verifieer prerequisites en registreer de baseline.
2. Voer `CLN-01` in één eigen story/branch/PR uit.
3. Laat developer, reviewer en tester de uiteindelijke cleanupcommit goedkeuren.
4. Merge zonder bypass en wacht op alle post-mergechecks.
5. Maak een verse checkout of schone worktree op exact de gemergede default-branch-SHA.
6. Voer de canonieke volledige repositorygate plus lokale, CI-, Docker-, documentatie-,
   architectuur- en operationele eindverificatie uit.
7. Los iedere failure via de juiste afzonderlijke story op; herhaal daarna de volledige
   eindverificatie vanaf stap 5.
8. Vul pas bij volledig groen bewijs het onderdeel `Eindbewijs` in
   [VOORTGANG.md](VOORTGANG.md) en zet plan 09 en het traject op `AFGEROND`.

## Canonieke volledige repositorygate en baseline vóór CLN-01

Leg minimaal vast:

```bash
git status --short
git log -1 --oneline
mvn clean verify
./quality/run.sh
cd dashboard-frontend
flutter pub get
flutter analyze
flutter test
```

Voer daarnaast onvoorwaardelijk de exacte canonieke quality-, architectuur- en diagramcommando's
uit plan 08, de `VER-02` repository-aggregator, alle door `FIX-03`/`VER-02` vastgelegde agent-,
assistant- en dashboardimagebuilds met runtime-smokes en de `DOC-01`-documentatie-audit uit. Deze
volledige gate geldt vóór CLN-01, na iedere reviewfix, vóór merge, post-merge en tijdens de
eindverificatie; impactsafweging verkleint haar nooit. Leg datum/tijd, exitcodes, testtellingen,
quality-/suppressiedelta, artifactlinks en diagramstatus vast. **Geen enkele falende test mag worden
genegeerd.** Een rode baseline wordt eerst via een blokkerende story hersteld.

## CLN-01 — Gerichte naam- en dode-codecleanup

### Storytitel

`Mechanische naamcleanup en verwijdering aantoonbaar dode code`

### Probleem en bewijs

Het bronplan noemt de volgende restschuld die bewust tot na alle package- en architectuurmigraties
is uitgesteld:

- mogelijk ongebruikte `projectKey`-parameters in `pollOnce`/`findAiIssues`;
- een bestand `YouTrackModels.kt` terwijl de code geen YouTrack meer gebruikt;
- import-only restbestanden `CostMonitorService.kt` en `CreditsPauseService.kt`;
- de POM-omschrijving “Read-only dashboard API” terwijl de bridgebackend muterende endpoints
  aanbiedt;
- historisch gegroeide packagepluraliteit;
- eventuele achtergebleven naam- of documentatiereferenties naar de brede `ProjectRepoResolver`,
  die volgens `ARC-07` al in smalle loader-, validator-, lookup- en settingsverantwoordelijkheden
  moet zijn opgesplitst.

Plannen 05 tot en met 08 kunnen namen of paden inmiddels hebben veranderd. Zoek daarom op symbool
en verantwoordelijkheid, niet alleen op het historische pad. Een verdwenen item is “al opgelost” en
wordt als zodanig met commitbewijs genoteerd; herstel het niet om deze checklist letterlijk te
kunnen uitvoeren.

### Voorafgaande inventarisatie

Maak in het storyworklog een tabel met voor ieder kandidaatitem:

- actuele symbool-/bestandspad;
- alle productie- en testreferenties;
- classificatie `mechanisch verwijderen`, `mechanisch hernoemen`, `al opgelost` of `blokkerend
  inhoudelijk werk`;
- verwachte gerichte tests;
- geraakte actuele documentatie.

Een item met runtime-effect, configuratieschemawijziging of nieuwe dependencyrichting krijgt de
classificatie `blokkerend inhoudelijk werk` en wordt niet in deze cleanupstory ontworpen.

### Concrete stappen

1. **Ongebruikte parameters:** zoek definities, overrides, callsites en reflectief/serialisatiegebruik
   van `projectKey` in `pollOnce`, `findAiIssues` en eventuele opvolgernamen. Verwijder parameter en
   argument alleen wanneer statische analyse en tests aantonen dat het contract projectagnostisch
   is. Implementeer geen nieuwe filtering zonder bestaand, eerder gemerged contract; dat zou
   functioneel werk zijn.
2. **Trackerbestandsnaam:** hernoem het voormalige `YouTrackModels.kt` naar een naam die de reeds
   bestaande eindverantwoordelijkheid beschrijft. Voeg geen tweede conflicterend `TrackerModels.kt`
   toe en verplaats geen typen opnieuw over modulegrenzen die plan 07 heeft vastgelegd.
3. **Import-only bestanden:** verwijder de actuele opvolgers van de import-only
   `CostMonitorService.kt`- en `CreditsPauseService.kt`-restbestanden wanneer `git grep` bewijst dat
   ze geen declaration, package metadata of noodzakelijk side-effect bevatten.
4. **POM-omschrijving:** vervang “Read-only dashboard API” door een feitelijke korte omschrijving van
   de dunne dashboardbridge. Wijzig geen artifact-id of runtimeconfig.
5. **Packagepluraliteit:** harmoniseer uitsluitend packages die na de afgeronde verplaatsingen
   aantoonbaar dezelfde conventie met afwijkende enkelvoud/meervoudnaam gebruiken. Doe dit als pure
   rename met imports/tests/docs; introduceer geen nieuwe lagen.
6. **Projectcatalogusgrens:** controleer dat `ARC-07` de brede `ProjectRepoResolver` volledig heeft
   verwijderd en consumers rechtstreeks de smalle YAML-/catalogusloader, validators,
   catalogus-/lookupports en getypeerde runtime-/deploymentsettings gebruiken. Verwijder uitsluitend
   achtergebleven obsolete naam- of documentatiereferenties als mechanische cleanup. Introduceer
   nadrukkelijk geen nieuwe overkoepelende `ProjectCatalog`- of andere facade. Bestaat de brede
   resolver nog, ontbreekt een afgesproken verantwoordelijkheid of is een gedrags-/ownershipkeuze
   nodig, zet CLN-01 op `GEBLOKKEERD` en herstel `ARC-07` in een afzonderlijke story.
7. Werk uitsluitend actuele docs, configuratievoorbeelden en tests bij die door deze namen geraakt
   worden. Wijzig geen historische storydocumenten buiten het eigen worklog.
8. Inspecteer na iedere stap de diff. Na iedere package-, bestands- of symboolmove draait
   `mvn clean verify` volledig groen voordat de volgende mechanische stap begint; alleen compile of
   een incrementele build is onvoldoende.

### Acceptatiecriteria

- Ieder bronplanitem is met pad en bewijs `opgelost`, `al eerder opgelost` of als echte blokkade aan
  een eerdere verantwoordelijkheid teruggegeven; niets verdwijnt stil uit de checklist.
- Er bestaan geen ongebruikte `projectKey`-parameters meer in de bedoelde callchain, tenzij een
  aantoonbaar bestaand publiek contract ze werkelijk gebruikt.
- Geen productie- of testreferentie gebruikt nog de obsolete YouTrackbestandsnaam.
- De import-only restbestanden zijn weg en hun verwijdering verandert geen bytecodegedrag.
- De dashboard-backend-POM beschrijft de actuele bridgeverantwoordelijkheid correct.
- Package-renames en het verwijderen van obsolete catalogusnaamreferenties zijn mechanisch;
  YAML/configuratiegedrag en publieke contracts zijn ongewijzigd.
- De brede `ProjectRepoResolver` is door `ARC-07` al weg; loader, validators, lookupports en
  runtime-/deploymentsettings blijven smal en deze story introduceert geen `ProjectCatalog`- of
  andere overkoepelende facade of nieuwe dependency.
- Geen nieuwe TODO, compatibilityshim, nieuwe/vervangende suppressie,
  migratie-/overtredingsallowlist of testskip is achtergebleven; het permanente
  composition-root-boundaryregister is exact en niet gegroeid.
- Qualitybaseline en Modulith dependencydiagram vertonen geen regressie.

### Gerichte verificatie

Pas paden aan de actuele gemigreerde code aan, maar voer minimaal equivalent bewijs uit:

```bash
rg -n 'projectKey' softwarefactory/src/main softwarefactory/src/test
rg -n 'YouTrackModels|YouTrack' --glob '!docs/stories/**' .
rg -n 'ProjectRepoResolver|ProjectCatalog' \
  softwarefactory/src/main factory-common/src/main agentworker/src/main dashboard-backend/src/main \
  docs/technical docs/factory README.md projects.yaml.example
git diff --find-renames --stat
mvn clean verify
mvn -pl softwarefactory -am -Dtest=ModulithArchitectureTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
./quality/run.sh
```

Draai daarnaast de gerichte tests van iedere geraakte callchain: projectconfig/catalogus,
orchestratorpolling, tracker/coremodellen, dashboardbridge en modulearchitectuur.

### Verplichte volledige verificatie vóór review

```bash
mvn clean verify
cd dashboard-frontend
flutter pub get
flutter analyze
flutter test
```

Draai ook onvoorwaardelijk alle overige onderdelen van de canonieke volledige repositorygate. Leg
alle resultaten vast op de exacte review-SHA.

### Expliciet buiten scope

- Geen functioneel gedrag, filtering, configuratieschema, database- of wirecontract wijzigen.
- Geen module, port, capability, handler, applicationservice of dependencyrichting ontwerpen.
- Geen grote service verder opsplitsen.
- Geen ontbrekend werk uit plan 01 tot en met 08 in deze cleanup-PR verstoppen.
- Geen historische docs/stories herschrijven buiten het eigen storyworklog.
- Geen baseline, threshold, suppressie of `allowedDependencies` verruimen.

### Reviewer-aandachtspunten

- Gebruik `git diff --find-renames` en controleer dat renames geen logicawijzigingen verbergen.
- Verifieer ieder verwijderd bestand/symbool via productie-, test-, reflection- en
  configurationreferences.
- Controleer dat geen overkoepelende `ProjectCatalog`- of andere godfacade is geïntroduceerd en dat
  iedere catalogusconsumer rechtstreeks de kleinste door `ARC-07` vastgelegde port/settingsvorm
  gebruikt.
- Weiger iedere “kleine verbetering” die niet mechanisch of in het bronplan benoemd is.
- Controleer dat actuele docs en imports volledig zijn bijgewerkt en historische storydocs met rust
  zijn gelaten.

### Tester-aandachtspunten

- Test de uiteindelijke reviewde commit, niet een eerdere rename-SHA.
- Draai na gerichte tests de volledige Maven-, Flutter-, quality-, architectuur-, docs- en
  Dockergates.
- Controleer dat testtellingen niet dalen door onbedoeld hernoemde/onvindbare testklassen.
- Iedere failure, missing/skipped check of toolfout gaat terug naar de developer.
- **Geen enkele falende test mag worden genegeerd**, ongeacht ouderdom of vermeende relatie met de
  cleanup.

### CLN-01-afronding

Merge `CLN-01` pas wanneer story, PR, reviewer en tester dezelfde uiteindelijke SHA noemen en alle
checks groen zijn. Controleer daarna de post-mergechecks op de default-branch-SHA en registreer in
`VOORTGANG.md`: inventarisatietabel, story/branch/PR/commit, renamebewijs, exacte commands,
testtellingen, qualitydelta en eventuele teruggelegde blokkade. Bij een blokkade blijft plan 09
`GEBLOKKEERD`; begin de eindverificatie niet.

## Trajectbrede eindverificatie

Voer deze fase uit in een verse checkout of aantoonbaar schone worktree op de gemergede
default-branch-SHA na `CLN-01`. Noteer de SHA vóór het eerste commando en gebruik gedurende de
controle geen andere commit.

### 1. Schone repository en traceerbaarheid

- Controleer `git status --short`, default branch, remote SHA en laatste post-mergecheck.
- Controleer dat alle werkpakketten in `VOORTGANG.md` storykey, branch/PR, commit en groen bewijs
  bevatten.
- Controleer dat geen actieve trajectbranch of open blokkerende PR ten onrechte als afgerond staat.
- Controleer dat de `MOD-01`-migratieallowlist leeg is; het permanente `ARC-07`
  composition-root-boundaryregister exact en niet gegroeid is; de bekende versioned
  suppressiebaseline één of nul en niet gegroeid is; en geen skip, quarantine, production
  compatibilityfacade, compatibilityshim of verwijder-TODO resteert. Een vervolgstory legaliseert
  geen facade en houdt het traject geblokkeerd.

### 2. Volledige lokale build- en testsuite

Voer vanaf een schone build uit:

```bash
mvn clean verify
cd dashboard-frontend
flutter pub get
flutter analyze
flutter test
```

Voer vervolgens de overige onderdelen van de canonieke volledige repositorygate uit plan 08 uit.
Controleer onverkorte tellingen, exitcodes, de onveranderlijke auditbaseline, actuele ratchet- en
suppressiedelta, bijgewerkte hotspotmatrix, het permanente boundaryregister en een lege diagramdiff.
Vergelijk iedere rij uit de hotspotmatrix met `baselines/quality-cc7cac2.json` via de door QLT-01
geteste symboolmapping. Vul eindpad/symbool, eindwaarde, delta, eigenaarstory en artifactlink in. Een
verdwenen oud pad zonder aantoonbare symboolverwijdering of inhoudelijke daling is geen verbetering.

### 3. Images en runtime-smokes

Voer de in `FIX-03`/`VER-02` vastgelegde canonieke commands uit voor minimaal:

- agent build-stage en volledige `agent:local`-image;
- `assistant:local` boven op het agentimage;
- dashboard-backend-image;
- dashboard-frontend webimage en, waar de bestaande gate dit voorschrijft, APK-build;
- minimale start/CLI-smoke per gebouwd runtime-image.

Gebruik geen lokaal oud image als bewijs. Leg image-id/digest, bron-SHA, command en uitkomst vast.

### 4. Compose-, bridge- en quickstartsmoke

Voer exact de in `FIX-04` gedocumenteerde canonieke quickstart/smoke uit. Controleer minimaal:

- Compose bouwt vanaf een schone checkout;
- backend en frontend worden gezond;
- `/healthz` antwoordt succesvol;
- `/api/v1/status` werkt met de gedocumenteerde authroute;
- de factory maakt met dezelfde bridge-token verbinding;
- de UI/status ziet de bridge als verbonden;
- stop/restart en cleanup gedragen zich volgens de inmiddels geteste operationele contracten.

Plaats geen secrets in het bewijs.

### 5. CI-, merge- en releasebewijs

- Controleer dat de stabiele repository-aggregator verplicht is onder branch protection en groen is
  voor de eind-SHA.
- Controleer dat Maven, Flutter, quality, modulearchitectuur en agent-image-smoke werkelijk
  onderliggende vereisten zijn; missing/skipped mag niet als groen worden geïnterpreteerd.
- Controleer via tests en actuele configuratie dat ieder merge-entrypoint dezelfde projectbewuste
  policy gebruikt, pending wacht en rood/missing fail-closed blokkeert.
- Controleer een recente traceerbare backend- en frontendrelease onder protected `main`: image-SHA,
  manifest-PR, gemergede manifesttag en draaiende/deploystatus. Start geen nieuwe productie-uitrol
  alleen om bewijs te maken wanneer recent bewijs op de huidige code geldig is; ontbreekt geldig
  bewijs, registreer en voer de expliciet geautoriseerde releaseverificatie uit volgens het runbook.

### 6. Duurzaamheid en foutpaden

- Draai de fault-injection-/restarttests voor duurzame agent-completion uit `REL-01`.
- Controleer dat retries usage, events, comments, attachments en screenshots niet dupliceren.
- Draai work-cleanupgrens- en actieve-workspacetests uit `OPS-01`.
- Controleer testerbewijs-loopback uit `VER-01`, inclusief ontbrekende tooling en rode commandexit.
- Controleer bridgecontracten, boolean force-refresh en getypeerde tracker-not-foundregressie.

### 7. Documentatie-audit

Voer de canonieke audit uit `DOC-01` uit en controleer minimaal rootmodules, Modulithmodules,
HTTP-mappings, scheduled jobs, Flywayversies, `SF_*`-configkeys, docs-skeleton en agentinvarianten.
Doorloop de quickstart en testcommands letterlijk. Controleer dat README, runbook,
`docs/factory`, `docs/technical`, Compose en voorbeelden dezelfde actuele werkelijkheid beschrijven.

## Volledige eindcriteria van het hele traject

Het traject mag alleen worden afgesloten wanneer **ieder** criterium hieronder met duurzaam bewijs
in [VOORTGANG.md](VOORTGANG.md) is afgevinkt:

1. Agent- en assistantimages bouwen op een schone checkout en hun minimale runtime-smokes slagen.
2. Image-releases werken onder protected `main`; deploymentmanifesten volgen traceerbaar de
   gebouwde SHA zonder pushbypass.
3. Elk automatisch en handmatig merge-entrypoint gebruikt dezelfde projectbewuste groene-checkpolicy.
4. Pending CI veroorzaakt wachten en opnieuw beoordelen, geen permanente `Error`.
5. Testergoedkeuring bevat machine-verifieerbaar groen bewijs voor de juiste checkout/SHA.
6. Maven, Flutter en image-smoke zijn verplichte PR-verificatie onder één stabiele aggregatorcheck.
7. Agent-completion is na iedere geteste gedeeltelijke fout en procesrestart hervatbaar en
   idempotent, zonder dubbele side-effects.
8. De lokale quickstart is vanaf een schone checkout letterlijk uitvoerbaar.
9. `bridge` en `web` zijn transportadapters van de dashboard application-API en importeren elkaar
   niet.
10. Iedere module-root bevat uitsluitend publieke interfaces/ports en noodzakelijke modulemetadata.
11. Iedere publieke named interface `models` bevat uitsluitend aantoonbaar cross-module gebruikte,
    publieke immutable Kotlin data classes; enum/sealed/value types staan alleen in expliciete
    `types`-interfaces wanneer ze publiek contract zijn; publieke typed exceptions staan alleen in
    expliciete `errors`-interfaces. `types` bevat geen gewone classes/exceptions en `errors` geen
    niet-exceptions of generieke technische implementatiefouten.
12. Concrete implementaties staan in interne subpackages en worden niet cross-module geïmporteerd.
13. De in het bronplan genoemde god classes zijn opgesplitst zonder vervangende facade met dezelfde
    breedte of veranderredenen.
14. Trackerinterfaces zijn capabilitygericht en bevatten geen stille no-opdefaults of onverwachte
    `UnsupportedOperationException`-fallbacks.
15. Supplier-neutrale agentlogica heeft neutrale namen en één gedeelde implementatie; suppliers
    bevatten alleen hun eigen command-, credential- en streamgedrag.
16. Actuele documentatie wordt automatisch op inventarisdrift gecontroleerd en de audit is groen.
17. Detekt/quality rapporteert geen nieuwe schuld en meet alle Kotlin-mainmodules; de versioned
    suppressiebaseline is vanaf de bekende ene suppressie alleen gelijk gebleven of naar nul
    gedaald; de per-hotspot voor/na-matrix bewijst afname ten opzichte van auditcommit `cc7cac2` en
    een rename telt niet als afname van ongewijzigde schuld.
18. Expliciete Modulith `allowedDependencies`, named interfaces en het gegenereerde
    dependencydiagram zijn onderling consistent en groen; de `MOD-01`-migratieallowlist is leeg en
    het permanente `ARC-07` composition-root-boundaryregister is exact en niet gegroeid.
19. `mvn clean verify`, `flutter analyze`, `flutter test`, alle canonieke Docker-smokes en de
    documentatie-audit zijn groen op de gemergede eind-SHA.
20. Ieder werkpakket en iedere verplichte modulestory heeft een eigen Factory-story en traceerbare
    gemergede PR/commit.
21. Developer, reviewer en tester hebben de uiteindelijke relevante commits expliciet goedgekeurd
    op basis van vers groen bewijs.
22. Code, actuele documentatie, migraties, workflows en deploymentconfiguratie spreken elkaar niet
    tegen.
23. Er staat geen open blokkade, geaccepteerde/ongecontroleerde uitzondering, bypass, tijdelijke
    migratieallowlist, quarantined test, production compatibilityfacade of compatibilityshim. Een
    bestaande verwijderstory houdt het traject `GEBLOKKEERD` totdat de verwijdering gemerged en de
    volledige gate groen is.
24. **Geen enkele falende test is genegeerd** — niet als pre-existing, ongerelateerd, flaky,
    omgevingsgebonden of “alleen lokaal”.

Een criterium is niet voldaan door alleen een chatbericht, lokale ongepushte wijziging, oud groen
testrapport of een gerichte test zonder volledige gate.

## Expliciet buiten scope van plan 09

- Geen nieuwe architectuur-, module-, API-, persistence-, configuratie- of deploymentkeuze maken.
- Geen ontbrekend eerder werk in `CLN-01` combineren.
- Geen productiewijziging uitvoeren om een eindcriterium achteraf anders te definiëren.
- Geen test, check, rule, baseline, dependency of acceptatiecriterium afzwakken.
- Geen nieuwe feature, UI-wijziging of functioneel gedrag toevoegen.
- Geen productie-uitrol starten zonder de bestaande autorisatie en het runbook; verificatie verruimt
  de bevoegdheid niet.

## Planbrede reviewer- en tester-aandachtspunten

### Reviewer

- Controleer dat de cleanupdiff zuiver mechanisch is en dat ontbrekend eerder werk naar de juiste
  story teruggaat.
- Audit het eindbewijs op SHA-consistentie: lokale tests, CI, images, manifesten, diagram en docs
  moeten naar de juiste gemergede toestand verwijzen.
- Controleer dat geen baseline-, migratieallowlist- of docsaanpassing een regressie alleen
  administratief legaliseert en dat het permanente boundaryregister niet als escape hatch dient.
- Beoordeel ieder eindcriterium afzonderlijk; een grotendeels groen traject is niet afgerond.

### Tester

- Werk op een verse checkout/schone worktree en voer commands zelf uit.
- Controleer testtellingen, artifacts, image-digests, externe runstatussen en zichtbare runtime-smokes.
- Herhaal na iedere reparatiestory de volledige eindverificatie; hergebruik geen oud resultaat.
- Missing, skipped, cancelled, queued en `in_progress` zijn geen groen bewijs.
- **Geen enkele falende test mag worden genegeerd.**

## Plan-afronding en definitieve overdracht

Vul pas na volledige goedkeuring onder `Eindbewijs` in [VOORTGANG.md](VOORTGANG.md) minimaal in:

- gemergede eind-SHA en default branch;
- `mvn clean verify` met tellingen en exitcode;
- `flutter analyze` en `flutter test` met tellingen en exitcodes;
- onveranderlijke auditbaseline `cc7cac2`, actuele ratchetbaseline, volledige per-hotspot
  voor/na-matrix, suppressiedelta één→één of één→nul en qualityartifactlink;
- Modulith-verificatie, dependency-matrix en reproduceerbaar diagram;
- agent-/assistant- en dashboardimage-digests plus smoke-uitkomsten;
- Compose-/bridge-/quickstartsmoke;
- documentatie-audit;
- branch-protection-/aggregatorstatus en relevante release-/manifestbewijzen;
- fault-injection-, testerbewijs- en cleanupregressies;
- bewijs dat de `MOD-01`-migratieallowlist leeg is en het permanente `ARC-07`
  composition-root-boundaryregister exact en niet gegroeid is;
- reviewer- en testerakkoord op hun exacte approval-SHA's;
- expliciet `geen open blokkades, geen production compatibilityfacades en geen geaccepteerde
  uitzonderingen`.

Zet daarna pas `CLN-01`, plan 09, `Afgeronde plannen` en het gehele traject op `AFGEROND`. De
definitieve overdracht bestaat uit de gemergede eind-SHA, `VOORTGANG.md` en links naar duurzaam
CI-/releasebewijs. Een chatverslag alleen is geen overdracht.

Wanneer één criterium rood of onbewezen is, blijft het traject `GEBLOKKEERD` of `BEZIG`; registreer
de exacte eigenaar en eerstvolgende actie. Sluit het traject nooit met de formulering “alles groen
behalve …”.
