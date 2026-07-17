# Verbeterplan: stories soepeler door de pipeline (juli 2026)

Doel: een story wordt **vlot** verwerkt, maar wél volledig getest en gereviewd — zonder dat
rollen elkaars werk overdoen of stranden op omgevingsproblemen. Dit document is de bijhoudlijst:
wat is al gefixt, wat staat open, en in welke volgorde pakken we het op.

Aanleiding: SF-987 (PNF) en SF-1009 (factory) op 2026-07-17 — beide uiteindelijk succesvol
afgerond, maar met samen ±10 afkeur-/herstelrondes die allemaal een aanwijsbare, structurele
oorzaak hadden (zie "Al gefixt" en de openstaande punten hieronder).

---

## Al gefixt (2026-07-17)

- [x] **Docker-socket voor Testcontainers in agent-containers** — `mvn verify` met echte
  Postgres werkt nu in developer/reviewer/tester (socket-mount + `--group-add` +
  `TESTCONTAINERS_HOST_OVERRIDE`; géén chmod/root). Commit `04d6f46`.
- [x] **Verdampende main-merge** — de post-run-sync pushte alleen bij een vuile werkboom,
  waardoor de main-merge van een "niets te doen"-developer-ronde verloren ging en de tester
  verouderde code bleef afkeuren. Sync pusht nu ook bij een al-gecommitte voorsprong
  (`GitApi.aheadOfRemote`). Commit `eb804ef`.
- [x] **Flaky kill-timeout-test (agentworker)** — faalde consistent in de agent-sandbox
  (geen PID-1-subreaper → gekilld kind blijft zombie → `isAlive()` blijft true). Test
  accepteert nu dood-of-zombie. Commits `073dc7f` + `f6e601a`.
- [x] **PNF-deploy-timeout 10 → 20 min** — CI-imagebuild + ArgoCD-sync duurde ~12-15 min,
  waardoor de deploy-check uittimede terwijl de deploy gewoon landde (`projects.yaml`, lokaal).
- [x] **Postgres-autostart na reboot** — `docker compose up` struikelde in de loop over de
  `:?`-verplichte dashboard-secrets; nu eerst kale `docker start` met compose+dummies als
  fallback. Commit `0324e9b`.
- [x] **LaunchAgent-KeepAlive** — launchd herstart de factory-loop nu als die faalt (bv.
  Docker te traag op bij boot); bewust stoppen blijft gestopt. Commit `78db1a3` + plist.

---

## Openstaand — in fix-volgorde

De volgorde is gekozen op: eerst deblokkeren (zonder groene CI merget geen enkele
factory-story), dan de duurste faalmodus (afkeur-loops) dichtzetten, dan snelheid, dan
structurele verbeteringen.

### 1. CI op main groen maken ✅ *(afgerond 2026-07-17, commit b023562 — "Repository verification" groen op main)*

De verplichte check **"Repository verification"** faalde op elke main-commit sinds
2026-07-17 09:19. Vier rode componenten, alle opgelost:

- [x] **Documentation audit**: script gebruikt nu `grep` i.p.v. het op de runner ontbrekende
  `rg` (exit 127 was niet te onderscheiden van "fact ontbreekt" → valse roods).
- [x] **Frontend verification**: Flutter-pin in `verify.yml` 3.32.8 → 3.44.6 (Dart `^3.9`-eis).
- [x] **Kotlin quality ratchet**: écht opgelost i.p.v. baseline opgerekt — `dockerRunCommand`
  gesplitst in helpers en de bridge-dispatch naar een inner `OperationRouter` (netto −4
  findings); baseline alleen een fingerprint-swap voor al bestaande constructor-schuld.
- [x] **Backend verification**: bevestigde flake — groen op de rerun (b023562), geen repro.
- [x] **Bewaking**: `tools/check-main-ci-green` telegramt zodra "Repository verification" op
  main niet groen is. De losse LaunchAgent-planning is op Robberts verzoek weer verwijderd
  (2026-07-17): de check wordt een nightly job in het bestaande `.factory/nightly`-mechanisme
  — job voegt Robbert zelf toe; het script is daarvoor direct aanroepbaar.

### 2. Afkeur-loops dichtzetten (flake-beleid + schone staat) ✅ *(afgerond 2026-07-17)*

- [x] **Kill-verbod op verify-commando's**: developer- én tester-regels in
  `AgentPromptContracts.kt` verbieden nu `timeout`/kill rond het vangnet (een gekilde build
  corrumpeerde `jacoco-it.exec` en kostte twee afkeurrondes).
- [x] **Schone staat tussen rollen**: `clean` toegevoegd aan `repository-maven-verify` in
  `.factory/verification.yaml` (met uitleg-comment). Voor PNF volgt hetzelfde in blok 6.
- [x] **Flake-protocol in de tester-prompt**: rode test buiten de story-diff → eerst geïsoleerd
  én één keer volledig herdraaien; beide groen = flake (goedkeuren + expliciet melden + tip).
  Plus: één groene vangnet-run is bewijs genoeg.
- [x] **Bekende e2e-flakes**: de mitigatie bleek al aanwezig (`SF_ACTIVE_PHASE_RECOVERY_DELAY_MS`
  = 600000 in `E2eTestConfig`, neemt de settle-grace-race weg); suite vandaag meermaals volledig
  groen incl. CI — geen repro, niets extra nodig.
- [x] **Test-chain-cap heeft een uitweg**: `resume` op de subtaak verhoogt nu een per-issue
  limiet (`AI Max Test Chain Resets`, V17-migratie) — de spiegel van de developer-loopback-escape.
  De cap-melding noemt het pad expliciet.
- [ ] **Restpunt — image-rebuild-detectie**: de loop herbouwt `agent:local` alleen als de
  `git pull` de wijziging binnenbrengt; bij lokaal gepushte commits is de pull een no-op en
  blijft de oude image staan (zelfde klasse als de verdampende merge). Fix: vergelijk bv. de
  image-ingebakken commit-sha met HEAD i.p.v. de pull-diff.

### 3. Runs sneller maken (zelfde grondigheid, minder wachten) ✅ *(afgerond 2026-07-17)*

- [x] **Persistente build-caches per repo**: bouw/test-rollen mounten nu `work/build-caches/
  <repo-slug>/m2` en `.../pub-cache` (DockerAgentRuntime.buildCacheMounts). Gedeeld per repo,
  weggooibaar bij corruptie; scheelt de dependency-download van elke run.
- [x] **Tijdsbesteding zichtbaar**: bleek grotendeels al gedekt — het dashboard-model ontsluit
  `durationMs`/`costUsdEst`/tokens per run en SF-1038 toont start/looptijd op de Agents-tab.
  Rest (aggregaat "tijd/kosten per rol per story"): genoteerd als kandidaat voor de
  proces-teststory — mooi afgebakend en zichtbaar resultaat.

### 4. Dubbel werk eruit: revisiongebonden testbewijs door de hele keten ✅ *(kern afgerond 2026-07-17; hergebruik-optimalisatie als kandidaat-story)*

Vandaag draaide dezelfde testsuite tot 4× per ronde: developer verify → reviewer hertest →
tester 2× verify. Het mechanisme om dat te stoppen bestaat al (SF-927: revisiongebonden
testbewijs voor de testerpoort) — trek het door:

- [x] **Developer levert verplicht harness-geverifieerd bewijs af**: de agent-harness draait
  na elke `developed` het volledige vangnet deterministisch (zelfde poort als de tester, SF-927);
  rood = automatisch `development-rejected` mét diagnose. Een rood vangnet komt zo een volledige
  review-ronde eerder boven en "groen claimen" kan niet meer.
- [x] **Reviewer hertest niet standaard**: prompt-regel — geen volledige vangnet-herruns, alleen
  gerichte checks; harness-bewijs is leidend.
- [x] **Tester draait het vangnet niet meer zelf**: de harness doet dat al ná zijn run; zijn
  tijd gaat naar gedrag/preview/E2E. Netto per ronde: van ~4-5 volledige suite-runs naar 3.
- [ ] **Vervolg (kandidaat-story): volledig bewijs-hergebruik dev→tester** — de tester-harness
  kan de run overslaan als developer-bewijs bij exact dezelfde inhoud hoort. Vereist een
  tree-identiteit die `docs/stories` (worklog-commits van tussenrollen) uitsluit — een bewuste
  aanpassing van de SF-927-poortsemantiek, dus apart oppakken.

### 5. Prompts en tips: klein en richtinggevend ✅ *(afgerond 2026-07-17)*

- [x] **Audit van `task.md` per rol**: bleek al gedisciplineerd — kop, parent-story +
  subtakenlijst, description en uitsluitend nieuwe (ongeprocessede) comments per rol; geen
  volledige historie. Geen wijziging nodig.
- [x] **Agent-tips cap**: dedupe bestond al (upsert per key), maar álle tips gingen elke
  prompt in (66+ voor de factory-developer). Nu gaan alleen de 30 recentste mee, met een
  voetnoot hoeveel er zijn weggelaten; de rest blijft in de database.

### 6. PNF-specifieke valkuilen ✅ *(afgerond 2026-07-17)*

- [x] **Preview-image-gap**: bleek al gefixt in PNF's `build-images.yml` — de PR-trigger heeft
  bewust géén paths-filter ("elke PR moet een image met z'n SHA krijgen"), dus ook docs-only
  PR's krijgen een preview-image. Klein restpunt genoteerd: bij een docs-only *merge naar main*
  bouwt de push-trigger (mét paths-filter) geen image; als de openshift-watch-deploy-check dan
  een nieuwe rollout verwacht, timet die uit — controleren zodra zo'n story zich voordoet.
- [x] **`clean verify` voor PNF**: stond al in PNF's `.factory/verification.yaml`.
- [x] **pubspec.lock-drift**: lockfile-discipline toegevoegd aan PNF's
  `docs/factory/agents/developer.md` en `tester.md` (drift vóór handover terugzetten of
  expliciet verantwoorden). PNF-commit `f3ad6b6`.

---

## Werkwijze

Per punt: fixen (mag als factory-story — goede testcase van de pipeline zelf), afvinken in dit
document, en bij structurele lessen ook de agent-prompts/`.factory`-docs bijwerken. Voor punt 1
staat de diagnose ook los beschreven zodat een verse sessie er direct mee aan de slag kan.
