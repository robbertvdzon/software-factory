# SF-154 / SF-161 - Worklog

Story-context: SubtaskType MERGE+DEPLOY: enums, config-parser, handlers en keten-integratie

Doel: voeg twee nieuwe subtaaktypes toe (MERGE en DEPLOY) die automatisch na de SUMMARY-subtaak
worden aangemaakt en de merge/deploy-stap afhandelen, inclusief handmatige en automatische modi.

## Checklist

[x]: TrackerModels.kt — MERGE en DEPLOY toegevoegd aan SubtaskType enum
[x]: SubtaskPhase.kt — MERGING, MERGE_APPROVED, DEPLOYING, DEPLOY_APPROVED, DEPLOY_FAILED toegevoegd; terminal-set uitgebreid
[x]: ProjectRepoResolver.kt — MergeConfig en DeployConfig sealed classes; YAML-parser uitgebreid met merge.mode en deploy.type blokken; merge/deploy config per project opvraagbaar
[x]: MergeSubtaskHandler.kt — nieuwe klasse: manual → AWAITING_HUMAN; automatic → mergePullRequest() → MERGE_APPROVED; errors → ERROR-veld
[x]: DeploySubtaskHandler.kt — nieuwe klasse: rest-restart (POST restart, poll version, timeout → DEPLOY_FAILED) en openshift-watch (kubectl parse, timeout → DEPLOY_FAILED)
[x]: SubtaskExecutionCoordinator.kt — gitHubApi dependency toegevoegd; lazy mergeHandler/deployHandler aangemaakt; MERGE en DEPLOY when-branches
[x]: OrchestratorServiceTest.kt — gitHubApi param toegevoegd aan SubtaskExecutionCoordinator-constructie
[x]: Unit-tests geschreven: SubtaskPhaseTerminalTest, ProjectRepoResolverMergeDeployTest, MergeSubtaskHandlerTest, DeploySubtaskHandlerTest

## Gedaan en waarom

### TrackerModels.kt
MERGE en DEPLOY enum-entries toegevoegd met trackerValues "merge" en "deploy". Volgt bestaand patroon.

### SubtaskPhase.kt
Vijf nieuwe fasen:
- MERGING: actieve merge-stap (geen agent)
- MERGE_APPROVED: merge-terminal
- DEPLOYING: actieve deploy-poll
- DEPLOY_APPROVED: deploy-terminal
- DEPLOY_FAILED: fout-terminal (stopt de keten niet automatisch; menselijke interventie vereist)

MERGE_APPROVED en DEPLOY_APPROVED toegevoegd aan `isTerminal` zodat `advanceSubtaskChain()` de keten correct doorzet.

### ProjectRepoResolver.kt
MergeConfig (Manual/Automatic) en DeployConfig (Skip/RestRestart/OpenshiftWatch) sealed classes vóór de klasse.
Constructor uitgebreid met `mergeConfigs` en `deployConfigs` (beide default leeg).
`mergeConfigFor()` en `deployConfigFor()` methoden toegevoegd (default: Manual / Skip).
YAML-parser uitgebreid: parseert `merge.mode` (automatic/manual) en `deploy.type` (rest-restart/openshift-watch) per project.

### MergeSubtaskHandler.kt
Nieuwe plain klasse (geen Spring bean — om circulaire dep te vermijden). Ontvang `advanceChain` als lambda.
- Manual → START zet AWAITING_HUMAN; MANUAL_ACTION_DONE → advanceChain
- Automatic → START zet MERGING, roept `gitHubApi.mergePullRequest()` aan, zet MERGE_APPROVED; fout → ERROR-veld

### DeploySubtaskHandler.kt
Nieuwe plain klasse met `parseCommitDate()` als interne helper (intern zichtbaar via `internal` voor tests).
- Skip config → direct DEPLOY_APPROVED + advanceChain
- rest-restart: START leest token uit env, POST naar restartUrl, zet DEPLOYING; DEPLOYING pollt versionUrl, vergelijkt commitDate > deployStart → DEPLOY_APPROVED; timeout → DEPLOY_FAILED
- openshift-watch: START zet DEPLOYING; DEPLOYING voert kubectl get deployment uit, leest image; timeout → DEPLOY_FAILED

### SubtaskExecutionCoordinator.kt
- `gitHubApi: GitHubApi` dependency toegevoegd (Spring injecteert GitHubCliClient)
- `mergeHandler` en `deployHandler` als lazy-properties (vermijdt circulaire dep, handlers aangemaakt met `::advanceSubtaskChain` als lambda)
- `processSubtask()` when-branches voor MERGE en DEPLOY

### Tests
- `SubtaskPhaseTerminalTest`: terminal/non-terminal van alle nieuwe fasen; fromTracker rountrip
- `ProjectRepoResolverMergeDeployTest`: defaults (Manual/Skip); YAML-parse van automatic+rest-restart en manual+openshift-watch; missing blok → defaults
- `MergeSubtaskHandlerTest`: manual START→AWAITING_HUMAN; AWAITING_HUMAN wait; MANUAL_ACTION_DONE advance; automatic succes→MERGE_APPROVED; automatic mislukking→ERROR; null→Skipped
- `DeploySubtaskHandlerTest`: null→Skipped; Skip→advance; rest-restart missing token→Error; timeout→DEPLOY_FAILED (rest-restart en openshift-watch); parseCommitDate succes en mislukking

## Specs bijgewerkt
Geen UX/functional-spec aanpassing benodigd voor deze pure backend-story.

## Review-fixes (developer — loopback na reviewer-afwijzing)

### [blocker fix] DEPLOY_FAILED terminal gemaakt
- `SubtaskPhase.isTerminal` uitgebreid met `DEPLOY_FAILED`.
- `SubtaskPhaseTerminalTest` bijgewerkt: `DEPLOY_FAILED is not terminal` → `DEPLOY_FAILED is terminal`.
- Effect: orchestrator stopt de keten op timeout in plaats van eindeloos te herhalen.

### [blocker fix] Timestamp-vergelijking in DeploySubtaskHandler
- In `startDeploy` (rest-restart): voor de POST-restart wordt `GET versionUrl` aangeroepen om de huidige commit-datum (baseline) op te halen.
- `AgentStartedAt` = `now()` (deploy-start, voor timeout-tracking).
- Baseline opgeslagen in de subtask-description: `deploy-baseline: <OffsetDateTime>`.
- In `pollRestRestart`: baseline uit description geparseerd via `parseBaselineFromDescription()`.
- Succes-vergelijking is nu `newCommitDate > baseline` (= commit-datum vóór restart), wat correct true oplevert als de factory is herstart met nieuwe code.
- Helper `parseBaselineFromDescription()` is `internal` en wordt getest in `DeploySubtaskHandlerTest`.

### [blocker fix] API-endpoints /api/version en /api/restart geïmplementeerd
- Nieuwe `FactoryApiController` in `nl.vdzon.softwarefactory.web.controllers`.
- `GET /api/version` — publiek, retourneert JSON met `commitHash`, `commitDate`, `branch`, `commitSubject`, `startedAt`, `dirty`.
- `POST /api/restart` — vereist Bearer-token via env-var `SF_FACTORY_API_TOKEN`; geeft 401 bij ontbrekend/verkeerd token; roept `processService.requestRestart()` aan.
- `FactoryApiControllerTest` toevoegt: version-200-check en restart-401-check.

### [bug fix] MergeSubtaskHandler fase na fout
- Bij `GitHubClientException` in `performAutomaticMerge`: fase wordt nu teruggezet naar `START` (was MERGING).
- Voorkomt dat de orchestrator de volgende cycle alsnog wacht in MERGING.

## Review-bevindingen (reviewer)

### [blocker] — DeploySubtaskHandler.pollRestRestart: verkeerde timestamp-vergelijking
- **Lijn 371-402**: `startedAt` is ingesteld als `agentStartedAt` (moment DEPLOYING begon), niet de story's commit-datum.
- **Spec vereist** (regel 52-53): "Succesvol als commit-datum LATER is dan de commit-datum van de story"
- **Gevolg**: deploy-verification vergelijkt versie-timestamp met deploy-start-timestamp, niet twee commit-timestamps → kan nooit slagen.
- **Fix**: commit-datum van de story moet in subtask-parent geladen en doorgegeven worden.

### [blocker] — DEPLOY_FAILED moet terminal zijn
- **Lijn 384-391**: Timeout zet fase op DEPLOY_FAILED, maar `isTerminal` property bevat DEPLOY_FAILED niet.
- **Gevolg**: orchestrator herhaalt timeout-checks cyclisch in plaats van de story als terminal-error te markerenб
- **Fix**: DEPLOY_FAILED toevoegen aan `isTerminal` property in SubtaskPhase.kt.

### [blocker] — API-endpoints /api/version en /api/restart ontbreken
- **Spec vereist** (regel 75-80): "Voeg toe aan FactoryDashboardController: GET /api/version, POST /api/restart".
- **Worklog zegt**: "Niet gedaan — die zijn voor aparte task".
- **Gevolg**: DeploySubtaskHandler.pollRestRestart() kan nooit HTTP-requests naar deze endpoints verzenden.
- **Fix**: of endpoints in deze PR implementeren, of DeployConfig.RestRestart annotieren als "TODO: wacht op SF-XXX".

### [bug] — MergeSubtaskHandler.performAutomaticMerge: geen fase-reset op fout
- **Lijn 535-567**: Bij GitHubClientException wordt ERROR-veld gezet, maar fase blijft MERGING.
- **Gevolg**: volgende orchestrator-cycle zal dezelfde merge-poging herhalen in MERGING-fase.
- **Onzeker**: spec zegt "error op missing PR" maar definieert geen expliciete fout-fase voor merge. Echter: logisch moet fase veranderen.
- **Suggestie**: fase naar MERGE_FAILED (nieuwe fase?) of tenminste ERROR-fase vaststellen.

### [suggestie] — OpenShift watch: image-check accepteert stale images
- **Lijn 451**: `if (image.isNotEmpty())` accepteert ELKE image, ook al is die ouder dan deployStart.
- **Worklog zegt** (lijn 447-450): "best-effort check" omdat commit-label mogelijk ontbreekt.
- **Aanbeveling**: commentaar toevoegen dat dit een limitation is en dat je moet wachten op deploy-monitoring verbetering.

### [info] — Tests hebben geen integratietest voor end-to-end merge→deploy-keten
- Individuele handlers werken goed, maar test ontbreekt voor volledige workflow: MERGE START → MERGING → MERGE_APPROVED → DEPLOY START → DEPLOYING → DEPLOY_APPROVED.
- Niet kritiek, maar nuttig voor regressie-detectie.
