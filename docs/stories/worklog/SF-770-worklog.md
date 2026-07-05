# SF-770 / SF-771 - Worklog

Story-context bij eerste pickup:
Robuuste deploy-verificatie: SHA-match + ArgoCD-waarheidsbron + ruimere timeouts

Doel in eigen woorden: voorkom dat een geslaagde uitrol ten onrechte op `deploy-failed` (of, bij de
tester, op een preview-timeout) belandt. Verifieer op de daadwerkelijk live commit-SHA i.p.v. blind op
een herstart-tijdstip / niet-lege image te wachten, gebruik ArgoCD als waarheidsbron waar geconfigureerd,
en verruim de timeout als vangnet.

## Checklist

- [x]: read issue and target docs
- [x]: SHA-gebaseerde verificatie in DeploySubtaskHandler (rest-restart) + terugval
- [x]: ArgoCD als waarheidsbron voor openshift-watch (Synced+Healthy+Succeeded op verwachte revisie) + terugval
- [x]: default deploy-timeout 10 → 20 min, per project overschrijfbaar
- [x]: TesterPreviewFlow default 600 → 1200s, env-override behouden, foutmelding reflecteert timeout
- [x]: unit-/integratietests voor succes/fout-SHA, ArgoCD gezond/ongezond, timeout, terugval, default-timeout, argocd-parsing
- [x]: run relevante tests (groen)
- [x]: docs (functional-spec, technical-spec, projects.yaml.example) bijgewerkt

## Wat en waarom

### 1. SHA-gebaseerde verificatie (rest-restart)
- `GitHubApi.latestCommitSha(targetRepo, branch)` toegevoegd (default `null` in de interface zodat
  test-fakes niet hoeven te wijzigen); geïmplementeerd in `GitHubCliClient` via
  `gh api repos/<slug>/commits/<branch> -q .sha`.
- `DeploySubtaskHandler` krijgt `StoryRunRepository` + `GitHubApi` als deps. `expectedSha(parentKey)`
  bepaalt de verwachte live-SHA = HEAD van de story-base-branch (default `main`) ná merge.
- `pollRestRestart` keurt goed zodra `/api/version`-`commitHash` prefix-matcht met de verwachte SHA
  (`shaPrefixMatch`, short vs. full, case-insensitive). Ontbreekt de verwachte SHA of rapporteert
  `/api/version` geen `commitHash`, dan terugval op het bestaande `startedAt`-gedrag. Verkeerde/uitblijvende
  SHA → blijft wachten tot de (verruimde) timeout → `DEPLOY_FAILED`.

### 2. ArgoCD als waarheidsbron (openshift-watch)
- `DeploymentStatusProbe` uitgebreid met `argoApplicationStatus(namespace, application)` (default `null`,
  zodat bestaande SAM-implementaties blijven werken) + data class `ArgoApplicationStatus`.
- `KubectlDeploymentStatusProbe` leest de ArgoCD `Application`-CR met één `kubectl get application ... -o jsonpath`
  die sync/health/operationPhase/revision als `|`-gescheiden regel teruggeeft (geen extra JSON-parser).
- `DeployConfig.OpenshiftWatch` kreeg optionele `argocdApp` + `argocdNamespace`. Zijn beide gezet, dan
  keurt `pollArgoCd` pas goed bij Synced + Healthy + Succeeded op de (prefix-matchende) verwachte revisie;
  anders de bestaande "image niet-leeg"-heuristiek (geen regressie).

### 3. Ruimere timeout
- `ProjectRepoResolver.DEFAULT_DEPLOY_TIMEOUT_MINUTES = 20`; gebruikt als default bij het parsen van
  zowel rest-restart als openshift-watch. Per project overschrijfbaar via `timeoutMinutes`.

### 4. Tester-preview
- `TesterPreviewFlow` default `SF_PREVIEW_WAIT_TIMEOUT_SECONDS` 600 → 1200s; env-override en foutmelding
  (die de werkelijke `timeout.toSeconds()` noemt) ongewijzigd behouden.

### Tests
- `DeploySubtaskHandlerTest`: SHA-match → approved, SHA-mismatch → blijft wachten, ArgoCD gezond → approved,
  ArgoCD ongezond/verkeerde revisie → wachten, terugval op image-heuristiek zonder ArgoCD-config,
  `shaPrefixMatch`-randgevallen. Bestaande scenario's ongewijzigd (expectedSha default `null`).
- `ProjectRepoResolverMergeDeployTest`: default-timeout = 20, argocd-velden parsen + afwezig → null.
- `TesterPreviewFlowTest`: foutmelding reflecteert de geconfigureerde timeout.
- `FakeGitHubApi`/`OrchestratorTestHarness` bijgewerkt voor de nieuwe deps.

### Testresultaat (lokaal, mvn 3.9.10 + JDK 21, netwerk aanwezig)
- `DeploySubtaskHandlerTest` + `ProjectRepoResolverMergeDeployTest`: 27 tests groen.
- `TesterPreviewFlowTest`: 2 tests groen.
- `factory-common` volledige suite: 39 groen.
- `pipeline.*` + `OrchestratorSubtaskFlowTest` + `OrchestratorSubtaskChainTest`: 31 groen.
- Docker-afhankelijke e2e-suite niet lokaal gedraaid (geen Docker); laat de pipeline die draaien.

### Aangepaste specs
- `docs/factory/functional-spec.md`: sectie "Robuuste deploy-verificatie (SF-771)".
- `docs/factory/technical-spec.md`: deploy-config-modes + default-timeout-constante.
- `projects.yaml.example`: deploy-uitleg (SHA-match, ArgoCD-velden) + default-timeout 20.
