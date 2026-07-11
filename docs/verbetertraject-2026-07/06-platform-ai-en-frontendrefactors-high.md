# Plan 06 — Platform-, AI- en frontendrefactors

## Metadata

| Veld | Waarde |
| --- | --- |
| Plan | 06 |
| Status | `NIET GESTART` |
| Werkpakketten | `ARC-05`, `ARC-06`, `ARC-07`, `UI-01` |
| Prioriteit / omvang | P2 / M-L, L, M-L, L |
| Aanbevolen model | GPT-5.6 Sol |
| Effort | High |
| Waarom dit niveau | Het plan raakt drie AI-suppliers, Maven-reactor en Dockerbuilds, configuratie- en I/O-grenzen en de Flutter-API-laag. Iedere runtime moet apart én repositorybreed groen blijven. |
| Prerequisite | Plan 05 volledig `AFGEROND`, gemerged en groen volgens `VOORTGANG.md` |
| Volgend plan | Plan 07 |

Dit plan bevat vier afzonderlijke werkpakketten met ieder een eigen Factory-story. Het deelt
supplier-onafhankelijke agentlogica, maakt contractdependencies licht, centraliseert configuratie
en externe I/O en maakt Flutterfeatures getypeerd. Het plan is geen repositorybrede rename- of
package-migratie; die volgt in plan 07/09.

## Bindende bronnen

Lees vóór de start en vóór ieder werkpakket volledig:

- `docs/verbetertraject-2026-07/UITVOERREGELS.md`;
- `docs/verbetertraject-2026-07/VOORTGANG.md`;
- `docs/verbeterplan-onderhoudbaarheid-2026-07.md`, vooral `ARC-05`, `ARC-06`, `ARC-07`, `UI-01`;
- de actuele MOD-01-moduleconventie uit plan 03;
- de Docker-/mini-reactoroplossing uit `FIX-03`;
- de bridgebooleancontracttest uit `FIX-05`;
- de volledige PR-verificatie uit `VER-02`;
- de application-/capabilitygrenzen uit plan 05.

Controleer actuele paden na eerdere refactors met `rg --files` en `rg` op typenaam. Een genoemd oud
pad is bewijs/context, geen toestemming om een compatibilitykopie terug te plaatsen.

## Harde uitvoerinvarianten

- Eén werkpakketcode betekent één Factory-story, branch en PR. Werk in de volgorde ARC-05,
  ARC-06, ARC-07, UI-01 en begin pas na merge plus post-mergeverificatie van de voorganger.
- Mechanisch en inhoudelijk werk worden niet gemengd:
  - een pure bestands-/modulemove verandert geen gedrag of abstraheert geen logica;
  - een inhoudelijke extractie werkt op stabiele paden en krijgt eigen commits na een groene move;
  - voer gerichte tests en de volledige relevante gate uit tussen beide fasen.
- Na iedere mechanische bestands-/package-/modulemove en iedere POM-wijziging geldt
  `mvn clean verify` als verplichte Maven-gate vóór review en opnieuw op de gemergede SHA. Een
  incrementele `mvn verify` is daarvoor geen vervanging.
- Voer in iedere story, ongeacht geraakte paden, onvoorwaardelijk het door `VER-02` vastgelegde
  canonieke volledige repositorygatecommando uit vóór review, na iedere reviewfix en post-merge.
  Losse componentcommando's vervangen die ene canonieke gate niet.
- Geen version-upgrade, dependencyvernieuwing of formatterbrede diff meenemen “omdat de POM of
  Flutterbestanden toch wijzigen”.
- Houd alle wire-, result-file-, CLI-, env-, HTTP-, JSON-, UI- en databasecontracten gelijk, behalve
  expliciet additieve interne types met backward-compatibilitytests.
- Voeg geen generieke “common utils”-, “platform”-, HTTP- of processgodmodule toe. Iedere gedeelde
  abstractie moet minimaal twee echte consumers en één duidelijke verantwoordelijkheid hebben.
- Geen enkele falende unit-, integratie-, e2e-, contract-, Flutter-, Docker- of smoketest mag
  worden genegeerd, ook niet als zij pre-existing, flaky, omgevingsgebonden of ongerelateerd lijkt.
- Geen `@Disabled`, skip, quarantine, `|| true`, suppressie, pathfilterbypass of zwakkere test
  gebruiken als vervanging voor volledig groen bewijs.

## Prerequisites en baseline

1. Controleer dat plan 05 en ARC-01 t/m ARC-04 `AFGEROND` zijn en dat de default branch alle
   post-mergechecks heeft doorlopen.
2. Controleer de actuele Mavenmodules, Docker-mini-reactors, dashboardcontracten en tracker-
   capabilitypaden; noteer afwijkingen van de peildatum.
3. Voer vóór iedere Kotlin/Maven-story uit:

   ```bash
   git status --short
   git log -1 --oneline
   mvn clean verify
   ./quality/run.sh
   ```

4. Voer vóór `UI-01` bovendien uit:

   ```bash
   cd dashboard-frontend
   flutter pub get
   flutter analyze
   flutter test
   flutter build web --release
   ```

   Bouw vanuit de repositoryroot tevens de actuele productie-image:

   ```bash
   docker build -f dashboard-frontend/Dockerfile dashboard-frontend
   ```

   Draai bovendien de canonieke APK-buildjob uit
   `.github/workflows/dashboard-frontend-image.yml` wanneer `VER-02` die als verplichte component
   van Repository verification heeft vastgelegd. Gebruik daarvoor de echte workflow/job met haar
   signingsecrets; vervang haar niet door een lokaal unsigned alternatief.

5. Neem uit de `VER-02`-overdracht het **exacte uitvoerbare canonieke volledige
   repositorygatecommando** over en voer het uit. Ontbreekt daar een concreet commando of is een
   vereiste component niet groen, dan is de story geblokkeerd; reconstrueer geen smallere variant.
6. Leg SHA, datum/tijd, exitcodes, testtellingen, Docker-/Flutterversie en qualityscore vast. Een
   rode baseline blokkeert de story en wordt niet administratief genegeerd.
7. Maak vervolgens de eigen Factory-story en registreer storykey, branch, PR en status meteen in
   `VOORTGANG.md`. Start niet tevens een tweede interne Factory-uitvoerder.

## Kopieerbare startopdracht

```text
Voer plan 06 volledig autonoom en strikt sequentieel uit volgens
docs/verbetertraject-2026-07/06-platform-ai-en-frontendrefactors-high.md.

Lees vóór iedere wijziging ook volledig:
- docs/verbetertraject-2026-07/UITVOERREGELS.md
- docs/verbetertraject-2026-07/VOORTGANG.md
- docs/verbeterplan-onderhoudbaarheid-2026-07.md
- de actuele MOD-01-moduleconventie en output van plannen 03 en 05

Controleer eerst dat plan 05 AFGEROND en groen is. Voer ARC-05, ARC-06, ARC-07 en UI-01 exact in
die volgorde uit. Maak per werkpakket een eigen Factory-story, branch en PR en begin de volgende
story pas na groene merge- en post-mergeverificatie van de vorige. Scheid mechanische moves strikt
van inhoudelijke refactors in afzonderlijke, tussentijds groen geverifieerde commits. Behoud alle
externe contracten, introduceer geen nieuwe common/godlaag en werk VOORTGANG.md na iedere
overdracht bij. Laat developer, reviewer en tester de gerichte en volledige Maven-, Docker- en/of
Fluttergates uitvoeren en voer per story onvoorwaardelijk exact het door VER-02 overgedragen
canonieke volledige repositorygatecommando uit. Negeer geen enkele falende test. Push en merge
uitsluitend na alle vereiste groene checks; stop alleen bij een echte externe blokkade of
onomkeerbare productbeslissing.
```

## Canonieke volledige repositorygate

`VER-02` levert één concreet, uitvoerbaar repositorygatecommando op dat Maven, Modulith, Flutter en
de verplichte image-/aggregatorchecks fail-closed samenbrengt. Dat overgedragen commando is vanaf
plan 03 de enige canonieke volledige repositorygate. Iedere story in dit plan voert exact dat
commando onvoorwaardelijk uit:

1. op de uiteindelijke storycommit vóór review;
2. opnieuw na iedere review- of testerfix;
3. opnieuw op de gemergede default-branch-SHA.

Gerichte tests, `mvn clean verify`, `./quality/run.sh`, losse Flutterbuilds en Dockerbuilds blijven
daarnaast verplicht. Zij vervangen de canonieke repositorygate niet. Wanneer het concrete commando
of een vereiste component ontbreekt, is dat een blocker en geen reden om de gate zelf smaller te
reconstrueren.

## Verplichte volgorde en scheiding van werksoorten

```text
Plan 05 gemerged
  -> ARC-05 inhoudelijke agentcore-extractie
  -> ARC-06 uitsluitend mechanische Maven-/contractgrenzen
  -> ARC-07 inhoudelijke config- en I/O-centralisatie
  -> UI-01 eerst mechanische screensplit, daarna typed modellen per feature
  -> planbrede nacontrole
```

Reden voor deze volgorde:

- ARC-05 maakt eerst de werkelijke agentworkerverantwoordelijkheden zichtbaar zonder tegelijk
  artifacts/modules te verplaatsen.
- ARC-06 kan daarna packages/artifacts en parent-POM mechanisch herschikken op stabiele inhoud.
- ARC-07 gebruikt vervolgens de definitieve contract-/toolinggrenzen voor config- en I/O-ports.
- UI-01 raakt een andere runtime, maar wordt toch als laatste uitgevoerd zodat de volledige
  repositorygate één stabiele backend-/contractbasis heeft.

---

## ARC-05 — Maak de AI-core werkelijk supplier-neutraal

**Voorgestelde Factory-storytitel:** `Supplier-neutrale agentcore voor Claude Codex en Copilot`

### Probleem en bewijs

`CodexAiClient` en `CopilotAiClient` importeren `ClaudePromptBuilder` en `ClaudeOutcomeParser` uit
het Claude-subpackage, hoewel prompt- en outcomecontracten supplieronafhankelijk zijn. Claude,
Codex en Copilot dupliceren bovendien processtart, stdoutredactie, `.task.md`-beheer,
credential-homecontrole en outcomeconstructie. `ClaudeCodeAiClient.kt` bevat client, runner,
prompts en meerdere parsers in ruim 750 regels. Namen en dependencyrichting maken onbedoeld Claude
de architecturale eigenaar van alle suppliers.

Inventariseer minimaal:

- `agentworker/.../agent/AiClient.kt`;
- `agentworker/.../agent/ai/claude/ClaudeCodeAiClient.kt`;
- `agentworker/.../agent/ai/codex/CodexAiClient.kt`;
- `agentworker/.../agent/ai/copilot/CopilotAiClient.kt`;
- `DummyAiClient` en supplierfactory;
- alle Claude/Codex/Copilot- en CLI-tests.

### Concrete stappen

1. Maak vóór de eerste wijziging een **machineleesbare agentworker-qualitybaseline**. De huidige
   `./quality/run.sh` meet alleen `softwarefactory` en is dus geen bewijs voor deze story. Gebruik
   bij voorkeur Detekt met exact dezelfde gepinde versie en `quality/detekt.yml`, gericht op
   `agentworker/src/main/kotlin`; een gelijkwaardige analyzer is alleen toegestaan wanneer Detekt
   technisch niet uitvoerbaar is en dezelfde class-/method-complexiteitsregels plus suppressies
   machineleesbaar rapporteert. Leg exact commando, toolversie, configuratiehash, sourceset,
   XML/SARIF/JSON-rapport, totaaltelling, suppressietelling en tellingen voor minimaal `LargeClass`,
   `TooManyFunctions`, `LongMethod`, `LongParameterList`, `CyclomaticComplexMethod` en
   `CognitiveComplexMethod` vast. Ontbrekend rapport of toolfailure is rood, nooit nul bevindingen.
2. Leg parametrische contracttests vast voor alle rollen en suppliers:
   - prompt bevat dezelfde rolregels/outputcontracten;
   - phase/outcomealiases mappen gelijk;
   - developer, questions, reject, planner-subtasks en knowledge-updates blijven gelijk;
   - parsefout, lege output, CLI-exitcode en usage/events behouden supplierspecifieke foutcodes.
3. Extracteer inhoudelijk supplier-neutrale types naar `agent.ai.shared` met neutrale namen, minimaal:
   - `AgentPromptBuilder`;
   - `AgentOutcomeParser`;
   - `TaskFileManager`;
   - een kleine `CliProcessRunner`/request-resultvorm voor procesmechanics.
4. Houd supplierspecifiek:
   - CLI-commandopbouw en flags;
   - credentialpolicy en toegestane env;
   - stream-/JSONL-parser en usagevelden;
   - supplierspecifieke retry/foutcode wanneer bestaand gedrag dat vereist.
5. Maak redactie één gedeelde stap vóór output in geheugen/log/events komt. Geef de runner een
   expliciete env-map en stdinpolicy; voorkom dat secrets uit de parentenv ongemerkt meeliften.
6. Verplaats daarna het Claude-bestand mechanisch naar afzonderlijke client-, prompt- en
   parserbestanden. Deze move krijgt een aparte commit zonder logica; draai tests vóór en na en
   voer op die mechanische commit `mvn clean verify` plus de canonieke volledige repositorygate uit.
7. Migreer Claude, Codex en Copilot één voor één naar shared components. Maak na iedere supplier de
   parametrische en supplierspecifieke tests groen voordat de volgende wijzigt.
8. Verwijder alle cross-supplierimports uit `agent.ai.claude` en alle gedupliceerde helpers.
9. Draai na iedere suppliermigratie en op de uiteindelijke commit exact dezelfde
   agentworker-qualitymeting als bij stap 1. Nieuwe findings of suppressies zijn verboden; de
   Claude-hotspot moet aantoonbaar dalen en de storyspecifieke `LargeClass`-/`TooManyFunctions`-
   bevinding verdwijnen of met concrete voor/na-telling dalen volgens het acceptatiecriterium.
10. Werk agentworker/module-/supplierdocumentatie en relevante docs-skeletonpromptbronnen bij zonder
   promptinhoud onbedoeld te wijzigen.

### Acceptatiecriteria

- Codex en Copilot importeren niets uit `agent.ai.claude`; Claude importeert niets uit andere
  supplierpackages.
- Supplier-neutrale prompts/outcomes/taskfile/processmechanics hebben neutrale namen en één bron.
- Iedere supplierclass bevat hoofdzakelijk command, credentials en streamparser.
- Parametrische contracttests bewijzen gelijke rolcontracten voor Claude, Codex en Copilot.
- Supplierspecifieke usage, events, errorcodes, credentialgedrag en flags blijven getest.
- `.task.md` en eventuele last-messagefiles worden bij succes én fout correct opgeruimd en niet
  gecommit.
- Geen secret komt ongeredigeerd in logs/events.
- De agentworker-voor- en nameting gebruiken exact dezelfde gepinde tool, configuratie en sourceset;
  hun machineleesbare rapporten zijn als storybewijs beschikbaar.
- Agentworker krijgt geen nieuwe finding of suppressie. De oude Claude-large-classhotspot verdwijnt
  als `LargeClass`-bevinding en de relevante `TooManyFunctions`-/complexiteitstelling daalt; iedere
  afwijking blokkeert review.

### Gerichte verificatie

```bash
rg -n 'agent\.ai\.claude\.(ClaudePromptBuilder|ClaudeOutcomeParser)' agentworker/src/main agentworker/src/test
mvn -pl agentworker -am test
mvn -pl agentworker -am \
  -Dtest=ClaudeCodeAiClientTest,CodexAiClientTest,CopilotAiClientTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Voer ook de nieuwe parametrische suppliercontractsuite expliciet uit. Draai daarna exact het in stap
1 vastgelegde agentworker-Detekt-/qualitycommando opnieuw en vergelijk de machineleesbare rapporten;
`./quality/run.sh` mag hiervoor niet als vervanging worden gebruikt.

### Volledige verificatie

```bash
mvn clean verify
./quality/run.sh
```

Voer daarnaast de agentworker-qualitynameting en onvoorwaardelijk de canonieke volledige
repositorygate uit zoals hierboven vastgelegd.

### Buiten scope

- Geen modelrouting, prijsberekening, promptregels, agentrollen of supplierfeature wijzigen.
- Geen Mavenmodules splitsen; dat is ARC-06.
- Geen config/I/O-repositorybrede centralisatie; alleen de direct gedeelde agentrunner binnen
  agentworker. ARC-07 volgt later.
- Geen dependencyversies upgraden.

### Reviewer- en tester-aandachtspunten

- Reviewer: vergelijk prompttekst byte-/regelgericht waar semantiek stabiel moet blijven.
- Reviewer: controleer dat de shared laag niet supplierspecifieke conditionals verzamelt.
- Reviewer: inspecteer env-sanitization, stdin, cleanup en secretredactie.
- Reviewer: vergelijk de agentworker-machinebaseline en -narun op tool/config/sourcesethash,
  findings per regel en suppressies; accepteer geen handmatige telling of alleen `quality/run.sh`.
- Tester: draai alle drie suppliers met fake runners voor succes/fout/lege output/credentials.
- Tester: controleer planner-subtasks, knowledge, usage en events exact.
- Iedere falende test blokkeert approval; niets mag als pre-existing worden genegeerd.

### Story-overdracht

Leg shared contracts, resterende supplierverantwoordelijkheden, exacte prompt-/parsertests en beide
agentworker-qualityrapporten met delta vast. Merge en voer post-merge `mvn clean verify`, dezelfde
agentworker-qualitymeting en de canonieke volledige repositorygate uit. ARC-06 start alleen vanaf
deze groene SHA.

---

## ARC-06 — Splits contracts van tooling en maak de root-POM een parent

**Voorgestelde Factory-storytitel:** `Lichte contractmodule en uniforme Maven-parent zonder gedragswijziging`

### Werksoort

Dit is een **mechanisch platformwerkpakket**. Refactor geen prompts, configsemantiek, git/GitHub-
gedrag, docslogica of previewgedrag. Iedere module-/packageverplaatsing krijgt eerst een pure move-
commit; POM-/Dockerwiring volgt in een aparte commit, met groene gates ertussen. Voer na iedere
move- of POM-commit en vóór review `mvn clean verify` plus de canonieke volledige repositorygate uit;
herhaal beide post-merge.

### Probleem en bewijs

`factory-common` bevat bridge- en agent-resultcontracten naast config/YAML, docs, git, GitHub,
preview, support en Spring-components. `dashboard-backend` gebruikt alleen `contract.*`, maar trekt
de brede module en onder meer Spring-context/SnakeYAML mee. De root-POM is alleen aggregator; vier
child-POMs dupliceren Boot-, Java-, Kotlin-, plugin- en interne versies. Docker-mini-reactors zijn
gevoelig voor reactorwijzigingen.

### Concrete stappen

1. Maak vóór wijzigingen een dependencyinventaris per package en consumer met `mvn dependency:tree`
   en `rg`-imports. Leg vast welke packages contract, tooling of projectconfig zijn.
2. Voeg `factory-contracts` toe als lichte **productie-jar** met uitsluitend wiretypes en de
   minimale wire-readers/-writers die deze contracten serialiseren of framen:
   - bridgeframes en hun frame reader/writer;
   - agent-resultfile en bijbehorende DTO's;
   - alleen dependencies die deze serialisatiecontracten rechtstreeks vereisen.
   Fixtures, fake frames, builders, sample payloads en testhelpers zijn verboden onder
   `factory-contracts/src/main`. Plaats ze in `src/test` wanneer alleen die module ze gebruikt, of in
   een expliciet testartifact/test-fixtures-variant die nooit als productie-runtime dependency wordt
   meegenomen wanneer meerdere consumers dezelfde fixtures nodig hebben.
3. Verplaats contractbestanden mechanisch met `git mv`/equivalente patchmove, zonder package- of
   veldwijziging. Maak contracttests groen vóór andere codewijzigingen.
4. Hernoem/splits het resterende brede artifact naar een duidelijk toolingartifact voor
   git/GitHub/docs/preview/support. Beoordeel projectcatalogus/configtypen op dependencycohesie:
   - kies een afzonderlijke kleine projectconfigmodule wanneer zowel server als agentworker haar
     nodig hebben zonder de rest van tooling;
   - documenteer de keuze en dependencygraph;
   - creëer geen lege of één-consumer-module alleen voor symmetrie;
   - splits de brede `ProjectRepoResolver` hier nog niet inhoudelijk en hernoem haar niet alleen om
     de Mavenmove af te ronden; ARC-07 splitst haar verantwoordelijkheden na de mechanische
     artifactgrenzen.
5. Laat `dashboard-backend` uitsluitend van `factory-contracts` afhangen. Voeg een boundarytest/
   Enforcerregel toe die Spring-context en SnakeYAML op het contracts-runtimeclasspath verbiedt.
6. Maak root `pom.xml` tegelijk aggregator en echte parent:
   - één group/version;
   - één Java-/Kotlin-/Boot-/Modulithversiebron;
   - dependencyManagement en pluginManagement;
   - childmodules erven via `relativePath` en dupliceren geen interne versie.
7. Behoud module-specifieke plugins/profielen alleen waar ze werkelijk horen; maak de lichte
   contractmodule geen Spring Boot-app.
8. Pas Dockerfile.agent, dashboard-backend-Dockerfile en alle mini-reactors volgens het in FIX-03
   gekozen patroon aan. Introduceer geen tweede afwijkend workaroundrecept.
9. Werk workflows, scripts, module-/developmentdocs en dependencydiagram bij.
10. Controleer met `git diff --find-renames` dat deze story geen inhoudelijke productiecodewijziging
    of dependency-upgrade bevat.

### Acceptatiecriteria

- `factory-contracts` bevat uitsluitend wirecontracten en heeft geen Spring-context/SnakeYAML-
  runtime dependency.
- De productie-jar bevat geen fixtures, fakes, sample payloads, testbuilders of andere testhelpers;
  gedeelde fixtures leven uitsluitend in een herkenbaar testartifact dat geen runtimeconsumer heeft.
- Dashboard-backend hangt niet van tooling/projectconfig af.
- Factory en agentworker gebruiken exact dezelfde agent-resulttypes; factory en dashboard-backend
  exact dezelfde bridgeframes.
- Alle child-POMs erven versies en gemeenschappelijke pluginconfig uit de root-parent.
- Er is één expliciete modulelijst en één interne projectversie.
- Docker-mini-reactors en imagebuilds uit FIX-03 blijven werken na de modulewijziging.
- Geen JSON-veld, packagecontract, runtimegedrag of dependencyversie wijzigt ongemerkt.
- Contract-, Maven Enforcer-/boundary- en volledige tests zijn groen.
- Een geautomatiseerde jar-/dependencyboundarytest faalt wanneer een fixture in het
  productieartifact terechtkomt of een consumer het testartifact met runtime scope binnenhaalt.

### Gerichte verificatie

```bash
mvn -pl factory-contracts -am test
mvn -pl dashboard-backend -am dependency:tree
mvn -pl dashboard-backend -am test
mvn -pl agentworker,softwarefactory -am test
docker build --target build -f Dockerfile.agent .
```

Voer de actuele FIX-03-smokes voor agent, assistant en dashboard-backend uit en bewijs via
dependencytree/Enforcer dat contracts licht blijft.

Inspecteer bovendien de gebouwde productie-jar en draai de boundarytest die bewijst dat uitsluitend
wiretypes/readers/-writers en noodzakelijke metadata aanwezig zijn; een handmatige steekproef alleen
is onvoldoende.

### Volledige verificatie

```bash
mvn clean verify
./quality/run.sh
```

Voer daarna onvoorwaardelijk de canonieke volledige repositorygate uit zoals hierboven vastgelegd.

### Buiten scope

- Geen dependencyversies upgraden of Maven-buildgedrag optimaliseren buiten deduplicatie.
- Geen config-/env-/HTTP-/processrefactor; dat is ARC-07.
- Geen MOD-03-packageconventiemigratie van alle modules.
- Geen contractveld hernoemen of verwijderen.

### Reviewer- en tester-aandachtspunten

- Reviewer: gebruik rename-detectie en controleer dat pure moves inhoudelijk identiek zijn.
- Reviewer: inspecteer effective POMs, dependency scopes en mogelijke transitieve dependencyverlies.
- Reviewer: inspecteer de inhoud van de productie-jar en dependency scopes; fixtures/testhelpers
  mogen alleen in `src/test` of het expliciete niet-runtime testartifact voorkomen.
- Reviewer: controleer alle Docker COPY-/reactorpaden na de nieuwe modulelijst.
- Tester: bouw vanaf een schone dependencycache/checkout waar mogelijk en draai contractfixtures aan
  beide consumers.
- Tester: bouw agent-, assistant- en dashboard-backendimage; een alleen lokaal warme Maven-cache is
  onvoldoende bewijs.
- Geen enkele falende test of build mag worden genegeerd.

### Story-overdracht

Leg de uiteindelijke modulelijst, artifactdependencies, parentversies, boundaryregels en Docker-
smokeresultaten vast. Leg ook productie- versus testartifactinhoud vast. Merge en voer post-merge
`mvn clean verify` en de canonieke volledige repositorygate uit voordat ARC-07 begint.

---

## ARC-07 — Centraliseer configuratie en externe proces-/HTTP-I/O

**Voorgestelde Factory-storytitel:** `Eenduidige configuratie en injecteerbare externe I-O adapters`

### Probleem en bewijs

Applicatielogica leest op meerdere plaatsen rechtstreeks `System.getenv`, maakt `ProcessBuilder`
of bouwt eigen `HttpClient`s. Daardoor wijkt gelaagde `secrets.env`-config af van procesenv en zijn
timeout, redactie en fouttypen inconsistent. Concreet leest `ProjectDeployClient.forceRestart()`
alleen `System.getenv(deployConfig.tokenEnvVar)` en mist zo een token dat via de gelaagde loader wel
in `ConfigApi.resolvedValues()` staat. Andere huidige voorbeelden zijn assistant timeout/image,
nightly/GitHubtokenfallbacks, git-versioncommands en meerdere HTTP-clients.

Daarnaast combineert de huidige `ProjectRepoResolver` catalogus-/YAML-laden, validatie,
repositoryselectie, runtime-instellingen en deploymentdetails. Alleen hernoemen naar
`ProjectCatalog` zou die verantwoordelijkheden en afhankelijkheden verbergen in een nieuwe godfacade;
ARC-07 splitst ze daarom inhoudelijk en aantoonbaar.

### Runtimegrens

Centralisatie betekent per runtime een expliciete composition root:

- `softwarefactory`: `SecretsEnvLoader`/wiring mag procesenv lezen; downstream gebruikt
  `ConfigApi`, typed settings of `FactorySecrets`;
- `agentworker`: uitsluitend `main`/CLI-composition root leest `System.getenv()`; downstream krijgt
  een immutable env/configobject;
- `dashboard-backend`: uitsluitend de dashboard secrets/configloader leest procesenv;
- home-directorynormalisatie en platformentrypoints mogen een gedocumenteerde uitzondering zijn;
  businessservices niet.

**Configprecedence wordt niet repositorybreed gelijkgetrokken.** Neem vóór wijziging per runtime de
door `DOC-01` vastgelegde en met characterizationtests bevestigde precedence over. Behoud die
volgorde letterlijk voor `softwarefactory`, `agentworker` en `dashboard-backend`, ook wanneer zij
onderling verschillen. Een afwijking tussen DOC-01 en code is een blokkade die eerst expliciet wordt
beslist; kies niet stil één globale volgorde. Met name dashboardprecedence wijzigt alleen in een
afzonderlijk geautoriseerde gedragsmigratie met eigen voor/na-contracttests, documentatie en rollout-
impact. Zo'n migratie is geen impliciet onderdeel van deze centralisatie.

Leg daarnaast een permanent, versiebeheerd **composition-root-boundaryregister** vast. Dit register
bevat alleen legitieme architectuurgrenzen die procesenv of concrete process/HTTP-mechanics mógen
aanroepen, met bestand, runtime, capability en reden. Het is geen tijdelijke allowlist of
schuldadministratie en hoeft niet naar nul te krimpen; nieuwe of bredere entries vereisen een
zichtbare architectuurreview en een falende/positieve sourcecheck.

### Concrete stappen

1. Maak een volledige inventaris en classificeer iedere directe I/O-match:

   ```bash
   rg -n 'System\.getenv|ProcessBuilder|HttpClient\.new' \
     softwarefactory/src/main agentworker/src/main dashboard-backend/src/main factory-tooling/src/main
   ```

   Pas het toolingpad aan de ARC-06-uitkomst aan. Noteer eigenaar, reden, configkey, timeout,
   redactie, gewenste port en de beoogde entry in het permanente boundaryregister per match.
2. Neem de configmatrix uit `DOC-01` over en voeg vóór refactoring characterizationtests toe voor de
   **werkelijke precedence per runtime**. Test minimaal gelijke key in alle beschikbare lagen,
   file-only, env-only, lege waarde en ontbrekende verplichte waarde. Wijzig geen precedence om de
   loaders uniform te maken. Bij een code/docverschil wordt de story geblokkeerd totdat de bron van
   waarheid expliciet is hersteld. Dashboardprecedence blijft ongewijzigd tenzij daarvoor een
   afzonderlijk geautoriseerde gedragsmigratie bestaat.
3. Definieer typed settings per feature waar losse stringkeys nu verspreid zijn. `ConfigApi`/
   `FactorySecrets` blijft bron, maar wordt geen nieuwe god interface. Valideer verplichte waarden
   bij startup en houd secrets uit `toString`/logs.
4. Splits `ProjectRepoResolver` op basis van concrete consumers, zonder overkoepelende facade:
   - één YAML-/catalogusloader die uitsluitend bytes/tekst naar catalogusrecords vertaalt;
   - afzonderlijke validators voor schema-, repository- en deploymentinvarianten;
   - immutable typed runtime-instellingen voor agent/workspace/imagegedrag;
   - immutable typed deploymentinstellingen voor target, tokenreferentie en restart/deploygedrag;
   - smalle catalogus-/lookupports voor consumers die alleen project- of repogegevens lezen.
   Migreer iedere consumer rechtstreeks naar de kleinste passende port/settingsvorm en verwijder de
   brede `ProjectRepoResolver`. Introduceer geen `ProjectCatalog`, `ProjectConfigService` of andere
   facade die loader, validatie, runtime en deployment opnieuw combineert. Behoud YAML-schema,
   defaults, validatiefouten en projectselectiesemantiek met characterizationtests.
5. Herstel `ProjectDeployClient` zodat `tokenEnvVar` uit de volledige, voor **softwarefactory**
   geldende gelaagde resolved config wordt gelezen. Test file-/secrets-only, de volgens DOC-01
   geldende overridevolgorde, ontbrekend token en redactie bij fout; neem niet automatisch aan dat
   dashboard of agentworker dezelfde precedence heeft.
6. Maak externe I/O injecteerbaar via kleine ports/adapters met consistente timeout en getypeerde
   fouten:
   - command execution voor docker/git/kubectl/host commands;
   - HTTP execution voor GitHub/release/deploy/Telegram waar mechanics werkelijk gelijk zijn;
   - responseinterpretatie blijft bij de featureclient, niet in één universele HTTP-godservice.
7. Migreer matches feature voor feature. Maak na iedere feature gerichte tests groen en verwijder de
   directe call; laat geen oud fallbackpad staan.
8. Voor agent suppliers blijft commandopbouw/credentialpolicy uit ARC-05 eigenaar; hergebruik de
   shared runner zonder supplierflags in een platformlaag te duwen.
9. Injecteer `Clock`, timeout/config en fake runners in tests; gebruik geen echte env/netwerk/process
   in unit-tests.
10. Maak een architectuur-/sourcecheck die iedere directe env/process/HTTP-call buiten het
    permanente composition-root-boundaryregister laat falen. Test zowel een verboden nieuwe call als
    een legitieme geregistreerde composition root. Iedere registerwijziging is een bewuste
    architectuurwijziging met bestand, runtime, capability en reden; gebruik geen wildcard of
    tijdelijke schuldentry.
11. Werk `properties.default.env`, secretsvoorbeelden, configreferentie, module-/external-systemsdocs
   en runbook bij. Noteer nooit echte waarden.

### Acceptatiecriteria

- `ProjectDeployClient` gebruikt gelaagde config en de regressietest voor secrets-file-only token
  is groen.
- Iedere runtime behoudt aantoonbaar haar door DOC-01 vastgelegde precedence. Dashboardprecedence is
  niet stil gewijzigd; een eventuele wijziging heeft een afzonderlijk geautoriseerde
  gedragsmigratie en eigen contract-/rolloutbewijs.
- `ProjectRepoResolver` bestaat niet meer. YAML/catalogusladen, validatie, runtime-instellingen,
  deploymentinstellingen en cataloguslookup hebben afzonderlijke smalle verantwoordelijkheden en
  geen nieuwe facade combineert ze opnieuw.
- Businessservices lezen geen `System.getenv`; alleen gedocumenteerde composition roots doen dat.
- Directe `ProcessBuilder`/`HttpClient.new*` staan alleen in benoemde adapters of expliciete
  platformentrypoints.
- Iedere externe call heeft een begrensde timeout, veilige env, redactie en een getypeerd foutpad.
- Unit-tests gebruiken injected config/runners/clients en zijn onafhankelijk van hostenv/netwerk.
- Source-/architectuurcheck faalt bij een kunstmatige nieuwe directe call buiten het permanente
  boundaryregister en accepteert uitsluitend de geregistreerde legitieme boundaries.
- Bestaand deploy-, Telegram-, GitHub-, nightly-, assistant- en agentgedrag blijft gelijk.
- Geen nieuwe universele config-, projectcatalogus-, HTTP- of processgodservice ontstaat.

### Gerichte verificatie

```bash
rg -n 'System\.getenv|ProcessBuilder|HttpClient\.new' softwarefactory/src/main agentworker/src/main dashboard-backend/src/main
mvn -pl factory-tooling,softwarefactory,agentworker,dashboard-backend -am test
./quality/run.sh
```

Pas de toolingartifactnaam aan ARC-06 aan. Voer daarnaast de precedence-characterizationtests per
runtime, de `ProjectRepoResolver`-consumer-/validator-/settingsregressies, de nieuwe
force-deployregressietests, de boundaryregistercheck en relevante fake-runner/clienttests expliciet
uit.

### Volledige verificatie

```bash
mvn clean verify
./quality/run.sh
```

Voer daarna onvoorwaardelijk de canonieke volledige repositorygate uit zoals hierboven vastgelegd.
Voer de door VER-02/FIX-03 vereiste image-/repositorysmokes bovendien expliciet uit wanneer geraakte
config of runners in images gebruikt worden.

### Buiten scope

- Geen nieuwe configfeature of externe integratie toevoegen.
- Geen secrets migreren/roteren of echte secrets in tests/docs opnemen.
- Geen precedence tussen runtimes uniformeren en geen dashboardprecedence wijzigen zonder een
  afzonderlijk geautoriseerde gedragsmigratie.
- Geen brede Maven-/packageverplaatsing; ARC-06 is al afgerond en MOD-03 volgt later.
- Geen HTTP-libraryvervanging alleen uit voorkeur.

### Reviewer- en tester-aandachtspunten

- Reviewer: vergelijk per runtime code, DOC-01-matrix en characterizationtests; eis behoud van de
  afzonderlijke precedence en controleer dat dashboard niet stil naar softwarefactorygedrag is
  omgezet.
- Reviewer: controleer de `ProjectRepoResolver`-consumermatrix en weiger een nieuwe facade die YAML,
  validatie, runtime- en deploymentsettings opnieuw bundelt.
- Reviewer: zoek naar fallback op `System.getenv` die de nieuwe port alsnog omzeilt.
- Reviewer: controleer timeout/cancellation, process-env-register en fouttypen; behandel het
  composition-root-boundaryregister als permanente architectuurgrens, niet als tijdelijke allowlist.
- Tester: test iedere runtime met een hermetische env volgens haar eigen precedence, plus
  secrets-file-only token, geldige override en ontbrekende waarde.
- Tester: test YAML/catalogusfouten, validatie, runtime-/deploymentsettings en alle gemigreerde
  ProjectRepoResolver-consumers zonder brede compatibilityfacade.
- Tester: voer relevante Docker-/deploy-/assistant-smokes met fakes of veilige lokale omgeving uit.
- Iedere failure blokkeert; niets mag als pre-existing worden genegeerd.

### Story-overdracht

Leg het definitieve permanente composition-root-boundaryregister, de precedence per runtime, typed
settings, I/O-ports/adapters en de opgesplitste catalogusloader/validators/runtime-/deploymentsettings
vast. Noteer expliciet dat de historische CLN-01-gedachte “`ProjectRepoResolver` hernoemen naar
`ProjectCatalog`” door deze inhoudelijke split is vervallen en niet zonder nieuwe opdracht mag worden
uitgevoerd. Merge en voer post-merge `mvn clean verify` en de canonieke volledige repositorygate uit
voordat UI-01 begint.

---

## UI-01 — Splits Flutterfeatures en gebruik typed API-modellen

**Voorgestelde Factory-storytitel:** `Flutterfeatures met typed dashboard-API modellen`

### Probleem en bewijs

`dashboard-frontend/lib/screens/overview_screens.dart` bevat Dashboard, Agents, Merged, Projects,
Nightly en Settings in ongeveer 995 regels. `ApiClient.getJson/postJson` en vrijwel alle schermen
gebruiken `Map<String, dynamic>` en losse stringkeys. Daardoor ontdekt de compiler contractdrift
niet en worden fouttypen pas tijdens renderen zichtbaar. FIX-05 bewees dit met het string/boolean-
refreshdefect.

### Verplichte interne fasering — nooit mengen

UI-01 blijft conform de uitvoerregels één Factory-story, maar heeft twee strikt gescheiden,
tussentijds groene fasen en commits:

1. **Mechanische fase:** alleen scherm-/widgetbestanden verplaatsen en imports aanpassen; geen JSON-
   parsing, state, tekst, layout of gedrag wijzigen.
2. **Inhoudelijke fase:** typed modellen en API-adapters feature voor feature introduceren; geen
   gelijktijdige bestandsmassamove of visuele redesign.

De inhoudelijke fase start pas nadat `flutter analyze`, `flutter test`, `flutter build web --release`,
de actuele dashboard-frontend-productie-Dockerbuild, `mvn clean verify` en de canonieke volledige
repositorygate groen zijn op de mechanische commit. Wanneer de actuele `VER-02`-gate de APK-job
verplicht, moet ook die echte job groen zijn.

### Concrete stappen — mechanische fase

1. Leg widget-characterizationtests vast voor navigatie en zichtbare hoofdtoestanden van alle zes
   schermen: gevuld, leeg, loading, offline/fout en relevante action busy/confirmation.
2. Splits `overview_screens.dart` minimaal naar featurebestanden/-mappen voor:
   - dashboard;
   - agents/assistantstatus;
   - merged;
   - projects/builds/downloads/live status;
   - nightly;
   - settings.
3. Verplaats alleen lokaal gedeelde visuele widgets naar een duidelijk `shared/widgets`-bestand
   wanneer minimaal twee features ze gebruiken. Laat featurewidgets bij hun feature.
4. Pas imports/navigatie aan, gebruik rename-detectie en bewijs nul zichtbare/semantische wijziging.
5. Commit en draai `flutter analyze`, `flutter test`, `flutter build web --release`, de actuele
   productiebuild via `dashboard-frontend/Dockerfile`, relevante Mavencontracttests,
   `mvn clean verify` en de canonieke volledige repositorygate voordat de inhoudelijke fase begint.
   Draai ook de echte APK-job uit `.github/workflows/dashboard-frontend-image.yml` wanneer die in de
   actuele VER-02-gate canoniek/verplicht is; een unsigned lokale APK vervangt die signed job niet.

### Concrete stappen — inhoudelijke fase

1. Definieer immutable typed responsemodellen met expliciete `fromJson`-parsing en parsingtests.
   Begin verplicht met Projects/Builds/Downloads en het echte booleancontract uit FIX-05.
2. Hanteer één compatibiliteitsbeleid:
   - vereiste velden met verkeerd type geven een benoemde parse-/contractfout;
   - optionele velden hebben expliciete defaults/nullability;
   - onbekende additieve velden worden genegeerd voor forward compatibility;
   - string/boolean/nummercoercion gebeurt alleen wanneer het wirecontract dit aantoonbaar toestaat,
     niet via algemene `text`/`boolValue`-magie.
3. Introduceer typed featuregateways/repositories bovenop `ApiClient`. `ApiClient` blijft alleen
   verantwoordelijk voor HTTP, auth, JSON-decode op envelopniveau en uniforme HTTP/foutvertaling.
4. Migreer feature voor feature in deze volgorde en maak tests na iedere feature groen:
   Projects/Builds -> Dashboard -> Agents/Merged -> Nightly -> Settings -> overige geraakte
   story/sharedmodellen.
5. Introduceer typed requestobjects voor POST-acties waar nu losse maps worden gebouwd. Bewaar
   endpointpaden en JSON-veldnamen.
6. Verplaats algemene formatters (`formatTimestamp`, `formatDuration`, bytes) naar shared UI/value
   formatting en feature-specifieke helpers naar de feature. Laat auth-/SSEmechanics bij API/state.
7. Verwijder gemigreerde `Map<String, dynamic>`-casts en algemene coercionhelpers. Een raw map mag
   alleen op de laagste decodegrens kort bestaan en verlaat die laag niet.
8. Voeg parsingtests toe met actuele backendfixtures en widgettests voor malformed/optional/unknown
   data. Gebruik stabiele widgettests; voeg geen timinggevoelige goldens toe zonder noodzaak.
9. Bouw na de laatste feature opnieuw web release en de productie-Dockerimage. Draai de canonieke
   APK-job wanneer die volgens VER-02 verplicht is en vergelijk haar artifact-/buildresultaat met de
   baseline; een alleen groen `flutter test` is geen productiebouwbewijs.
10. Werk `dashboard-frontend/README.md`, frontendarchitectuur, API-/bridgecontractdocs en testcommands
    bij.

### Acceptatiecriteria

- `overview_screens.dart` bestaat niet meer of bevat hoogstens expliciete exports, geen schermlogica.
- Ieder hoofdscherm heeft een eigen featurebestand/-map en eigen characterizationtests.
- UI-widgets consumeren typed modellen; raw `Map<String, dynamic>` lekt niet uit de decode/API-laag.
- Projects/Builds gebruikt een echte boolean voor refresh en heeft parsing-/widgetregressietests.
- Vereiste verkeerde types falen gecontroleerd; onbekende velden blijven compatible; optionele
  velden hebben gedocumenteerde defaults.
- `ApiClient` bevat HTTP/auth/error/SSEmechanics maar geen schermformattering of featuremapping.
- Navigatie, teksten, acties, loading/offline/error/empty state en layoutgedrag blijven gelijk.
- De mechanische en inhoudelijke fasen zijn als afzonderlijke commits met afzonderlijk groen bewijs
  traceerbaar.
- `flutter analyze`, alle Fluttertests, `flutter build web --release`, de actuele
  dashboard-frontend-productie-Dockerbuild en de volledige repositorygate zijn groen.
- Wanneer de actuele Repository verification de APK-job verplicht, is ook de echte signed APK-job
  op de reviewde en gemergede SHA groen; zij is niet door een lokale unsigned build vervangen.

### Gerichte verificatie

```bash
cd dashboard-frontend
flutter analyze
flutter test test/screens/projects_screen_test.dart
flutter test test/screens/dashboard_overview_screen_test.dart
flutter test test/screens/settings_screen_test.dart
flutter test
flutter build web --release
cd ..
docker build -f dashboard-frontend/Dockerfile dashboard-frontend
```

Voer ook alle nieuwe modelparsing- en featuretests expliciet uit. Draai vanuit de repositoryroot de
bridge-/contracttests omdat typed Dartmodellen daarop aansluiten:

```bash
mvn -pl factory-contracts,dashboard-backend,softwarefactory -am test
```

### Volledige verificatie

```bash
mvn clean verify
./quality/run.sh
cd dashboard-frontend
flutter analyze
flutter test
flutter build web --release
cd ..
docker build -f dashboard-frontend/Dockerfile dashboard-frontend
```

Voer onvoorwaardelijk de canonieke volledige repositorygate uit op de gepushte SHA. Start en
controleer daarnaast de echte APK-job uit `.github/workflows/dashboard-frontend-image.yml` wanneer
die in de actuele VER-02-gate verplicht/canoniek is. Geen enkele falende Flutter-, Maven-, Docker-
of APK-build mag worden genegeerd.

### Buiten scope

- Geen visuele redesign, nieuwe navigatie, nieuw state-managementframework of backendfeature.
- Geen automatisch codegeneratiepakket toevoegen tenzij vooraf bewezen eenvoudiger en zonder brede
  generated diff; handmatige immutable parsing is de veilige default.
- Geen endpoint- of wireveld hernoemen.
- Geen backend application-/trackerrefactor heropenen.

### Reviewer- en tester-aandachtspunten

- Reviewer: controleer dat de mechanische commit echt alleen moves/imports bevat.
- Reviewer: inspecteer required/optional/unknown typebeleid en zoek resterende raw-mapcasts in
  widgets.
- Reviewer: controleer dat typed gateways niet HTTP/auth dupliceren en `ApiClient` geen nieuwe god
  class wordt.
- Reviewer: controleer voor beide commits het web-releaseartifact en de productie-Dockerbuild, plus
  de echte APK-job wanneer die canoniek verplicht is.
- Tester: vergelijk alle zes schermen vóór/na voor loading, offline, fout, leeg en gevuld.
- Tester: voer malformed/unknown/optional JSON-fixtures en refreshacties uit.
- Tester: test de uiteindelijke reviewde commit met `flutter analyze`, volledige `flutter test` en
  `flutter build web --release`, de dashboard-frontend-productie-Dockerbuild en de canonieke volledige
  repositorygate. Controleer ook de verplichte echte APK-job. Iedere failure gaat terug naar
  developer.

### Story-overdracht

Leg screen-/featuremapping, typed modellen, parseregels, resterende bewuste raw decodegrens en beide
tussentijdse groene commits vast. Leg web-release-, productie-Docker- en eventueel APK-bewijs vast.
Merge en voer post-merge `mvn clean verify` en de canonieke volledige repositorygate uit, inclusief
de verplichte productiebuildjobs.

---

## Planbrede verificatie na UI-01

Voer vanaf de gemergede default branch minimaal uit:

```bash
git status --short
git log -1 --oneline
mvn clean verify
./quality/run.sh
cd dashboard-frontend
flutter analyze
flutter test
flutter build web --release
cd ..
docker build -f dashboard-frontend/Dockerfile dashboard-frontend
docker build --target build -f Dockerfile.agent .
```

Voer daarnaast onvoorwaardelijk het exacte canonieke volledige repositorygatecommando uit, de
actuele FIX-03-image-smokes, de echte APK-job wanneer die in VER-02 verplicht is, de
documentatie-audit en modulearchitectuurtests. Controleer dependencygrenzen met de
ARC-06-boundarytest en directe env/processcalls met het permanente ARC-07-boundaryregister. Iedere
failure is een blocker; geen enkele falende test mag als pre-existing of ongerelateerd worden
genegeerd.

## Plan-afronding

Plan 06 krijgt pas status `AFGEROND` als:

1. ARC-05, ARC-06, ARC-07 en UI-01 ieder een eigen traceerbare Factory-story, branch en PR hebben en
   zonder bypass gemerged zijn;
2. ieder werkpakket aantoonbaar vanaf de groene merge-SHA van zijn voorganger begon;
3. mechanische en inhoudelijke fasen in afzonderlijke commits en met tussentijds groen bewijs staan;
4. reviewer en tester iedere uiteindelijke commit expliciet hebben goedgekeurd;
5. `mvn clean verify`, de canonieke volledige repositorygate, contract-, Modulith-, Docker-,
   Flutter-web-, eventuele verplichte APK-, documentatie-, sourcecheck- en qualitygates groen zijn;
6. geen enkele testfailure is genegeerd, geskipt, onderdrukt of als pre-existing geaccepteerd;
7. suppliers neutrale shared code gebruiken, contracts licht en vrij van productiefixtures zijn,
   `ProjectRepoResolver` zonder vervangende godfacade is opgesplitst, config/I-O expliciet begrensd
   is en Flutterwidgets typed modellen consumeren;
8. geen tijdelijke facade, dubbele packagekopie, dependency-upgrade, MOD-01-allowlistgroei of oud
   fallbackpad is achtergebleven; het permanente composition-root-boundaryregister geldt hierbij
   juist als legitieme architectuurbron en niet als tijdelijke allowlist;
9. actuele Maven-, module-, config-, external-systems-, agent- en frontenddocs overeenkomen met code;
10. `VOORTGANG.md` alle storykeys, branches, PR's, commits, testresultaten en post-mergebewijs bevat.

## Overdracht naar plan 07

Leg minimaal vast:

- shared agentcoretypes en de exacte grens van iedere supplier;
- uiteindelijke Mavenmodule-/artifactlijst, parentconfig en dependencyboundaryregels;
- Docker-mini-reactor- en imagebuildcommands/resultaten;
- permanent composition-root-boundaryregister, precedence per runtime, typed settings en externe
  I/O-adapters;
- opgesplitste YAML-/catalogusloader, validators, runtime-/deploymentsettings en bevestiging dat
  `ProjectRepoResolver` niet later alleen naar `ProjectCatalog` moet worden hernoemd;
- Flutterfeaturestructuur, typed modellen, parsecompatibiliteitsbeleid en web-/productie-Docker-/
  eventueel verplicht APK-bouwbewijs;
- actuele MOD-01-allowlist, direct-callsourcecheck en qualityscore;
- de gemergede eind-SHA en volledige groene Maven-/Docker-/Flutter-/CI-resultaten.

Plan 07 mag pas starten wanneer plan 06 in `VOORTGANG.md` `AFGEROND` staat en een nieuwe agent vanaf
de actuele default branch `mvn clean verify`, de canonieke volledige repositorygate en alle overige
planbrede verificatie opnieuw groen kan uitvoeren. Plan 07 voert vervolgens mechanische
module-rootmigraties uit en mag de inhoudelijke refactors uit dit plan niet opnieuw openen.
