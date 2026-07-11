# Plan 07 — Modulemigraties

| Metadata | Waarde |
| --- | --- |
| Status | `NIET GESTART` |
| Werkpakketten | `MOD-02`, `MOD-03` |
| Aanbevolen model | GPT-5.6 Sol |
| Effort | Medium per mechanische modulestory; twee verplichte afzonderlijke High-designholdpoints |
| Waarom dit niveau | Package-, visibility-, named-interface- en importmigraties zijn per module mechanisch uitvoerbaar. Het nog noodzakelijke Telegram-API-design en de core-contractclassificatie mogen echter niet door een Medium-agent worden geïmproviseerd en krijgen daarom ieder vooraf een nieuwe GPT-5.6 Sol High Codex-taak met reviewholdpoint. |
| Verplichte voorganger | Plan 06 volledig `AFGEROND` en gemerged |
| Opvolger | [Plan 08](08-architectuur-en-kwaliteitsgates-high.md) |
| Bronnen | [Bronplan](../verbeterplan-onderhoudbaarheid-2026-07.md), [uitvoerregels](UITVOERREGELS.md), [voortgang](VOORTGANG.md) |

## Doel en begrenzing

Dit plan brengt eerst `telegram` en daarna iedere resterende Spring-Modulith-module afzonderlijk
onder de in `MOD-01` ingevoerde moduleconventie. Het plan verandert geen functioneel gedrag,
wirecontract, database-schema, pollingcadans of productbesluit. Het verplaatst en classificeert
bestaande typen, maakt publieke contractgrenzen zichtbaar en verwijdert per module de tijdelijke
`MOD-01`-migratieallowlist.

De mechanische uitvoering blijft Medium. Twee beslismomenten zijn nadrukkelijk **geen onderdeel van
een Medium-uitvoering**: vóór Telegram wordt de publieke capabilityset ontworpen, en vóór core
wordt ieder publiek contracttype geclassificeerd. Beide gebeuren als nieuwe High Codex-taak op de
al gemaakte module-storybranch, worden duurzaam gepusht en door een reviewer op een concrete SHA
goedgekeurd voordat een nieuwe Medium Codex-taak de mechanische migratie uitvoert. Het blijven negen
Factory-stories; alleen de story-uitvoering wordt bij Telegram en core over twee Codex-taken
verdeeld.

`MOD-03` is administratief één werkpakket, maar wordt **verplicht als één Factory-story per module**
uitgevoerd. Combineer geen twee modules in één branch of PR. De story voor `web` / `bridge` is een
nacontrole van de door `ARC-01` gerealiseerde architectuur en geen nieuwe bridgerefactor.

## Prerequisites

Begin niet voordat alle onderstaande punten aantoonbaar waar zijn:

- [ ] In [VOORTGANG.md](VOORTGANG.md) staan plannen 01 tot en met 06 op `AFGEROND`.
- [ ] `MOD-01`, `ARC-01`, `ARC-03`, `ARC-04`, `ARC-06` en `ARC-07` zijn gemerged en hun
      post-mergechecks zijn groen.
- [ ] De actuele moduleconventie, named-interface-regels en tijdelijke migratieallowlist uit `MOD-01` zijn
      in code en actuele technische documentatie terug te vinden.
- [ ] De oorspronkelijke qualitynulmeting
      [`baselines/quality-cc7cac2.json`](baselines/quality-cc7cac2.json) en de hotspotmatrix in
      [VOORTGANG.md](VOORTGANG.md) zijn aanwezig.
- [ ] De default branch is bijgewerkt en `git status --short` bevat geen onbekende wijzigingen.
- [ ] Een schone baseline `mvn clean verify` is groen.
- [ ] `./quality/run.sh` heeft een leesbare voor-meting opgeleverd.

Als een inhoudelijke voorganger niet werkelijk af is, zet plan 07 op `GEBLOKKEERD` met het ontbrekende
werk en hervat de betreffende voorganger. Repareer die architectuur niet stilletjes in een
modulemigratiestory.

## Kopieerbare startopdracht

```text
Voer plan 07 volledig autonoom en strikt sequentieel uit volgens
docs/verbetertraject-2026-07/07-modulemigraties-medium.md.

Lees vóór iedere wijziging ook volledig:
- docs/verbetertraject-2026-07/UITVOERREGELS.md
- docs/verbetertraject-2026-07/VOORTGANG.md
- docs/verbeterplan-onderhoudbaarheid-2026-07.md
- de door MOD-01 vastgelegde moduleconventie en architectuurtest

Controleer eerst dat plan 06 en alle genoemde inhoudelijke prerequisites gemerged en groen zijn.
Maak daarna precies één Factory-story per stap: MOD-02 telegram, vervolgens MOD-03 knowledge,
runtime, config, orchestrator, nightly, tracker, core en ten slotte web/bridge-nacontrole. Gebruik
voor iedere story een eigen branch en PR en merge elke story groen voordat je de volgende start.

Voer Telegram en core elk in twee nieuwe Codex-taken binnen hun eigen story uit. Start eerst een
GPT-5.6 Sol High taak die uitsluitend de verplichte capability-/consumermatrix of
contractclassificatiematrix vastlegt, commit en pusht. Laat een reviewer exact die design-SHA
goedkeuren en registreer het holdpoint in VOORTGANG.md. Start pas daarna een nieuwe GPT-5.6 Sol
Medium taak op dezelfde branch voor de mechanische implementatie. Een gewijzigde classificatie gaat
terug naar een nieuwe High-reviewronde; de Medium-agent neemt geen architectuurbesluit.

Voer buiten de twee High-holdpoints uitsluitend mechanische package-, visibility-, named-interface-
en importmigraties uit. Bewaak letterlijk dat `models` alleen publieke immutable Kotlin data
classes bevat; `types` alleen publieke enum/sealed/value-contracten; en `errors` alleen publieke
getypeerde exceptions. Export interne DTO's of technische exceptions nooit voor gemak.

Laat developer, reviewer en tester alle voorgeschreven gates uitvoeren. Geen enkele falende test
mag worden genegeerd, ook niet als deze al bestond of ongerelateerd lijkt. Werk VOORTGANG.md na
iedere overdracht, push, PR, merge en post-mergeverificatie bij. Stop alleen bij een echte externe
blokkade of wanneer een prerequisite inhoudelijk niet blijkt te zijn afgerond.
```

## Niet-onderhandelbare moduleconventie

Voor iedere featuremodule geldt na migratie:

```text
<module>/
  <Module>Api.kt of andere smalle portinterfaces
  models/
    *.kt
    package-info.java        # @NamedInterface("models")
  types/
    *.kt
    package-info.java        # @NamedInterface("types"); enum/sealed/value-contracten
  errors/
    *.kt
    package-info.java        # @NamedInterface("errors"); publieke typed exceptions
  services/                  # intern
  clients/                   # intern
  repositories/              # intern
  configurations/            # intern
```

De bestandsnamen zijn illustratief; de volgende regels zijn exact en bindend:

1. In een module-root staan uitsluitend publieke interfaces/ports en noodzakelijke modulemetadata.
   Geen Spring-component, implementatie, client, repository, scheduler, parser, policy of concrete
   configuratieclass mag in de root blijven.
2. Een publieke named interface `models` bevat **uitsluitend publieke immutable Kotlin
   `data class`-typen**.
3. `Immutable` betekent hier minimaal: alle publiek blootgestelde constructorproperties zijn `val`
   en het type exposeert geen muteerbare collectie of andere rechtstreeks wijzigbare state.
4. Ieder type in `models` moet aantoonbaar cross-modulecontract zijn: het staat in de signature van
   een publieke module-API of wordt werkelijk door een andere module via die publieke interface
   gebruikt. “Misschien later nuttig” en “handig voor imports” zijn geen bewijs.
5. Een module-interne data class blijft bij de implementatie of in een niet-geëxporteerd package.
   Verplaats een intern type nooit naar `models` om een dependencytest snel groen te krijgen.
6. Enums, sealed classes/interfaces, value classes, gewone classes, interfaces, exceptions,
   Spring-stereotypes, repositories, clients en services zijn **verboden** in `models`.
7. Een publiek enum-, sealed- of valuetype dat werkelijk cross-modulecontract is, komt in een
   afzonderlijke expliciete named interface `types`. Alleen deze drie categorieën zijn daar
   toegestaan; interne varianten blijven intern.
8. Een publieke getypeerde exception die aantoonbaar onderdeel is van een cross-modulefoutcontract
   komt in de afzonderlijke named interface `errors`. `errors` bevat uitsluitend publieke classes
   die direct of indirect `Throwable` uitbreiden, een specifieke semantische fout benoemen en door
   een publieke port worden gedocumenteerd of door een externe consumer getypeerd worden
   afgehandeld. Generieke technische, parser-, JDBC-, HTTP- of implementatieexceptions blijven
   intern. Exceptions staan nooit in root, `models` of `types`.
9. `package-info.java` mag als package-/named-interfacemetadata aanwezig zijn; het is geen model,
   type of errorcontract.
10. Andere modules importeren uitsluitend module-rootinterfaces of expliciete named interfaces.
   Imports uit `services`, `clients`, `repositories`, `configurations` of andere internals zijn fout.
11. Een modulemigratie verkleint de tijdelijke `MOD-01`-migratieallowlist en verwijdert alle regels voor de
   afgeronde module. De allowlist mag nooit groeien.

## Verplichte volgorde, modellen en storygrenzen

| Volgorde | Administratief pakket | Factory-story / Codex-taak | Verplicht model | Startvoorwaarde |
| --- | --- | --- | --- | --- |
| 1a | `MOD-02` | `telegram` — API-designholdpoint op dezelfde storybranch | Sol High, nieuwe taak | plan 06 groen; story en branch bestaan |
| 1b | `MOD-02` | `telegram` — mechanische migratie | Sol Medium, nieuwe taak | capability-/consumermatrix gepusht en reviewerakkoord op design-SHA |
| 2 | `MOD-03` | `knowledge` | Sol Medium | `telegram` gemerged en groen |
| 3 | `MOD-03` | `runtime` | Sol Medium | `knowledge` gemerged en groen |
| 4 | `MOD-03` | `config` | Sol Medium | `runtime` gemerged en groen |
| 5 | `MOD-03` | `orchestrator` | Sol Medium | `config` gemerged en groen |
| 6 | `MOD-03` | `nightly` | Sol Medium | `orchestrator` gemerged en groen |
| 7 | `MOD-03` | `tracker` | Sol Medium | `nightly` gemerged en groen |
| 8a | `MOD-03` | `core` — contractclassificatieholdpoint op dezelfde storybranch | Sol High, nieuwe taak | `tracker` gemerged en groen; core-story en branch bestaan |
| 8b | `MOD-03` | `core` — mechanische migratie | Sol Medium, nieuwe taak | volledige contract-/consumermatrix gepusht en reviewerakkoord op design-SHA |
| 9 | `MOD-03` | `web` / `bridge` nacontrole | Sol Medium | `core` gemerged en groen |

Start iedere story vanaf de dan actuele groene default branch. Packageverplaatsingen uit twee
stories mogen niet tegelijk in verschillende worktrees actief zijn.

### High-designholdpoint voor Telegram

De High-taak wijzigt geen productiecode en voert geen packageverplaatsing uit. Zij schrijft in het
eigen storyworklog een capability-/consumermatrix met minimaal:

- iedere huidige externe Telegramconsumer en de exact gebruikte operaties;
- per capability de ene smalle rootport, methodesignature, input-/outputcontracten en eigenaar;
- per contracttype de classificatie `models`, `types`, `errors` of `intern`, inclusief concreet
  cross-modulebewijs;
- bestaande Spring-wiring en de mechanische migratiestap per consumer;
- expliciet bewijs dat geen universele `TelegramApi`, brede composite of compatibilityfacade nodig
  is;
- karakterisatie-/contracttests die het huidige bericht-, polling-, reply- en assistantgedrag
  bevriezen.

Commit en push alleen matrix/worklog en eventueel testplan, open of actualiseer de PR en laat een
reviewer de design-SHA expliciet goedkeuren. Noteer High-taak-id, design-SHA, reviewer,
reviewtijdstip, matrixlink en besluit in de vaste overdracht van `VOORTGANG.md`. Bij afkeuring of
een ontbrekende consumer blijft de story `BEZIG`; de Medium-taak start niet.

### High-designholdpoint voor core

De High-taak inventariseert **ieder** publiek of cross-module gebruikt coretype en legt een volledige
contract-/consumermatrix vast. Iedere rij bevat minimaal huidig symbool/pad, alle consumers,
publieke portsignatures, serialization/persistencegebruik, doelmodule en exact één eindclassificatie:

| Classificatie | Bindende bestemming |
| --- | --- |
| publieke portinterface | module-root |
| publieke immutable Kotlin data class | `core.models` |
| publieke enum, sealed class/interface of value class | `core.types` |
| publieke specifieke typed exception | `core.errors` |
| policy, parser, fallback, technische exception of implementatiemodel | intern subpackage bij de eigenaar |

De matrix maakt ook zichtbaar wanneer een type semantisch bij een andere reeds gemigreerde module
hoort. Zo'n ownershipwijziging is geen mechanische coremove: registreer haar als blokkade bij de
verantwoordelijke eerdere story en ontwerp haar niet in Medium. Commit/push de matrix op de
core-storybranch en laat een reviewer de volledige design-SHA goedkeuren. Noteer dezelfde vaste
holdpointvelden als bij Telegram. De Medium-taak mag alleen de goedgekeurde rij-voor-rijmapping
uitvoeren; iedere nieuwe of gewijzigde rij vereist eerst een nieuwe High-reviewronde.

## Werkwijze die voor iedere modulestory geldt

1. Maak vóór iedere wijziging één niet-gestarte Factory-story volgens
   [UITVOERREGELS.md](UITVOERREGELS.md) en registreer story, branch en status in
   [VOORTGANG.md](VOORTGANG.md).
2. Leg de baseline vast met `git status --short`, `git log -1 --oneline`, `mvn clean verify` en
   `./quality/run.sh`.
3. Inventariseer met `rg` alle productietypen in de module-root, alle cross-module-imports van de
   module en alle typen in bestaande named interfaces.
4. Maak vóór verplaatsing een classificatietabel in het storyworklog met per type:
   `root-port`, `publiek model`, `publiek type`, `publieke typed exception`,
   `interne service/client/repository/configuration`, `technische exception` of `intern model`.
   Noteer voor ieder publiek model/type/error de concrete API-signature of externe
   consument die export rechtvaardigt.
5. Verplaats mechanisch; pas package declarations, imports, Spring component scanning/wiring,
   tests en actuele moduledocumentatie mee aan. Verander geen methodegedrag.
6. Draai na iedere coherente groep niet-packagewijzigingen een compile- of gerichte test. Na iedere
   package-, bestands- of symboolmove is `mvn clean verify` verplicht voordat de volgende groep
   begint; een incrementele compile of warme output is geen bewijs. Laat de diff klein genoeg om
   renames van inhoudelijke wijzigingen te onderscheiden.
7. Verwijder de migratieallowlistregels van precies deze module en draai de
   `MOD-01`-architectuurtest.
8. Draai de gerichte tests, daarna de volledige verificatie uit dit plan. Iedere failure gaat terug
   naar de developer. **Geen enkele falende test mag worden genegeerd.**
9. Laat reviewer en tester de uiteindelijke commit onafhankelijk controleren. Merge zonder bypass,
   verifieer de gemergede SHA en werk `VOORTGANG.md` bij voordat de volgende story start.

## MOD-02 — Migreer `telegram`

### Storytitel

`Telegram-module: publieke ports en modellen, implementatie intern`

### Probleem en bewijs

Op de broncommit stonden onder
`softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/telegram/` onder meer concrete clients,
pollers, JDBC-stores, notificatie-, reply- en assistantservices direct in de module-root. Daardoor
waren implementatiedetails impliciet publiek. De actuele uitvoering moet de situatie opnieuw meten;
de broninventaris is bewijs van de oorspronkelijke afwijking, niet een reden om inmiddels verplaatste
code terug te zetten.

### Concrete stappen

1. Controleer vóór productiecode dat de gepushte capability-/consumermatrix volledig is, de
   geregistreerde reviewer exact de design-SHA heeft goedgekeurd en de Medium-taak op die commit
   staat. Stop bij iedere afwijking.
2. Implementeer mechanisch uitsluitend de goedgekeurde smalle notificatie-, reply- en
   assistantports. Maak geen brede universele `TelegramApi` en wijzig geen signature buiten de
   matrix.
3. Verplaats `PendingQuestion`, `AssistantTip`, `AssistantReply`, `TelegramUpdate`,
   `AssistantStatus` en eventuele nieuw geïnventariseerde contracttypen exact volgens de
   goedgekeurde mapping naar `telegram.models`, `telegram.types`, `telegram.errors` of intern.
   `models` bevat alleen data classes, `types` alleen enum/sealed/value en `errors` alleen publieke
   typed exceptions.
4. Verplaats Telegram-client, poller, JDBC-stores, notification/reply/assistantservices,
   assistantclient en workspace-implementatie naar passende interne subpackages.
5. Laat Spring-wiring via de smalle ports werken en vervang alle cross-module-imports van concrete
   Telegramimplementaties.
6. Voeg named-interfacemetadata voor werkelijk publieke contractpackages toe, werk de module- en
   agentdocumentatie bij en verwijder alle Telegramregels uit de `MOD-01`-allowlist.

### Acceptatiecriteria

- De Telegram-root bevat uitsluitend publieke interfaces/ports en modulemetadata.
- `telegram.models` bevat uitsluitend bewezen cross-module publieke immutable data classes.
- Geen enum, sealed/value type, gewone class, interface, exception of Spring-component staat in
  `telegram.models`.
- `telegram.types` bevat uitsluitend bewezen publieke enum/sealed/value-contracten en een eventueel
  `telegram.errors` uitsluitend bewezen publieke typed exceptions.
- De gepushte capability-/consumermatrix, reviewer en goedgekeurde design-SHA staan in de vaste
  storyoverdracht; implementatie en matrix hebben geen afwijking.
- Andere modules importeren geen Telegram-internals.
- Alle `MOD-01`-allowlistregels voor Telegram zijn weg.
- Berichttekst, pollingcadans, persistence en zichtbaar Telegramgedrag zijn ongewijzigd.

### Gerichte verificatie

```bash
rg -n '^((data |enum |sealed |value )?class|interface|object) ' \
  softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/telegram
rg -n 'nl\.vdzon\.softwarefactory\.telegram\.(services|clients|repositories|configurations)' \
  softwarefactory/src/main/kotlin --glob '!**/telegram/**'
mvn -pl softwarefactory -am -Dtest='*Telegram*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl softwarefactory -am -Dtest=ModulithArchitectureTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

### Verplichte volledige verificatie

```bash
./quality/run.sh
mvn clean verify
```

Draai tevens de canonieke `MOD-01`-API-inventarisatie/allowlistcheck en leg de voor/na-delta vast.

### Buiten scope

Geen Telegramgedrag, berichttekst, pollingcadans, persistence, database-schema of assistantcontract
wijzigen. Splits grote services alleen voor de noodzakelijke packagegrens en ontwerp geen nieuwe
Telegramfacade.

### Reviewer- en tester-aandachtspunten

- Reviewer controleert vooral dat geen interne Telegram-DTO uit gemak publiek is gemaakt en dat er
  geen nieuwe facade met alle Telegramverantwoordelijkheden is ontstaan.
- Tester controleert notificaties, replies, assistentsessies, pollingoffsets en bestaande
  foutpaden via de bestaande unit-/flowtests.

## MOD-03 — Overige module-roots

Voor ieder van de volgende acht onderdelen wordt een afzonderlijke Factory-story, branch, PR,
review, testoverdracht en post-mergeverificatie gemaakt.

### Story 1 — `knowledge`

**Titel:** `Knowledge-module: API en cross-module modellen expliciet scheiden`

**Probleem en bewijs:** op de broncommit stonden `KnowledgeApi`, `AgentKnowledgeEntry` en
`AgentKnowledgeUpdateRequest` samen in de rootfile `knowledge/KnowledgeApi.kt`, terwijl service en
JDBC-repository al interne subpackages hadden. Contract en model waren daardoor niet afzonderlijk
geëxporteerd.

**Concrete stappen:** houd alleen knowledgeports in de root; classificeer beide data classes en
verplaats alleen bewezen cross-modulecontracten naar `knowledge.models`; houd repositoryrecords en
service-interne data intern; voeg named-interfacemetadata toe; werk imports, tests, moduledocs en
allowlist bij.

**Acceptatiecriteria:** root uitsluitend ports; data-class-only `knowledge.models`; geen externe
imports van `knowledge.services`/`repositories`; knowledge-allowlist leeg; gedrag en persistence
ongewijzigd.

**Gerichte verificatie:** draai `AgentKnowledgeServiceTest`, controller-/completiontests die de
knowledgeport gebruiken, `ModulithArchitectureTest` en compileer alle afhankelijke modules vóór de
volledige verificatie.

**Verplichte volledige verificatie:** draai `./quality/run.sh`, de canonieke `MOD-01`-check en
`mvn clean verify` op de uiteindelijke story-SHA.

**Buiten scope:** geen knowledgegedrag, opslagquery, schema of completionverwerking wijzigen.

**Reviewer/tester:** reviewer controleert het cross-modulebewijs van ieder geëxporteerd
knowledgemodel; tester draait knowledge-, controller-/completionregressies en daarna de volledige
suite zonder failures te negeren.

### Story 2 — `runtime`

**Titel:** `Runtime-module: completionports, payloadcontracten en internals scheiden`

**Probleem en bewijs:** op de broncommit stonden ports, `CompletionOutcome` en meerdere completion-
payloaddata classes in rootfiles; services, Docker-, command-, logging-, repository- en
workspacecode waren deels intern. `REL-01` en `OPS-01` hebben dit domein inhoudelijk gewijzigd en
zijn daarom harde prerequisites.

**Concrete stappen:** behoud alleen completion-/materializationports in de root; plaats bewezen
cross-module immutable data classes in `runtime.models`; plaats een publiek `CompletionOutcome` of
andere enum/sealed/value contracttypen in `runtime.types`, nooit in `models`; plaats alleen bewezen
publieke typed exceptions in `runtime.errors`; houd commandresults, technische exceptions,
Dockerinstellingen, repositoryrecords en workspace-internals intern tenzij een publieke signature
het tegendeel bewijst; pas wiring, consumers, tests, docs en migratieallowlist aan.

**Acceptatiecriteria:** runtime-root uitsluitend ports; `models` data-class-only; `types` alleen
expliciet benodigde publieke enum/sealed/value-contracten; een eventueel `errors` uitsluitend
publieke typed exceptions; duurzame completion/recovery uit `REL-01` blijft ongewijzigd; geen
cross-module runtime-internalimport; runtime-migratieallowlist leeg.

**Gerichte verificatie:** draai alle `runtime`-tests, completion fault-/restarttests uit `REL-01`,
workspace-cleanuptests, relevante e2e-completionflows en `ModulithArchitectureTest`.

**Verplichte volledige verificatie:** draai `./quality/run.sh`, de canonieke `MOD-01`-check en
`mvn clean verify` op de uiteindelijke story-SHA.

**Buiten scope:** geen completionstate, retry-/idempotencygedrag, Dockerdispatch of cleanupbeleid
wijzigen.

**Reviewer/tester:** reviewer controleert dat duurzame completiontypen correct als model/type/intern
zijn geclassificeerd; tester herhaalt fault-injection, restart, cleanup en relevante e2e-flows vóór
de volledige suite.

### Story 3 — `config`

**Titel:** `Config-module: ports, publieke configuratiemodellen en wiring scheiden`

**Probleem en bewijs:** de bronroot mengde `ConfigApi`, `OrchestratorSettingsFactory` en
`PostgresConnectionSettings`; concrete secretloaders en wiring stonden deels in subpackages.
`ARC-07` moet vóór deze story de enige configuratiebron en externe-I/O-grenzen hebben bepaald.

**Concrete stappen:** houd uitsluitend configuratieports in de root; verplaats concrete factories,
loaders, startup-loggers en Spring-configuratie intern; exporteer een settingsdata class alleen
wanneer een andere module deze via een publieke configport nodig heeft; laat interne datasource- of
parserdata intern; werk imports, configuratietests, actuele configuratiedocs en allowlist bij.

**Acceptatiecriteria:** root uitsluitend ports; geen composition-/wiringclass in root; eventuele
`config.models` voldoet exact aan de data-class-only/cross-module-regel; gelaagde secretresolutie en
configprecedence zijn ongewijzigd; config-allowlist leeg.

**Gerichte verificatie:** draai alle `config`-tests, secret-/database-/projectconfigtests,
force-deployregressietests uit `ARC-07` en `ModulithArchitectureTest`.

**Verplichte volledige verificatie:** draai `./quality/run.sh`, de canonieke `MOD-01`-check en
`mvn clean verify` op de uiteindelijke story-SHA.

**Buiten scope:** geen configkey, precedence, default, secretsource of validatiegedrag wijzigen.

**Reviewer/tester:** reviewer controleert dat composition-rootdetails intern blijven; tester bewijst
de bestaande secretprecedence, datasource- en projectconfigpaden en draait daarna de volledige suite.

### Story 4 — `orchestrator`

**Titel:** `Orchestrator-module: orchestrationports scheiden van services, schedulers en wiring`

**Probleem en bewijs:** de bronroot bevatte naast `OrchestratorApi` concrete configuration- en
importrestbestanden; services, schedulers en repositories stonden in subpackages. Na `ARC-03` moeten
commands en handlers al inhoudelijk zijn opgesplitst.

**Concrete stappen:** houd alleen orchestrationports in de root; verplaats concrete configuration,
factories en overige implementatietypen intern; classificeer nieuw ontstane command-/resultdata
streng en exporteer alleen echte cross-module data classes; behoud handlers en persistence intern;
werk wiring, tests, docs en allowlist bij. Verwijder geen dode importrestbestanden als verborgen
cleanup; dat gebeurt in `CLN-01`, tenzij de mechanische packageverplaatsing het bestand zonder
inhoud geheel overbodig maakt en de diff dit expliciet aantoont.

**Acceptatiecriteria:** root uitsluitend ports/metadata; geen scheduler, repository, service of
configuration publiek; commandgedrag en pollherstel ongewijzigd; geen internalimports;
orchestrator-allowlist leeg.

**Gerichte verificatie:** draai orchestrator-, commandhandler-, poller-, pipeline- en relevante
loopback-e2etests plus `ModulithArchitectureTest`.

**Verplichte volledige verificatie:** draai `./quality/run.sh`, de canonieke `MOD-01`-check en
`mvn clean verify` op de uiteindelijke story-SHA.

**Buiten scope:** geen commandsemantiek, pollcadans, recovery-, merge- of pipelinegedrag wijzigen en
geen cleanup uit `CLN-01` naar voren halen.

**Reviewer/tester:** reviewer controleert dat handlers/services intern blijven en geen brede nieuwe
port ontstaat; tester herhaalt command-, poll-, recovery- en loopbackflows vóór de volledige suite.

### Story 5 — `nightly`

**Titel:** `Nightly-module: gatewaycontracten en interne planning/persistence scheiden`

**Probleem en bewijs:** op de broncommit stonden gateway, planner, reader, digest, repositories,
scheduler en tijdservice direct in de root, met data classes, enum en sealed actiontypes door elkaar.

**Concrete stappen:** houd alleen werkelijk publieke nightlyports/gateways in de root; plaats
bewezen cross-module data classes in `nightly.models`; plaats publieke `NightlyOutcomeStatus`,
`NightlyAction` of andere enum/sealed/value contracts in `nightly.types` wanneer zij werkelijk
extern nodig zijn; houd jobparser-, digest-, planner- en repositoryrecords intern wanneer ze geen
publiek contract zijn; classificeer `NightlySubtasksConfigException` en andere exceptions expliciet
als intern of, alleen bij bewezen extern foutcontract, `nightly.errors`; verplaats planner/scheduler/
reader/repositories en concrete gatewayadapters intern; werk tests, docs en migratieallowlist bij.

**Acceptatiecriteria:** root uitsluitend ports; data-class-only `models`; niet-data-classtypen nooit
in `models`; nightlyplanning, `subtasks.yaml`-semantiek, scheduler en digest ongewijzigd; geen
internalimports; nightly-allowlist leeg.

**Gerichte verificatie:** draai alle `Nightly*Test`-klassen, nightly repository-integratietests,
nightly dashboard/gatewaytests en `ModulithArchitectureTest`.

**Verplichte volledige verificatie:** draai `./quality/run.sh`, de canonieke `MOD-01`-check en
`mvn clean verify` op de uiteindelijke story-SHA.

**Buiten scope:** geen schedule, planning, digestinhoud, `subtasks.yaml`-semantiek of persistence
wijzigen.

**Reviewer/tester:** reviewer controleert vooral dat repository-/digestdata niet onterecht publiek
worden; tester draait planning, reader, scheduler, digest, repositories en dashboardgateway vóór de
volledige suite.

### Story 6 — `tracker`

**Titel:** `Tracker-module: capabilityports en publieke trackerdata expliciet scheiden`

**Probleem en bewijs:** de bronroot bevatte de brede `TrackerApi` en `ProcessedCommentsApi`; na
`ARC-04` horen capabilityinterfaces en gesplitste Postgresinternals de bron van waarheid te zijn.
Deze story mag de capabilitykeuzes niet opnieuw ontwerpen.

**Concrete stappen:** behoud de door `ARC-04` vastgelegde smalle capabilityinterfaces in de root;
plaats alleen data classes die hun publieke signatures vormen in `tracker.models`; plaats publieke
enum/sealed/value contracts in `tracker.types`; plaats de getypeerde not-foundfout uit `FIX-06` in
`tracker.errors` wanneer `ARC-04` haar als publiek capabilityfoutcontract heeft vastgelegd, anders
in `errors` van de aantoonbare eigenaarmodule; houd generieke tracker-/JDBC-/HTTP-fouten intern.
Houd JDBC-repositories, clients, configurations, parsers en persistence records intern. Als nog een
brede compatibilityfacade uit `ARC-04` bestaat,
is plan 05 niet afgerond: registreer dat als blokkade en laat het daar herstellen. Werk daarna
consumers, tests, docs en allowlist bij.

**Acceptatiecriteria:** root bevat alleen capabilityports en modulemetadata; er bestaat geen brede
compatibilityfacade of onveilige default-no-op; `models` is data-class-only; consumers injecteren
alleen benodigde capabilities; `types` en `errors` voldoen aan hun exclusieve categorie; er zijn
geen tracker-internalimports en de tracker-migratieallowlist is leeg.

**Gerichte verificatie:** draai tracker unit-/Testcontainers-tests, stale-issue-regressie uit
`FIX-06`, alle capabilityconsumenttests en `ModulithArchitectureTest`.

**Verplichte volledige verificatie:** draai `./quality/run.sh`, de canonieke `MOD-01`-check en
`mvn clean verify` op de uiteindelijke story-SHA.

**Buiten scope:** geen capabilityset opnieuw ontwerpen en geen SQL-, schema- of trackersemantiek
wijzigen. Onvoltooide facademigratie gaat als blokkade terug naar `ARC-04`.

**Reviewer/tester:** reviewer vergelijkt iedere consumerinjectie met de door `ARC-04` gekozen
capability; tester draait capability-, Postgres-, not-found- en consumentregressies vóór de volledige
suite.

### Story 7 — `core`

**Titel:** `Core shared kernel: ports, modellen, typen, errors en intern beleid scheiden`

**Probleem en bewijs:** de bronroot was een breed shared kernel met ports, tientallen data classes,
enums, sealed results, concrete policies, parsers en een concrete fallbackruntime. Daardoor was
vrijwel alles impliciet publiek. Dit is de grootste mechanische migratie en blijft toch één
afzonderlijke core-story, voorafgegaan door het verplichte afzonderlijke High-reviewholdpoint.

**Concrete stappen:** controleer eerst de gepushte volledige core-contract-/consumermatrix en het
reviewerakkoord op de exacte design-SHA. Voer daarna iedere goedgekeurde rij mechanisch uit: ports
naar root, publieke immutable data classes naar `core.models`, publieke enum/sealed/value-contracten
naar `core.types`, publieke specifieke typed exceptions naar `core.errors`, en policies, parsers,
fallbacks, technische exceptions en implementatiemodellen intern. Werk imports, wiring, tests,
documentatie en migratieallowlist bij. Improviseer geen nieuwe bestemming en voer geen massale
inhoudelijke herschrijving uit; een matrixwijziging gaat terug naar de High-taak.

**Acceptatiecriteria:** core-root uitsluitend ports/metadata; `core.models` uitsluitend bewezen
cross-module publieke immutable data classes; `core.types` bevat de expliciet benodigde publieke
enum/sealed/value-contracten; `core.errors` uitsluitend bewezen publieke typed exceptions;
policies/parsers/technische exceptions zijn intern; alle afnemers gebruiken alleen
root/models/types/errors; de uiteindelijke mapping is rij voor rij gelijk aan de goedgekeurde
design-SHA en de core-migratieallowlist is leeg.

**Gerichte verificatie:** draai alle coretests, compileer de volledige reactor, draai alle
orchestrator/pipeline/runtime/trackertests die corecontracten consumeren en draai
`ModulithArchitectureTest`.

**Verplichte volledige verificatie:** draai `./quality/run.sh`, de canonieke `MOD-01`-check en
`mvn clean verify` op de uiteindelijke story-SHA.

**Buiten scope:** geen domeinmodel, enumwaarde, sealed result, portsignature, policy of parsergedrag
wijzigen; geen shared-kernelherontwerp uitvoeren.

**Reviewer/tester:** reviewer vergelijkt iedere diffregel met de goedgekeurde matrix, eist voor ieder
publiek coremodel/type/error concreet cross-modulebewijs en inspecteert moves als rename; tester
draait alle directe en indirecte coreconsumenten en daarna de volledige suite.

### Story 8 — `web` / `bridge` nacontrole

**Titel:** `Web- en bridgegrenzen na ARC-01 volledig conform moduleconventie maken`

**Probleem en bewijs:** de broncode publiceerde `web.services` als named interface zodat
`bridge` concrete webservices kon importeren. `web.models` bevatte bovendien onder andere een
sealed interface en enum, in strijd met de data-class-only-regel. `ARC-01` hoort inmiddels een
dashboard application-module en transportadapters te hebben ingevoerd.

**Concrete stappen:** verifieer dat `bridge` niets uit `web` importeert en beide uitsluitend de door
`ARC-01` vastgelegde dashboard application-API gebruiken; verwijder de tijdelijke named interface
`web.services`; herclassificeer resterende `web.models`-typen volgens de bindende regel en verwijder
het package wanneer het leeg is; controleer ook de bridge-root op concrete client/handlerclasses en
verplaats die volgens de reeds gekozen adapterstructuur intern; actualiseer moduledocs, diagrambron,
tests en de resterende allowlistregels. Voeg geen nieuw dashboardcontract toe.

**Acceptatiecriteria:** geen `bridge`-import uit `web`; geen publieke `web.services`-interface;
`web.models` bestaat alleen als het uitsluitend bewezen cross-module data classes bevat; enum/
sealed/value contracts staan zo nodig in een expliciete `types`-interface en publieke typed
exceptions uitsluitend in `errors`; web- en bridge-root volgen `MOD-01`; beide
migratieallowlists zijn leeg; bestaand bridge-/dashboardgedrag is gelijk.

**Gerichte verificatie:** draai bridgecontract-/handler-/clienttests, webcontroller- en dashboard-
applicationtests, de relevante e2e-APIflows en `ModulithArchitectureTest`. Gebruik tevens:

```bash
rg -n 'nl\.vdzon\.softwarefactory\.web\.' \
  softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/bridge
rg -n 'NamedInterface\("services"\)|web\.services' softwarefactory/src/main docs/technical
```

**Verplichte volledige verificatie:** draai `./quality/run.sh`, de canonieke `MOD-01`-check en
`mvn clean verify` op de uiteindelijke story-SHA.

**Buiten scope:** geen nieuw dashboard-/bridgecontract, endpoint, operatie, cachebeleid of
application-use-case ontwerpen; onafgemaakt `ARC-01`-werk gaat terug naar die voorganger.

**Reviewer/tester:** reviewer controleert nul imports tussen transportadapters en het verdwijnen van
`web.services`; tester draait bridgecontracten, dashboardqueries/-commands en relevante e2e-APIflows
vóór de volledige suite.

## Verplichte volledige verificatie per story

Na de gerichte tests van **iedere** story:

```bash
./quality/run.sh
mvn clean verify
```

Voer daarnaast na **iedere** story onvoorwaardelijk het exacte canonieke volledige
repositorygatecommando uit de `VER-02`-overdracht uit, inclusief de actuele documentatie-, Flutter-,
image- en runtime-smokes die daarin zijn vastgelegd. Controleer ook de door `MOD-01` geleverde
API-inventarisatie/migratieallowlistcheck met het canonieke commando uit plan 03. Ontbreekt een van
deze overgedragen commando's, dan is de voorganger niet afgerond en blokkeert de story; reconstrueer
geen kleinere vervangende gate. Leg commando, configversie, exitcode, datum/tijd, testtellingen,
qualityscore en voor/na-allowlist vast in story en `VOORTGANG.md`. De qualityscore mag niet stijgen;
een stijging is een blocker en wordt niet administratief weggeboekt.

## Expliciet buiten scope voor het hele plan

- Geen functionele wijziging aan Telegram, nightly, tracker, runtime, bridge of dashboard.
- Geen wire-, database-, tracker- of configuratieschemamigratie.
- Geen nieuwe applicationmodule, capabilityset of publieke facade ontwerpen; die keuzes zijn door
  de prerequisites gemaakt.
- Geen grote service opsplitsen behalve voor de noodzakelijke packagegrens.
- Geen repositorybrede package-/pluraliteitscleanup; dat volgt in `CLN-01`.
- Geen Detektbaseline of dependencyrichting invoeren; dat volgt in plan 08.
- Geen uitbreiding van de `MOD-01`-migratieallowlist, suppressie, testskip of tijdelijke
  internal-export om groen te worden. Het permanente `ARC-07` composition-root-boundaryregister
  blijft exact en mag niet als overtredingsallowlist worden behandeld.

## Planbrede reviewer- en tester-aandachtspunten

### Reviewer

- Controleer per publieke data class het genoteerde cross-modulebewijs.
- Zoek expliciet naar enums, sealed/value/gewone classes, interfaces, exceptions en Springtypen in
  ieder publiek `models`-package.
- Controleer dat renames/verplaatsingen geen inhoudelijke logicadiff verbergen.
- Controleer Spring-wiring, serializationtypen en Modulith named-interface-namen op compatibiliteit.
- Weiger de PR bij een gegroeide migratieallowlist, ongeautoriseerde wijziging van het permanente
  composition-root-boundaryregister, nieuw intern cross-module-import of ontbrekend
  post-mergebewijs.

### Tester

- Test de uiteindelijke reviewde commit en draai zowel gerichte moduleflows als `mvn clean verify`.
- Controleer bij Telegram/bridge/runtime expliciet bestaand zichtbaar gedrag en contractserialisatie.
- Vergelijk voor en na geen testtellingen weg en accepteer geen skip/quarantine.
- **Geen enkele falende test mag worden genegeerd**, ook niet als deze buiten de gemigreerde module
  lijkt te vallen of vóór de story al rood was.

## Plan-afronding en overdracht

Plan 07 is pas `AFGEROND` wanneer:

- alle negen verplichte stories afzonderlijk gemerged en post-merge groen zijn;
- de `MOD-03`-modulematrix in [VOORTGANG.md](VOORTGANG.md) voor alle acht regels story, branch/PR en
  bewijs bevat;
- de tijdelijke `MOD-01`-migratieallowlist repositorybreed volledig leeg is; iedere resterende regel,
  ook voor een niet in de matrix genoemde module, blokkeert plan-afronding;
- alle module-roots en publieke `models`-, `types`- en `errors`-interfaces aan hun exclusieve,
  bindende conventie voldoen;
- de Telegram- en core-story elk een gepushte High-design-SHA, capability-/consumermatrix,
  expliciet reviewerholdpoint en afzonderlijke Medium-implementatietaak bevatten;
- de actuele moduledocumentatie overeenkomt met de gemergede packages;
- de laatste gemergede default-branch-SHA `mvn clean verify`, de modulearchitectuurgate en de
  volledige canonieke `VER-02`-repositorygate groen doorloopt;
- reviewer en tester expliciet akkoord zijn en geen enkele failure is genegeerd.

Werk daarna plan 07 in `VOORTGANG.md` bij naar `AFGEROND` en draag aan plan 08 minimaal over:

- de gemergede eind-SHA van plan 07;
- de definitieve lijst Modulith-modules en named interfaces;
- bewijs dat de `MOD-01`-migratieallowlist exact nul regels bevat;
- pad, versie, exacte entries en ongewijzigde/krimpende delta van het permanente `ARC-07`
  composition-root-boundaryregister; dit register is geen overtredingsallowlist en wordt niet
  verwijderd om plan 08 te starten;
- het laatste volledige Maven- en qualitybewijs;
- eventuele expliciete publieke `types`- en `errors`-interfaces;
- bevestiging dat geen modulemigratie of architectuurblokkade openstaat.

Plan 08 mag niet starten zolang één moduleverhaal of migratieallowlistverwijdering uit dit plan
openstaat. Een niet-leeg `MOD-01`-register is altijd een blokkade; er bestaat geen verklaarde
restcategorie.
