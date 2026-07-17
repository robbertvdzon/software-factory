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

### 1. CI op main groen maken ⛔ *(blokkeert nu elke automatische factory-merge)*

De verplichte check **"Repository verification"** faalt op elke main-commit (minstens sinds
2026-07-17 09:19). Vier rode componenten, oorzaken al gediagnosticeerd:

- [ ] **Documentation audit**: `tools/audit-documentation` gebruikt `rg`, maar de
  ubuntu-runner heeft geen ripgrep (`rg: command not found`) en meldt dan onterecht
  "Ontbrekende documentatiefact". Fix: ripgrep installeren in de workflow-job óf het script
  op grep laten terugvallen.
- [ ] **Frontend verification**: de gepinde Flutter-versie in de workflow is te oud voor de
  Dart `^3.9`-eis van `softwarefactory_dashboard` (CI adviseert zelf 3.44.6). Fix: pin bumpen.
- [ ] **Kotlin quality ratchet**: structurele baseline wijkt af (`renamed: 1, ok: false`) —
  een hernoeming zonder baseline-update. Fix: baseline bewust bijwerken (beleidskeuze).
- [ ] **Backend verification**: faalde op main maar slaagde op een branch met dezelfde suite —
  vermoedelijk de bekende dispatch-tel-flakiness. Eerst herdraaien; alleen fixen bij repro.
- [ ] **Bewaking**: "main is groen" als invariant — dagelijkse check of Telegram-alarm zodra
  main-CI rood wordt, zodat dit nooit meer pas bij een geblokkeerde merge opvalt.

### 2. Afkeur-loops dichtzetten (flake-beleid + schone staat)

- [ ] **Kill-verbod op verify-commando's** in de agent-prompts: de developer killde zijn eigen
  `mvn verify` met `timeout 300` — dat corrumpeerde `jacoco-it.exec` en liet de tester twee
  rondes later afkeuren terwijl 61/61 tests groen waren. Verify draait altijd tot het einde.
- [ ] **Schone staat tussen rollen**: `clean verify` in `.factory/verification.yaml` (of de
  factory ruimt `target/` bij rolwissel), zodat corrupt/verouderd buildresidu in het gedeelde
  story-workspace nooit een volgende ronde kan vergiftigen.
- [ ] **Flake-protocol in de tester-prompt**: bij een rode test die níét in de story-diff zit
  eerst die test in isolatie herdraaien voordat je afkeurt; één volledige verify-run is bewijs
  genoeg (de tester draaide 'm nu 2× "ter bevestiging" — dubbel zo duur).
- [ ] **Bekende e2e-flakes fixen**: de dispatch-tel-assertions in de softwarefactory-e2e-suite
  flaken incidenteel; met de absolute testerpoort is elke flake extreem duur.
- [ ] **Test-chain-cap krijgt een uitweg**: na 3 test-resets is de enige route nu handwerk
  (paused/re-implement/DB-update). Maak een "reset test-keten"-actie in het dashboard, of laat
  de cap per verse story-run tellen.

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
