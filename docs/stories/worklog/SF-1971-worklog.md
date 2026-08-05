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
