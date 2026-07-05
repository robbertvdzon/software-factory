# SF-770 - Deploy-verificatie: false-negative deploy-failed voorkomen

## Story

Deploy-verificatie: false-negative deploy-failed voorkomen

<!-- refined-by-factory -->

## Scope

Maak de deploy-verificatie van de factory robuust zodat een geslaagde uitrol niet langer ten onrechte op `deploy-failed` (of, bij de tester, op een preview-timeout) belandt. De kern: verifieer op de daadwerkelijk live SHA in plaats van blind op een herstart-tijdstip/HTTP-200 te wachten, gebruik ArgoCD als waarheidsbron waar dat van toepassing is, en verruim de timeout als vangnet.

In scope:
1. **SHA-gebaseerde verificatie** in `DeploySubtaskHandler`: een deploy geldt pas als geslaagd wanneer de gedeployde service de nieuwe commit-SHA rapporteert.
   - De verwachte SHA is de zojuist gemergede commit (bepaald uit de HEAD van de base-branch ná merge, of uit een bij de merge vastgelegde merge-commit-SHA).
   - Poll `/api/version` (config `versionUrl`) tot `commitHash` overeenkomt met de verwachte SHA (prefix-match, zodat short vs. full SHA werkt).
2. **ArgoCD als waarheidsbron** voor GitOps-/OpenShift-deploys: wacht via kubectl op de ArgoCD `Application` tot `sync.status=Synced` **én** `health.status=Healthy` **én** `operationState.phase=Succeeded` op de verwachte revisie/SHA, i.p.v. de huidige "image niet-leeg"-heuristiek. Configuratie (ArgoCD-app + namespace) komt in het bestaande `deploy:`-blok van `projects.yaml`.
3. **Ruimere timeout als vangnet**: verhoog de default deploy-timeout van 10 min (600s) naar ca. 20 min (1200s), instelbaar per project via de bestaande `timeoutMinutes`. Pas dán, ná de timeout, `DEPLOY_FAILED` zetten.
4. **Tester-preview-check gelijktrekken**: verhoog de default van `SF_PREVIEW_WAIT_TIMEOUT_SECONDS` in `TesterPreviewFlow` (600s → ~1200s), env-override behouden, zodat de HTTP-200-wachtstap dezelfde ruime marge krijgt.

Buiten scope: nieuwe deploy-strategieën anders dan de hierboven genoemde; wijzigingen aan hoe ArgoCD zelf synct; het daadwerkelijk invullen van project-specifieke config-waarden (dat is ops-config in `projects.yaml`).

## Acceptance criteria

- Een geslaagde deploy zet de subtaak **niet** meer op `deploy-failed`, mits:
  - `/api/version` van de target de nieuwe (gemergede) commit-SHA toont, en
  - waar ArgoCD geconfigureerd is: de `Application` Synced + Healthy + `operationState=Succeeded` op de verwachte revisie is.
- Bij een **echte** fout blijft de stap falen: als de rollout binnen de (verruimde) timeout niet Synced/Healthy wordt, of `/api/version` de verwachte SHA nooit toont (bv. oude build blijft live / verkeerde SHA), gaat de subtaak alsnog naar `DEPLOY_FAILED`.
- De deploy-timeout is verhoogd (default ~1200s) en blijft per project overschrijfbaar via `timeoutMinutes`.
- De tester-preview-wachtstap gebruikt een verruimde default-timeout (~1200s), instelbaar via `SF_PREVIEW_WAIT_TIMEOUT_SECONDS`; de foutmelding reflecteert de werkelijke timeout.
- Bestaande deploy-modes zonder ArgoCD-/versie-config blijven werken (geen regressie): ontbreekt `versionUrl`/ArgoCD-config, dan valt de handler terug op het huidige gedrag.
- Unit-/integratietests dekken: succes op matchende SHA, fout op verkeerde/uitblijvende SHA, ArgoCD Synced+Healthy+Succeeded → approved vs. ongezond/verkeerde revisie → failed, en de timeout-grens. De bestaande e2e-suite blijft groen.

## Aannames

- **ArgoCD-status wordt via kubectl** (bestaande `SF_KUBECONFIG` / `DeploymentStatusProbe`-patroon) opgehaald als k8s-`Application`-CR (`status.sync.status`, `status.health.status`, `status.operationState.phase`, `status.sync.revision`); er wordt géén aparte ArgoCD-API-client geïntroduceerd.
- **De verwachte SHA** wordt bepaald uit de base-branch HEAD ná merge (origin/`main`) of uit een bij de merge vastgelegde merge-commit-SHA; SHA-vergelijking is prefix-tolerant (short vs. full).
- **Targets exposen `/api/version`** met een `commitHash`-veld analoog aan de factory zelf; ontbreekt dit endpoint/veld, dan geldt alleen de ArgoCD-/bestaande check.
- **Verruimde timeout ~20 min (1200s)** als concrete default binnen de door het issue genoemde 900–1200s-bandbreedte; per project bij te stellen.
- **ArgoCD-config** (app-naam + namespace) landt als optionele velden in het bestaande `deploy:`-blok van `projects.yaml`; concrete waarden per project zijn ops-config, niet onderdeel van deze story.
- **Poll-interval** blijft de bestaande `pollIntervalSeconds` (default 15s).

## Eindsamenvatting

Ik heb `.task.md`, het worklog en de story-diff gelezen. Alles is groen en afgerond; ik heb geen openstaande vragen. Hieronder de eindsamenvatting voor de PO.

---

## Eindsamenvatting — SF-770: Deploy-verificatie: false-negative `deploy-failed` voorkomen

### Wat is gebouwd
Een geslaagde uitrol werd voorheen soms onterecht op `deploy-failed` (of bij de tester op een preview-timeout) gezet, omdat er blind op een herstart-tijdstip of een niet-lege image werd gewacht. De verificatie is nu gebaseerd op de **werkelijk live commit-SHA** en op **ArgoCD als waarheidsbron**, met een ruimere timeout als vangnet.

1. **SHA-gebaseerde verificatie (rest-restart)** — `DeploySubtaskHandler` keurt een deploy pas goed zodra `/api/version` (`commitHash`) prefix-matcht met de verwachte SHA = HEAD van de story-base-branch (default `main`) ná merge. Prefix-match werkt voor zowel short als full SHA (case-insensitive). Nieuwe helper `GitHubApi.latestCommitSha(...)`, geïmplementeerd via `gh api`.
2. **ArgoCD-waarheidsbron (openshift-watch)** — via `kubectl` wordt de ArgoCD `Application`-CR uitgelezen; goedkeuring alleen bij **Synced + Healthy + operationState=Succeeded** op de verwachte revisie. Config-velden `argocdApp` + `argocdNamespace` toegevoegd aan het `deploy:`-blok.
3. **Ruimere timeout** — default deploy-timeout 10 → **20 min (1200s)** (`DEFAULT_DEPLOY_TIMEOUT_MINUTES`), per project overschrijfbaar via `timeoutMinutes`.
4. **Tester-preview gelijkgetrokken** — `TesterPreviewFlow` default `SF_PREVIEW_WAIT_TIMEOUT_SECONDS` 600 → **1200s**; env-override behouden en de foutmelding noemt de werkelijke timeout.

### Belangrijkste keuzes
- **Terugval = geen regressie**: ontbreekt de verwachte SHA of `commitHash`, dan geldt het bestaande `startedAt`-gedrag; ontbreekt ArgoCD-config, dan de bestaande "image niet-leeg"-heuristiek.
- **Geen aparte ArgoCD-API-client**: één `kubectl -o jsonpath`-call levert sync/health/phase/revision als `|`-gescheiden regel — geen extra JSON-parser.
- **Verwachte SHA = base-branch HEAD** (lichte optie i.p.v. opgeslagen merge-SHA). Bewuste trade-off: latere merges op `main` kunnen in theorie een false-negative geven; gedocumenteerd.
- **Bij een echte fout blijft de stap falen**: verkeerde/uitblijvende SHA of ongezonde ArgoCD-status → na de verruimde timeout alsnog `DEPLOY_FAILED`.

### Wat is getest
- Volledige `softwarefactory`-suite groen: **458 tests, 0 failures/errors/skipped**.
- Gericht: `DeploySubtaskHandlerTest` (17), `ProjectRepoResolverMergeDeployTest` (10), `TesterPreviewFlowTest` (2) — allen groen.
- Gedekte scenario's: SHA-match → approve; SHA-mismatch → wachten → timeout → `DEPLOY_FAILED`; ArgoCD gezond → approve; ongezond/verkeerde revisie → wachten; terugval op image-heuristiek zonder config; default-timeout = 20 min; ArgoCD-veld-parsing; `shaPrefixMatch`-randgevallen; tester-preview foutmelding reflecteert timeout.

### Bewust niet gedaan
- **Docker-afhankelijke e2e-suite** niet lokaal gedraaid (geen Docker in de agent-omgeving) — wordt door CI gedekt.
- **Project-specifieke config-waarden** (concrete ArgoCD-app/namespace, per-project `timeoutMinutes`) zijn ops-config in `projects.yaml` en vallen buiten scope.
- Geen nieuwe deploy-strategieën of wijzigingen aan hoe ArgoCD zelf synct.

### Bijgewerkte documentatie
`docs/factory/functional-spec.md`, `docs/factory/technical-spec.md` en `projects.yaml.example` (SHA-match, ArgoCD-velden, default-timeout 20).

---
