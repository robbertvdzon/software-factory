# Plan 02 — Directe reparaties

**Status:** NIET GESTART<br>
**Werkpakketten:** FIX-02, FIX-03, FIX-04, FIX-05, FIX-06, OPS-01<br>
**Model:** GPT-5.6 Sol<br>
**Effort:** Medium<br>
**Waarom dit niveau:** de defecten zijn concreet en reproduceerbaar, maar raken release-automation,
Docker, lokale authenticatie/bridge, wiretypes, trackerfouten en veilige cleanup. De veilige
volgorde en onafhankelijke regressietests zijn belangrijker dan brede herarchitectuur.<br>
**Bron:** docs/verbeterplan-onderhoudbaarheid-2026-07.md<br>
**Voortgang:** docs/verbetertraject-2026-07/VOORTGANG.md

> Harde regel: geen enkele falende test mag worden genegeerd. Pre-existing, flaky,
> omgevingsgebonden en ongerelateerde failures zijn blockers. Geen skip, bypass, quarantine,
> suppressie of kleinere suite geldt als groen bewijs.

## Doel en resultaat

Na dit plan werkt de releasebot onder protected main, bouwen agent- en assistantimages
reproduceerbaar, is de lokale quickstart kopieerbaar, stuurt de bridge echte booleans, sluit de
costmonitor stale runs op een getypeerde not-foundfout en verwijdert cleanup nooit actieve
workspaces.

## Prerequisites

1. De plandocumentatie van SF-925 is gemerged.
2. Default branch en verplichte checks zijn groen; de worktree is schoon.
3. Docker en Docker Compose zijn beschikbaar.
4. GitHub-toegang voor workflow-/PR-verificatie is beschikbaar voordat FIX-02 wordt afgerond.
5. Lees UITVOERREGELS.md, VOORTGANG.md, het volledige bronplan en dit bestand.

Plan 01 mag alleen in een strikt gescheiden worktree parallel lopen. Deel geen storybranch of
onafgeronde wijzigingen. Rebase ieder werkpakket op de laatste groene default branch.

## Kopieerbare startopdracht

    Voer plan 02 volledig autonoom uit volgens
    docs/verbetertraject-2026-07/02-directe-reparaties-medium.md.

    Lees eerst UITVOERREGELS.md, VOORTGANG.md en het volledige bronplan. Maak voor FIX-02,
    FIX-03, FIX-04, FIX-05, FIX-06 en OPS-01 elk een eigen niet-gestarte Factory-story,
    branch en PR. Volg exact de interne volgorde uit het plan en begin een volgende story pas
    wanneer de vorige is gemerged en post-merge groen. Laat developer, reviewer en tester alle
    gates uitvoeren, negeer geen enkele falende test, werk VOORTGANG.md bij iedere overdracht
    bij en stop alleen bij een echte externe blokkade of onomkeerbare productbeslissing.

## Verplichte interne volgorde

1. FIX-02 — releasebot via PR onder branch protection.
2. FIX-03 — één robuuste Docker-mini-reactorbasis.
3. FIX-04 — quickstart en Compose bovenop de gerepareerde Dockerbasis.
4. FIX-05 — getypeerde force-refresh, daarna ook via de werkende bridge-smoke controleren.
5. FIX-06 — getypeerde tracker-not-foundfout.
6. OPS-01 — actieve workspaces expliciet uitsluiten van cleanup.
7. Planbrede eindverificatie en overdracht naar plan 03.

FIX-03 moet vóór FIX-04 omdat beide Docker-/buildcontext raken. Alle stories worden afzonderlijk
gemerged; combineer hotfixes niet.

## Baseline vóór iedere story

    git status --short
    git log -1 --oneline
    mvn verify

Voer bij Kotlinwijzigingen ook ./quality/run.sh uit. Bij Flutter- of Flutterworkflowscope:

    cd dashboard-frontend
    flutter analyze
    flutter test

Leg commando, exitcode, datum/tijd en tellingen vast.

Voor ieder gericht Mavencommando met `-Dsurefire.failIfNoSpecifiedTests=false` geldt aanvullend:
de optie is uitsluitend bedoeld om `-am`-dependency-modules zonder match niet te laten falen. Leg
uit vers gegenereerde Surefire-/Failsafe-rapporten vast dat iedere expliciete doelmodule waarvoor
het commando bewijs claimt meer dan nul geselecteerde tests heeft uitgevoerd. Nul tests in de
doelmodule is rood, ook bij exitcode 0; een oud rapport geldt niet als bewijs.

---

## FIX-02 — Image-manifestupdates onder protected main

**Voorgestelde Factory-storytitel:** Releasebot werkt via PR onder branch protection<br>
**Prioriteit / omvang:** P0 / S-M

### Probleem en bewijs

.github/scripts/bump-images.sh commit en pusht rechtstreeks naar main. Branch protection weigert
dit; retries veranderen een policy rejection niet. Daardoor kan een image gebouwd zijn terwijl
deploy/base/kustomization.yaml op een oude SHA blijft.

### Concrete stappen

1. Reproduceer of leg de falende workflowrun en huidige scriptflow vast.
2. Splits bumpberekening, branchnaam, commit en PR-open/update in testbare shellfuncties.
3. Laat de bot een componentgebonden tijdelijke branch gebruiken en uitsluitend de relevante
   image-SHA wijzigen.
4. Open of update een traceerbare PR; laat normale required checks draaien en merge alleen via
   auto-merge of een expliciete vervolgjob zonder bypass.
5. Voeg concurrency per component toe. Zorg dat backend- en frontendbumps elkaars wijziging
   behouden en dat reruns idempotent zijn.
6. Maak manifestupdates monotoon per component: een oudere workflowrun of PR mag een al zichtbare
   nieuwere SHA nooit terugzetten. Voeg een deterministische out-of-ordertest toe waarin run A met
   een oude SHA pauzeert, run B met een nieuwere SHA voltooit en A daarna hervat; het eindmanifest
   blijft op B en de PR van A wordt aantoonbaar als superseded gemarkeerd en gesloten.
7. Classificeer alleen echte netwerk-/pushraces als retryable. Policy rejection faalt direct met
   diagnose.
8. Voorkom dat een manifest-only merge hetzelfde image opnieuw bouwt.
9. Voeg een shell-/integratietest toe met een tijdelijke bare Git-repo en fake gh-interface.

### Acceptatiecriteria

- Geen workflowtoken pusht rechtstreeks naar protected main.
- Iedere gebouwde SHA is via een traceerbare bump-PR terug te vinden; alleen de nieuwste geldige SHA
  per component blijft merge-eligible en bereikt het eindmanifest.
- Parallelle backend-/frontendbumps behouden beide updates.
- Rerun opent geen stapel duplicaat-PR's en verliest geen nieuwere SHA.
- De out-of-ordertest A oud → B nieuw → A hervat eindigt op B; A kan het manifest niet downgraden
  en haar PR wordt aantoonbaar als superseded gemarkeerd en gesloten.
- Manifest-only merges veroorzaken geen rebuild-loop.
- Backend- en frontendworkflow eindigen volledig groen.

### Gerichte verificatie

    bash -n .github/scripts/bump-images.sh
    # Voer de nieuwe shell-/integratietests uit volgens hun repositorycommando.
    rg -n 'git push.*main|push.*HEAD:main' .github

### Volledige verificatie

    mvn verify

Start daarna beide workflow_dispatch-runs en controleer image, bump-PR, required checks,
manifest-SHA en post-merge status op de actuele SHA.

### Buiten scope

Geen branch-protectionbypass, geen deployarchitectuurwijziging en nog geen nieuwe CI-aggregator;
VER-02 doet dat.

### Reviewer/tester

Reviewer controleert permissions, concurrency, idempotentie, looppreventie en secretredactie.
Tester voert de bare-repotest en beide echte workflowpaden uit en accepteert geen gedeeltelijk
groene workflow.

---

## FIX-03 — Reproduceerbare agent-imagebuild

**Voorgestelde Factory-storytitel:** Dockerfile.agent bouwt reproduceerbaar uit root-context<br>
**Prioriteit / omvang:** P0 / S

### Probleem en bewijs

Dockerfile.agent kopieert een root-POM die vier childmodules noemt, maar slechts factory-common en
agentworker zijn aanwezig. Maven parseert de ontbrekende modules vóór projectselectie. Het
dashboard-backend-Dockerfile heeft een afwijkende sed-workaround; twee recepten kunnen opnieuw
divergeren.

### Concrete stappen

1. Leg het falende schone build-stagecommando vast.
2. Ontwerp één expliciet mini-reactorpatroon of gedeeld script voor agentworker en
   dashboard-backend; vermijd twee fragiele sedvarianten.
3. Zorg dat alleen benodigde POMs/bronnen worden gekopieerd en Docker-layercache bruikbaar blijft.
4. Bewijs dat factory-common, agentworker en runtime dependencies in agent:local zitten.
5. Bouw ook assistant:local, omdat Dockerfile.assistant daarvan afhangt.
6. Voeg een goedkope CI-smoke voor minimaal de agent build-stage toe, zonder tests over te slaan
   als bewijs voor de uiteindelijke volledige build.

### Acceptatiecriteria

- Schone docker build --target build -f Dockerfile.agent . slaagt.
- ./factory build-images maakt werkende agent:local en assistant:local.
- Het gedeelde patroon werkt ook voor dashboard-backend.
- CI faalt bij opnieuw ontbrekende reactorpaden of dependencies.

### Gerichte verificatie

    docker build --no-cache --target build -f Dockerfile.agent .
    ./factory build-images
    docker image inspect agent:local assistant:local

Voer een minimale AgentCli-/image-startsmoke uit zonder secrets te loggen.

### Volledige verificatie

    mvn verify

Controleer de nieuwe CI-smoke op de uiteindelijke PR en na merge.

### Buiten scope

Geen Maven-modules splitsen, geen root-POM-parentrefactor en geen agentgedrag wijzigen.

### Reviewer/tester

Reviewer controleert reproduceerbaarheid, layercache en dat het mini-reactorpatroon niet stil
modules overslaat. Tester bouwt vanaf een schone Dockercache en valideert beide eindimages.

---

## FIX-04 — Werkende lokale quickstart

**Voorgestelde Factory-storytitel:** Werkende lokale Compose-, SSO- en bridge-quickstart<br>
**Prioriteit / omvang:** P0 / M<br>
**Afhankelijk van:** gemergede FIX-03

### Probleem en bewijs

README/Compose gebruiken verkeerde context/commands, secrets.env.example mist verplichte
Google-SSOwaarden en bevat oude passwordconfig, de dunne backend krijgt obsolete databaseconfig,
het bridgevoorbeeld gebruikt 8081 in plaats van Composepoort 9090 en het losse Maven-startcommando
installeert factory-common niet.

### Concrete stappen

1. Corrigeer Compose build-context naar repositoryroot en het juiste Dockerfilepad.
2. Verwijder obsolete database- en username/passwordconfig uit backend-Compose en voorbeelden.
3. Voeg veilige placeholders voor SF_GOOGLE_CLIENT_ID, SF_DASHBOARD_REMEMBER_SECRET,
   SF_BRIDGE_TOKEN en lokale SF_BRIDGE_URLS toe; log nooit waarden.
4. Maak ./factory local-services en ./factory start canoniek. Documenteer
   ws://localhost:9090/bridge en dezelfde token aan beide kanten.
5. Breng README, runbook, secrets.env.example, docs/factory/secrets-local.md en relevante
   deployment/developmentdocs in lijn.
6. Voeg een herhaalbaar smoke-script toe: build/start, service-health en `/healthz`; bewijs daarna
   zowel een negatieve ongeauthenticeerde API-call met exact `401` als een deterministische
   geauthenticeerde call naar `/api/v1/status` met `200` en JSON `connected=true` nadat factory is
   gestart. Verkrijg/bewaar het smoketoken zonder de waarde in commandoregel, output of logs te
   tonen.
7. Voeg cleanup/trap toe zodat de smoke geen containers of tijdelijke secrets achterlaat.

### Acceptatiecriteria

- Een nieuwe developer kan de quickstart vanaf een schone checkout letterlijk volgen.
- Backend/frontend bouwen; backend is healthy; bridge wordt connected.
- De smoke bewijst afzonderlijk de ongeauthenticeerde `401` en de geauthenticeerde
  `/api/v1/status`-response met `connected=true`; een `401` geldt nooit als bewijs van een werkende
  geauthenticeerde bridge.
- Voorbeeldconfig bevat alle vereiste en geen dode login-/databasekeys voor de backend.
- Alle lokale docs noemen dezelfde commands, poorten en configprecedence.

### Gerichte verificatie

    docker compose -f docker/docker-compose.yml config
    ./factory local-services
    curl --fail http://localhost:9090/healthz
    # Voer het nieuwe Compose-/bridge-smokescript uit.

### Volledige verificatie

    mvn verify
    cd dashboard-frontend
    flutter analyze
    flutter test

Herhaal de quickstart-smoke vanaf een schone Compose-toestand en ruim daarna gecontroleerd op.

### Buiten scope

Geen productie-OIDC-provider aanmaken, geen echte secrets committen en geen bridgeprotocol
herontwerpen.

### Reviewer/tester

Reviewer volgt de documentatie alsof er geen lokale voorkennis is en controleert secretredactie.
Tester voert exact de gepubliceerde quickstart uit, inclusief teardown en reconnect.

---

## FIX-05 — Getypeerde force-refresh over de bridge

**Voorgestelde Factory-storytitel:** Dashboard refresh omzeilt caches daadwerkelijk<br>
**Prioriteit / omvang:** P0 / S

### Probleem en bewijs

BridgeApiController serialiseert force als JSON-string, terwijl BridgeRequestHandler een
JSON-boolean verwacht. Expliciet verversen omzeilt de cache daardoor niet.

### Concrete stappen

1. Voeg eerst controllertests toe die het exacte bridgeframe voor projects, downloads en builds
   vastleggen.
2. Maak de parameterhelper typeveilig genoeg voor boolean zonder willekeurige JsonNode-casts.
3. Stuur force=true/false als echte JSON-booleans; laat ontbrekend veld zijn bestaande default
   behouden.
4. Voeg factory-common-contractfixtures/tests toe die beide brugzijden tegen hetzelfde type testen.
5. Verifieer via de lokale bridge uit FIX-04 dat force=true werkelijk de cache omzeilt.
6. Leg vóór en na de wijziging de numerieke qualityscore van `./quality/run.sh` vast met commit-SHA,
   datum/tijd en exitcode. Onderzoek iedere nieuwe finding; de eindscore mag niet stijgen.

### Acceptatiecriteria

- Alle drie endpoints sturen boolean true/false en geen strings.
- Ontbrekend forceveld blijft backward-compatible.
- Contracttest faalt bij toekomstige type-afwijking.
- Een zichtbare/instrumenteerbare cachetest bewijst echte refresh.
- De quality-nameting is vastgelegd en de totale qualityscore is gelijk of lager dan de baseline.

### Gerichte verificatie

    mvn -pl factory-common,dashboard-backend,softwarefactory -am -Dtest='*Bridge*Test' \
      -Dsurefire.failIfNoSpecifiedTests=false test
    ./quality/run.sh

### Volledige verificatie

    mvn verify

Voer aanvullend de lokale bridge-smoke voor missing, false en true uit.

### Buiten scope

Geen volledig getypeerd commandregister of bridge-/webrefactor; ARC-01 volgt later.

### Reviewer/tester

Reviewer controleert wirecompatibiliteit en dat de helper niet een nieuwe untyped escape hatch
wordt. Tester inspecteert frames én observeert cachegedrag.

---

## FIX-06 — Getypeerde tracker-not-foundfout

**Voorgestelde Factory-storytitel:** Stale story-runs sluiten bij ontbrekend Postgres-issue<br>
**Prioriteit / omvang:** P0 / S-M

### Probleem en bewijs

PostgresTrackerClient meldt onbekende issues met generieke TrackerApiException. CostMonitorService
classificeert nog op de oude tekst status 404; de test gebruikt dezelfde oude tekst en maskeert de
regressie. Stale actieve runs blijven daardoor iedere poll fouten geven.

### Concrete stappen

1. Voeg een typed TrackerIssueNotFoundException of gelijkwaardig sealed resultaat toe.
2. Laat PostgresTrackerClient uitsluitend dit type gebruiken voor ontbrekende issue-keys.
3. Laat alle relevante consumenten op type beslissen; verwijder exceptiontekstclassificatie.
4. Zoek repositorybreed naar 404-/message-matching rond trackerfouten.
5. Voeg unit-tests en exact
   `softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/StaleTrackerRunClosureE2eTest.kt`
   toe. Deze Failsafe-test gebruikt Testcontainers Postgres, maakt een actieve stale run zonder
   bijbehorend issue en bewijst over twee polls dat de run eenmaal wordt gesloten en daarna stil
   blijft.
6. Behoud transport-/databasefouten als technische fouten; classificeer ze niet als not found.

### Acceptatiecriteria

- Geen tracker-not-foundbesluit is afhankelijk van exceptiontekst.
- Stale run wordt gesloten en veroorzaakt geen herhaalde pollfout.
- Echte databasefout wordt niet als not found verborgen.
- Tests gebruiken actuele Postgressemantiek, niet historische externe-trackertekst.
- `StaleTrackerRunClosureE2eTest` bestaat onder het exact voorgeschreven pad en draait via
  Failsafe `verify` tegen Testcontainers Postgres.

### Gerichte verificatie

    rg -n 'status 404|message.*404|contains.*404' softwarefactory/src
    mvn -pl softwarefactory -am -Dtest='*CostMonitorServiceTest,*PostgresTrackerClientTest' \
      -Dsurefire.failIfNoSpecifiedTests=false test
    mvn -pl softwarefactory -am -Dit.test=StaleTrackerRunClosureE2eTest \
      -Dsurefire.failIfNoSpecifiedTests=false verify

De exact benoemde Failsafe-klasse is verplicht; een wildcard of kleinere test vervangt haar niet.

### Volledige verificatie

    mvn verify
    ./quality/run.sh

### Buiten scope

Geen brede TrackerApi-capabilitysplit of databaseschemamigratie; ARC-04 doet dit.

### Reviewer/tester

Reviewer controleert het fouttype over modulegrenzen en dat infrastructuurfouten zichtbaar blijven.
Tester verifieert twee opeenvolgende polls en de negatieve databasefoutcase.

---

## OPS-01 — Voorkom cleanup van actieve workspaces

**Voorgestelde Factory-storytitel:** Actieve workspaces zijn hard uitgesloten van retention-cleanup<br>
**Prioriteit / omvang:** P1 / M

### Probleem en bewijs

De docs garanderen dat actieve workmappen nooit worden verwijderd, maar WorkCleanupPoller beslist
alleen op mtime. Een langdurige stille agent, storycheckout of assistantsessie kan theoretisch
worden verwijderd terwijl die actief is.

### Concrete stappen

1. Inventariseer de vier beheerde roots en de duurzame bronnen waarmee actieve agent-, story- en
   assistantsessiepaden kunnen worden bepaald.
2. Introduceer een kleine injecteerbare active-workspacebron; voorkom een afhankelijkheid op een
   brede runtimefacade.
3. Normaliseer paden en sla ieder actief pad en noodzakelijke ancestor/top-level entry vóór
   leeftijdsberekening en delete over.
4. Gebruik mtime alleen voor niet-actieve kandidaten; behoud de bestaande root-escapebeveiliging.
5. Definieer de exacte grens: jonger dan retentie blijft, exact op/over grens mag alleen weg als
   niet actief.
6. Voeg tests toe voor actieve oude map, inactieve oude map, boundary, nested assistantsessie,
   verdwenen racekandidaat, symlink/root escape en fouttolerantie.
7. Werk scheduled-jobs.md, technical-spec en runbook bij met de werkelijk afgedwongen garantie.

### Acceptatiecriteria

- Geen actief agent-, story- of assistantpad wordt verwijderd, ongeacht mtime.
- Inactieve verlopen entries worden nog steeds verwijderd.
- Boundary- en racegedrag is deterministisch getest.
- Cleanup faalt veilig per entry en verwijdert niets buiten de beheerde roots.

### Gerichte verificatie

    mvn -pl softwarefactory -am -Dtest='*WorkCleanup*Test' \
      -Dsurefire.failIfNoSpecifiedTests=false test
    ./quality/run.sh

### Volledige verificatie

    mvn verify

Herhaal na merge de cleanup-tests en mvn verify op de gemergede SHA.

### Buiten scope

Geen algemene lifecycle-/completionrefactor en geen nieuwe retentieproducten of UI.

### Reviewer/tester

Reviewer controleert normalisatie, racevensters en dat actieve bronfouten fail-safe zijn. Tester
gebruikt een actieve map met oude mtime en verifieert dat alleen inactieve siblings verdwijnen.

## Planbrede eindverificatie

Voer na OPS-01 op de gemergede default branch uit:

    git status --short
    mvn verify
    ./quality/run.sh
    cd dashboard-frontend
    flutter analyze
    flutter test

Voer daarnaast uit:

- agent- en assistant-imagebuild/smoke;
- Compose-/health-/bridge-smoke met cleanup;
- FIX-02 shelltests en echte backend-/frontendworkflowcontrole;
- controle dat alle relevante post-mergechecks groen zijn.

Iedere failure heropent de verantwoordelijke story of krijgt eerst een aparte blokkerende story.

## Plan-afronding en overdracht

Plan 02 is pas AFGEROND wanneer alle zes stories afzonderlijk zijn gemerged, reviewer/tester op de
uiteindelijke commits akkoord zijn, de volledige planbrede gates groen zijn en VOORTGANG.md per
story storykey, branch, PR, SHA en testbewijs bevat.

Overdracht naar plan 03:

- FIX-02: botbranch-/PR-/concurrencycontract en actuele workflowbestanden;
- FIX-03: mini-reactor- en image-smokecommando;
- FIX-04: canonieke quickstart, poorten en configmatrix;
- FIX-05: definitieve force-wiretypefixtures;
- FIX-06: typed not-foundcontract en consumenten;
- OPS-01: active-workspacebron en retentiegrens;
- expliciete bevestiging dat geen falende test is genegeerd.

Plan 03 start pas wanneer plan 01 én plan 02 in VOORTGANG.md AFGEROND en post-merge groen zijn.
