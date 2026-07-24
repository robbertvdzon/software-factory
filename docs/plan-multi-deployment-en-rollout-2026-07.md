# Plan: multi-deployment per project + deploy-zichtbaarheid

**Bron:** [docs/idee-multi-deployment-per-project.md](idee-multi-deployment-per-project.md)
**Status:** Story 1, 2, 3 en 4 AFGEROND (2026-07-24); story 5 nog NIET GESTART
**Uitvoering:** één voor één, in de volgorde hieronder — story 4 en 5 hebben story 1 nodig.

## Volgorde en afhankelijkheden

1. Multi-deployment routing (`deploy:`-lijst + `matchPaths`) — fundament
2. Telegram-melding niet meer premature
3. APK sync-status op project-tab
4. Story-detail per-onderdeel build-status — hangt af van 1
5. `deployedAt` + `StoryDeployReconciler` + Rollout-tab — hangt af van 1

Story 2 en 3 hebben geen afhankelijkheden en mogen ook los/eerder, maar staan hier in
uitvoeringsvolgorde.

---

## Story 1 — Multi-deployment routing per project

**Status:** AFGEROND (2026-07-24) — `deploy:` in `projects.yaml` accepteert nu ook een lijst
deploy-doelen met `matchPaths`; `DeploySubtaskHandler` bewaakt alleen de door de story-diff geraakte
doelen en wacht pas op `deploy-approved` tot alle geraakte doelen groen zijn (geen match → direct
approved). Story-diff komt via een nieuwe `GitHubApi.changedFiles(repo, prNumber)`
(`gh api repos/<slug>/pulls/<pr>/files`) — er bleek geen bruikbaar lokaal-`git diff`-mechanisme in de
orchestrator zelf te bestaan, dit is de functioneel equivalente vervanging. `mvn test` op
factory-common+softwarefactory: 539/539 groen; `mvn verify` (incl. e2e/failsafe) ook groen.
`projects.yaml.example` heeft nu het robberts-assistent-voorbeeld met 5 onderdelen; het echte
`projects.yaml` is bewust ongemoeid gelaten.

**Probleem:** `ProjectDeploymentSettings.deployConfigFor(projectName)` geeft precies één
`DeployConfig` terug. `DeploySubtaskHandler` bewaakt daardoor altijd exact één deploy-doel,
ongeacht welke bestanden de story wijzigde (zie doc, sectie "Het gat").

**Scope:**
- `deploy:` in `projects.yaml` wordt een lijst van deploy-doelen, elk met een `matchPaths`-achtig
  veld (analoog aan `pathPrefixes` op `VerificationCommand`, `.factory/verification.yaml`).
- Bestaand enkelvoudig `deploy:`-blok blijft werken (optionele lijst-vorm, geen breaking change).
- `DeploySubtaskHandler` bepaalt via de story-diff (`git diff origin/<base>...HEAD`, mechanisme
  bestaat al) welke deploy-doelen geraakt zijn, en bewaakt alleen die.
- Bij meerdere geraakte doelen: pas `deploy-approved` als alle geraakte doelen groen zijn.
- Geen path-match (bv. docs-only wijziging): direct `deploy-approved` zonder wachten, net als
  `Skip` nu.

**Relevante bestanden:**
- `factory-common/src/main/kotlin/nl/vdzon/softwarefactory/dashboard/config/ProjectConfiguration.kt`
  (`deployConfigFor`, regel 71-73, 217-220)
- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/pipeline/service/DeploySubtaskHandler.kt`
- `projects.yaml`, `projects.yaml.example`

**Acceptatiecriteria:**
- Een project met meerdere deploy-doelen en `matchPaths` per doel is configureerbaar.
- Een story die alleen doel A raakt wacht niet op doel B's status.
- Een story die A én B raakt wacht op beide.
- Bestaande enkelvoudige `deploy:`-configs blijven ongewijzigd werken (regressietest).
- Unit-tests voor: geen match (direct approved), één match, meerdere matches, backward-compat
  enkelvoudig blok.

**Buiten scope:** de Telegram-timing-fix (story 2), de reconciler (story 5).

---

## Story 2 — Telegram-melding niet meer premature

**Status:** AFGEROND (2026-07-24) — `DeployConfig.Skip` heeft nu een optioneel `apkCheck`-veld
(default `false` = ongewijzigd instant-approve-gedrag) plus `timeoutMinutes`. Staat `apkCheck: true`
(bv. `notities-apk`/`wind-apk` in `projects.yaml.example`), dan telt zo'n Skip-doel voortaan mee in
`DeploySubtaskHandler`'s multi-target-watchlijst (net als rest-restart/openshift-watch, via Story
1's `startDeployTargets`/`pollDeployTargets`-patroon): START gaat naar DEPLOYING i.p.v. instant
DEPLOY_APPROVED, en de nieuwe `apkReleaseReady()`-check (hergebruikt de bestaande
`ApkReleaseProbe`/`GitHubApkReleaseProbe`-poort, zelfde poort als `TelegramResultNotifyPoller`) moet
een release ná de deploy-starttijd vinden vóórdat de subtaak op DEPLOY_APPROVED (terminaal) mag.
Zonder gevonden release blijft de subtaak `Skipped` (non-terminaal, dus ook geen
Telegram-DONE-melding); na de eigen `timeoutMinutes` volgt DEPLOY_FAILED, net als de andere
deploy-typen. `TelegramResultNotifyPoller`/`confirmApk()` is bewust BEHOUDEN (niet verwijderd): hij
gebruikt nog de single-target `deployConfigFor()` (Story 1's "eerste doel als representatieve
config"), voegt een verrijkte melding toe (downloadlink) en dekt ook de losse openshift-watch
liveUrl-HTTP-200-check die geen equivalent heeft in `DeploySubtaskHandler` — 'm nu verwijderen zou
dus functionaliteit laten vervallen zonder vervanging, niet alleen dubbel werk wegnemen. Tests:
`mvn -pl factory-common,softwarefactory -am test` groen (587/587, 0 failures/errors), incl. 4 nieuwe
scenario's in `DeploySubtaskHandlerTest` (apkCheck=false blijft instant + probe wordt niet
aangeroepen; apkCheck=true blijft wachten zonder release; approve zodra de release verschijnt;
timeout zonder release) en 2 nieuwe parse-tests in `ProjectConfigurationMergeDeployTest`
(`apkCheck`/`timeoutMinutes` uit YAML). `mvn -pl factory-common,softwarefactory -am verify` ook
groen.

**Probleem:** `DeploySubtaskHandler` zet bij `DeployConfig.Skip` de DEPLOY-subtaak instant op
`DEPLOY_APPROVED` (regel 79-99), zonder artifact-check. `TelegramNotificationService
.classifySubtaskDone()` (regel 366-369) stuurt "✅ klaar" zodra een subtaak terminaal is
(`SubtaskPhase.isTerminal`, `SubtaskPhase.kt:83-92`) — dus te vroeg voor APK-achtige doelen. Het
bestaande `TelegramResultNotifyPoller`/`ApkReleaseProbe`-mechanisme (regel 80-133) wacht al wel
correct, maar is een los, opt-in kanaal náást de premature melding, geen vervanging.

**Scope:**
- Voor Skip/APK-achtige deploy-doelen: `DeploySubtaskHandler` wacht met `DEPLOY_APPROVED` tot een
  echte artifact-check slaagt (zelfde soort probe als `ApkReleaseProbe`), in plaats van instant
  approven.
- Daarmee wordt de subtaak pas terminaal (en dus de Telegram-DONE-melding pas verstuurd) als de
  APK er echt is — het losse `TelegramResultNotifyPoller`-kanaal kan daarna eventueel vervallen of
  blijft als extra vangnet, maar de premature eerste melding verdwijnt.

**Relevante bestanden:**
- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/pipeline/service/DeploySubtaskHandler.kt`
  (regel 79-99)
- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/telegram/services/TelegramResultNotifyPoller.kt`
  (`confirmApk`, regel 128-133) en `ApkReleaseProbe`/`GitHubApkReleaseProbe`
- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/telegram/services/TelegramNotificationService.kt`
  (regel 366-369)

**Acceptatiecriteria:**
- Voor een Skip/APK-deploy-doel blijft de DEPLOY-subtaak non-terminaal totdat de artifact-check
  slaagt.
- Er komt precies één Telegram "klaar"-melding, op het moment dat de APK er daadwerkelijk is.
- Voor niet-APK Skip-doelen (waar geen artifact-check zinvol is) blijft het gedrag ongewijzigd
  (instant approve) — alleen APK-achtige doelen krijgen de wachtstap.
- Test die simuleert: artifact nog niet aanwezig → non-terminaal; artifact verschijnt → terminaal
  + melding.

**Buiten scope:** multi-deployment-routing (story 1) — kan onafhankelijk hiervan.

---

## Story 3 — APK sync-status op project-tab

**Status:** AFGEROND (2026-07-24) — `DownloadInfo` heeft nu `commitSha` (geëxtraheerd uit de
release-body via het patroon `"commit <sha>"`, zie `GitHubReleaseClient.extractCommitSha` —
geverifieerd via de echte GitHub-API dat `target_commitish` hiervoor niet bruikbaar is, dat is
gewoon de branchnaam `"main"`, terwijl de CI-workflows van deze repo's de daadwerkelijke commit-sha
altijd expliciet in de release-body zetten) en `syncStatus: BuildSyncStatus` (hergebruikt, niet
uitgebreid — dezelfde 3-waardige enum als voor `liveComponents`/`buildStatus` volstond). `syncStatus`
wordt gezet in `DashboardQueryService.downloads()` (nieuwe `apkSyncStatus()`-companion, zelfde
prefix-tolerante `shaPrefixMatch` als `buildStatusFor`), die daarvoor per project ook de laatste
main-build-sha ophaalt via `GitHubActionsClient` (al gecached per repo-slug, dus geen extra
rate-limit-kosten bovenop `/api/v1/builds`). Frontend: `_DownloadRow` toont nu dezelfde
`_SyncStatusBadge` als `_LiveComponentRow`/`_ProjectBuildStatusRow`; de rij is omgezet van `Row` naar
`Wrap` (zelfde recept als `_LiveComponentRow`) om een harde `RenderFlex`-overflow te voorkomen zodra
een lange appnaam samenvalt met de langste badge-tekst ("Geen productieversie beschikbaar").
Tests: backend `mvn -pl factory-common,softwarefactory -am test` 552/552 groen (nieuwe
`apkSyncStatus`- en `extractCommitSha`-tests), `mvn -pl factory-common,softwarefactory -am verify`
ook groen (incl. 71 e2e/failsafe-tests); frontend `flutter test` 61/61 groen (4 nieuwe/uitgebreide
scenario's in `test/screens/projects_screen_test.dart`: out-of-sync-, in-sync- en
UNAVAILABLE-badge op een APK-rij). Commit: zie git log (SF-1213-story-3).

**Probleem:** OpenShift-`liveComponents` tonen al image + uptime + sync-status
(`_LiveComponentRow`, `projects_screen.dart:240-282`). APK's hebben alleen een platte
downloadlijst zonder sync-check (`_DownloadRow`, `projects_screen.dart:294-334`).

**Scope:**
- Geef de APK-rij dezelfde sync-badge als de liveComponents: vergelijk laatste APK-release-commit
  met de laatste main-build-SHA (zelfde soort vergelijking als `BuildSyncStatus`).
- UI-badge analoog aan "Loopt achter op main" (`projects_screen.dart:377-378`).

**Relevante bestanden:**
- `factory-common/src/main/kotlin/nl/vdzon/softwarefactory/dashboard/services/DashboardQueryService.kt`
  (`buildStatusFor`, regel 476-495; `fetchLiveComponents`, regel 414-430)
- `factory-common/src/main/kotlin/nl/vdzon/softwarefactory/dashboard/types/BuildSyncStatus.kt`
- `dashboard-frontend/lib/screens/projects_screen.dart` (`_DownloadRow`, regel 294-334)

**Acceptatiecriteria:**
- Een APK die ouder is dan de laatste main-build toont "loopt achter op main" (of gelijkwaardige
  badge).
- Een up-to-date APK toont geen waarschuwing.
- Backend-test voor de sync-berekening; UI toont de badge-state.

**Buiten scope:** wijzigingen aan OpenShift-liveComponents zelf.

---

## Story 4 — Story-detail per-onderdeel build-status

**Status:** AFGEROND (2026-07-24) — commit `7f93a35`. Nieuw: pipeline-poort
`DeployTargetStatusApi.matchedDeployTargetsFor` (`pipeline/DeployTargetStatusApi.kt`, model
`MatchedDeployTarget` in `pipeline/models/`) — hergebruikt exact `DeploySubtaskHandler`'s eigen
`matchedTargets`/`changedPaths`/`needsWatch` i.p.v. de matchPaths-bepaling een tweede keer te
implementeren; `DeploySubtaskHandler` implementeert deze poort nu ook. `StoryDetailPageData` heeft
twee nieuwe velden: `deployTargets: List<DeployTargetStatusView>` (naam + `DeployTargetRuntimeStatus`:
PENDING/IN_PROGRESS/DONE/FAILED) en `deployRolloutStage: DeployRolloutStage?`
(IN_PULL_REQUEST/MERGED_AWAITING_DEPLOY/DEPLOYED/DEPLOY_FAILED), berekend in
`DashboardQueryService.deployRolloutView()`. Er bleek geen apart per-doel-statusveld te bestaan om
"uit te lezen": `DeploySubtaskHandler` bewaakt alle geraakte, niet-Skip doelen in dezelfde
DEPLOYING-poll en zet pas in één keer DEPLOY_APPROVED/DEPLOY_FAILED zodra ALLE doelen klaar zijn —
dus elk geraakt doel krijgt een coarse status afgeleid van de DEPLOY-subtaakfase (een niet-bewaakt
Skip-doel telt altijd als DONE). Het PR-vs-gemerged-onderscheid is afgeleid uit de MERGE-subtaakfase
(`MERGE_APPROVED` = gemerged) gecombineerd met de deploy-doel-status. Module-boundary: de
`dashboard`-module (Spring Modulith) mocht `pipeline` nog niet kennen — `dashboard/package-info.java`
kreeg `"pipeline"` + `"pipeline :: models"` toegevoegd; de nieuwe enums
(`DeployTargetRuntimeStatus`/`DeployRolloutStage`) staan in `dashboard/types/` (niet in
`dashboard/models/`, dat is een "alleen immutable data classes"-named-interface volgens
`ModuleApiConventionTest`). Frontend: `_SubtasksPanel`/`_subtaskRow` in `story_detail_screen.dart`
toont voor de DEPLOY-subtaak-rij per-doel-badges (`_DeployTargetBadge`) en het rollout-label
(`_DeployRolloutBadge`, naast de fase-badge) — hergebruikt de bestaande `StatusBadge`/`BadgeTone`
(zelfde stijl als Story 3's `_SyncStatusBadge`); "geen deploy-doelen geraakt" wordt expliciet getoond
als de lijst leeg is. Tests: backend
`mvn -pl factory-common,softwarefactory -am -Dsurefire.failIfNoSpecifiedTests=false test` en
`mvn verify` op diezelfde modules groen (incl. `ModulithArchitectureTest`/`ModuleApiConventionTest`,
`DeploySubtaskHandlerTest` +2, `DashboardQueryServiceTest` +7); frontend `flutter test` groen (63/63,
incl. 2 nieuwe widget-tests in `story_detail_screen_test.dart`).

**Vereist:** story 1 gemerged (levert de deploy-doelen-lijst als databron).

**Probleem:** `StoryDetailPageData`/`_SubtasksPanel` (`FactoryDashboardModels.kt:135-154`,
`story_detail_screen.dart:505-543`) toont per subtaak alleen key/samenvatting/type/fase — geen
opsplitsing naar backend/frontend/APK, geen aparte "PR open vs gemerged, wacht op productie"-
indicator los van de generieke MERGE-subtaakfase.

**Scope:**
- Backend: `StoryDetailPageData` breidt de DEPLOY-subtaak-representatie uit met de lijst
  deploy-doelen (uit story 1) en per doel: naam, of het geraakt is door deze story, en de
  actuele status (wachtend/groen/gefaald).
- Frontend: toon per deploy-doel een rij met status, plus een duidelijk onderscheid "in PR" vs
  "gemerged, wacht op productie-deploy" (afgeleid van MERGE-subtaakfase + deploy-doel-status).

**Relevante bestanden:**
- `factory-common/src/main/kotlin/nl/vdzon/softwarefactory/dashboard/types/FactoryDashboardModels.kt`
  (regel 135-154)
- `dashboard-frontend/lib/screens/story_detail_screen.dart` (regel 505-543)
- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/pipeline/service/MergeSubtaskHandler.kt`

**Acceptatiecriteria:**
- Story-detail toont voor een story met meerdere geraakte deploy-doelen elk doel apart met eigen
  status.
- Onderscheid "PR open" vs "gemerged, wacht op deploy" is zichtbaar zonder de generieke
  subtaak-fase te hoeven interpreteren.
- Backend-test dat de juiste subset deploy-doelen wordt meegegeven (alleen geraakte doelen).

**Buiten scope:** de Rollout-tab / `deployedAt` (story 5) — dit is puur de PR/lopende-fase-status.

---

## Story 5 — `deployedAt` + `StoryDeployReconciler` + Rollout-tab

**Vereist:** story 1 gemerged (multi-artifact "volledig deployed"-check heeft de deploy-doelen-
lijst nodig).

**Ontwerpbeslissing:** de story mag naar Done zodra gemerged (resp. bij de factory zelf: na
herstart), los van of de deploy al daadwerkelijk live staat — de factory kan na de merge toch niks
meer afdwingen. "Echt live" wordt apart bijgehouden, niet als blokkerende voorwaarde voor
storyafronding.

**Scope:**
- **`deployedAt`-veld** op de story (timestamp, nullable), losstaand van het story-proces gezet.
- **`StoryDeployReconciler`** (backend, periodieke job): bekijkt stories met state = Done en
  `deployedAt = null`. Per geraakt deploy-doel (uit story 1):
  - OpenShift/rest-doelen: `git merge-base --is-ancestor <merge-commit-van-story>
    <huidige-live-SHA>` — dit lost het geval "een latere story is al gemerged én gedeployed" vanzelf
    op, want die latere live-SHA is dan automatisch een ancestor-nakomeling.
  - APK-achtige doelen: nieuwe release gevonden ná de merge-tijd (zelfde mechanisme als
    `ApkReleaseProbe`).
  - Alle geraakte doelen groen → `deployedAt` zetten.
- **Frontend: "Rollout"-tab** in de sidebar (naast Stories/My actions/Dashboard/.../Merged/...).
  Toont stories met state = Done en `deployedAt = null`, met per doel een link naar de build en
  diens status. Zodra `deployedAt` gezet is verdwijnt de story uit deze lijst (of toont "Live
  sinds <tijd>", afhankelijk van wat prettiger blijkt in de praktijk).

**Relevante bestanden (nieuw of te wijzigen):**
- Story-model/entity (waar de story-state al staat) — nieuw `deployedAt`-veld
  + migratie.
- Nieuwe service `StoryDeployReconciler` in
  `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/pipeline/service/` (analoog aan
  bestaande pollers zoals `TelegramResultNotifyPoller`).
- `factory-common/.../dashboard/services/DashboardQueryService.kt` — nieuwe query voor de
  Rollout-lijst.
- `dashboard-frontend/lib/screens/` — nieuw `rollout_screen.dart` + sidebar-item.

**Acceptatiecriteria:**
- Een story met één deploy-doel krijgt `deployedAt` gezet zodra dat doel live staat.
- Een story met meerdere geraakte doelen krijgt `deployedAt` pas als alle doelen live staan.
- Een oudere story wiens wijziging al in een latere, live gedeployde SHA zit, wordt via de
  ancestor-check ook als live herkend (test: story A merget, story B merget en deployt erna,
  reconciler-run herkent A als live zonder dat A's eigen SHA ooit los gedeployed is).
- Rollout-tab toont alleen Done-stories zonder `deployedAt`, met werkende links naar build-status.
- Reconciler-run is idempotent en veilig te herhalen (geen dubbele meldingen/side-effects).

**Buiten scope:** wijzigingen aan de story-afrondingslogica zelf (state → Done blijft zoals nu,
alleen `deployedAt` komt erbij).

---

## Afronding per story

Voor elke story: implementatie + tests groen (`mvn verify` waar backend geraakt wordt, Flutter-
tests waar frontend geraakt wordt), en een korte update in dit document (status-regel bovenaan)
zodra afgerond, vóór de volgende story start.
