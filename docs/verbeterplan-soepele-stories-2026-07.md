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
- [x] **Bewaking**: `tools/check-main-ci-green` + LaunchAgent `nl.vdzon.factory-main-ci-check`
  (dagelijks 09:00) stuurt een Telegram-alarm zodra "Repository verification" op main niet
  groen is.

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

### 3. Runs sneller maken (zelfde grondigheid, minder wachten)

- [ ] **Persistente build-caches per repo**: elke agent-run downloadt nu alle Maven/pub/Flutter-
  dependencies opnieuw (cache leeft in de wegwerp-container; alleen `/work` is gemount). Mount
  een per-repo cache-volume voor `.m2`/pub-cache — scheelt naar schatting 2-5 min per run,
  elke run.
- [ ] **Tijdsbesteding zichtbaar maken**: dashboard-staatje "waar ging de tijd heen per story"
  op basis van de al aanwezige `duration_ms`/`cost_usd_est` per agent-run, zodat uitschieters
  (te lange of te uitgebreide runs) meetbaar worden i.p.v. een gevoel.

### 4. Dubbel werk eruit: revisiongebonden testbewijs door de hele keten

Vandaag draaide dezelfde testsuite tot 4× per ronde: developer verify → reviewer hertest →
tester 2× verify. Het mechanisme om dat te stoppen bestaat al (SF-927: revisiongebonden
testbewijs voor de testerpoort) — trek het door:

- [ ] **Developer levert verplicht groen bewijs op zijn HEAD-sha af** vóór handover (dan kan
  "mvn verify werkt niet" nooit meer als verrassing bij de tester landen — jouw punt 4).
- [ ] **Reviewer hertest niet standaard**: reviewt code en doet alleen gerichte spot-checks;
  vertrouwt het bewijs zolang de sha klopt.
- [ ] **Tester accepteert geldig bewijs** (sha ongewijzigd) en besteedt zijn tijd aan wat
  alleen hij doet: preview-deploy en E2E-scenario's.

### 5. Prompts en tips: klein en richtinggevend

- [ ] **Audit van `task.md` per rol**: alleen taak, acceptatiecriteria, het exacte
  verify-commando en de relevante richtlijnen — geen volledige story-historie.
- [ ] **Agent-tips cap + dedupe**: tips stapelen per repo op (elke run kan er bij schrijven);
  zonder maximum en periodieke opschoning wordt het ruis die elke prompt vergroot.

### 6. PNF-specifieke valkuilen

- [ ] **Preview-image-gap**: een docs/deploy-only PR bouwt geen image → preview pint een
  niet-bestaande sha → 503 → tester-setup strandt. Detecteer dit en sla de preview-stap
  gemotiveerd over (of bouw altijd een image).
- [ ] **pubspec.lock-drift**: kale `pubspec.lock`-wijzigingen (bijproduct van `flutter pub
  get`) kostten een reviewronde. Prompt-regel "commit nooit een pubspec.lock-wijziging zonder
  pubspec.yaml-reden", of laat de sync die automatisch terugdraaien.

---

## Werkwijze

Per punt: fixen (mag als factory-story — goede testcase van de pipeline zelf), afvinken in dit
document, en bij structurele lessen ook de agent-prompts/`.factory`-docs bijwerken. Voor punt 1
staat de diagnose ook los beschreven zodat een verse sessie er direct mee aan de slag kan.
