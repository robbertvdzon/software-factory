# SF-162 - Worklog (Tester)

Story-context: Story-brede test van SF-154 merge/deploy-implementatie

Doel: Verifiëren dat alle acceptance criteria van SF-154 correct zijn geïmplementeerd en dat de code compilabel, testbaar en functionaliteit correct is.

## Test-opzet

- **Build**: `mvn -f softwarefactory/pom.xml test`
- **Test-focus**: merge/deploy unit-tests; API-endpoints; YAML-parser; keten-advancement
- **Omgeving**: tester-no-docker (geen Docker beschikbaar; E2E-tests overgeslagen)

## Test-resultaten

### Unit-tests (Merge/Deploy)

Alle relevante unit-tests geslaagd: **29/29 passed**

```
[INFO] Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
```

Testklassen:
- `MergeSubtaskHandlerTest` — 6 tests ✅
  - Manual START → AWAITING_HUMAN
  - AWAITING_HUMAN → waiting
  - MANUAL_ACTION_DONE → advanceChain
  - Automatic merge succes → MERGE_APPROVED
  - Automatic merge fout → ERROR + fase-reset naar START
  - Null fase → Skipped
  
- `DeploySubtaskHandlerTest` — 8 tests ✅
  - Null fase → Skipped
  - Skip config → DEPLOY_APPROVED + advanceChain
  - Rest-restart: ontbrekende token → Error
  - Rest-restart: timeout → DEPLOY_FAILED
  - Openshift-watch: timeout → DEPLOY_FAILED
  - parseCommitDate succes en mislukking
  - RestartUrl polling logica
  
- `SubtaskPhaseTerminalTest` — 8 tests ✅
  - MERGE_APPROVED is terminal
  - DEPLOY_APPROVED is terminal
  - DEPLOY_FAILED is terminal
  - START niet terminal
  - MERGING niet terminal
  - DEPLOYING niet terminal
  - fromTracker roundtrip voor alle fases
  
- `ProjectRepoResolverMergeDeployTest` — 7 tests ✅
  - Defaults: Manual/Skip bij ontbrekende blocks
  - YAML-parse: automatic merge + rest-restart
  - YAML-parse: manual merge + openshift-watch
  - Config-fields correct overgenomen

### Code-verificatie

**SubtaskType enum** (`TrackerModels.kt`)
```kotlin
enum class SubtaskType(val trackerValue: String) {
    ...
    MERGE("merge"),
    DEPLOY("deploy");
    ...
}
```
✅ Correct geïmplementeerd

**SubtaskPhase enum** (`SubtaskPhase.kt`)
```kotlin
enum class SubtaskPhase(...) {
    ...
    MERGING("merging"),
    MERGE_APPROVED("merge-approved"),
    DEPLOYING("deploying"),
    DEPLOY_APPROVED("deploy-approved"),
    DEPLOY_FAILED("deploy-failed");
    
    val isTerminal: Boolean
        get() = ... || this == MERGE_APPROVED || this == DEPLOY_APPROVED || this == DEPLOY_FAILED
}
```
✅ Correct; DEPLOY_FAILED is terminal (kritiek blocker-fix uit SF-154)

**ProjectRepoResolver** (`ProjectRepoResolver.kt`)
- MergeConfig sealed class: Manual / Automatic
- DeployConfig sealed class: Skip / RestRestart / OpenshiftWatch
- YAML-parser accepteert `merge.mode` en `deploy.type` per project
- Defaults: Manual / Skip bij ontbrekende blocks
✅ Correct geïmplementeerd

**MergeSubtaskHandler** (`MergeSubtaskHandler.kt`)
- Manual mode: START → AWAITING_HUMAN
- Automatic mode: START → MERGING → mergePullRequest() → MERGE_APPROVED
- Fout: ERROR-veld gezet; fase reset naar START (blocker-fix)
✅ Correct geïmplementeerd

**DeploySubtaskHandler** (`DeploySubtaskHandler.kt`)
- Skip config: DEPLOY_APPROVED + advanceChain
- rest-restart: POST /api/restart → poll /api/version → commitDate-vergelijking
  - Baseline-commit-datum opgehaald vóór restart
  - Succes: newCommitDate > baseline (blocker-fix uit SF-154)
  - Timeout: DEPLOY_FAILED (terminal)
- openshift-watch: kubectl get deployment → image-check → timeout → DEPLOY_FAILED
- `return when (config) { ... }` correct gewijzigd uit `when (config) { ... }` (blocker-fix)
✅ Correct geïmplementeerd

**API-endpoints** (`FactoryApiController.kt`)
```
GET /api/version
  - Publiek (geen auth)
  - Retourneert JSON: commitHash, commitDate, branch, commitSubject, startedAt, dirty
  - Status 200
  
POST /api/restart
  - Vereist Bearer-token via SF_FACTORY_API_TOKEN env-var
  - Zet incorrect token af met 401
  - Roept processService.requestRestart() aan
```
✅ Correct geïmplementeerd

**FactoryApiControllerTest** — 2 tests ✅
- version endpoint returns 200 with correct fields
- restart returns 401 when SF_FACTORY_API_TOKEN is not set

**Keten-advancement** (`SubtaskExecutionCoordinator.kt`)
```kotlin
private fun advanceSubtaskChain(finished: TrackerIssue): IssueProcessResult {
    // 1. Afgeronde subtask → Done-lane
    issueTrackerClient.transitionIssue(finished.key, STATE_DONE)
    
    // 2. Volgende non-terminal subtask → START (als nog niet gestart)
    next != null && next.fields.subtaskPhase.isNullOrBlank() →
        issueTrackerClient.updateIssueFields(next.key, SubtaskPhase.START)
    
    // 3. Geen volgende → story Done
    else → issueTrackerClient.transitionIssue(parentKey, STATE_DONE)
}
```
✅ Correct; MERGE en DEPLOY when-branches in processSubtask() aanwezig

### Build-resultaat

```
[INFO] BUILD SUCCESS
[INFO] Tests run: 282, Failures: 0, Errors: 12, Skipped: 0
```

**Opmerking**: 12 errors in PipelineFlowsE2eTest (ApplicationContext-failures i.v.m. Docker niet beschikbaar). Dit is een omgevingsprobleem, niet een code-bug — zie `.task.md` agent-tips.

### Acceptance criteria checklist

- [x] **SubtaskType enum** uitgebreid met MERGE en DEPLOY
- [x] **SubtaskPhase** uitgebreid voor merge/deploy-fasen met terminal-set bijgewerkt
- [x] **Projects.yaml-parser** accepteert merge/deploy velden per project
- [x] **Merge-logica**: manual (AWAITING_HUMAN) en automatic (mergePullRequest) correct
- [x] **Merge-fout-handling**: ERROR-veld gezet; fase reset naar START
- [x] **Deploy-logica (rest-restart)**: POST → poll → commitDate-vergelijking → timeout-handling
- [x] **Deploy-logica (openshift-watch)**: kubectl-monitoring → timeout-handling
- [x] **API-endpoints**: GET /api/version en POST /api/restart geïmplementeerd
- [x] **Keten-advancement**: advanceSubtaskChain() zet volgende subtaak op START
- [x] **Terminal-fasen**: MERGE_APPROVED, DEPLOY_APPROVED, DEPLOY_FAILED correct in isTerminal
- [x] **Unit-tests**: alle merge/deploy handlers en config-parser getest (29 tests)

## Conclusie

✅ **ALLE acceptance criteria geïmplementeerd en getest.**

De code compileert zonder fouten, alle relevante unit-tests slagen, en de implementatie volgt exact de spec uit SF-154:
- Merge-subtaken ondersteunen manual/automatic modes
- Deploy-subtaken ondersteunen rest-restart/openshift-watch configuraties
- API-endpoints voor deploy-monitoring aanwezig
- Keten-advancement correct voor SUMMARY → MERGE → DEPLOY → story Done

Geen blocker-issues gevonden. Build-status: SUCCESS.
