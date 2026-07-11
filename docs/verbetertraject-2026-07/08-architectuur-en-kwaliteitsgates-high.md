# Plan 08 — Architectuur- en kwaliteitsgates

| Metadata | Waarde |
| --- | --- |
| Status | `NIET GESTART` |
| Werkpakketten | `QLT-01`, daarna `ARC-08` |
| Aanbevolen model | GPT-5.6 Sol |
| Effort | High |
| Waarom dit niveau | Dit plan zet de na zeven plannen bereikte structuur om in blijvend, repositorybreed beleid. Een te grove baseline of te ruime dependencyallowlist geeft schijnzekerheid; een te strenge gate blokkeert iedere volgende wijziging. De gate moet daarom aantoonbaar juist, fail-closed en onderhoudbaar zijn. |
| Verplichte voorganger | Plan 07 volledig `AFGEROND` en gemerged |
| Opvolger | [Plan 09](09-cleanup-en-eindverificatie-light.md) |
| Bronnen | [Bronplan](../verbeterplan-onderhoudbaarheid-2026-07.md), [uitvoerregels](UITVOERREGELS.md), [voortgang](VOORTGANG.md) |

## Doel en vaste volgorde

Dit plan voert precies twee afzonderlijke Factory-stories uit:

1. `QLT-01`: maak kwaliteitsmeting een echte regressiegate;
2. `ARC-08`: leg daarna de bedoelde Modulith-afhankelijkheden expliciet vast en genereer het
   dependencydiagram.

De volgorde is bindend. `ARC-08` moet zelf door de nieuwe qualitygate van `QLT-01` lopen. Combineer
beide pakketten niet in één branch of PR.

## Prerequisites

Begin niet voordat alle onderstaande punten aantoonbaar waar zijn:

- [ ] In [VOORTGANG.md](VOORTGANG.md) staan plannen 01 tot en met 07 op `AFGEROND`.
- [ ] Alle negen modulemigratiestories van plan 07 zijn gemerged en post-merge groen.
- [ ] De tijdelijke `MOD-01`-migratieallowlist is repositorybreed aantoonbaar **volledig leeg**.
      Iedere resterende regel blokkeert plan 08 en gaat terug naar haar eigenaarplan; er bestaat
      geen verklaarde restcategorie.
- [ ] Het permanente `ARC-07` composition-root-boundaryregister heeft een versioned pad, exacte
      entries en een groene sourcecheck. Dit register blijft bestaan, is geen overtredingsallowlist
      en mag niet groeien.
- [ ] De onveranderlijke trajectnulmeting
      [`baselines/quality-cc7cac2.json`](baselines/quality-cc7cac2.json) en de ingevulde
      per-hotspotmatrix in [VOORTGANG.md](VOORTGANG.md) zijn aanwezig.
- [ ] `ARC-01` heeft `web` en `bridge` van de dashboard application-API afhankelijk gemaakt.
- [ ] `ARC-06` heeft de definitieve Mavenmodulegrenzen en gedeelde parent-POM opgeleverd.
- [ ] De actuele default branch is schoon en `mvn clean verify` is groen.
- [ ] `flutter analyze`, `flutter test`, de image-smokes en documentatie-audit uit eerdere plannen
      zijn groen op dezelfde of een aantoonbaar equivalente gemergede SHA.

Wanneer de feitelijke modulelijst of named interfaces niet overeenkomen met de overdracht van plan
07, registreer dat als blokkade en herstel plan 07. Gebruik `ARC-08` niet om onafgemaakte
modulemigraties te verbergen in ruime `allowedDependencies`.

## Kopieerbare startopdracht

```text
Voer plan 08 volledig autonoom en in de voorgeschreven volgorde uit volgens
docs/verbetertraject-2026-07/08-architectuur-en-kwaliteitsgates-high.md.

Lees vóór iedere wijziging ook volledig:
- docs/verbetertraject-2026-07/UITVOERREGELS.md
- docs/verbetertraject-2026-07/VOORTGANG.md
- docs/verbeterplan-onderhoudbaarheid-2026-07.md
- de actuele MOD-01-moduleconventie en architectuurtest
- de overdracht en definitieve modulelijst van plan 07

Controleer dat plan 07 volledig gemerged en groen is. Maak eerst één Factory-story, branch en PR
voor QLT-01. Bouw een fail-closed kwaliteitsratchet voor alle Kotlin-mainmodules, los de vastgelegde
Kotlincompilerwarning op, publiceer rapporten en bewijs met negatieve tests dat nieuwe structurele
schuld en suppressies CI rood maken. Houd de bekende suppressiebaseline versioned: auditcommit
cc7cac2 bevat exact één productie-suppressie; de actuele telling mag alleen één of nul zijn en nooit
groeien. Bewijs ook dat file-, package- en symboolrenames dezelfde bestaande finding neutraal
meenemen zonder findingruil te legaliseren. Merge QLT-01 pas na reviewer-, tester- en
post-mergebewijs.

Maak daarna een aparte Factory-story voor ARC-08. Leg per definitieve Modulith-module expliciete
allowedDependencies vast op alleen root-API's of named interfaces en genereer een deterministisch
dependencydiagram. Neem nooit simpelweg alle bestaande imports op als toegestane architectuur.
Bewijs met negatieve architectuurtests dat een verboden dependency faalt.

Geen enkele falende test mag worden genegeerd, ook niet als deze pre-existing, flaky,
omgevingsgebonden of ogenschijnlijk ongerelateerd is. Gebruik geen baselinegroei, nieuwe/vervangende
suppressie, migratie-/overtredingsallowlist, skip of `|| true` om groen te worden; het permanente
composition-root-boundaryregister blijft juist bestaan. Werk VOORTGANG.md bij na iedere
overdracht, push, PR, merge en post-mergeverificatie en ga autonoom door tot beide stories
aantoonbaar afgerond zijn.
```

## Canonieke volledige repositorygate vanaf plan 08

Vanaf de eerste QLT-01-baseline voert developer, reviewer én tester na de gerichte tests
onvoorwaardelijk dezelfde volledige gate uit op hun exacte SHA:

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

Voer daarnaast zonder impactsafweging de canonieke `VER-02` repository-aggregator, de door
`FIX-03`/`VER-02` vastgelegde agent-, assistant- en dashboardimagebuilds met runtime-smokes, de
`DOC-01`-documentatie-audit, de Modulitharchitectuurgate en de qualityartifactcontrole uit. Een
ontbrekend lokaal hulpmiddel of externe CI-run is een blokkade, geen reden om een onderdeel over te
slaan. Herhaal de volledige gate na iedere reviewfix en op de gemergede default-branch-SHA.

## Verplichte storywerkwijze

Voor beide stories gelden naast [UITVOERREGELS.md](UITVOERREGELS.md) de volgende stappen:

1. Maak de Factory-story vóór de eerste wijziging en registreer storykey, branch en status in
   [VOORTGANG.md](VOORTGANG.md).
2. Start vanaf de actuele groene default branch en leg vast:

   ```bash
   git status --short
   git log -1 --oneline
   mvn clean verify
   ./quality/run.sh
   cd dashboard-frontend && flutter pub get && flutter analyze && flutter test
   ```

3. Sla command, exitcode, datum/tijd, testtellingen en rapportlocaties in story/worklog op.
4. Voeg vóór productie-integratie geautomatiseerde positieve én negatieve tests van de nieuwe gate
   toe. Een handmatige observatie dat een script “lijkt te werken” is onvoldoende.
5. Laat developer, reviewer en tester de uiteindelijke commit controleren. Na iedere reviewfix volgt
   opnieuw de gerichte test en de onvoorwaardelijke canonieke volledige repositorygate hierboven.
6. Merge zonder bypass, controleer de gemergede SHA en werk `VOORTGANG.md` bij voordat het volgende
   werkpakket start.

**Harde regel:** geen enkele falende unit-, integratie-, e2e-, contract-, Flutter-, Docker-,
documentatie- of smoketest mag worden genegeerd of als “pre-existing” worden geaccepteerd.

## QLT-01 — Maak kwaliteitsmeting een echte regressiegate

### Storytitel

`Repositorybrede quality-ratchet zonder nieuwe schuld of nieuwe suppressies`

### Probleem en bewijs

Op de broncommit:

- mat [quality/run.sh](../../quality/run.sh) alleen `softwarefactory`-maincode;
- ving het script de Detekt-exitcode af met `|| true`;
- stond in [quality/detekt.yml](../../quality/detekt.yml) een praktisch onbeperkte `maxIssues`;
- leefde het resultaat alleen onder het gitignored `qualityrun/`;
- draaide het Detektprofiel uitsluitend vanuit de `softwarefactory`-POM;
- is de onveranderlijke uitgangsmeting inmiddels versioned vastgelegd in
  [`baselines/quality-cc7cac2.json`](baselines/quality-cc7cac2.json): score 354, 353 findings en
  exact één productie-suppressie (`BridgeRequestHandler.kt`, `@Suppress("unused")`), met veel
  bestaande `MaxLineLength`/`MagicNumber`-ruis;
- waarschuwde de Kotlincompiler bij de arrayconstructie in de toenmalige
  `PostgresTrackerClient.kt:383` voor intersection-type-inferentie die in een toekomstige
  Kotlinversie een compileerfout wordt.

Meet aan het begin van deze story ook de actuele gemergede plan-07-SHA. Dat wordt de technische
ratchetbaseline voor toekomstige PR's, maar vervangt de oorspronkelijke trajectnulmeting niet. De
actuele suppressietelling moet nul of één zijn: één alleen wanneer exact de bekende suppressie nog
bestaat, nul wanneer een eerdere story haar veilig verwijderde. Een andere of tweede suppressie is
reeds een regressie en blokkeert QLT-01.

### Gewenst gatecontract

De implementatie moet minimaal de volgende eigenschappen hebben:

1. **Volledige scope:** alle Mavenmodules met Kotlinproductiecode uit de actuele rootreactor worden
   gemeten. Nieuwe Kotlinmodule toevoegen zonder qualitymeting maakt de gate rood.
2. **Twee versioned meetpunten:** de auditbaseline `quality-cc7cac2.json` blijft onveranderlijk voor
   trajectvergelijking. Daarnaast staat een machineleesbare ratchetbaseline voor de gemergede
   plan-07-SHA in Git, met schemaversie,
   modulenamen, rule-id's en stabiele findingidentiteit. Een uitsluitend totaalaantal is
   onvoldoende, omdat één opgeloste finding anders ongemerkt voor één nieuwe kan worden ingeruild.
3. **Structurele ratchet:** nieuwe of verergerde bevindingen op minimaal
   `CyclomaticComplexMethod`, `CognitiveComplexMethod`, `LongMethod`, `LargeClass`,
   `TooManyFunctions`, `NestedBlockDepth`, `LongParameterList`, `ComplexCondition`, `ReturnCount`
   en `ThrowsCount` blokkeren CI. Bestaande stijlruis zoals `MaxLineLength` en `MagicNumber` blijft
   zichtbaar in het rapport, maar hoeft niet in één keer opgelost te worden.
4. **Suppressieratchet:** de versioned bekende productiebaseline is één op `cc7cac2` en mag alleen
   dalen. Iedere nieuwe of vervangende `@Suppress`, `@SuppressWarnings`, Detekt-disable of
   vergelijkbare uitschakeling maakt de gate rood, ook wanneer het totaalaantal één blijft. Als de
   bekende suppressie veilig wordt verwijderd, wordt nul de nieuwe bovengrens.
5. **Geen administratief groen:** ontbrekende bronmodule, ontbrekend/ongeldig rapport,
   Detekt-crash, comparatorfout of baselineparsefout geeft non-zero exit. Het productiepad bevat
   geen `|| true` of equivalent.
6. **Geen baselinegroei in feature-PR's:** de ratchetbaseline mag alleen gelijk blijven of krimpen.
   Automatisch “baseline opnieuw genereren” na een regressie is verboden.
7. **Rename-neutraal maar niet ruilbaar:** een zuivere file-, package- of symboolrename draagt
   dezelfde finding via stabiele symboolmapping neutraal over. De comparator mag een opgeloste
   finding en een inhoudelijk andere finding nooit als rename koppelen, ook niet bij gelijke
   rule-id of gelijk totaal. Ambigue mapping faalt gesloten en vraagt review.
8. **Bruikbare uitvoer:** lokaal en in CI toont de gate per module nieuwe, opgeloste en resterende
   findings, suppressiedelta en een eindstatus. CI publiceert machine- en mensleesbare rapporten ook
   bij falen.
9. **Flutter:** `flutter analyze` blijft een afzonderlijke verplichte job uit `VER-02`; de
   aggregatorcheck maakt pas groen wanneer Kotlinquality én Flutteranalyse groen zijn.

Gebruik bij voorkeur de officiële Detektbaseline wanneer die aan stabiele findingidentiteit en
multimodule-uitvoer voldoet. Een eigen comparator is alleen aanvaardbaar met een versioned schema,
deterministische sortering en uitgebreide fixtures. Vergelijk nooit alleen regelnummers of één
aggregaatscore.

### Concrete stappen

1. Inventariseer de actuele root-POM-modules en alle `src/main/kotlin`-roots na `ARC-06`. Noteer de
   verwachte modulelijst in een testfixture of laat die direct uit de reactor afleiden.
2. Kies één canonieke, versiebeheerste ratchetbaselinevorm en documenteer hoe findingidentiteit,
   file-/package-/symbool- en moduleverplaatsing, ambigue mapping en baselinekrimp werken. Behoud de
   auditbaseline ongewijzigd als apart meetpunt.
3. Centraliseer pluginversie/configuratie in de gedeelde parent-POM; vermijd per-module
   afwijkende Detektregels.
4. Maak `quality/run.sh` een dunne, fail-closed ingang voor dezelfde logica die CI gebruikt. Behoud
   lokale timestamped rapportage alleen als afgeleide output, niet als bron van waarheid.
5. Voeg comparatortests/fixtures toe voor: identieke baseline, minder findings, nieuwe finding,
   findingruil met gelijk totaal, bekende suppressie één→één en één→nul, vervangende/nieuwe
   suppressie, ontbrekende module, ontbrekend rapport, beschadigde baseline en toolcrash. Voeg
   afzonderlijke positieve fixtures toe voor een zuivere file-, package- en symboolrename en
   negatieve fixtures voor een lookalike-rename die inhoudelijk een findingruil is en voor ambigue
   symbolmapping.
6. Genereer de ratchetbaseline uitsluitend op de groene plan-07-SHA; laat reviewer de inhoud,
   modulenamen, suppressie-identiteit en relatie met `quality-cc7cac2.json` controleren voordat deze
   wordt gecommit. Zij bevat nul of exact de ene bekende suppressie, nooit een andere/tweede.
7. Los de actuele intersection-typecompilerwarning in `PostgresTrackerClient` getypeerd op en voeg
   waar zinvol een regressietest toe. Verberg de warning niet met compilerflags.
8. Integreer de qualitycheck in de stabiele repository-aggregator uit `VER-02`; upload rapporten via
   een `always()`-stap zonder de faalstatus te neutraliseren.
9. Werk developmentdocs, technische kwaliteitsdocumentatie en agentinstructies bij met exact
   dezelfde lokale en CI-commands.

### Acceptatiecriteria

- Alle actuele Kotlin-mainmodules staan aantoonbaar onder dezelfde qualitygate.
- Een kunstmatig toegevoegde complexe methode maakt de gerichte negatieve test en CI rood.
- Eén bestaande finding oplossen en elders één nieuwe toevoegen blijft rood ondanks gelijk totaal.
- Een zuivere file-, package- of symboolrename blijft neutraal; een findingruil of ambigue mapping
  blijft rood.
- De bekende suppressie blijft versioned één of daalt veilig naar nul; een nieuwe of vervangende
  suppressie maakt de gate rood.
- Een ontbrekend rapport of een Detekt-uitvoeringsfout maakt de gate rood.
- Een echte finding verwijderen laat de baseline/rapportage aantoonbaar krimpen zonder handmatige
  scoretruc.
- De Kotlincompilerwarning in `PostgresTrackerClient` is weg bij een schone compile.
- CI publiceert per-module rapport en delta bij groen én rood.
- De volledige bestaande suite en canonieke repositorygate blijven groen; de ratchetbaseline is
  niet groter dan de gemeten plan-07-uitgangstoestand en de auditbaseline is ongewijzigd.

### Gerichte verificatie

Voer de door deze story toegevoegde comparator-/plugintests uit en bewijs daarnaast in een tijdelijke
testfixture — niet door rode testcode te committen — minimaal de negatieve scenario's “nieuwe
finding”, “nieuwe/vervangende suppressie”, “findingruil”, “ambigue rename” en “ontbrekend rapport”,
plus de drie positieve neutrale renamefixtures. Voer vervolgens de canonieke volledige
repositorygate uit; minimaal:

```bash
./quality/run.sh
mvn clean compile
mvn clean verify
cd dashboard-frontend
flutter pub get
flutter analyze
flutter test
```

Controleer in CI dat de qualityjob onderdeel is van de verplichte aggregator en download het
gepubliceerde rapport om te bewijzen dat het artifact leesbaar is.

### Expliciet buiten scope

- Niet alle historische stijl- en complexiteitsbevindingen in deze story oplossen.
- Geen thresholds verhogen, rules uitschakelen of baseline regenereren om een nieuwe finding te
  accepteren.
- Geen module-/SRP-refactor uitvoeren; plan 07 en eerdere ARC-stories moeten al af zijn.
- Geen andere analyzer voor Flutter introduceren; de bestaande `flutter analyze` blijft leidend.

### Reviewer- en tester-aandachtspunten

**Reviewer:** controleer vooral op aggregaatscoretrucs, instabiele line-numberfingerprints,
onbemeten nieuwe modules, `continue-on-error`, `|| true`, workflowstappen die artifacts wel
publiceren maar de job groen maken, en baselinebestanden die nieuwe schuld opnemen.

**Tester:** draai de positieve en negatieve fixtures onafhankelijk, controleer exitcodes en
rapportinhoud, voer een schone compile uit voor de compilerwarning en draai daarna alle volledige
gates. Geen enkele failure mag worden genegeerd.

### Story-afronding en overdracht aan ARC-08

Merge pas wanneer de gemergede default-branch-SHA de nieuwe qualitygate zelf groen doorloopt.
Registreer met de vaste storyoverdracht in `VOORTGANG.md`: story/PR/commit, onveranderlijke
auditbaseline, ratchetbaselineformat/-pad, gemeten modules, blocking-rulelijst, rename-mapping,
suppressie-identiteit/telling, bijgewerkte hotspotmatrix, artifactlinks en volledige testresultaten
op de approval-SHA's. ARC-08 start niet wanneer de qualitygate alleen lokaal werkt, niet verplicht
is in CI of de voor/na-matrix niet duurzaam herleidbaar is.

## ARC-08 — Leg toegestane Modulith-afhankelijkheden expliciet vast

### Storytitel

`Expliciete Modulith dependencyrichtingen en actueel dependencydiagram`

### Probleem en bewijs

De bestaande `ApplicationModules.verify()` in
`softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/ModulithArchitectureTest.kt` bewaakt cycli
en imports uit interne packages, maar legt zonder modulemetadata niet iedere bedoelde
dependencyrichting vast. Na plan 07 zijn roots en named interfaces opgeschoond; zonder expliciete
`allowedDependencies` kan een later geldig-publiek maar architectonisch ongewenst import alsnog
sluipen.

De beoogde richting uit het bronplan is bindend:

```text
transportadapters (web, bridge, Telegram-transport)
    -> application-API's
    -> domein en ports

infrastructuur -> implementeert ports
transportmodule -X-> andere transportmodule
```

`bridge` mag dus niet van `web` afhangen; beide gebruiken de dashboard application-API uit
`ARC-01`. Een concreet intern package wordt nooit toegestaan alleen omdat het nu geïmporteerd
wordt.

### Concrete stappen

1. Laat Spring Modulith op de actuele gemergede code de werkelijke modules, named interfaces en
   dependencies inventariseren. Vergelijk dit met de overdracht van plan 07 en actuele
   moduledocumentatie.
2. Maak vóór wijziging een reviewbare matrix met voor iedere module:
   - publieke verantwoordelijkheid;
   - toegestane afhankelijke modules/named interfaces;
   - motivatie per dependency;
   - verboden transport-naar-transport- en internaldependencies.
3. Beoordeel iedere actuele edge. Voeg een edge niet automatisch toe aan de allowlist. Een edge die
   de beoogde richting schendt wordt via de bestaande publieke port rechtgezet of als blokkerende
   voorganger teruggelegd; maak geen wildcard om verder te kunnen.
4. Voeg per module expliciete Spring-Modulith-metadata met `allowedDependencies` toe. Verwijs waar
   van toepassing naar named interfaces (`module :: models`, `module :: types`, `module :: errors`
   of de actuele ondersteunde syntaxis), niet naar een heel implementatiepackage.
5. Sta voor een module zonder uitgaande dependency expliciet geen dependencies toe. Gebruik geen
   `*`, catch-all of tijdelijke groeiallowlist.
6. Breid de architectuurtest uit zodat hij minimaal controleert:
   - iedere productiemodule heeft expliciete dependencymetadata;
   - alle genoemde modules/named interfaces bestaan;
   - cross-module-imports raken alleen root-API's of named interfaces;
   - `web`, `bridge` en Telegram-transport importeren elkaar niet;
   - dependencycycli en internals blijven verboden;
   - de publieke `models`-data-class-only-regel uit `MOD-01` blijft actief;
   - `types` uitsluitend publieke enum/sealed/value-contracten bevat;
   - `errors` uitsluitend aantoonbaar cross-module publieke typed exceptions bevat en generieke
     technische exceptions intern houdt.
7. Voeg gerichte negatieve fixtures toe die een verboden adapterdependency, een internalimport,
   een niet-bestaande named interface, een niet-data class in `models`, een gewone/exceptionclass in
   `types` en een niet-exception of generieke technische exception in `errors` laten falen.
8. Genereer met de Spring-Modulith-documentatiefaciliteit of een gelijkwaardig deterministisch
   projectscript een dependencydiagram en modulecanvas onder `docs/technical/`. Commit de bronvorm
   en, indien bestaande docs dat vereisen, de reproduceerbare render; neem geen handmatig getekende
   waarheid naast de code op.
9. Voeg een driftcheck toe: opnieuw genereren op een ongewijzigde checkout geeft geen diff, en een
   dependencywijziging zonder metadata/docs maakt CI rood.
10. Werk `docs/technical/modules.md`, de technische spec en onboarding bij met de definitieve matrix,
    named interfaces en het generatiecommando.

### Acceptatiecriteria

- Iedere actuele Spring-Modulith-productiemodule declareert expliciete toegestane dependencies.
- Iedere toegestane edge heeft een gedocumenteerde reden en wijst alleen naar een publieke root-API
  of named interface (`models`, `types` of `errors`).
- Geen wildcard, catch-all of tijdelijke architecture-bypass bestaat.
- `bridge` importeert niets uit `web`; transportmodules importeren elkaar niet.
- Een kunstmatige verboden dependency, internalimport en foutieve named interface laten de
  architectuurtest falen.
- De bestaande cycle-/internal- en data-class-only-controles blijven groen en actief.
- De exclusieve `types`- en `errors`-regels zijn positief en negatief getest.
- Het dependencydiagram wordt deterministisch uit de code gegenereerd en stemt overeen met de
  expliciete matrix.
- De nieuwe `QLT-01`-gate rapporteert geen regressie door deze story.

### Gerichte verificatie

```bash
mvn -pl softwarefactory -am -Dtest=ModulithArchitectureTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
./quality/run.sh
# Draai het in deze story vastgelegde diagram-/driftcommando tweemaal;
# de tweede run moet een lege git diff opleveren.
git diff --exit-code -- docs/technical
```

Voer de negatieve architectuurfixtures afzonderlijk uit en leg vast welke verwachte regel de
failure veroorzaakte. Laat geen bewust falende fixture in de normale groene suite achter.

### Verplichte volledige verificatie

Voer onvoorwaardelijk de canonieke volledige repositorygate uit dit plan uit, inclusief
`mvn clean verify`, Flutter met `flutter pub get`, alle image-/runtime-smokes,
documentatie-audit, repository-aggregator en qualityartifactcontrole. Een gerichte groene
architectuurtest vervangt geen enkel onderdeel.

### Expliciet buiten scope

- Geen nieuwe module of applicationlaag ontwerpen.
- Geen onafgemaakte plan-07-migratie alsnog met een dependencyallowlist accepteren.
- Geen bedrijfslogica, transportcontract, databaseschema of UI-gedrag wijzigen.
- Geen dependencies toestaan op basis van “de code compileert nu zo” zonder architectuurreden.
- Geen handmatig diagram onderhouden dat van de gegenereerde codewaarheid kan afwijken.

### Reviewer- en tester-aandachtspunten

**Reviewer:** beoordeel de dependency-matrix edge voor edge; controleer vooral omgekeerde
application/infrastructureafhankelijkheden, transportkoppelingen, hele-moduletoegang waar een named
interface volstaat en metadata die alleen de huidige overtreding legaliseert.

**Tester:** draai positieve en negatieve architectuurtests, hergenereer het diagram tweemaal,
controleer een lege tweede diff en voer vervolgens de volledige repositorygate uit. Een falende
test of driftcheck is altijd blocker en mag niet worden genegeerd.

## Plan-afronding en overdracht

Plan 08 krijgt pas status `AFGEROND` wanneer:

- `QLT-01` en `ARC-08` elk een eigen gemergede story, branch, PR, reviewer- en testerbewijs hebben;
- de gemergede default-branch-SHA de qualitygate, architectuurtest, `mvn clean verify`, Fluttergates,
  Docker-smokes en documentatie-audit groen doorloopt;
- de ratchetbaseline niet is gegroeid, de bekende suppressiebaseline één of veilig nul is, geen
  nieuwe/vervangende suppressie bestaat en alle Kotlin-mainmodules gemeten worden;
- de onveranderlijke auditbaseline en actuele hotspotmatrix een traceerbare voor/na-relatie hebben;
- de `MOD-01`-migratieallowlist exact leeg is en het permanente `ARC-07`
  composition-root-boundaryregister versioned, exact en niet gegroeid is;
- iedere Modulith-module expliciete, gemotiveerde dependencies heeft zonder wildcard/bypass;
- het gegenereerde dependencydiagram reproduceerbaar en actueel is;
- geen enkele falende test, check of toolfout als pre-existing is genegeerd;
- [VOORTGANG.md](VOORTGANG.md) storykeys, PR's, commits, baseline-/artifactlinks,
  dependency-matrix/diagram en post-mergebewijs bevat.

Draag aan plan 09 over:

- de gemergede eind-SHA van plan 08;
- het exacte lokale/CI-commando voor de qualitygate;
- baselinepad, schemaversie, gemeten modules en actuele delta;
- auditbaselinepad/commit, actuele suppressietelling en bijgewerkte hotspotmatrix;
- het exacte architectuur- en diagramgeneratiecommando;
- de definitieve moduledependency-matrix en link naar het diagram;
- bevestiging dat de `MOD-01`-migratieallowlist leeg is, het `ARC-07`
  composition-root-boundaryregister exact en niet gegroeid is, de bekende suppressiebaseline niet
  is gegroeid, geen gate is geskipt en geen architectuurblokkade openstaat.

Plan 09 mag deze gates alleen uitvoeren en controleren; het mag hun architectuur of beleid niet
opnieuw ontwerpen.
