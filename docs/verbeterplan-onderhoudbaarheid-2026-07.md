# Verbeterplan onderhoudbaarheid Software Factory

**Peildatum:** 11 juli 2026  
**Geanalyseerde commit:** `cc7cac2` (`main`)  
**Doelgroep:** losse developer-, reviewer- en tester-agents die geen context uit eerdere gesprekken hebben  
**Scope:** `factory-common`, `softwarefactory`, `agentworker`, `dashboard-backend`,
`dashboard-frontend`, build/deployconfiguratie en actuele documentatie

## 1. Samenvatting en oordeel

De repository is geen rommeltje. De grove architectuur is logisch: vier Maven-modules, een losse
Flutter-frontend, expliciete poorten, gedeelde wire-contracten, Flyway-migraties, een
Spring-Modulith-test en een groot geautomatiseerd testvangnet. Op de peildatum zijn er 41 tests in
`factory-common`, 465 unit-tests plus 40 integratie/e2e-tests in `softwarefactory`, 38 tests in
`agentworker` en 37 tests in `dashboard-backend`. De laatst uitgevoerde `mvn verify` was groen.

De code is wel organisch gegroeid. Er zijn te brede services, stringly typed transportcontracten,
verspreide configuratietoegang en documentatie die recente wijzigingen maar gedeeltelijk volgt.
Belangrijker: de audit vond meerdere actuele functionele en operationele fouten. Daarom is de
eindbeoordeling:

- **architectuurbasis:** goed;
- **testbasis:** goed voor Kotlin/backend, onvoldoende voor Flutter en agent-imagebouw;
- **module-isolatie:** redelijk, maar `bridge` en `web` zijn te sterk gekoppeld;
- **single responsibility:** onvoldoende in een herkenbare groep hotspots;
- **documentatiebetrouwbaarheid:** wisselend; `docs/factory` is bruikbaar, `docs/technical` en de
  lokale setup bevatten aantoonbare drift;
- **operationele staat:** eerst de P0-pakketten uitvoeren; de huidige release- en mergepaden hebben
  regressies.

## 2. Bevestigde uitgangsmeting

Voer vóór ieder werkpakket opnieuw een korte baseline uit, omdat de repository intussen gewijzigd
kan zijn.

| Onderdeel | Stand op peildatum |
| --- | --- |
| Maven-reactor | 4 modules: `factory-common`, `softwarefactory`, `agentworker`, `dashboard-backend` |
| Modulith-packages | 11: `bridge`, `config`, `core`, `knowledge`, `nightly`, `orchestrator`, `pipeline`, `runtime`, `telegram`, `tracker`, `web` |
| Backendtests | 621 totaal, inclusief 40 failsafe/Testcontainers-e2e-tests |
| Fluttertests | 5 testbestanden; niet opgenomen in de verplichte PR-check |
| Detekt | score 354 = 353 findings + 1 suppressie; alleen `softwarefactory/src/main` |
| Grootste Kotlin-class | `FactoryDashboardService.kt`, 831 regels en 37 functies |
| Grootste Dart-bestand | `overview_screens.dart`, 995 regels |
| Factory-HTTP-methodemappings | 10 |
| Dashboard-backend-methodemappings | 38, plus WebSocket `/bridge` |
| `@Scheduled`-methodes | 5, plus 2 eigen daemonthreads |

Belangrijkste Detekt-complexiteitshotspots zijn `DeploySubtaskHandler`, `AgentDispatcher`,
`SubtaskExecutionCoordinator`, `AgentRunCompletionService`, `FactoryDashboardService`,
`BridgeRequestHandler` en de Telegram-services. Het volledige lokale rapport wordt gemaakt met:

```bash
./quality/run.sh
```

De totaalscore bevat veel stijlbevindingen. Gebruik voor structurele prioritering vooral
`CyclomaticComplexMethod`, `LongMethod`, `NestedBlockDepth`, `LongParameterList` en
`TooManyFunctions`.

## 3. Regels voor iedere uitvoerende agent

Deze regels gelden voor alle onderstaande werkpakketten.

1. Lees dit hele werkpakket en de genoemde bronbestanden voordat je wijzigt.
2. Leg vóór de wijziging een reproduceerbare failing test of concrete baseline vast.
3. Houd de scope bij één werkpakket; combineer geen naburige refactors “omdat je er toch bent”.
4. Behoud bestaande wire-, database- en trackercontracten, tenzij het werkpakket expliciet een
   contractmigratie vraagt.
5. Voeg geen `@Suppress`, Detekt-disable of testskip toe om groen te worden.
6. Iedere rode test is een blocker, ook als deze pre-existing of ogenschijnlijk ongerelateerd is.
7. Draai minimaal de in het werkpakket genoemde gerichte tests en daarna vanaf de root:

   ```bash
   mvn verify
   ```

8. Bij Flutterwijzigingen ook:

   ```bash
   cd dashboard-frontend
   flutter analyze
   flutter test
   ```

9. Draai bij Kotlin-refactors vóór en na `./quality/run.sh`; de score mag niet stijgen en de
   relevante complexiteitsbevindingen moeten dalen.
10. Werk alle door gedrag of architectuur geraakte actuele documentatie in dezelfde story bij.
11. Pas geen historische documenten onder `docs/stories/` aan, behalve het worklog van de eigen
    story wanneer de factory daarom vraagt.
12. Een werkpakket is pas klaar als een reviewer de diff op scope, regressies, tests en docs heeft
    gecontroleerd.

## 4. Uitvoervolgorde

```text
Golf 0 — direct herstel
  FIX-01  mergebeleid        FIX-02  releasebot        FIX-03  agent-image
  FIX-04  lokale setup       FIX-05  bridge-refresh    FIX-06  tracker-not-found

Golf 1 — betrouwbare verwerking en bewijs
  VER-01  deterministische testbewijzen
  VER-02  volledige PR-verificatie
  REL-01  hervatbare agent-completion
  OPS-01  veilige work-cleanup
  DOC-01  documentatiebron van waarheid

Golf 2 — module- en SRP-refactors
  ARC-01  dashboard application-module
  ARC-02  dashboard-use-cases opsplitsen
  ARC-03  commands en subtaskhandlers opsplitsen
  ARC-04  tracker-capabilities en persistence
  ARC-05  supplier-neutrale agentcore
  ARC-06  contracts/tooling-modules en Maven-parent
  ARC-07  configuratie en externe I/O centraliseren
  UI-01   Flutter features en typed modellen

Golf 3 — blijvende borging en naamopschoning
  QLT-01  quality regression gate
  ARC-08  expliciete Modulith-afhankelijkheden
  CLN-01  namen, dode bestanden en ongebruikte parameters
```

Binnen Golf 0 mogen de pakketten parallel worden uitgevoerd, mits agents exclusief eigenaar zijn
van hun genoemde bestanden. `FIX-01` moet klaar zijn vóór `VER-01`. `FIX-02` moet klaar zijn vóór
`VER-02`, omdat beide GitHub-workflows wijzigen. `ARC-01` moet vóór `ARC-02`; `ARC-03` en `ARC-04`
kunnen daarna parallel. `QLT-01` wordt als laatste ingevoerd, maar iedere eerdere story gebruikt de
score al als niet-verslechteringscheck.

---

## 5. Golf 0 — direct herstel

### FIX-01 — Centraliseer en herstel het mergebeleid

**Prioriteit / omvang:** P0 / M  
**Voorgestelde storytitel:** “Projectbewuste groene merge-gate zonder bypass of pending-error”

#### Probleem en bewijs

- Het automatische pad controleert checks in
  `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/pipeline/service/MergeSubtaskHandler.kt:68-75`.
- Het handmatige `@factory:command:merge`-pad roept in
  `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/orchestrator/services/ManualCommandService.kt:175-189`
  rechtstreeks `mergePullRequest` aan en
  omzeilt daarmee de applicatiegate.
- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/pipeline/service/MergeSubtaskHandler.kt:97-99`
  vereist voor iedere target-repository hardcoded
  `Backend verification`, terwijl `projects.yaml` meerdere technologisch verschillende repos
  ondersteunt en geen checkconfig kent.
- Pending, ontbrekend en rood worden nu allemaal een exception en daarna `Error`. Een normale
  nog-lopende CI-check kan een story dus permanent blokkeren in plaats van later opnieuw te worden
  gepolld.

#### Opdracht

1. Introduceer één merge-use-case, bijvoorbeeld `PullRequestMergeService`, die als enige
   `GitHubApi.mergePullRequest` mag aanroepen.
2. Laat zowel `MergeSubtaskHandler` als `ManualCommandService` deze service gebruiken.
3. Modelleer readiness getypeerd als minimaal `Ready`, `Pending` en `Blocked`:
   - `Ready`: alle voor deze repo vereiste checks zijn groen op de actuele PR-head;
   - `Pending`: check queued/in-progress; geen `Error`, geen merge, later opnieuw proberen;
   - `Blocked`: ontbrekend, skipped, cancelled of failed; fail-closed met duidelijke fout.
4. Maak vereiste checks projectbewust. Voeg bijvoorbeeld `requiredChecks:` aan een project in
   `projects.yaml` toe, of lees de vereiste branch-protectionchecks van GitHub. Valideer bij opstart
   dat iedere mergebare repo een niet-lege policy heeft.
5. Verwijder de dubbele literal `Backend verification`; workflownaam en runtimepolicy mogen niet
   ongemerkt uit elkaar lopen.
6. Documenteer de policy in `projects.yaml.example`, functional spec, technical spec en runbook.

#### Acceptatiecriteria

- Geen productiecode buiten de centrale mergeservice roept `mergePullRequest` aan.
- Automatische én handmatige merge blokkeren bij rood/ontbrekend bewijs.
- Pending laat de story zonder `Error` wachten en wordt bij een volgende poll opnieuw beoordeeld.
- Twee testprojecten met verschillende checknamen kunnen onafhankelijk mergen.
- Een check van een oude SHA geldt niet als groen bewijs voor de actuele PR-head.
- Unit- en e2e-tests dekken ready, pending, missing, skipped, cancelled, failed en API-fout voor
  beide entrypoints.

#### Verificatie

```bash
rg -n 'mergePullRequest\(' softwarefactory/src/main factory-common/src/main
mvn -pl factory-common,softwarefactory -am test
mvn verify
```

#### Buiten scope

Geen deploylogica wijzigen en geen branch-protectionbypass toevoegen.

### FIX-02 — Maak image-manifestupdates compatibel met protected `main`

**Prioriteit / omvang:** P0 / S-M  
**Voorgestelde storytitel:** “Releasebot werkt via PR onder branch protection”

#### Probleem en bewijs

`.github/scripts/bump-images.sh:44-47` commit en pusht direct naar `main`. Sinds `main` de check
`Backend verification` verplicht, wordt die push geweigerd. GitHub-run `29124225964` bouwde en
pushte het backendimage, maar job `bump-manifests` faalde na vijf zinloze retries. Daardoor bleef
`deploy/base/kustomization.yaml:14-20` op `sha-2c23720` staan.

#### Opdracht

1. Laat de bot een tijdelijke branch maken, alleen het relevante image bijwerken en een PR openen
   of bijwerken.
2. Laat de normale verplichte checks op die PR draaien; merge daarna via GitHub auto-merge of een
   expliciete vervolgjob zonder branch-protectionbypass.
3. Voeg `concurrency` toe per component, zodat twee imagebuilds niet elkaars bump overschrijven.
4. Maak retries alleen voor echte races/netwerkfouten; een policy rejection is niet retryable.
5. Zorg dat een manifest-only merge niet opnieuw hetzelfde image bouwt.
6. Voeg een testbare shellfunctie of integratietest met een tijdelijke bare Git-repo toe.

#### Acceptatiecriteria

- Backend- en frontendimageworkflows eindigen volledig groen onder de huidige branch protection.
- Iedere gebouwde SHA komt via een traceerbare PR in `deploy/base/kustomization.yaml` terecht.
- Parallelle backend/frontendbumps behouden beide wijzigingen.
- Geen workflowtoken heeft een rechtstreekse pushbypass naar `main` nodig.

#### Verificatie

```bash
bash -n .github/scripts/bump-images.sh
# Voer de toegevoegde shell-/integratietests uit.
# Start daarna beide workflows_dispatch-runs en controleer image, PR en manifest-SHA.
```

### FIX-03 — Herstel en borg de agent-imagebuild

**Prioriteit / omvang:** P0 / S  
**Voorgestelde storytitel:** “Dockerfile.agent bouwt reproduceerbaar uit root-context”

#### Probleem en bewijs

`Dockerfile.agent:6-11` kopieert alleen `factory-common` en `agentworker`, maar de gekopieerde
root-POM verwijst ook naar `softwarefactory` en `dashboard-backend`. Maven parseert alle childmodules
voordat `-pl` selecteert; `docker build --target build -f Dockerfile.agent .` faalt daarom. Het
dashboard-backend-Dockerfile heeft al een tijdelijke `sed`-workaround op regels 15-20.

#### Opdracht

1. Maak voor beide Dockerfiles één robuust mini-reactorpatroon. Een expliciete kleine build-POM of
   gedeeld script heeft voorkeur boven twee afwijkende `sed`-recepten.
2. Bewijs dat `factory-common` en `agentworker` plus runtime dependencies in het image staan.
3. Voeg een goedkope CI-smoketest voor minimaal de build-stage toe.
4. Test ook `./factory build-images`; `Dockerfile.assistant` bouwt immers voort op `agent:local`.

#### Acceptatiecriteria

- `docker build --target build -f Dockerfile.agent .` slaagt op een schone checkout.
- `./factory build-images` maakt werkende `agent:local` en `assistant:local` images.
- De CI faalt wanneer de reactor of benodigde copy-paden later opnieuw breken.

### FIX-04 — Maak de lokale quickstart werkelijk uitvoerbaar

**Prioriteit / omvang:** P0 / M  
**Voorgestelde storytitel:** “Werkende lokale Compose-, SSO- en bridge-quickstart”

#### Probleem en bewijs

- README gebruikt in de root `docker compose up`, terwijl compose onder `docker/` staat.
- `docker/docker-compose.yml:22-25` bouwt dashboard-backend met context
  `../dashboard-backend`; het Dockerfile verwacht juist root-context en kopieert
  `factory-common` en `dashboard-backend`.
- Backend vereist `SF_GOOGLE_CLIENT_ID` en `SF_DASHBOARD_REMEMBER_SECRET`
  (`dashboard-backend/src/main/kotlin/nl/vdzon/softwarefactory/dashboard/config/DashboardConfig.kt:43-56`),
  maar `secrets.env.example` bevat die niet en bevat nog
  `SF_DASHBOARD_PASSWORD`.
- Compose injecteert nog een database-URL in de inmiddels dunne bridgebackend.
- Het bridgevoorbeeld gebruikt poort 8081, terwijl Compose backend op 9090 publiceert.
- Rechtstreeks `mvn -f softwarefactory/pom.xml spring-boot:run` is op een schone checkout niet
  betrouwbaar doordat `factory-common` eerst geïnstalleerd moet zijn; `./factory start` doet dit wel.

#### Opdracht

1. Corrigeer backend build-context naar repositoryroot en Dockerfilepad.
2. Verwijder obsolete database/dashboard-passwordconfig en voeg alle vereiste SSO-/bridgewaarden
   met veilige lokale voorbeelden toe.
3. Maak `./factory local-services` en `./factory start` de canonieke commands.
4. Documenteer de lokale brug als `ws://localhost:9090/bridge` en zorg dat dezelfde bridge-token
   aan beide processen wordt gegeven.
5. Voeg een smoke-script toe dat Compose bouwt, services gezond ziet en `/healthz` plus
   `/api/v1/status` controleert.

#### Acceptatiecriteria

- Een nieuwe ontwikkelaar kan vanaf een schone checkout de gedocumenteerde stappen kopiëren.
- Backend en frontend bouwen, backend wordt healthy en de UI ziet na factory-start een verbonden
  bridge.
- `secrets.env.example`, Compose, README, runbook en `docs/factory/secrets-local.md` spreken elkaar
  niet tegen.

### FIX-05 — Herstel de getypeerde `force`-refresh over de bridge

**Prioriteit / omvang:** P0 / S  
**Voorgestelde storytitel:** “Dashboard refresh omzeilt caches daadwerkelijk”

#### Probleem en bewijs

`BridgeApiController` stuurt voor projects/downloads/builds `force` als JSON-string; helper
`paramsOf` accepteert alleen `Pair<String, String>`
(`dashboard-backend/src/main/kotlin/nl/vdzon/softwarefactory/dashboard/bridge/BridgeApiController.kt:387`).
De factory accepteert `force` uitsluitend als JSON-boolean
(`softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/bridge/BridgeRequestHandler.kt:194`). Hierdoor
werkt expliciet verversen niet.

#### Opdracht en acceptatiecriteria

1. Stuur een echte boolean en voeg controllertests toe die het exacte bridgeframe asserten.
2. Test alle drie endpoints met `force=false`, `force=true` en ontbrekend veld.
3. Leg een contracttest in `factory-common` vast zodat types aan beide brugzijden gelijk blijven.
4. Bereid geen volledige bridgerefactor in deze hotfix voor; die staat in `ARC-01`.

### FIX-06 — Gebruik een getypeerde tracker-not-foundfout

**Prioriteit / omvang:** P0 / S-M  
**Voorgestelde storytitel:** “Stale story-runs sluiten bij ontbrekend Postgres-issue”

#### Probleem en bewijs

`softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/tracker/clients/PostgresTrackerClient.kt:125`
meldt een onbekende issue-key via `TrackerApiException`. De costmonitor herkent op
`softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/orchestrator/services/CostMonitorService.kt:204`
alleen de oude tekst `status 404`. De bijbehorende
test gebruikt nog die oude externe-trackertekst en maskeert zo de regressie.

#### Opdracht en acceptatiecriteria

1. Voeg `TrackerIssueNotFoundException` of een gelijkwaardig getypeerd resultaat toe.
2. Laat `PostgresTrackerClient` dit type produceren en consumenten op type beslissen.
3. Verwijder foutclassificatie op exceptiontekst.
4. Voeg een integratietest met echte Testcontainers-Postgres toe: een stale actieve run waarvan het
   issue ontbreekt wordt netjes gesloten en blijft niet iedere poll fouten geven.

---

## 6. Golf 1 — betrouwbare verwerking en bewijs

### VER-01 — Maak testergoedkeuring machine-verifieerbaar

**Prioriteit / omvang:** P1 / L  
**Afhankelijk van:** FIX-01

#### Probleem

De agentprompts verbieden goedkeuring bij rode tests, maar `AgentResultFile` bevat geen
gestructureerd testbewijs. Een tester kan nog steeds `tested` rapporteren zonder dat de factory het
commando, de exitcode of de geteste revision kan controleren. De uiteindelijke GitHub-check is een
goed laatste vangnet, maar maakt het testerbesluit zelf niet waarheidsgetrouw.

#### Opdracht

1. Definieer per target-repo een versioned verificationconfig, bijvoorbeeld
   `.factory/verification.yaml`, met verplichte commands en optionele timeouts/working directories.
2. Laat `agentworker` deze commands deterministisch uitvoeren na de tester-AI-run. Vertrouw niet op
   proza van het model.
3. Breid het gedeelde resultcontract backward-compatible uit met per command: command-id,
   start/eindtijd, exitcode, duur, geteste Git-tree/SHA en rapportlocatie/samenvatting.
4. Forceer bij ontbrekend bewijs of non-zero exit een `test-rejected`-uitkomst met bruikbare
   diagnose voor de developer.
5. Laat de factory een gemelde `tested` weigeren wanneer bewijs ontbreekt, rood is of niet bij de
   geteste checkout hoort.
6. Houd de uiteindelijke GitHub-check als tweede, onafhankelijke mergegate.

#### Acceptatiecriteria

- Geen testerresultaat kan naar `tested` zonder volledig groen gestructureerd bewijs.
- “Pre-existing”, flaky of omgevingsgebonden fouten volgen exact hetzelfde rejectpad.
- Ontbrekende tooling is rood/geblokkeerd, nooit groen.
- Contracttests bewijzen backward compatibility en onbekende velden.
- E2e-test bewijst rood bewijs → hele test-loopback → developer.

### VER-02 — Breid de verplichte PR-verificatie uit

**Prioriteit / omvang:** P1 / M  
**Afhankelijk van:** FIX-02

#### Opdracht

1. Voeg voor Flutter een PR-job toe met gepinde versie, `flutter pub get`, `flutter analyze` en
   `flutter test`.
2. Voeg de agent-Docker-buildstage uit FIX-03 toe als smokecheck.
3. Gebruik één stabiele aggregatorcheck, bijvoorbeeld `Repository verification`, die pas groen is
   wanneer alle relevante jobs groen zijn. Laat pathfilters nooit een vereiste check geheel
   ontbreken; rapporteer dan bewust success/skipped via de aggregator.
4. Leg branch protection en vereiste checks declaratief vast of voeg een audit-script toe dat
   afwijkingen detecteert.
5. Laat imagebuilds pas na groene verificatie publiceren.

#### Acceptatiecriteria

- Een kapotte Dart-test, Kotlin-test, Modulith-grens of agent-Dockerfile blokkeert merge.
- De checknaam staat op één beheerste plaats en is consistent met FIX-01.
- CI-output toont afzonderlijk backend, frontend en image-smoke, plus één stabiele eindcheck.

### REL-01 — Maak agent-completion duurzaam en hervatbaar

**Prioriteit / omvang:** P1 / L

#### Probleem en bewijs

`AgentRunCompletionService.complete()` sluit eerst de run en voert daarna meerdere onafhankelijke
side-effects uit.
`softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/orchestrator/repositories/RunRepositories.kt:308`
accepteert afronding alleen wanneer `ended_at IS NULL`.
Valt een latere tracker-, PR-, usage- of artifactstap uit, dan vindt een retry geen actieve run meer
en kan de halfverwerkte completion niet betrouwbaar hervatten.

#### Opdracht

1. Maak completion een duurzame state machine of gebruik een transactionele outbox met expliciete
   stappen/statussen.
2. Maak iedere stap idempotent: usage niet dubbel tellen, comments/attachments niet dupliceren en
   PR-metadata veilig upserten.
3. Voeg een reconciler toe voor incomplete completions na restart.
4. Splits orchestration, repositorysync, trackertransitie, testerartifacts, knowledge/comments en
   cleanup in kleine services.

#### Acceptatiecriteria

- Fault-injection na iedere stap plus restart leidt uiteindelijk tot exact één volledige
  completion.
- Geen usage, events, comments of screenshots worden dubbel opgeslagen.
- De hoofdservice is een korte orchestrator; side-effectservices zijn apart unit-testbaar.

### OPS-01 — Voorkom cleanup van nog actieve workspaces

**Prioriteit / omvang:** P1 / M

#### Probleem

`docs/technical/scheduled-jobs.md` garandeert dat actieve workmappen nooit worden verwijderd, maar
`softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/runtime/workspaces/WorkCleanupPoller.kt:87`
beslist alleen op mtime en controleert geen actieve runs. Een langdurige
stille agent kan daardoor in theorie zijn workspace verliezen.

#### Opdracht en acceptatiecriteria

1. Injecteer een kleine active-workspacebron en sla paden van actieve agent-, story- en
   assistantsessies altijd over.
2. Gebruik mtime alleen voor niet-actieve kandidaten.
3. Voeg race-/boundarytests toe voor precies de retentiegrens en een actieve map met oude mtime.
4. Documenteer de werkelijk afgedwongen garantie.

### DOC-01 — Herstel de actuele documentatie en blokkeer nieuwe drift

**Prioriteit / omvang:** P1 / M-L  
**Mag parallel met:** VER-01 en REL-01, maar voer een laatste rebasecontrole uit

#### Minimaal te corrigeren feiten

- README/local setup en SSO-/bridgeconfig uit FIX-04.
- 4 Maven-modules, 11 Modulith-packages, 10 factory-endpoints, 38 backend-endpoints, 5 scheduled
  methodes, 2 daemonthreads en Flyway V1-V15.
- `bridge` als eigen package/module; dashboard-backend is een dunne bridge en queryt geen tracker-DB
  of GitHub.
- Geen ingebouwd HTML-dashboard meer.
- Vast pollinterval plus event-driven wake, niet het oude adaptieve interval.
- Mergecheckpolicy uit FIX-01 en de nightly-uitzondering waarbij `subtasks.yaml` volledig leidend is.
- Verwijder dode configdocumentatie zoals username/password en verifieer Copilot credentialgedrag.
- Maak `dashboard-frontend/README.md` projectspecifiek.
- Synchroniseer essentiële agentinvarianten in het docs-skeleton; laat generieke commandvelden alleen
  als expliciete placeholders staan wanneer bootstrap ze later invult.

#### Driftborging

Voeg een documentatie-audit toe die minimaal vergelijkt:

- root-POM-modules;
- top-level Modulith-packages;
- HTTP-mappings per applicatie;
- `@Scheduled`-methodes;
- Flyway-migratieversies;
- geconfigureerde/gedocumenteerde `SF_*` keys;
- verplichte docs-skeletonbestanden en agentinvarianten.

Verwijder het woord “gegenereerd” voor documenten die niet gegenereerd of automatisch
geverifieerd worden.

#### Acceptatiecriteria

- Een nieuwe developer kan quickstart en testcommands letterlijk uitvoeren.
- Alle genoemde inventarissen komen overeen met code of worden gegenereerd.
- De audit faalt aantoonbaar bij een kunstmatig toegevoegde endpoint/package/configkey zonder
  documentatie-update.

---

## 7. Golf 2 — module- en SRP-refactors

### ARC-01 — Introduceer een echte dashboard application-module

**Prioriteit / omvang:** P2 / L

`web` hoort een transportadapter te zijn, maar `FactoryDashboardService` bevat application-use-cases
en de bridge importeert concrete `web.services` en `web.models`. Named interfaces publiceren nu een
groot deel van web om dit toe te staan.

#### Opdracht

1. Maak package/module `dashboard` met smalle `DashboardQueryApi` en `DashboardCommandApi`.
2. Verplaats transportonafhankelijke request/responsemodellen en dashboardrepositories daarheen.
3. Laat zowel `web`-controllers als `bridge` adapters van deze APIs zijn.
4. Vervang de grote `when(operation)` en losse `JsonNode`-parameters door een getypeerd
   bridgecommand-/handlerregister met exhaustieve contracttests.
5. Verwijder daarna de brede named exposure van `web.services` en `web.models`.

#### Acceptatiecriteria

- `bridge` importeert niets uit `web`.
- Modulith legt expliciet vast: transportadapters → dashboard application API → domeinpoorten.
- Onbekende operatie, onjuist type en contractcompatibiliteit blijven getest.

### ARC-02 — Splits dashboard-use-cases per verantwoordelijkheid

**Prioriteit / omvang:** P2 / L  
**Afhankelijk van:** ARC-01

Splits `FactoryDashboardService` in zelfstandig benoemde use-cases, minimaal:

- dashboard/stories/my-actions queries;
- story detail en screenshots;
- projects/build/deploystatus;
- downloads/releases;
- nightly queries/commands;
- settings;
- story lifecyclecommands.

Gebruik requestdata classes voor `createStory` en vergelijkbare lange parameterlijsten. Houd caches
bij de externe queryadapter waarop ze betrekking hebben, niet in een universele facade.

**Klaar wanneer:** geen vervangende god-facade ontstaat, iedere service maximaal één duidelijke
veranderreden heeft en de Detekt-hotspot voor de oude class verdwenen is.

### ARC-03 — Splits commands en subtaskpipeline in handlers

**Prioriteit / omvang:** P2 / L  
**Afhankelijk van:** FIX-01

1. Maak één handler per `FactoryCommand`; laat `ManualCommandService` alleen parse/dispatch doen.
2. Maak één handler per `SubtaskType`; laat `SubtaskExecutionCoordinator` alleen routeren en de
   gemeenschappelijke keten/recovery bewaken.
3. Introduceer contextdata classes in plaats van lange parameterlijsten in `AgentDispatcher`.
4. Centraliseer feedbackblokschrijven en transitionguards.
5. Voeg characterizationtests toe vóór het verplaatsen van gedrag.

**Klaar wanneer:** nieuwe commands/subtasktypes geen nieuwe `when`-tak in een 500-regelige class
vereisen en bestaande flow-/e2e-tests ongewijzigd gedrag bewijzen.

### ARC-04 — Segregeer tracker-capabilities en persistence

**Prioriteit / omvang:** P2 / L  
**Afhankelijk van:** FIX-06

`TrackerApi` combineert lezen, schrijven, lifecycle, comments, attachments en processed markers en
heeft default-no-ops/`UnsupportedOperationException`. `PostgresTrackerClient` combineert dezelfde
opslaggebieden. Ook runrepositories gebruiken lange argumentlijsten.

#### Opdracht

1. Introduceer capabilities zoals `IssueReader`, `IssueWriter`, `CommentPort`, `AttachmentPort` en
   `ProcessedCommentPort`.
2. Injecteer per consument alleen benodigde capabilities; verwijder onveilige defaults.
3. Splits de Postgres-adapter intern in issue-, comment-, attachment- en keyrepositories.
4. Vervang `updatePullRequest`, `updateWorkspace` en `recordStarted`-parameterlijsten door typed
   command/updateobjects.
5. Behoud één facade alleen waar backward compatibility tijdelijk nodig is en markeer de
   verwijderroute.

### ARC-05 — Maak de AI-core werkelijk supplier-neutraal

**Prioriteit / omvang:** P2 / M-L

Codex en Copilot importeren `ClaudePromptBuilder` en `ClaudeOutcomeParser`; drie clients dupliceren
processtart, redactie, taskfilebeheer en outcomevertaling.

#### Opdracht

1. Verplaats gedeelde logica naar `agent.ai.shared` met namen als `AgentPromptBuilder`,
   `AgentOutcomeParser`, `CliProcessRunner` en `TaskFileManager`.
2. Houd per supplier alleen commandopbouw, credentialpolicy en streamparser over.
3. Maak tests parametrisch over Claude, Codex en Copilot voor identieke rolcontracten.
4. Splits het huidige Claude-bestand van ruim 750 regels op in client, prompts en parsers.

### ARC-06 — Splits contracts van tooling en maak de root-POM een parent

**Prioriteit / omvang:** P2 / L

`factory-common` bevat wire-contracten, config/YAML, docs, git, GitHub, preview en Spring-components.
`dashboard-backend` heeft alleen bridgecontracten nodig maar krijgt de brede module mee.

#### Opdracht

1. Maak een lichte `factory-contracts`-module zonder Spring/SnakeYAML voor bridgeframes en
   agent-resultcontract.
2. Houd git/GitHub/docs/preview/support in een toolingmodule; beoordeel projectconfig als aparte
   module.
3. Laat dashboard-backend alleen van `factory-contracts` afhangen.
4. Maak de root-POM een gedeelde parent voor Java/Kotlin/Boot/pluginversies naast aggregator.
5. Houd Docker-mini-reactors uit FIX-03 werkend en voeg dependency boundarytests toe.

### ARC-07 — Centraliseer configuratie en externe proces-/HTTP-I/O

**Prioriteit / omvang:** P2 / M-L

Er staan nog directe `System.getenv`-/`ProcessBuilder`-paden buiten de aangewezen adapters. Een
concreet defect is
`softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/web/services/ProjectDeployClient.kt:21-25`:
force-deploy leest alleen de procesomgeving en
mist tokens die via de gelaagde `secrets.env`-loader beschikbaar zijn. Ook assistent-timeout/image,
nightly GitHubtoken en enkele GitHubpaden hebben TODO’s voor `ConfigApi`.

#### Opdracht

1. Maak `ConfigApi`/`FactorySecrets` de enige bron voor applicatieconfig; alleen composition roots
   mogen `System.getenv` lezen.
2. Gebruik gedeelde, injecteerbare command- en HTTP-runners met timeout, redactie en consistente
   fouttypen.
3. Fix force-deploy voor tokens uit de gelaagde config en voeg een regressietest toe.
4. Inventariseer iedere resterende directe env/process-call en documenteer bewuste uitzonderingen
   (`main`, home-directorynormalisatie).

### UI-01 — Splits Flutterfeatures en gebruik typed API-modellen

**Prioriteit / omvang:** P2 / L  
**Mag parallel met:** ARC-05 en ARC-06

`overview_screens.dart` bevat zes schermen en 995 regels; `api_client.dart` retourneert overal
`Map<String, dynamic>`. Contractfouten worden daardoor pas runtime zichtbaar.

#### Opdracht

1. Maak één featuremap/schermbestand voor dashboard, agents, merged, projects, nightly en settings.
2. Introduceer typed responsemodellen met expliciete JSON-parsing en parsingtests.
3. Houd `ApiClient` bij HTTP/auth/foutvertaling; verplaats UI-formathelpers naar feature/shared UI.
4. Begin met Projects/Builds en leg het booleantype uit FIX-05 vast.
5. Houd widgetgedrag via characterization/golden tests stabiel.

---

## 8. Golf 3 — blijvende borging en cleanup

### QLT-01 — Maak de kwaliteitsmeting een echte regressiegate

**Prioriteit / omvang:** P2 / M

`quality/run.sh` meet alleen `softwarefactory` main-code, negeert de Detekt-exitcode met `|| true`
en schrijft de baseline uitsluitend naar gitignored `qualityrun/`. De CI kan dus niet vaststellen of
een PR de score verslechtert.

#### Opdracht

1. Commit een machineleesbare baseline per module of per relevante rulecategorie.
2. Meet alle Kotlin-mainmodules; voeg `flutter analyze` apart toe.
3. Laat CI falen op nieuwe complexiteitsbevindingen en nieuwe suppressies. Vermijd een rigide gate
   op alle bestaande MaxLineLength/MagicNumber-ruis; werk met “geen nieuwe schuld” en gerichte
   dalingsdoelen.
4. Los de Kotlin compilerwarning in
   `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/tracker/clients/PostgresTrackerClient.kt:383`
   op; toekomstige Kotlinversies maken deze intersection-type-inferentie een error.
5. Publiceer het rapport als CI-artifact en toon voor/na-delta in PR’s.

### ARC-08 — Leg toegestane Modulith-afhankelijkheden expliciet vast

**Prioriteit / omvang:** P2 / M  
**Afhankelijk van:** ARC-01 en ARC-06

`ApplicationModules.verify()` bewaakt cycli en interne packages, maar niet iedere bedoelde
dependencyrichting. Leg per module `allowedDependencies` vast en genereer een dependencydiagram.

**Beoogde richting:** adapters (`web`, `bridge`, Telegram transport) → application APIs → domein en
poorten; infrastructuur implementeert poorten; transportmodules importeren elkaar niet.

### CLN-01 — Gerichte naam- en dode-codecleanup

**Prioriteit / omvang:** P3 / S-M  
**Voer als laatste mechanische story uit.**

Minimaal te beoordelen:

- verwijder of implementeer ongebruikte `projectKey`-parameters in `pollOnce`/`findAiIssues`;
- hernoem `YouTrackModels.kt`, dat geen YouTrack meer bevat;
- verwijder import-only restbestanden `CostMonitorService.kt` en `CreditsPauseService.kt`;
- wijzig de POM-omschrijving “Read-only dashboard API”, want er zijn veel muterende endpoints;
- harmoniseer packagepluraliteit pas nadat classes zijn verplaatst;
- laat `ProjectRepoResolver` na ARC-07 evolueren naar `ProjectCatalog` plus aparte YAML-loader en
  validators; de huidige naam dekt Telegram, private files, deploy, approvals en live components
  niet meer.

Geen functionele wijzigingen in deze story. Gebruik `git diff --find-renames`, compileer na iedere
mechanische stap en werk alle imports/docs bij.

## 9. Eindcriteria voor het hele traject

Het traject is afgerond wanneer al het volgende aantoonbaar waar is:

- agent- en assistantimages bouwen op een schone checkout;
- image-releases werken onder protected `main` en deploymentmanifesten volgen de gebouwde SHA;
- elk merge-entrypoint gebruikt dezelfde projectbewuste groene-checkpolicy;
- pending CI veroorzaakt wachten, geen permanente Error;
- testergoedkeuring bevat machine-verifieerbaar groen bewijs;
- Maven, Flutter en image-smoke zijn verplichte PR-verificatie;
- agent-completion is na elke gedeeltelijke fout/restart hervatbaar;
- lokale quickstart is letterlijk uitvoerbaar;
- bridge en web zijn adapters van een dashboard application-API en importeren elkaar niet;
- de genoemde god classes zijn opgesplitst zonder vervangende facade met dezelfde breedte;
- trackerinterfaces zijn capabilitygericht en hebben geen stille no-opdefaults;
- supplier-neutrale agentlogica heeft neutrale namen en één implementatie;
- actuele docs worden automatisch op inventarisdrift gecontroleerd;
- Detekt rapporteert geen nieuwe schuld en de complexiteitshotspots zijn meetbaar afgenomen;
- `mvn verify`, `flutter analyze`, `flutter test`, Docker-smokes en documentatie-audit zijn groen.

## 10. Wat bewust niet wordt aanbevolen

- Geen volledige rewrite; de huidige tests en domeinmodellen zijn waardevol.
- Geen massale package-/bestandsrename tegelijk met gedragsrefactors.
- Geen generieke “maximaal 300 regels”-regel zonder verantwoordelijkheid te beoordelen.
- Geen suppressies, testskips of branch-protectionbypasses om het traject sneller groen te maken.
- Geen samenvoeging van dashboard-backend en factory alleen om een module minder te hebben; de
  uitgaande bridge heeft een verdedigbare deploymentreden.
- Geen opsplitsing van elke kleine interface/class. Splits alleen rond aantoonbare veranderredenen,
  transacties en testbare use-cases.
