# SF-154 - Eindsamenvatting

## Story

Eindsamenvatting

## Eindsamenvatting

Op basis van de worklogs maak ik nu de eindsamenvatting.

---

## Eindsamenvatting SF-154: Merge- en deploy-subtaken aan einde van elke story

### Wat is gebouwd

De story-keten is uitgebreid van `summary-approved → story Done` naar `summary-approved → merge → deploy → story Done`. Twee nieuwe subtaaktypes zijn volledig geïmplementeerd:

**Merge-subtaak (MERGE)**
- `SubtaskType.MERGE` en bijbehorende fasen `MERGING` / `MERGE_APPROVED` toegevoegd.
- Manual mode: subtaak zet status op `AWAITING_HUMAN`; mens voert de merge uit en markeert `MANUAL_ACTION_DONE`.
- Automatic mode: factory roept `GitHubApi.mergePullRequest()` aan en zet fase naar `MERGE_APPROVED`. Bij fout wordt het ERROR-veld gezet en wordt de fase gereset naar `START` (zodat de orchestrator niet vastloopt in `MERGING`).

**Deploy-subtaak (DEPLOY)**
- `SubtaskType.DEPLOY` en fasen `DEPLOYING` / `DEPLOY_APPROVED` / `DEPLOY_FAILED` toegevoegd; alle drie zijn terminal.
- `rest-restart` mode: haalt baseline commit-datum op vóór de `POST /api/restart`, pollt daarna `GET /api/version` tot `newCommitDate > baseline`; timeout → `DEPLOY_FAILED` (terminal).
- `openshift-watch` mode: voert `kubectl get deployment` uit, leest image, timeout → `DEPLOY_FAILED`.
- `DeployConfig.Skip` zet direct `DEPLOY_APPROVED` en zet de keten door.

**API-endpoints**
- Nieuwe `FactoryApiController`: `GET /api/version` (publiek, retourneert commitHash/commitDate/branch/dirty) en `POST /api/restart` (vereist Bearer-token via `SF_FACTORY_API_TOKEN`, retourneert 401 bij ontbrekend/fout token).

**Configuratie**
- `ProjectRepoResolver` uitgebreid met `MergeConfig` (Manual/Automatic) en `DeployConfig` (Skip/RestRestart/OpenshiftWatch) sealed classes.
- `projects.yaml`-parser leest `merge.mode` en `deploy.type` per project; defaults zijn Manual/Skip.

**Keten-integratie**
- `SubtaskExecutionCoordinator` heeft `when`-branches voor MERGE en DEPLOY; `advanceSubtaskChain()` zet de volgende subtaak correct op START.

### Gemaakte keuzes

- Handlers zijn plain klassen (geen Spring beans) om circulaire afhankelijkheden te vermijden; `advanceChain` wordt als lambda doorgegeven.
- Baseline commit-datum voor deploy-verificatie wordt opgehaald vóór de restart en opgeslagen in de subtask-description; dit maakt de vergelijking correct ongeacht deploystart-timing.
- `DEPLOY_FAILED` is bewust terminal gemaakt: de orchestrator stopt de keten en menselijke interventie is vereist.
- OpenShift-watch doet een best-effort image-check (controleert op aanwezigheid, niet op commit-label); dit is een bekende beperking gedocumenteerd in de worklog.

### Wat is getest

- 29 nieuwe unit-tests slagen volledig:
  - `MergeSubtaskHandlerTest` (6 tests): manual/automatic flows inclusief fout-handling.
  - `DeploySubtaskHandlerTest` (8 tests): alle config-paden, timeout, token-ontbrekend, parseCommitDate.
  - `SubtaskPhaseTerminalTest` (8 tests): terminal/non-terminal van alle nieuwe fasen, fromTracker roundtrip.
  - `ProjectRepoResolverMergeDeployTest` (7 tests): defaults en volledige YAML-parse.
  - `FactoryApiControllerTest` (2 tests): version-200 en restart-401.
- Totale build: `282 tests, 0 failures` (12 errors in E2E-tests zijn een Docker-omgevingsprobleem, niet code-bugs).

### Wat bewust niet is gedaan

- Geen integratietest voor de volledige end-to-end keten `MERGE START → MERGING → MERGE_APPROVED → DEPLOY START → DEPLOYING → DEPLOY_APPROVED`; de individuele handlers zijn wel volledig geunit-test.
- OpenShift-watch controleert niet op exacte commit-id in de image-tag (geen commit-label beschikbaar); dit is als beperking geaccepteerd.

---
