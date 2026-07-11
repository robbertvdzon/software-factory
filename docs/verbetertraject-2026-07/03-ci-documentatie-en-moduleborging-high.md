# Plan 03 — CI-, documentatie- en moduleborging

**Status:** NIET GESTART<br>
**Werkpakketten:** VER-02, MOD-01, DOC-01<br>
**Model:** GPT-5.6 Sol<br>
**Effort:** High<br>
**Waarom dit niveau:** dit plan verandert blijvend mergebeleid, branch-protectionchecks,
repositorybrede documentatie-inventarissen en de toegestane publieke Modulith-oppervlakte. Te
zwakke gates geven schijnzekerheid; te strakke gates kunnen iedere PR blokkeren.<br>
**Bron:** docs/verbeterplan-onderhoudbaarheid-2026-07.md<br>
**Voortgang:** docs/verbetertraject-2026-07/VOORTGANG.md

> Harde regel: geen enkele falende test mag worden genegeerd. Iedere unit-, integratie-, e2e-,
> contract-, Flutter-, Docker-, smoke- of documentatie-auditfailure is een blocker, ongeacht
> ouderdom, vermeende relatie, flakiness of omgeving.

## Doel en resultaat

Na dit plan blokkeert één stabiele aggregator iedere Kotlin-, Flutter-, Modulith-,
agent-image- of documentatieregressie. De publieke moduleconventie is gedocumenteerd en
testgedreven afgedwongen met een alleen-krimpende tijdelijke allowlist. Actuele documentatie is
feitelijk correct en inventarisdrift faalt automatisch.

## Harde prerequisites

Start niet voordat alle punten waar zijn:

1. plan 01 staat AFGEROND in VOORTGANG.md;
2. plan 02 staat AFGEROND in VOORTGANG.md;
3. alle stories uit beide plannen zijn gemerged;
4. post-merge mvn verify, Fluttergates, image-/Compose-smokes en relevante workflows zijn groen;
5. de actuele default branch en worktree zijn schoon;
6. de overdracht bevat mergepolicy/checkconfig, verification-evidence, botflow, Docker-mini-reactor
   en quickstartcommando's.

Ontbrekend bewijs is een blokkade, niet een uitnodiging om aannames te maken.

Lees vóór wijziging volledig:

- docs/verbetertraject-2026-07/UITVOERREGELS.md;
- docs/verbetertraject-2026-07/VOORTGANG.md;
- docs/verbeterplan-onderhoudbaarheid-2026-07.md;
- plannen 01 en 02 plus hun gemergede implementaties;
- dit bestand.

## Kopieerbare startopdracht

    Voer plan 03 volledig autonoom uit volgens
    docs/verbetertraject-2026-07/03-ci-documentatie-en-moduleborging-high.md.

    Verifieer eerst dat plan 01 en plan 02 volledig gemerged en groen zijn. Lees
    UITVOERREGELS.md, VOORTGANG.md, het bronplan en beide overdrachten. Maak voor VER-02,
    MOD-01 en DOC-01 elk een afzonderlijke niet-gestarte Factory-story, branch en PR. Volg de
    verplichte volgorde, merge en verifieer iedere story voordat de volgende start. Negeer geen
    enkele falende test. Werk VOORTGANG.md bij iedere overdracht bij, wijzig branch protection
    zonder bypass en stop alleen bij een echte externe blokkade of onomkeerbare productbeslissing.

## Verplichte volgorde

1. VER-02 — stabiele volledige PR-verificatie en veilige checkmigratie.
2. MOD-01 — module-API-conventie en regressietest bovenop de nieuwe CI-gate.
3. DOC-01 — feiten herstellen en audit als laatste, zodat ook VER-02 en MOD-01 worden meegenomen.
4. Planbrede eindcontrole en overdracht naar plan 04.

Iedere stap krijgt een eigen story/branch/PR en wordt post-merge groen voordat de volgende begint.

## Baseline vóór iedere story

    git status --short
    git log -1 --oneline
    mvn verify
    ./quality/run.sh
    cd dashboard-frontend
    flutter analyze
    flutter test

Voor Docker-/CI-scope ook de agent build-stage en relevante scripts uitvoeren. Leg datum/tijd,
exitcodes, tellingen, SHA en qualityscore vast.

Voor ieder gericht Mavencommando met `-Dsurefire.failIfNoSpecifiedTests=false` geldt aanvullend:
de optie is uitsluitend bedoeld om `-am`-dependency-modules zonder match niet te laten falen. Leg
uit vers gegenereerde Surefire-/Failsafe-rapporten vast dat iedere expliciete doelmodule waarvoor
het commando bewijs claimt meer dan nul geselecteerde tests heeft uitgevoerd. Nul tests in de
doelmodule is rood, ook bij exitcode 0; een oud rapport geldt niet als bewijs.

---

## VER-02 — Breid verplichte PR-verificatie uit

**Voorgestelde Factory-storytitel:** Eén stabiele repositoryverificatie voor backend, Flutter en agent-image<br>
**Prioriteit / omvang:** P1 / M<br>
**Afhankelijk van:** FIX-01, FIX-02 en FIX-03

### Probleem en bewijs

De huidige required check dekt Maven, maar niet Flutteranalyse/tests of de agent-Dockerbuild.
Losse pathfilters kunnen een required check laten ontbreken. Workflownaam, runtime mergepolicy en
branch protection kunnen uit elkaar lopen. Imagepublicatie mag niet vóór volledige verificatie.

### Concrete stappen

1. Inventariseer bestaande workflows, checknamen, branch protection, FIX-01-projectpolicy en
   FIX-02-imagepublicatie. Leg de actuele groene uitgangsrun vast.
2. Voeg afzonderlijke jobs toe voor:
   - backend: volledige Maven verify inclusief Modulith en failsafe;
   - frontend: gepinde Flutterversie, pub get, flutter analyze en flutter test;
   - agent-image: reproduceerbare build-stage-smoke uit FIX-03.
3. Introduceer één stabiele eindcheck, Repository verification, die met always-semantiek alle
   vereiste jobresultaten beoordeelt. Een bewust niet-relevant pad mag intern als gecontroleerd
   succes worden behandeld; de aggregator zelf moet altijd bestaan.
4. Lever daarnaast één versioned, repositorylokaal **canoniek volledig gate-entrypoint** op
   (bijvoorbeeld `tools/verify-repository`, of een aantoonbaar bestaand equivalent) met stabiele
   command-id/configversie. Vanaf een schone checkout voert dit entrypoint alle lokaal uitvoerbare
   Maven-, Flutter-, image-/runtime-smoke- en documentatiegates fail-closed uit en rapporteert het
   per onderdeel command, exitcode en telling. CI gebruikt dezelfde onderliggende commands; er
   ontstaat geen tweede, afwijkende lokale waarheid. Leg voor de uitsluitend externe
   branch-protection-/workflowcontrole een exact apart auditcommando vast.
5. Laat missing, cancelled, failed en onverwacht skipped nooit groen worden. Queued/in-progress
   blijft wachten.
6. Pin actions/toolversies voldoende om reproduceerbaarheid te waarborgen en gebruik minimale
   permissions plus componentgerichte concurrency.
7. Laat imagepublicatie uitsluitend na groene repositoryverificatie plaatsvinden; voorkom
   manifest-/image-loops uit FIX-02.
8. Voeg een branch-protectionaudit of declaratieve configuratie toe die expected required checks,
   strict/current-head en admin-/bypassbeleid controleert.
9. Migreer veilig:
   - voeg de nieuwe aggregator eerst toe;
   - bewijs die groen op een PR en default-branch-SHA;
   - update daarna FIX-01-projectconfig en branch protection naar dezelfde naam;
   - houd de oude check verplicht totdat de nieuwe aantoonbaar beschikbaar is;
   - verwijder nooit tijdelijk alle gates en gebruik geen bypass.
10. Voeg tests/fixtures toe voor aggregatorbeslissingen, inclusief skipped/cancelled/missing, en
    contracttests voor het lokale gate-entrypoint: ontbrekende tooling, non-zero subcommand, timeout
    of nul geselecteerde verplichte tests maakt het geheel rood.
11. Werk technische CI-, merge- en runbookdocumentatie bij; DOC-01 doet later de brede eindcontrole.

### Acceptatiecriteria

- Kapotte Kotlin-, Modulith-, Dart- of agent-Dockerwijziging blokkeert merge.
- Repository verification bestaat op iedere relevante PR en is alleen groen als alle verplichte
  componentgates groen zijn.
- Pathfilters kunnen de stabiele required check niet laten verdwijnen.
- Runtime mergepolicy, workflow en branch protection gebruiken dezelfde beheerste checknaam.
- Imagepublicatie start pas na groen en veroorzaakt geen bump-loop.
- Branch-protectionaudit detecteert missing/extra/verkeerde checks en bypassdrift.
- Het versioned lokale gate-entrypoint is vanaf een schone checkout letterlijk uitvoerbaar, gebruikt
  dezelfde commands als CI en faalt op iedere ontbrekende/rode component; command-id en
  configversie staan in de overdracht.

### Gerichte verificatie

    # Voer de toegevoegde workflow-/aggregatorunittests of scripts uit.
    mvn -pl factory-common,softwarefactory -am -Dsurefire.failIfNoSpecifiedTests=false test
    cd dashboard-frontend
    flutter analyze
    flutter test
    cd ..
    docker build --target build -f Dockerfile.agent .

Maak tijdelijke, niet te mergen negatieve PR-commits of testfixtures waarmee iedere componentjob
en de aggregator aantoonbaar rood worden; herstel ze vóór de definitieve commit.

### Volledige verificatie

    mvn verify
    cd dashboard-frontend
    flutter analyze
    flutter test
    cd ..
    ./factory build-images
    # Voer branch-protectionaudit en alle CI-smokes uit.

Controleer de echte PR, de veilige checkmigratie en post-merge Repository verification op de
gemergede SHA.

### Buiten scope

- Geen branch-protectionbypass.
- Geen algemene quality/Detekt-deltagate; QLT-01 volgt later.
- Geen nieuwe releasearchitectuur buiten de FIX-02-flow.
- Geen componenttest overslaan vanwege pathfilters.

### Reviewer/tester

Reviewer controleert permissions, eventtriggers, concurrency, always-logica, checknaamtransitie en
publicatievolgorde. Tester bewijst met negatieve fixtures dat ieder component en missing/skipped/
cancelled de aggregator blokkeert, en valideert daarna de uiteindelijke echte workflowrun.

### Afronding VER-02

Leg oude/nieuwe checknaam, branch-protectionstatus, workflowruns, PR, merge-SHA, het exacte
versioned lokale gate-entrypoint met command-id/configversie, het exacte externe auditcommando en
alle onderliggende gates vast. Begin MOD-01 pas na groene post-merge aggregator.

---

## MOD-01 — Publieke module-API-conventie afdwingen

**Voorgestelde Factory-storytitel:** Uniforme Modulith-conventie voor API, publieke modellen en internals<br>
**Prioriteit / omvang:** P2 / M<br>
**Afhankelijk van:** gemergede VER-02

### Probleem en bewijs

Modulepubliekheid volgt nu toevallige packagelocatie. Concrete Telegram-/bridgeimplementaties staan
in roots, DTO's en APIs zijn gemengd en web.services is breed geëxposeerd om bridge toegang te
geven. Zonder afdwingbare conventie kan iedere volgende story nieuwe interne details publiceren.

### Doelconventie

- Een featuremodule-root bevat uitsluitend smalle publieke interfaces/ports en
  package-/modulemetadata.
- services, clients, repositories, schedulers en configurations zijn intern.
- Een named interface models bevat uitsluitend publieke immutable Kotlin data classes die
  aantoonbaar in een publieke API-signature of echte cross-moduledependency voorkomen. Alle
  properties zijn `val` en geen publiek model exposeert een mutable collectie of andere mutable
  state; `models` blijft data-class-only.
- Publieke enums, sealed/value types staan in een afzonderlijke bewust geëxporteerde types-interface.
- Iedere module met getypeerde publieke fouten gebruikt een afzonderlijk package met exact
  `@NamedInterface("errors")`. Dit package bevat uitsluitend exceptions die aantoonbaar deel zijn
  van een publieke API-signature of cross-modulecontract. Interne exceptions, generieke
  `RuntimeException`-wrappers, modellen, DTO's en services zijn daar verboden; publieke exceptions
  horen niet in de module-root, `models` of `types`.
- Interne DTO's blijven intern.
- web.services moet na ARC-01 verdwijnen; tot die tijd staat iedere bestaande afwijking expliciet
  in een alleen-krimpende allowlist.

### Concrete stappen

1. Inventariseer alle Modulith-roots, named interfaces, cross-module-imports, publieke modellen en
   Spring-stereotypes. Leg ApplicationModules.verify en qualitybaseline vast.
2. Documenteer de conventie in technical-spec en een korte ADR/moduleconventie met voorbeelden en
   beslisregels voor model versus type versus intern.
3. Voeg een architectuurtest toe die minimaal faalt op:
   - concrete productietypes in een module-root;
   - Spring-components/clients/repositories/services in publiek models;
   - niet-data-class of intern-only type in models;
   - een `var`, geëxposeerde `MutableCollection` of andere publiek muteerbare state in models;
   - een publiek fouttype buiten `@NamedInterface("errors")`, of een niet-exception, interne-only
     exception, generieke wrapper, DTO, model of service binnen `errors`;
   - cross-module-import van intern subpackage;
   - nieuw geëxporteerd model zonder publiek/cross-modulegebruik.
4. Maak een expliciete tijdelijke allowlist per bestaand bestand/type met reden en eigenaarplan.
   Geen wildcardpackage, patroon of automatisch geaccepteerde groei.
5. Leg publieke enums/sealed/value types vast via types in plaats van models te verwateren.
6. Voeg negative testfixtures of programmatic testcases toe die iedere regel aantoonbaar laten
   falen, zonder tijdelijke productiecode achter te laten. Neem minimaal fixtures op voor een
   data class met `var`, een data class met `MutableList`, een niet-exception in `errors`, een
   internal-only exception in `errors` en een publiek exceptiontype in het verkeerde package.
   Voeg daarnaast een positieve fixture toe waarin een cross-module caller uitsluitend een
   getypeerde publieke exception via `errors` gebruikt.
7. Laat de test onderdeel zijn van Maven verify en dus Repository verification.
8. Genereer of controleer een leesbare publieke API-inventaris per module.
9. Verplaats in deze story geen volledige modules; latere MOD-/ARC-plannen krimpen de allowlist.

### Acceptatiecriteria

- Kunstmatige concrete rootclass, Spring-model, gewone class in models, intern-only model en
  interne cross-module-import laten de test falen.
- `models` is aantoonbaar data-class-only en immutable: `var`, mutable collecties en andere
  geëxposeerde mutable state laten de architectuurtest falen.
- Publieke getypeerde exceptions zijn uitsluitend via een aparte `@NamedInterface("errors")`
  zichtbaar; de positieve cross-modulefixture werkt en ieder verboden errors-type faalt.
- Bestaande afwijkingen staan individueel en gemotiveerd in een alleen-krimpende allowlist.
- Een nieuwe afwijking vereist een zichtbare test-/allowlistwijziging en kan niet stil passeren.
- ApplicationModules.verify en volledige Mavenverificatie blijven groen.
- Conventie, test en gegenereerde/gecontroleerde API-inventaris spreken elkaar niet tegen.

### Gerichte verificatie

    mvn -pl softwarefactory -am -Dtest='*Modulith*Test,*Architecture*Test' \
      -Dsurefire.failIfNoSpecifiedTests=false test
    ./quality/run.sh

Voer de gedocumenteerde negatieve testcases uit en herstel iedere tijdelijke mutatie.

### Volledige verificatie

    mvn verify

Controleer Repository verification op de PR en gemergede SHA.

### Buiten scope

- Geen massale packageverplaatsing.
- Geen telegram-, dashboard-, tracker- of coremigratie uitvoeren.
- Geen wildcardallowlist, suppressie of verzwakte Modulith-check.
- Geen inhoudelijk gedrag veranderen.

### Reviewer/tester

Reviewer controleert dat de test semantisch en niet alleen naamgebaseerd afdwingt, dat models
werkelijk data-class-only is en dat de allowlist niet kan groeien zonder diff. Tester voert alle
negative fixtures en de bestaande Modulith-test onafhankelijk uit.

### Afronding MOD-01

Leg conventieversie, ADR, testklasse, allowlistitems, API-inventaris, qualitydelta en merge-SHA vast.
DOC-01 gebruikt dit als definitieve documentatiebron.

---

## DOC-01 — Herstel actuele documentatie en blokkeer drift

**Voorgestelde Factory-storytitel:** Actuele repositorydocumentatie met automatische driftcontrole<br>
**Prioriteit / omvang:** P1 / M-L<br>
**Afhankelijk van:** gemergede VER-02 en MOD-01

### Probleem en bewijs

README/local setup, docs/technical, runbook, frontend-README en docs-skeleton spreken code en elkaar
tegen. Bevestigde drift omvat foutieve module/package/endpoint/schedulertellingen, V14 in plaats van
V15, oud HTML-dashboard, adaptieve poller, dode login-/Copilotconfig, ontbrekende bridgearchitectuur
en een niet-uitvoerbare quickstart. Docs/technical wordt gegenereerd genoemd zonder generator of
audit.

### Concrete feiten die minimaal moeten kloppen

- 4 Mavenmodules en de losse Flutterfrontend.
- 11 Modulith-packages, inclusief bridge.
- 10 factory-HTTP-mappings, 38 dashboard-backendmappings en WebSocket /bridge.
- 5 Scheduled-methodes en 2 daemonthreads.
- Flyway V1 tot en met V15 en de tracker-tabellen.
- Dashboard-backend is een dunne bridge en queryt geen tracker-DB of GitHub.
- Geen ingebouwd HTML-dashboard.
- Vast backup-pollinterval plus event-driven wake.
- Projectbewuste mergepolicy en Repository verification uit plan 01/VER-02.
- Nightly subtasks.yaml is volledig leidend en vormt een uitzondering op auto-appended subtaken.
- Werkende quickstart, SSO-/bridgeconfig en poorten uit FIX-04.
- Werkelijk Copilotcredentialgedrag en geen dode username/password/remember-days/cookieconfig.
- Active-workspacegarantie uit OPS-01.
- Module-API-conventie en allowlist uit MOD-01.

### Concrete stappen

1. Maak een actuele machine-inventaris van POMmodules, top-level Modulith-packages, HTTP-mappings,
   WebSocketroutes, Scheduled-methodes, daemonthreads, Flywaybestanden en gelezen SF-keys.
2. Corrigeer minimaal README, runbook, docs/factory, docs/technical en
   dashboard-frontend/README.md. Wijzig geen historische docs/stories-documenten.
3. Maak de frontend-README projectspecifiek: toolchain, build/test, Google-login, backend/bridge,
   lokale en Dockercommands.
4. Maak één configmatrix met key, consumer, required/default, bron, precedence en secretstatus.
   Verwijder dode configuratiedocumentatie en echte dode voorbeeldkeys.
5. Synchroniseer essentiële developer/reviewer/testerinvarianten in docs-skeleton zonder
   repo-specifieke ingevulde waarden als generiek contract te presenteren. Een templateplaceholder
   is uitsluitend toegestaan wanneer hij in het skeletoncontract expliciet is verklaard met naam,
   betekenis, bootstrapbron en het moment waarop bootstrap hem vervangt. De audit onderscheidt
   deze verklaarde skeletonplaceholders van onverklaarde placeholders en van oningevulde waarden in
   een concrete actieve repository; die laatste twee blijven failures.
6. Verwijder het woord gegenereerd waar geen echte generator bestaat, of genereer de inventaris
   aantoonbaar.
7. Voeg een deterministische documentatie-audit toe die minimaal controleert:
   - root-POMmodules;
   - Modulith-roots;
   - HTTP-mappings per applicatie en /bridge;
   - Scheduled-methodes en Flywayversie;
   - geconfigureerde versus gedocumenteerde SF-keys;
   - verplichte skeletonbestanden en groene-/specinvarianten.
8. Laat de audit met testfixtures aantoonbaar falen bij een kunstmatig endpoint, package, migratie,
   configkey of ontbrekende skeletoninvariant.
9. Integreer de audit in Maven verify of Repository verification zonder een always-green wrapper.
10. Volg de quickstart letterlijk vanaf een schone toestand en corrigeer iedere stap die impliciete
    lokale voorkennis vereist.
11. Rebasecontroleer na alle wijzigingen dat VER-02-/MOD-01-docs en tellingen nog actueel zijn.

### Acceptatiecriteria

- Een nieuwe developer kan quickstart en testcommands letterlijk uitvoeren.
- Alle inventarissen komen overeen met code of worden automatisch gegenereerd.
- Configmatrix bevat geen gelezen-but-undocumented of documented-but-dead keys zonder expliciete
  motivering.
- Documentatie-audit faalt op iedere genoemde kunstmatige drift en draait in de required CI.
- Skeleton bevat essentiële test- en specinvarianten, maar geen echte secrets of misleidende
  repo-specifieke defaults.
- Expliciet verklaarde bootstrap-templateplaceholders in docs-skeleton zijn toegestaan en worden
  door de audit herkend; iedere onverklaarde placeholder of niet-vervangen placeholder in een
  concrete actieve repo faalt.
- Canonieke docs spreken elkaar niet tegen over bridge, poller, merge, nightly en cleanup.

### Gerichte verificatie

    # Voer de nieuwe documentatie-audit direct uit.
    rg -n 'ingebouwd HTML|adaptief|SF_DASHBOARD_PASSWORD|SF_COPILOT_CREDENTIALS_DIR|V1.*V14' \
      README.md runbook.md docs/factory docs/technical properties.default.env secrets.env.example
    docker compose -f docker/docker-compose.yml config
    # Volg de quickstart- en bridge-smoke uit FIX-04.

Onderzoek iedere rg-hit inhoudelijk; een historische of expliciet ontkennende vermelding is niet
automatisch fout.

### Volledige verificatie

    mvn verify
    ./quality/run.sh
    cd dashboard-frontend
    flutter analyze
    flutter test
    cd ..
    ./factory build-images
    # Voer Compose-/bridge-smoke, documentatie-audit en branch-protectionaudit uit.

Controleer Repository verification en de documentatie-audit opnieuw op de gemergede SHA.

### Buiten scope

- Geen historische story-/worklogdocumenten herschrijven.
- Geen productgedrag veranderen om verouderde tekst waar te maken.
- Geen handmatige telling zonder audit als blijvende oplossing.
- Geen brede modulemigratie; MOD-02/MOD-03 en ARC-01 volgen later.

### Reviewer/tester

Reviewer traceert iedere feitelijke claim naar code/config en controleert dat de audit niet
hardcoded dezelfde foutieve waarheid spiegelt. Tester volgt quickstart als nieuwe developer,
injecteert tijdelijke driftfixtures, draait alle volledige gates en accepteert geen dode link,
onverklaarde/misleidende placeholder of mislukte smoke. Een expliciet in het skeletoncontract
verklaarde bootstrap-templateplaceholder is bewust toegestaan zolang de concrete-repo-audit haar
na bootstrap niet meer aantreft.

## Planbrede eindverificatie

Na DOC-01 op de gemergede default branch:

    git status --short
    mvn verify
    ./quality/run.sh
    cd dashboard-frontend
    flutter analyze
    flutter test
    cd ..
    ./factory build-images

Voer ook uit:

- Repository verification en branch-protectionaudit;
- documentatie-audit inclusief negatieve fixtures;
- Compose-/SSO-/bridgequickstartsmoke met cleanup;
- controle van current-head required checks en imagepublicatievoorwaarden.

Iedere failure wordt hersteld en daarna worden gerichte én volledige gates herhaald.

## Plan-afronding en overdracht naar plan 04

Plan 03 is pas AFGEROND wanneer alle drie stories afzonderlijk zijn gemerged, de stabiele required
check actief is, de Modulith/API-gate onderdeel van verify is, de documentatie-audit drift detecteert,
alle planbrede gates op de gemergede SHA groen zijn en VOORTGANG.md alle stories/PR's/SHA's en
bewijs bevat.

Overdracht naar plan 04 — duurzame agent-completion:

- actuele default-branch-SHA en groene Repository verification-run;
- definitieve required checknaam plus branch-protectionauditcommando;
- locatie en gebruik van modulearchitectuurtest, allowlist en publieke API-inventaris;
- locatie en gebruik van documentatie-audit;
- actuele quickstart-, image- en Fluttergatecommando's;
- open tijdelijke allowlistitems met eigenaarplan;
- expliciete bevestiging dat geen enkele falende test is genegeerd.

Plan 04 start uitsluitend wanneer plan 03 in VOORTGANG.md AFGEROND staat en alle post-mergegates
nog groen zijn.
