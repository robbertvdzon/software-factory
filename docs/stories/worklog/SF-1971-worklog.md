# SF-1971 - Worklog

Story-context bij eerste pickup:
E2e-dekking deploy-doelen: sample-deploy-config, probe-dubbel, changedFiles-override en DeployTargetsE2eTest

In eigen woorden: de deploy-stap van de factory had als enige stap geen end-to-end test en ging in
acht dagen twee keer stuk in productie. De laatste keer bleef de deploy hangen omdat de deploy-stap
ná de merge de verkeerde story-run-administratie las; de story-diff werd daardoor onbepaalbaar, de
`matchPaths`-filter ging fail-open en een deploy-doel dat de story helemaal niet raakt deed alsnog
mee — en werd nooit "live". Deze story voegt uitsluitend testcode toe die die keten echt doorloopt
en bewijst dat alleen de geraakte deploy-doelen meedoen. Geen productiecode gewijzigd.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes (sample-deploy-config, probe-dubbel, changedFiles-override, DeployTargetsE2eTest)
[x]: handmatig faalbewijs (AC 5) leveren
[x]: run relevant tests + volledig vangnet (`mvn verify`)
[x]: update story-log with results

## Gedaan / rationale

1. **`E2eTestConfig` — tweede projectnaam `sample-deploy` met twee deploy-doelen.**
   `projectRepoResolver()` mapt nu zowel `sample` (ongewijzigd) als `sample-deploy` naar dezelfde
   `LOCAL_REMOTE.path`, en vult voor `sample-deploy` de bestaande `deployTargets`-parameter met exact
   twee `DeployConfig.OpenshiftWatch`-doelen: `sample-deploy-backend` (`matchPaths = ["backend/"]`,
   geraakt door de gefakete diff) en `sample-deploy-frontend` (`matchPaths = ["frontend/"]`, niet
   geraakt). Beide zonder `argocdApp`/`argocdNamespace` (zodat `openshiftWatchReady` de
   image-heuristiek gebruikt) en met `timeoutMinutes = 30`, zodat in een faalscenario de
   await-timeout van de test het signaal is en niet `deploy-failed`.

   **Afwijking van de refined story (bewust, technisch noodzakelijk):** de aanname
   "`requiredChecks` voor `sample-deploy` is niet nodig" klopt niet.
   `ProjectAwarePullRequestMergeService` roept in zijn `init`-blok
   `projectRepoResolver.requireCompleteMergePolicies()` aan, en die eist een niet-lege
   `requiredChecks` voor élke geconfigureerde projectnaam. Zonder policy voor `sample-deploy` valt de
   hele Spring-context om en falen álle e2e-tests. `sample-deploy` heeft daarom dezelfde
   `{"E2E verification"}`-policy als `sample`; de policy zelf is inhoudelijk irrelevant omdat de
   e2e-`FakeGitHubApi.requiredChecks` onvoorwaardelijk `Ready` teruggeeft. `sample` en zijn
   requiredChecks zijn volledig ongewijzigd gebleven.

2. **`@Bean @Primary DeploymentStatusProbe` in `E2eTestConfig`** (zelfde overschrijfpatroon als
   GitHub/Telegram/agent-runtime). Geeft alleen voor namespace+deployment van doel A een niet-lege
   image en voor doel B `null`; `argoApplicationStatus`/`runningPod` blijven op hun interface-default
   `null`. De echte `KubectlDeploymentStatusProbe` wordt in e2e-runs dus nooit aangeroepen.

3. **`FakeGitHubApi.changedFiles`-override** (de e2e-fake, niet die in `testsupport/`): geeft een
   vaste lijst `["backend/src/main/kotlin/App.kt", "docs/stories/worklog/e2e-worklog.md"]` terug.
   Zonder deze override geeft de `GitHubApi`-default `null` en behandelt `matchedTargets` dat
   fail-open — dan matchen alle doelen altijd en bewijst de test niets.

4. **`E2eTestBase.createStory`** kreeg een optionele `repo`-parameter met default `"sample"`, als
   laatste parameter. Alle bestaande aanroepers blijven ongewijzigd.

5. **Nieuwe klasse `e2e/DeployTargetsE2eTest : E2eTestBase()`** met één test op story-key `SP-600`
   (uniek in de suite): `Repo = sample-deploy`, scripted developer die het `github-pr`-event
   rapporteert, echte lokale squash-merge, daarna wachten tot de merge-subtaak op `merge-approved`
   en de deploy-subtaak op `deploy-approved` staat (await-timeout 180s). Bewust de volledige,
   normale story-keten (refine → plan → dev → review → test → summary → documentation → merge →
   deploy) en niet de kortere hotfix-keten: het productie-incident zat juist in een gewone story-run.
   Aanvullend assert de test via de read-only poort `DeployTargetStatusApi.matchedDeployTargetsFor`
   dat precies één doel (het geraakte) meedoet, en dat de deploy-subtaak nooit op `deploy-failed`
   heeft gestaan.

## Bewijs

- **Nieuwe test groen:**
  `mvn -o -f softwarefactory/pom.xml verify -Dit.test=DeployTargetsE2eTest -Dsurefire.skip=true`
  → `Tests run: 1, Failures: 0, Errors: 0` in 9,0s, BUILD SUCCESS.
- **Handmatig faalbewijs (AC 5):** met `changedFiles` tijdelijk terug op `null` (fail-open) draaide
  dezelfde test rood met
  `AssertionError: Timeout wachtend op: subtask-phase van SP-607 == deploy-approved` na 189,2s —
  de merge komt wel door, maar de deploy blijft in `deploying` hangen omdat het niet-geraakte doel
  `sample-deploy-frontend` meedoet en daar nooit een image verschijnt. Die tijdelijke wijziging is
  daarna teruggedraaid en staat niet in de diff.
- **Volledig vangnet:** `mvn -o -B verify` vanaf de repo-root → exitcode 0, BUILD SUCCESS in ~5m,
  0 failures / 0 errors over alle modules; alle 15 e2e-klassen (incl. de nieuwe) groen.
- **Quality-ratchet:** `./quality/run.sh` is rood met 4 `new`-bevindingen
  (`StoryRefinementCoordinator.kt` CyclomaticComplexMethod + ReturnCount, 2×
  `RunRepositories.kt` TooManyFunctions). Machinaal bewezen pre-existent: dezelfde run in een schone
  worktree op HEAD (`git worktree add --detach /tmp/base HEAD`) geeft een identieke `new`-lijst. Alle
  vier zitten in `src/main`; deze story raakt uitsluitend `src/test`, dat Detekt hier niet scant. Niet
  opgerekt in de baseline en niet blokkerend (de ratchet zit bewust niet in
  `.github/workflows/verify.yml` of `.factory/verification.yaml`).

## Docs

Geen `docs/factory/`-spec geraakt: deze story voegt uitsluitend testcode toe en verandert niets aan
de werking, de configuratie of de architectuur van de factory. `development.md` beschrijft de
e2e-suite al generiek (package `...e2e`, op basis van `E2eTestBase`), en die beschrijving blijft
onveranderd geldig.

## Review (SF-1972, reviewer)

Diff t.o.v. `main` beoordeeld: 4 testbestanden + dit worklog, geen productiecode — scope klopt.
Gecontroleerd en akkoord:

- `changedFiles` heeft precies één productie-consument (`DeploySubtaskHandler.changedPaths`), dus de
  override op de e2e-fake raakt geen andere e2e-test; `sample` houdt lege `deployTargets` en volgt
  dus nog steeds de Skip-route (`FullRefineToDevelopE2eTest` blijft geldig).
- De keten in `DeployTargetsE2eTest` is aantoonbaar bewijskrachtig: `matchedTargets` (fail-open bij
  `changedPaths == null`) → doel `frontend/` doet mee → `openshiftWatchReady` krijgt van de
  test-probe `null` → nooit `deploy-approved`. `timeoutMinutes = 30` > await-180s, dus de
  await-timeout is inderdaad het faalsignaal.
- Geen ArgoCD-config op de doelen → image-route, geen SHA-verificatie. Klopt met
  `openshiftWatchReady`.
- De afwijking op de story-aanname (`requiredChecks` voor `sample-deploy`) is geverifieerd nodig:
  `ProjectConfiguration.requireCompleteMergePolicies()` eist een entry voor élke naam in `byName`.
  `requiredChecksForRepo` (beide namen wijzen naar dezelfde remote) blijft consistent.
- Tweede projectnaam raakt `projectNames()`-consumenten (RecentCommitsPoller, cleanup-scheduler,
  audit-gateway) alleen met een extra iteratie over dezelfde lokale remote; de volledige e2e-suite is
  groen gedraaid.
- Gerichte hercontrole in de reviewsandbox (geen Docker, dus geen e2e-run):
  `mvn -o -B -q -pl factory-common,softwarefactory -am test-compile` → exit 0.

Niet-blokkerende punten: `DEPLOY_MATCHED_IMAGE` is public maar wordt buiten `E2eTestConfig` niet
gebruikt (mag `private`), en de slot-assertie op `deploy-failed` kan met een 30-minuten-timeout
binnen een 180s-test nooit afgaan — documenteert intentie, kost niets.

## Test (SF-1973, tester)

**Volledig vangnet** (`.factory/verification.yaml` → `mvn verify`), vanaf repo-root, 05-08-2026:
`mvn -B --no-transfer-progress verify` → **exitcode 0, BUILD SUCCESS in 05:05 min**.
Totaal 1117 tests, 0 failures / 0 errors / 0 skipped, verdeeld over
factory-contracts 16 · factory-common 55 · softwarefactory 839 unit + **85 e2e** (was 78+1 nieuw,
rest door de suite-groei sinds de vorige meting) · agentworker 61 · dashboard-backend 61.
Geen forkflake deze ronde (`FactoryApiControllerTest` groen). Werktree na afloop schoon
(`git status --porcelain` leeg).

**De nieuwe test:** `nl.vdzon.softwarefactory.e2e.DeployTargetsE2eTest` → `Tests run: 1,
Failures: 0, Errors: 0` in 9,3 s, binnen de failsafe-fase. Alle bestaande e2e-klassen groen,
inclusief `FullRefineToDevelopE2eTest` (Skip-route op `sample`) en `MergePolicyE2eTest`.

**Gedragscontrole op de AC's (los van de groene run):**

- AC 1/2/3/4 gelezen tegen de diff: `sample` is inhoudelijk ongewijzigd (zelfde remote, zelfde
  `requiredChecks`, geen deploy-doelen), `sample-deploy` heeft exact twee `OpenshiftWatch`-doelen met
  elkaar uitsluitende `matchPaths` (`backend/` vs `frontend/`) en zonder ArgoCD-config.
- Dat doel A écht via de test-dubbel live werd (en de echte `KubectlDeploymentStatusProbe` dus niet
  meedeed) volgt uit de test zelf: hij haalt `deploy-approved` en assert daarna via
  `DeployTargetStatusApi.matchedDeployTargetsFor` dat exact `sample-deploy-backend` meedoet — een
  image voor die namespace/deployment kan alleen uit de `@Primary`-dubbel komen.
- De afwijking van de story-aanname over `requiredChecks` is nagerekend en klopt:
  `ProjectConfiguration.requireCompleteMergePolicies()` (factory-common,
  `ProjectConfiguration.kt:311-316`) eist een entry voor élke naam in `byName`; zonder policy voor
  `sample-deploy` valt de hele Spring-context om.
- AC 5 (faalbewijs met `changedFiles = null`) is door de developer handmatig gedraaid; de tester heeft
  dit niet herhaald (zou een tijdelijke codewijziging vragen, wat buiten de testerrol valt). De
  code-keten die het bewijs draagt is wel nagelopen en sluit: `changedPaths == null` →
  `matchedTargets` fail-open (`DeploySubtaskHandler.kt:104-109`) → `frontend/`-doel doet mee →
  `openshiftWatchReady` (`:417-434`) krijgt van de dubbel `null` → nooit `deploy-approved`.
- `changedFiles` heeft precies één productie-consument (`DeploySubtaskHandler.changedPaths`); de
  override op de e2e-fake raakt dus geen andere e2e-test. `GitHubCliClient` (productie) is niet
  geraakt.
- Story-key `SP-600` is uniek binnen de e2e-suite (gecontroleerd over alle e2e-klassen).

**Omgeving:** `/work/screenshots` bestaat niet en er is geen preview-URL/browser in de
tester-sandbox, dus browser-/preview-scenario's en screenshots waren niet mogelijk; de e2e-suite is
hier de zwaarste beschikbare gedragstest.

Conclusie: **tested**. Geen bevindingen die terug moeten naar de developer.
