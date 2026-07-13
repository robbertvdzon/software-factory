# Voortgang autonoom verbetertraject

**Trajectstatus:** `AFGEROND`<br>
**Uitvoering verbeterpunten:** `AFGEROND VOOR PLANNEN 01–09`<br>
**Afgeronde plannen:** 9 / 9<br>
**Afgeronde werkpakketten:** 25 / 25<br>
**Laatste update:** 13 juli 2026 — plan 04 / REL-01 alsnog volledig uitgevoerd; traject compleet

Dit bestand is de duurzame voortgangsbron voor gemerged werk. Tijdens een actieve story zijn de
Factory-story en gepushte PR de realtime bron; werk dit bestand bij iedere overdracht mee bij.

Voor plannen 03–09 geldt de expliciete [versnelde gebruikersoverride](UITVOERREGELS.md#gebruikersoverride-versnelde-uitvoering-voor-plannen-0309):
één branch/PR per plan, één lokale commit en gerichte testset per story, één volledige lokale
eindgate en één push/merge per plan, zonder GitHub-buildmonitoring of evidence-PR's.

## Planoverzicht

| Plan | Niveau | Status | Prerequisites | Actieve/afgeronde stories | Bewijs |
| --- | --- | --- | --- | --- | --- |
| [01](01-merge-en-testinvariant-high.md) | Sol High | `AFGEROND` | plandocumentatie gemerged | `SF-926`, `SF-927` | FIX-01 `d4f3280`; VER-01 gemerged `223a6d2` |
| [02](02-directe-reparaties-medium.md) | Sol Medium | `AFGEROND` | plandocumentatie gemerged | `SF-928`, `SF-929`, `SF-930`, `SF-931`, `SF-939`, `SF-940`; blocker `SF-941` | eind-main `a3ee8c0`; lokaal 666; quality 353; Flutter 14; eindrun `29171926490` groen |
| [03](03-ci-documentatie-en-moduleborging-high.md) | Sol High | `AFGEROND` | plan 01 en 02 | `SF-962`, `SF-963`, `SF-964` | lokale fasegate groen op `5455663` |
| [04](04-duurzame-agent-completion-ultra.md) | Sol Ultra | `AFGEROND` | plan 03 | `SF-986` | duurzame Postgres-inbox, 12 hervatbare stappen en failure-injectionmatrix; lokale fasegate groen |
| [05](05-application-en-domeinrefactors-high.md) | Sol High | `AFGEROND` | plan 04 was initieel uitgesteld en is na plan 09 alsnog afgerond | `SF-966`, `SF-967`, `SF-968`, `SF-969` | lokale fasegate groen op `4123859`; PR #121 |
| [06](06-platform-ai-en-frontendrefactors-high.md) | Sol High | `AFGEROND` | plan 05 | `SF-970`, `SF-971`, `SF-972`, `SF-973` | lokale fasegate groen op `e88bdb0` |
| [07](07-modulemigraties-medium.md) | Sol Medium per mechanische module; gebruikersoverride voor holdpoints | `AFGEROND` | plan 06 | `SF-974` t/m `SF-982` | negen storycommits; lokale fasegate groen op `58ca7da` |
| [08](08-architectuur-en-kwaliteitsgates-high.md) | Sol High | `AFGEROND` | plan 07 | `SF-983`, `SF-984` | repositoryratchet en expliciete matrix/diagram; lokale fasegate groen |
| [09](09-cleanup-en-eindverificatie-light.md) | Sol Light | `AFGEROND` | plan 08 | `SF-985` | mechanische cleanup; gerichte gates en volledige lokale repositorygate groen |

## Werkpakketten

| Werkpakket | Plan | Status | Story | Branch / PR | Laatste groene bewijs |
| --- | --- | --- | --- | --- | --- |
| FIX-01 | 01 | `AFGEROND` | `SF-926` | `codex/SF-926-fix-01-merge-gate` / [PR #75](https://github.com/robbertvdzon/software-factory/pull/75) | merge `d4f3280`: lokaal 637 tests; GitHub `29154308271` groen |
| VER-01 | 01 | `AFGEROND` | `SF-927` | `codex/SF-927-ver-01-tester-evidence` / [PR #76](https://github.com/robbertvdzon/software-factory/pull/76) | gemerged `223a6d2`; post-merge baseline volledig groen |
| FIX-02 | 02 | `AFGEROND` | `SF-928` | implementatie/reparaties PR #77/#78/#80/#83/#86/#89/#92 | eind-main `a5b6b76`; backend `29164368822`, frontend `29164368852`, manifest-PR's #93/#94 groen |
| FIX-03 | 02 | `AFGEROND` | `SF-929` | `codex/SF-929-fix-03-docker-mini-reactor` / [PR #96](https://github.com/robbertvdzon/software-factory/pull/96) | merge `a81f7d3`; CI agent-buildstage en repository groen; backend image/bump-PR #97 groen |
| FIX-04 | 02 | `AFGEROND` | `SF-930` | `codex/SF-930-fix-04-local-quickstart` / [PR #99](https://github.com/robbertvdzon/software-factory/pull/99) | merge `b69bd9b`; post-merge smoke 200/401/200 connected en run `29166935313` groen |
| FIX-05 | 02 | `AFGEROND` | `SF-931` | `codex/SF-931-fix-05-typed-force-refresh` / [PR #101](https://github.com/robbertvdzon/software-factory/pull/101) | merge `2bde8b3`; lokaal 662 tests; GitHub `29168546402` groen |
| FIX-06 | 02 | `AFGEROND` | `SF-939` | `codex/SF-939-fix-06-typed-tracker-not-found` / [PR #104](https://github.com/robbertvdzon/software-factory/pull/104) | merge `77a8c5a`; lokaal 663 tests; GitHub `29169615518` groen |
| OPS-01 | 02 | `AFGEROND` | `SF-940` | [PR #106](https://github.com/robbertvdzon/software-factory/pull/106) + qualityfix [#107](https://github.com/robbertvdzon/software-factory/pull/107) | merges `716907a`/`f5c4791`; lokaal 666; quality 353; GitHub groen |
| BLK-02 | 02 | `AFGEROND` | `SF-941` | `codex/SF-941-telegram-poller-concurrency-test` / [PR #110](https://github.com/robbertvdzon/software-factory/pull/110) | merge `a3ee8c0`; test 10× groen; eindrun `29171926490` groen |
| VER-02 | 03 | `AFGEROND` | `SF-962` | `codex/phase-03-ci-doc-module` / fase-PR | gerichte Flutter-, Docker-, YAML- en scriptgates groen; volledige fasegate groen |
| DOC-01 | 03 | `AFGEROND` | `SF-964` | `codex/phase-03-ci-doc-module` / fase-PR | `tools/audit-documentation` groen, opgenomen in Repository verification |
| MOD-01 | 03 | `AFGEROND` | `SF-963` | `codex/phase-03-ci-doc-module` / fase-PR | Modulith- en module-API-conventietests groen |
| REL-01 | 04 | `AFGEROND` | `SF-986` | `codex/phase-04-durable-completion` / fase-PR | V16-inbox en stabiele step-ledger; gerichte recovery-E2E 5 tests groen; volledige lokale repositorygate groen |
| ARC-01 | 05 | `AFGEROND` | `SF-966` | `codex/phase-05-application-domain` / [PR #121](https://github.com/robbertvdzon/software-factory/pull/121) | dashboardapplication uit web; gerichte tests groen; commit `20f1739`/`d35d140` |
| ARC-02 | 05 | `AFGEROND` | `SF-967` | `codex/phase-05-application-domain` / [PR #121](https://github.com/robbertvdzon/software-factory/pull/121) | commands en queries gescheiden; gerichte tests groen; commit `6f92edb` |
| ARC-03 | 05 | `AFGEROND` | `SF-968` | `codex/phase-05-application-domain` / [PR #121](https://github.com/robbertvdzon/software-factory/pull/121) | getypeerde command-/subtaskregistratie; gerichte tests groen; commit `a65acd8` |
| ARC-04 | 05 | `AFGEROND` | `SF-969` | `codex/phase-05-application-domain` / [PR #121](https://github.com/robbertvdzon/software-factory/pull/121) | tracker-capabilities en getypeerde runupdates; fasegate groen; commit `4123859` |
| ARC-05 | 06 | `AFGEROND` | `SF-970` | `codex/phase-06-platform-ai-frontend` / fase-PR | supplier-neutrale prompt/outcome-, proces- en taakbestanddelen; 45 agentworkertests groen; `c720da8` |
| ARC-06 | 06 | `AFGEROND` | `SF-971` | `codex/phase-06-platform-ai-frontend` / fase-PR | licht `factory-contracts`-artifact, parent/reactor en beide Docker-buildstages groen; `2170224` |
| ARC-07 | 06 | `AFGEROND` | `SF-972` | `codex/phase-06-platform-ai-frontend` / fase-PR | smalle projectsettings, gecentraliseerde resolved config en fail-closed composition-rootregister; `82b853f` |
| UI-01 | 06 | `AFGEROND` | `SF-973` | `codex/phase-06-platform-ai-frontend` / fase-PR | zes featurescreens gesplitst, getypeerde projectmodellen; Flutter analyze/17 tests/web/Docker groen; `e88bdb0` |
| MOD-02 | 07 | `AFGEROND` | `SF-974` | `codex/phase-07-module-migrations` / fase-PR | Telegram-capabilitygrens en gerichte tests groen; `b461d30` |
| MOD-03 | 07 | `AFGEROND` | `SF-975`–`SF-982` | `codex/phase-07-module-migrations` / fase-PR | acht modulemigraties; lege rootallowlist; Maven 625, quality 480 en repositorygate groen op `58ca7da` |
| QLT-01 | 08 | `AFGEROND` | `SF-983` | `codex/phase-08-architecture-quality` / fase-PR | vijf Kotlinmodules; 637 structurele findings, 1 suppressie; comparatorfixtures groen; `ce320bd` |
| ARC-08 | 08 | `AFGEROND` | `SF-984` | `codex/phase-08-architecture-quality` / fase-PR | 20 expliciete moduleallowlists; gegenereerde matrix/diagram en negatieve fixtures groen; `4fb8eab` |
| CLN-01 | 09 | `AFGEROND` | `SF-985` | `codex/phase-09-cleanup-final` / fase-PR | twee 100%-renames, ongebruikte parameters weg, worklog en gerichte regressies groen; `2b31eb4` |

## Verplichte vaste storyoverdracht

Maak voor **iedere** Factory-story onder `Storyoverdrachten` een kopie van onderstaande twee
tabellen. Een losse link of vrije logregel vervangt geen veld. Gebruik `n.v.t.` met reden wanneer
een veld aantoonbaar niet van toepassing is; laat niets leeg. Een story wordt niet `AFGEROND`
zonder volledige overdracht.

### Storymetadata-template

| Veld | Verplichte inhoud |
| --- | --- |
| Werkpakket / story / titel | Code, Factory-key en exacte titel |
| Status / eigenaar | `NIET GESTART`, `BEZIG`, `GEBLOKKEERD` of `AFGEROND`; actieve eigenaar |
| Uitvoertaken / model / effort | Codex-taak-id of link, gekozen model/effort en eventuele afzonderlijke design-/implementatietaken |
| Baseline | Default branch, baseline-SHA, datum/tijd, schone-worktreestatus en baseline-artifact |
| Branch / PR | Branch, PR-link/nummer en actuele PR-head-SHA |
| Designholdpoint | Matrix-/besluitlink, gepushte design-SHA, reviewer en approvaltijd; `n.v.t.` met reden wanneer niet vereist |
| Uiteindelijke story-SHA | Exacte door developer overgedragen en door reviewer/tester beoordeelde SHA |
| Merge / post-merge | Mergecommit, gemergede default-branch-SHA en post-merge-runlink |
| Artifacts | CI-runs, rapporten, images/digests, screenshots, diagrammen en workloglinks |
| Architectuur-/contractbesluiten | Besluit, motivatie, geraakte publieke contracten en beslisbron; geen chat-only besluit |
| Grensstaat | `MOD-01`-migratieallowlisttelling/delta; ARC-07-boundaryregister versie/telling/delta; suppressiebaseline versie/telling/delta |
| Open items / blokkades | Exact probleem, eigenaar en eerstvolgende actie; voor afronding verplicht `geen` |
| Volgende startgate | Eerstvolgend plan/story, vereiste SHA en concrete prerequisitecontrole |

### Gate- en approvaltemplate

| Rol | Exacte SHA | Command / gate | Datum/tijd | Exit / tellingen | Artifact / akkoord |
| --- | --- | --- | --- | --- | --- |
| Developer | — | gerichte tests | — | — | — |
| Developer | — | volledige plan-/repositorygate | — | — | — |
| Reviewer | — | risicogerichte herhaling + volledige gate | — | — | expliciet oordeel |
| Tester | — | onafhankelijke gerichte tests + volledige gate | — | — | expliciet oordeel |
| Post-merge | — | verplichte checks op default branch | — | — | runlink |

Na iedere reviewfix worden de oude regels als historisch gemarkeerd en nieuwe regels voor de nieuwe
SHA toegevoegd; overschrijf geen bewijs alsof het op de nieuwe commit draaide.

## Storyoverdrachten

### FIX-01 / SF-926 — Projectbewuste groene merge-gate zonder bypass of pending-error

| Veld | Verplichte inhoud |
| --- | --- |
| Werkpakket / story / titel | FIX-01 / `SF-926` / Projectbewuste groene merge-gate zonder bypass of pending-error |
| Status / eigenaar | `AFGEROND`; Codex-taak van Robbert van der Zon |
| Uitvoertaken / model / effort | Huidige Codex-taak; GPT-5 / High volgens plan 01; implementatie, review en test worden afzonderlijk vastgelegd op de uiteindelijke SHA |
| Baseline | `main` op `67290c1`, 11 juli 2026 14:44–14:47 CEST, schone worktree; `mvn verify` exit 0 en `qualityrun/2026-07-11T14-46-50/quality-score.json` |
| Branch / PR | `codex/SF-926-fix-01-merge-gate`; [PR #75](https://github.com/robbertvdzon/software-factory/pull/75); inhoudelijke PR-head `18ca4a64b8a0b9ed99659bfd0d0777de964589ad` |
| Designholdpoint | n.v.t.; FIX-01 schrijft geen afzonderlijk designholdpoint voor |
| Uiteindelijke story-SHA | inhoudelijke developer-/reviewer-/tester-SHA `18ca4a64b8a0b9ed99659bfd0d0777de964589ad`; evidence-only PR-head `de1a441e516b1f634151996af3dfefeff3a524c5` |
| Merge / post-merge | squashmerge/default-branch-SHA `d4f32804a649c1032546480d00551bf5631818c7`; lokale `mvn verify` exit 0; [GitHub-run 29154308271](https://github.com/robbertvdzon/software-factory/actions/runs/29154308271) groen |
| Artifacts | baseline GitHub-run `29153099411`; developer/reviewer/tester Mavenrapporten: telkens 637 tests groen; quality `qualityrun/2026-07-11T15-02-49/` score 353; PR-runs `29153868120`/`29154184377` groen; post-merge `29154308271` groen |
| Architectuur-/contractbesluiten | Publieke `merge.PullRequestMergeService` met interne enige GitHub-mergecaller; projectpolicy `merge.requiredChecks`; check-runs op exact `headRefOid`; atomische `--match-head-commit`; pending handmatig commando blijft ongeprocessed voor retry |
| Grensstaat | MOD-01-allowlist nog niet aangemaakt; ARC-07-register nog niet aangemaakt; productiesuppressies 1→1 bij baseline |
| Open items / blokkades | geen |
| Volgende startgate | VER-01 gestart vanaf gemergede, lokaal en op protected check groene `d4f3280` |

| Rol | Exacte SHA | Command / gate | Datum/tijd | Exit / tellingen | Artifact / akkoord |
| --- | --- | --- | --- | --- | --- |
| Developer | `67290c1` (baseline) | `mvn verify` | 11 juli 2026 14:44–14:46 CEST | exit 0; 621 tests, 0 failures, 0 errors, 0 skipped | lokale Mavenrapporten |
| Developer | `67290c1` (baseline) | `./quality/run.sh` | 11 juli 2026 14:46 CEST | exit 0; score 354, 353 findings, 1 suppressie | `qualityrun/2026-07-11T14-46-50/` |
| Developer | storycandidate vóór commit | gerichte unit- en e2e-gates | 11 juli 2026 14:57–15:02 CEST | exit 0; 49 gerichte unit-tests + 2 `MergePolicyE2eTest`-tests, 0 failures/errors/skips | Surefire-/Failsafe-rapporten; alle readinessvarianten en beide head-races |
| Developer | storycandidate vóór commit | `mvn verify` | 11 juli 2026 15:05–15:07 CEST | exit 0; 87 rapporten, 637 tests, 0 failures, 0 errors, 0 skipped | lokale Mavenrapporten; totale duur 2:39 min |
| Developer | storycandidate vóór commit | `./quality/run.sh` | 11 juli 2026 15:02 CEST | exit 0; score 353, 352 findings, 1 suppressie; delta -1 | `qualityrun/2026-07-11T15-02-49/` |
| Reviewer | `18ca4a64b8a0b9ed99659bfd0d0777de964589ad` | callsite-/scope-/configreview; 49 gerichte tests + `MergePolicyE2eTest`; `mvn verify` | 11 juli 2026 15:11–15:16 CEST | exit 0; gericht 49 + 2 tests; volledig 87 rapporten/637 tests, 0 failures/errors/skips | akkoord zonder bevindingen; geen bypass/directe mergecaller buiten centrale service |
| Tester | `18ca4a64b8a0b9ed99659bfd0d0777de964589ad` | negatieve readinessmatrix + beide headraces; `mvn verify` | 11 juli 2026 15:16–15:20 CEST | exit 0; gericht 47 + 2 tests; volledig 87 rapporten/637 tests, 0 failures/errors/skips | akkoord; ready/pending/missing/skipped/cancelled/failed/API-fout voor beide entrypoints groen |
| Post-merge | `67290c1` (prerequisite) | Repository verification | 11 juli 2026 14:43–14:46 CEST | exit 0; `Backend verification` groen | GitHub-run `29153099411` |
| Post-merge | `d4f32804a649c1032546480d00551bf5631818c7` | `mvn verify`; Repository verification | 11 juli 2026 15:26–15:30 CEST | exit 0; lokaal 637 tests; GitHub `Backend verification` groen in 3m53s | GitHub-run `29154308271`; dashboardimage bouw groen, bekende manifestpush-fout is FIX-02 en geen testfailure |

### VER-01 / SF-927 — Testergoedkeuring vereist groen revisiongebonden testbewijs

| Veld | Verplichte inhoud |
| --- | --- |
| Werkpakket / story / titel | VER-01 / `SF-927` / Testergoedkeuring vereist groen revisiongebonden testbewijs |
| Status / eigenaar | `BEZIG`; Codex-taak van Robbert van der Zon |
| Uitvoertaken / model / effort | Huidige Codex-taak; GPT-5 / High volgens plan 01; afzonderlijke developer-, reviewer- en testergates uitgevoerd op uiteindelijke inhoud-SHA |
| Baseline | `main` op `d4f3280`, 11 juli 2026 15:26–15:30 CEST, schone worktree; lokale `mvn verify` 637 tests en GitHub-run `29154308271` groen |
| Branch / PR | `codex/SF-927-ver-01-tester-evidence`; [Software Factory PR #76](https://github.com/robbertvdzon/software-factory/pull/76); rollout-PR's personal-feed [#176](https://github.com/robbertvdzon/personal-news-feed-by-claude-code/pull/176) en robberts-assistent [#2](https://github.com/robbertvdzon/robberts-assistent/pull/2) gemerged |
| Designholdpoint | n.v.t.; VER-01 schrijft geen afzonderlijk designholdpoint voor; contract/rolloutbesluiten staan in worklog en rolloutmatrix |
| Uiteindelijke story-SHA | inhoudelijke developer-/reviewer-/tester-SHA `b14ebea0413469a78cdf6fcdcbdef2a0a9fc6e88`; evidence-only PR-head volgt |
| Merge / post-merge | eerste merge/default-branch-SHA `9f382a5`; repositorycheck loopt; echte backendrun `29160847558` bouwde image groen maar bump faalde fail-fast doordat `GH_TOKEN` niet als CLI-env stond; reparatiebranch actief |
| Artifacts | `VER-01-rolloutmatrix.md`; configs gemerged als `c0ff52c` en `c097db1`; productieparser op alle drie checkouts groen; reviewer 9+45+18+1 en tester 12+18+1 gericht groen; volledige suite 658 groen; quality 353; GitHub-run `29155791691` groen |
| Architectuur-/contractbesluiten | `.factory/verification.yaml` schema 1; argv zonder shell; additive `verificationEvidence`; agentworker meet, factory valideert onafhankelijk tegen actieve HEAD/worktree-tree; ongeldig `tested` wordt `test-rejected` |
| Grensstaat | MOD-01-allowlist nog niet aangemaakt; ARC-07-register nog niet aangemaakt; productiesuppressies blijven 1 vóór eindgate |
| Open items / blokkades | geen; evidence-eindgate, merge en post-mergegates volgen |
| Volgende startgate | plan 01 afronden na gemergede SF-927, alle drie configbaselines opnieuw valide en post-merge `mvn verify`/GitHub groen; plan 03 wacht daarnaast op plan 02 |

| Rol | Exacte SHA | Command / gate | Datum/tijd | Exit / tellingen | Artifact / akkoord |
| --- | --- | --- | --- | --- | --- |
| Developer | `d4f3280` (baseline) | `mvn verify`; GitHub Repository verification | 11 juli 2026 15:26–15:30 CEST | exit 0; 637 tests; protected check groen | lokaal rapport; run `29154308271` |
| Developer | worktree vóór commit | contract/parser, agentworker, validator en exacte e2e | 11 juli 2026 15:39–15:45 CEST | exit 0 na één gecorrigeerde testverwachting; 7 + 9 + 4 + 1 tests groen | Surefire/Failsafe-rapporten; eerste e2e-rood niet genegeerd en hersteld |
| Developer | storycandidate vóór commit | verplichte vier gerichte gates; `./quality/run.sh`; `mvn verify` | 11 juli 2026 15:52–16:10 CEST | exit 0; gericht 4 + 45 + 18 + 1; volledig vóór laatste parsertest 93 rapporten/656 tests, 0 failures/errors/skips; quality 353/352/1 | eerste volledige rood door te strenge clean-tree-eis volledig hersteld en herhaald; parser/identity na laatste hardening 9 groen |
| Reviewer (historisch) | `dacd6af` | contractcompatibiliteit, injectie/timeout/revision/bounded logs | 11 juli 2026 16:11–16:15 CEST | bevindingen: childprocess-timeout, reader-fail-closed en duration-/lengtevalidatie | volledig opgelost in `b14ebea`; geen approval op oude SHA |
| Reviewer | `b14ebea0413469a78cdf6fcdcbdef2a0a9fc6e88` | common 9; agentworker 45; AgentRunCompletion 18; exacte e2e 1; `./quality/run.sh`; `mvn verify` | 11 juli 2026 16:17–16:21 CEST | exit 0; volledig 93 rapporten/658 tests, 0 failures/errors/skips; quality 353/352/1 | expliciet akkoord zonder resterende bevindingen; `qualityrun/2026-07-11T16-17-53/` |
| Tester | `b14ebea0413469a78cdf6fcdcbdef2a0a9fc6e88` | runner/CLI 12; AgentRunCompletion 18; exacte e2e 1; `mvn verify` | 11 juli 2026 16:21–16:26 CEST | exit 0; volledig 93 rapporten/658 tests, 0 failures/errors/skips | expliciet akkoord; negatieve payload-/timeout-/revisionmatrix en volledige keten groen |
| Post-merge | — | configvalidatie alle default branches; `mvn verify`; GitHubchecks | — | volgt | — |

### FIX-02 / SF-928 — Releasebot werkt via PR onder branch protection

| Veld | Verplichte inhoud |
| --- | --- |
| Werkpakket / story / titel | FIX-02 / `SF-928` / Releasebot werkt via PR onder branch protection |
| Status / eigenaar | `AFGEROND`; huidige Codex-taak van Robbert van der Zon |
| Uitvoertaken / model / effort | Huidige Codex-taak; GPT-5 / Medium volgens plan 02; developer-eindgate groen, review/test volgen op inhoud-SHA |
| Baseline | `main` op `223a6d2`, 11 juli 2026 18:35–18:38 CEST, schone worktree; `mvn verify` exit 0, 658 tests groen |
| Branch / PR | initiële branch `codex/SF-928-fix-02-releasebot` / [PR #77](https://github.com/robbertvdzon/software-factory/pull/77); reparatiebranch `codex/SF-928-fix-02-gh-token` / [PR #78](https://github.com/robbertvdzon/software-factory/pull/78); reparatie-inhoud-SHA `0de40b2` |
| Designholdpoint | n.v.t.; FIX-02 schrijft geen afzonderlijk designholdpoint voor |
| Uiteindelijke story-SHA | initiële inhoud-SHA `b433adcc0009e8086943953e6a2c41ab88e483d9`; reparatie-inhoud-SHA `0de40b2` door developer/tester volledig groen, reviewer-CI volgt |
| Merge / post-merge | implementatie/reparaties gemerged via #77/#78/#80/#83/#86/#89/#92; definitieve bot-PR's #93/#94 gemerged; default-branch-SHA `a5b6b7699c613de546b585c4e63f762f2c320db9`; post-merge repositoryruns groen |
| Artifacts | finale backendrun `29164368822` en frontendrun `29164368852` volledig groen; bot-PR's #93/#94; beide tags `sha-68e1ad0`; oudere component-PR's aantoonbaar gesloten/superseded; lokale volledige gates 658 groen |
| Architectuur-/contractbesluiten | Component- en rungebonden botbranches/PR's; versioned `run_id`/`source_sha` per component als monotone arbiter; expliciete `verify.yml`-dispatch omdat PR's van `GITHUB_TOKEN` geen recursieve workflow-event starten; vervolgmerge wacht op required checks en gebruikt de exacte head-SHA zonder bypass |
| Grensstaat | MOD-01-allowlist nog niet aangemaakt; ARC-07-register nog niet aangemaakt; productiesuppressies blijven 1 |
| Open items / blokkades | geen |
| Volgende startgate | FIX-03 starten vanaf gemergede `a5b6b76` nadat evidence-only PR en post-mergecheck groen zijn |

| Rol | Exacte SHA | Command / gate | Datum/tijd | Exit / tellingen | Artifact / akkoord |
| --- | --- | --- | --- | --- | --- |
| Developer | `223a6d2` (baseline) | `mvn verify` | 11 juli 2026 18:35–18:38 CEST | exit 0; 93 rapporten/658 tests, 0 failures/errors/skips | lokale Mavenrapporten |
| Developer | storycandidate vóór commit | `bash -n` beide scripts; bare-repo/fake-gh race-integratie; directe-main-pushscan | 11 juli 2026 18:42 CEST | exit 0; A oud → B nieuw → A hervat behoudt B en sluit A; 0 directe main-pushes | terminalbewijs |
| Developer | storycandidate vóór commit | `mvn verify` | 11 juli 2026 18:42–18:45 CEST | exit 0; 93 rapporten/658 tests, 0 failures/errors/skips | lokale Mavenrapporten |
| Reviewer (historisch) | `0376533f174376e59314a43d2da78fa69c822e70` | repositoryconfig, race-integratie en `mvn verify` | 11 juli 2026 18:47–18:49 CEST | finding: auto-merge repositorybreed uitgeschakeld; volledige suite 658 groen | geen approval; finding hersteld met expliciete required-check-wacht/merge |
| Reviewer | `b433adcc0009e8086943953e6a2c41ab88e483d9` | script-/workflowdiff; bare-reporace; `mvn verify` | 11 juli 2026 18:50–18:53 CEST | exit 0; race groen; volledig 93 rapporten/658 tests, 0 failures/errors/skips | expliciet akkoord; geen directe main-push/bypass en disabled auto-merge correct afgevangen |
| Tester | `b433adcc0009e8086943953e6a2c41ab88e483d9` | onafhankelijke syntax-/bare-reporace; `mvn verify` | 11 juli 2026 18:53–18:56 CEST | exit 0; A oud → B nieuw → A sluit en B blijft; volledig 93 rapporten/658 tests, 0 failures/errors/skips | expliciet akkoord |
| Developer (reparatie) | `0de40b2` | GH_TOKEN-regressie; `OrchestratorGateE2eTest` 2×; `mvn verify` | 11 juli 2026 19:03–19:13 CEST | eerste volledige gate rood op setup-race; daarna 2×3 gericht en 658 volledig groen | beide gevonden failures hersteld, niets genegeerd |
| Tester (reparatie) | `0de40b2` | bare-reporace; exacte `OrchestratorGateE2eTest`; `mvn verify` | 11 juli 2026 19:14–19:18 CEST | exit 0; race groen; 3 gericht; volledig 93 rapporten/658 tests groen | expliciet akkoord |
| Developer (visibilityreparatie) | `a92cde9` | exacte `no checks reported`-fake; `mvn verify` | 11 juli 2026 19:26–19:29 CEST | exit 0; fake-gh retry groen; volledig 93 rapporten/658 tests groen | lookup retryt alleen begrensde no-checks-responsen |
| Tester (visibilityreparatie) | `a92cde9` | onafhankelijke bare-reporace + `mvn verify` | 11 juli 2026 19:29–19:32 CEST | exit 0; race groen; volledig 93 rapporten/658 tests groen | expliciet akkoord |
| Post-merge | `a5b6b7699c613de546b585c4e63f762f2c320db9` | echte backend-/frontendworkflows, bot-PR's, manifests en required checks | 11 juli 2026 20:56–21:07 CEST | backend `29164368822`, frontend `29164368852`, #93/#94 en beide tags groen | expliciet akkoord; geen directe main-push, downgrade of rebuild-loop |

### FIX-03 / SF-929 — Dockerfile.agent bouwt reproduceerbaar uit root-context

| Veld | Verplichte inhoud |
| --- | --- |
| Werkpakket / story / titel | FIX-03 / `SF-929` / Dockerfile.agent bouwt reproduceerbaar uit root-context |
| Status / eigenaar | `AFGEROND`; huidige Codex-taak van Robbert van der Zon |
| Uitvoertaken / model / effort | Huidige Codex-taak; GPT-5 / Medium volgens plan 02 |
| Baseline | `main` op `cd53de0`, 11 juli 2026 21:11–21:14 CEST, schone worktree; `mvn verify` 658 tests en GitHub-run `29164875478` groen |
| Branch / PR | `codex/SF-929-fix-03-docker-mini-reactor`; [PR #96](https://github.com/robbertvdzon/software-factory/pull/96); inhoud-SHA `16552af` |
| Designholdpoint | n.v.t.; FIX-03 schrijft geen afzonderlijk designholdpoint voor |
| Uiteindelijke story-SHA | inhoudelijke developer-/reviewer-/tester-SHA `16552af`; evidence-only head volgt |
| Merge / post-merge | inhoud/evidence squashmerge `a81f7d3b1ecf96cedfef4b7bad0f5bded978d30f`; backendmanifestmerge `b46c530`; post-merge repositoryrun en imageworkflow `29165702981` groen |
| Artifacts | schone agent- en dashboard-backend-buildstages groen; `agent:local` digest `af71af2`; `assistant:local` digest `29016a5`; mock AgentCli-smoke exit 0; Python-smoke groen; developer `mvn verify` 658 groen |
| Architectuur-/contractbesluiten | Eén portable `docker/prepare-mini-reactor.sh` valideert target en reduceert root-POM tot `factory-common` plus target; POM/dependencylaag vóór bronnen voor cache; beide Dockerfiles gebruiken exact dezelfde route |
| Grensstaat | MOD-01-allowlist nog niet aangemaakt; ARC-07-register nog niet aangemaakt; productiesuppressies blijven 1 |
| Open items / blokkades | geen |
| Volgende startgate | FIX-04 pas starten vanaf gemergede, lokaal en op GitHub groene FIX-03-SHA |

| Rol | Exacte SHA | Command / gate | Datum/tijd | Exit / tellingen | Artifact / akkoord |
| --- | --- | --- | --- | --- | --- |
| Developer | `cd53de0` | `mvn verify` | 11 juli 2026 21:11–21:14 CEST | exit 0; 93 rapporten/658 tests | lokale Mavenrapporten |
| Developer | storycandidate vóór commit | mini-reactortest; beide schone buildstages; `./factory build-images`; inspect; AgentCli/Python-smoke | 11 juli 2026 21:15–21:20 CEST | alles exit 0; beide images arm64 en startbaar | lokale Docker BuildKit-output en image-inspect |
| Developer | storycandidate vóór commit | `mvn verify` | 11 juli 2026 21:20–21:23 CEST | exit 0; 93 rapporten/658 tests | lokale Mavenrapporten |
| Reviewer | `16552af` | mini-reactortest; beide buildstages; volledige gate | 11 juli 2026 21:24–21:27 CEST | exit 0; beide buildstages cache/reproduceerbaar; 658 tests groen | expliciet akkoord; één gedeelde route en geen ontbrekende childmodules |
| Tester | `16552af` | `./factory build-images`; tester-mock AgentCli; assistant Python; `mvn verify` | 11 juli 2026 21:27–21:30 CEST | exit 0; beide images gebouwd/startbaar; 658 tests groen | expliciet akkoord |
| Post-merge | `a81f7d3b1ecf96cedfef4b7bad0f5bded978d30f` | CI agent-buildstage, backendimageworkflow/bump-PR en repositorycheck | 11 juli 2026 21:42–21:48 CEST | agent-buildstage groen; run `29165702981` groen; PR #97 gemerged | expliciet akkoord |

### FIX-04 / SF-930 — Werkende lokale Compose-, SSO- en bridge-quickstart

| Veld | Verplichte inhoud |
| --- | --- |
| Werkpakket / story / titel | FIX-04 / `SF-930` / Werkende lokale Compose-, SSO- en bridge-quickstart |
| Status / eigenaar | `AFGEROND`; huidige Codex-taak van Robbert van der Zon |
| Uitvoertaken / model / effort | Huidige Codex-taak; GPT-5 / Medium volgens plan 02 |
| Baseline | `main` op `3c4ad05`, 11 juli 2026 21:51–21:54 CEST, schone worktree; `mvn verify` 658 tests en post-merge CI groen |
| Branch / PR | `codex/SF-930-fix-04-local-quickstart`; [PR #99](https://github.com/robbertvdzon/software-factory/pull/99); inhoud-SHA `5c6ca82` |
| Designholdpoint | n.v.t.; FIX-04 schrijft geen afzonderlijk designholdpoint voor |
| Uiteindelijke story-SHA | inhoudelijke developer-/reviewer-/tester-SHA `5c6ca82`; evidence-head volgt |
| Merge / post-merge | squashmerge/default-branch-SHA `b69bd9bd1f4d06320b328eef2935ed77fc77d174`; post-merge smoke groen; GitHub-run `29166935313` groen |
| Artifacts | `docker compose config` groen zonder backend-DB/passwordconfig; geïsoleerde smoke bouwde frontend/backend, healthz 200, unauth 401, auth 200 `connected=true`, teardown; Flutter analyze/14 tests groen; Maven 658 groen |
| Architectuur-/contractbesluiten | `./factory local-services` gebruikt expliciet `secrets.env`, root-context en build; backend ontvangt uitsluitend login/sessie/bridgeconfig; smoke genereert secrets tijdelijk, bewaart bearer alleen in mode-600 curlconfig en gebruikt geïsoleerd Compose-project |
| Grensstaat | MOD-01-allowlist nog niet aangemaakt; ARC-07-register nog niet aangemaakt; productiesuppressies blijven 1 |
| Open items / blokkades | geen |
| Volgende startgate | FIX-05 pas starten vanaf gemergede, volledig groene FIX-04-SHA |

| Rol | Exacte SHA | Command / gate | Datum/tijd | Exit / tellingen | Artifact / akkoord |
| --- | --- | --- | --- | --- | --- |
| Developer | `3c4ad05` | `mvn verify` | 11 juli 2026 21:51–21:54 CEST | exit 0; 658 tests | lokale Mavenrapporten |
| Developer | storycandidate vóór commit | Composeconfig; geïsoleerde quickstart-smoke; `flutter analyze`; `flutter test` | 11 juli 2026 21:58–22:02 CEST | exit 0; 200/401/200 connected; analyze 0 issues; 14 Fluttertests | smoke-output en automatische teardown |
| Developer | storycandidate vóór commit | `mvn verify` | 11 juli 2026 22:02–22:05 CEST | exit 0; 658 tests | lokale Mavenrapporten |
| Reviewer | `5c6ca82` | docs/config/secretredactie; quickstart-smoke; Flutter analyze/test; `mvn verify` | 11 juli 2026 22:06–22:10 CEST | alles exit 0; 200/401/200 connected; Flutter 14; Maven 658 | expliciet akkoord |
| Tester | `5c6ca82` | onafhankelijke gepubliceerde smoke; Flutter analyze/test; `mvn verify` | 11 juli 2026 22:10–22:14 CEST | alles exit 0; teardown schoon; Flutter 14; Maven 658 | expliciet akkoord |
| Post-merge | `b69bd9bd1f4d06320b328eef2935ed77fc77d174` | geïsoleerde quickstart-smoke + repositorycheck | 11 juli 2026 22:18–22:22 CEST | healthz 200, unauth 401, auth 200 connected=true; run `29166935313` groen | expliciet akkoord |

### FIX-05 / SF-931 — Dashboard refresh omzeilt caches daadwerkelijk

| Veld | Verplichte inhoud |
| --- | --- |
| Werkpakket / story / titel | FIX-05 / `SF-931` / Dashboard refresh omzeilt caches daadwerkelijk |
| Status / eigenaar | `AFGEROND`; huidige Codex-taak van Robbert van der Zon |
| Uitvoertaken / model / effort | Huidige Codex-taak; GPT-5 / Medium volgens plan 02 |
| Baseline | `main` op `ecf0574`, 11 juli 2026 22:37–22:40 CEST, schone worktree; `mvn verify` groen; qualityscore 353 (352 findings + 1 suppressie) |
| Branch / PR | `codex/SF-931-fix-05-typed-force-refresh`; [PR #101](https://github.com/robbertvdzon/software-factory/pull/101) |
| Designholdpoint | n.v.t.; FIX-05 schrijft geen afzonderlijk designholdpoint voor |
| Uiteindelijke story-SHA | definitieve inhoudelijke developer-/reviewer-/tester-SHA `e2b2161`; evidence-head volgt |
| Merge / post-merge | squashmerge/default-branch-SHA `2bde8b3acdfd394f4493132e7cca16f713e9c484`; lokaal 662 tests; GitHub-run `29168546402` volledig groen |
| Artifacts | baseline `qualityrun/2026-07-11T22-40-05/quality-score.json`; nameting `qualityrun/2026-07-11T22-43-26/quality-score.json`; 3× missing/false/true-controllerframes; gedeelde forcefixture; lokale websocket-cache-smoke 1→1→2 broncalls |
| Architectuur-/contractbesluiten | `force` wordt op de wire een JSON-boolean; ontbrekend blijft backward-compatible |
| Grensstaat | MOD-01-allowlist nog niet aangemaakt; ARC-07-register nog niet aangemaakt; productiesuppressies 1 |
| Open items / blokkades | geen; de rode tussenrun `29167876627` is niet genegeerd maar gerepareerd via geïsoleerde scripted resultworkspaces |
| Volgende startgate | FIX-06 pas starten na gemergede en post-merge groene FIX-05 |

| Rol | Exacte SHA | Command / gate | Datum/tijd | Exit / tellingen | Artifact / akkoord |
| --- | --- | --- | --- | --- | --- |
| Developer | `ecf0574` | `mvn verify`; `./quality/run.sh` | 11 juli 2026 22:37–22:40 CEST | beide exit 0; qualityscore 353 | lokale Mavenrapporten; `qualityrun/2026-07-11T22-40-05/quality-score.json` |
| Developer | storycandidate vóór commit | gerichte `*Bridge*Test`; lokale BridgeClient-smoke missing/false/true; `./quality/run.sh`; `mvn verify` | 11 juli 2026 22:41–22:46 CEST | alles exit 0; doelmodules 10/21/19 tests; cachebroncalls 1→1→2; qualityscore 353; Maven 93 rapporten/662 tests | geen qualitytoename of nieuwe finding; exacte booleanframes groen |
| Reviewer | `3b47c06` | wirecompatibiliteit/helper/foutgedrag; gerichte `*Bridge*Test`; `mvn verify` | 11 juli 2026 22:47–22:50 CEST | alles exit 0; doelmodules >0; Maven 662 tests | expliciet akkoord; geen untyped escape hatch of string-force over |
| Tester | `3b47c06` | exacte contract/controllerframes; echte lokale websocket-cache-smoke; `mvn verify` | 11 juli 2026 22:51–22:54 CEST | alles exit 0; cache missing/false/true 1→1→2; Maven 662 tests | expliciet akkoord; true omzeilt cache, missing/false niet |
| Developer | storycandidate na CI-failure | CI-loganalyse; gerichte `PipelineLoopbackE2eTest`; `mvn verify` | 11 juli 2026 23:00–23:07 CEST | CI-race gereproduceerd; gerichte 5 tests en volledige 662 tests groen | scripted sibling-agents hebben nu geïsoleerde resultworkspaces met read-only repo-link voor testerbewijs |
| Reviewer | `e2b2161` | definitieve diff/race-isolatie; `mvn verify` | 11 juli 2026 23:07–23:10 CEST | exit 0; 93 rapporten/662 tests | expliciet akkoord; containerresultaatidentiteit blijft per dispatch behouden |
| Tester | `e2b2161` | volledige regressie inclusief falende E2E-route; `mvn verify` | 11 juli 2026 23:10–23:13 CEST | exit 0; 93 rapporten/662 tests | expliciet akkoord; geen resultaatoverschrijving of testerbewijsregressie |
| Post-merge | `2bde8b3acdfd394f4493132e7cca16f713e9c484` | `mvn verify`; repositorycheck | 11 juli 2026 23:17–23:21 CEST | lokaal exit 0/662 tests; run `29168546402` alle 3 jobs groen | expliciet akkoord |

### FIX-06 / SF-939 — Stale story-runs sluiten bij ontbrekend Postgres-issue

| Veld | Verplichte inhoud |
| --- | --- |
| Werkpakket / story / titel | FIX-06 / `SF-939` / Stale story-runs sluiten bij ontbrekend Postgres-issue |
| Status / eigenaar | `AFGEROND`; huidige Codex-taak van Robbert van der Zon |
| Uitvoertaken / model / effort | Huidige Codex-taak; GPT-5 / Medium volgens plan 02 |
| Baseline | `main` op `6fff5e9`, 11 juli 2026 23:35 CEST, schone worktree; `mvn verify` groen met 662 tests; qualityscore 353 (352 findings + 1 suppressie); defect gereproduceerd als generieke trackerfout voor verwijderd issue |
| Branch / PR | `codex/SF-939-fix-06-typed-tracker-not-found`; [PR #104](https://github.com/robbertvdzon/software-factory/pull/104) |
| Designholdpoint | n.v.t.; FIX-06 schrijft geen afzonderlijk designholdpoint voor |
| Uiteindelijke story-SHA | inhoudelijke developer-/reviewer-/tester-SHA `e88723b`; evidence-head volgt |
| Merge / post-merge | squashmerge/default-branch-SHA `77a8c5ae390627cd415fb117d22419817c85e42d`; gerichte unit- en exacte E2E-gates groen; lokaal 663 tests; GitHub-run `29169615518` volledig groen |
| Artifacts | baseline `qualityrun/2026-07-11T23-35-36/quality-score.json`; nameting `qualityrun/2026-07-11T23-39-30/quality-score.json`; exacte `StaleTrackerRunClosureE2eTest` via Failsafe/Testcontainers Postgres |
| Architectuur-/contractbesluiten | `TrackerIssueNotFoundException` is het enige signaal voor een ontbrekende issue-key; generieke transport-/databasefouten blijven technisch zichtbaar |
| Grensstaat | repositorybrede zoekopdracht vindt geen trackerbesluit op 404-/messagetekst; de resterende match in `NightlyJobsReader` verwerkt uitsluitend GitHub Contents-404 |
| Open items / blokkades | geen; een eerste testcompilefout door niet-publieke closurevelden is niet genegeerd maar opgelost via een directe teststate-query |
| Volgende startgate | OPS-01 pas starten na gemergede en post-merge groene FIX-06 |

| Rol | Exacte SHA | Command / gate | Datum/tijd | Exit / tellingen | Artifact / akkoord |
| --- | --- | --- | --- | --- | --- |
| Developer | `6fff5e9` | `mvn verify`; `./quality/run.sh` | 11 juli 2026 23:35–23:39 CEST | Maven exit 0/662 tests; qualityscore 353 | baseline en defectreproductie vastgelegd |
| Developer | storycandidate vóór commit | gerichte `*CostMonitorServiceTest,*PostgresTrackerClientTest`; exacte `StaleTrackerRunClosureE2eTest`; repositorybrede tekstzoeking; `./quality/run.sh` | 11 juli 2026 23:36–23:39 CEST | unitdoel 28 tests groen; E2E 1 groen; qualityscore 353 | twee polls sluiten exact eenmaal; generieke `status 404`-fout blijft zichtbaar |
| Reviewer | `e88723b` | fouttype over modulegrenzen en technische-foutpad; gerichte 28 tests; `mvn verify` | 11 juli 2026 23:43–23:46 CEST | beide exit 0; volledige Mavenpoort 663 tests | expliciet akkoord; alleen typed not-found sluit, infrastructuurfouten blijven zichtbaar |
| Tester | `e88723b` | exacte `StaleTrackerRunClosureE2eTest` via Failsafe; negatieve unitcase; `mvn verify` | 11 juli 2026 23:47–23:49 CEST | exacte E2E 1 groen; volledige Mavenpoort 663 tests | expliciet akkoord; eerste poll sluit, tweede blijft stil met identieke eindtijd |
| Post-merge | `77a8c5ae390627cd415fb117d22419817c85e42d` | gerichte 28 unit-tests; exacte E2E; `mvn verify`; repositorycheck | 11 juli 2026 23:55–23:59 CEST | alles exit 0; Maven 663 tests; run `29169615518` alle 3 jobs groen | expliciet akkoord; OPS-01-startgate open |

### OPS-01 / SF-940 — Actieve workspaces zijn hard uitgesloten van retention-cleanup

| Veld | Verplichte inhoud |
| --- | --- |
| Werkpakket / story / titel | OPS-01 / `SF-940` / Actieve workspaces zijn hard uitgesloten van retention-cleanup |
| Status / eigenaar | `AFGEROND`; huidige Codex-taak van Robbert van der Zon |
| Uitvoertaken / model / effort | Huidige Codex-taak; GPT-5 / Medium volgens plan 02 |
| Baseline | `main` op `5226cb4`, 12 juli 2026 00:09 CEST, schone worktree; code-identieke post-merge Mavenpoort 663 tests en GitHub-run `29169895430` groen; qualityscore 353 |
| Branch / PR | `codex/SF-940-ops-01-active-workspaces`; [PR #106](https://github.com/robbertvdzon/software-factory/pull/106) |
| Designholdpoint | n.v.t.; OPS-01 schrijft geen afzonderlijk designholdpoint voor |
| Uiteindelijke story-SHA | inhoudelijke developer-/reviewer-/tester-SHA `d62d0d1`; qualityfix `035e71c` |
| Merge / post-merge | PR #106 squashmerge `716907a`; PR #107 squashmerge `f5c4791`; post-merge cleanup 10, Modulith en Maven 666 groen; qualityscore 353 |
| Artifacts | qualitynameting `qualityrun/2026-07-12T00-14-35/quality-score.json`; gerichte cleanup-/assistant-/Modulithrapporten; volledige Mavenrapporten 666 tests |
| Architectuur-/contractbesluiten | kleine `ActiveWorkspaceSource`-lijst; Postgres story-/agentruns plus refcounted assistantregister; bronfout slaat de hele tick fail-safe over |
| Retentiegrens | jonger dan retentie blijft; exact op/over grens wordt alleen verwijderd wanneer geen actief pad overlapt |
| Open items / blokkades | geen; compile-/qualitytussenfouten zijn hersteld; de eerste volledige poort vond een stale verplaatste `.class`, waarna `mvn clean verify` én een gewone `mvn verify` beide groen zijn |
| Volgende startgate | planbrede eindverificatie volledig groen; plan 03 mag starten |

| Rol | Exacte SHA | Command / gate | Datum/tijd | Exit / tellingen | Artifact / akkoord |
| --- | --- | --- | --- | --- | --- |
| Developer | `5226cb4` | post-merge `mvn verify`; repositorycheck | 11–12 juli 2026 23:55–00:09 CEST | Maven 663 en GitHub-run `29169895430` groen | OPS-01-startgate bevestigd |
| Developer | storycandidate vóór commit | gerichte `*WorkCleanup*Test,*TelegramAssistantServiceTest` | 12 juli 2026 00:10–00:11 CEST | exit 0; 31 tests groen vóór aanvullende registrytest | actieve oude paden blijven; inactieve siblings, boundary, race, symlink en bronfout gedekt |
| Developer | storycandidate vóór commit | gerichte cleanup/assistant/Modulith; `./quality/run.sh`; `mvn clean verify`; daarna `mvn verify` | 12 juli 2026 00:12–00:24 CEST | gericht 33 groen; quality 353→353; beide volledige poorten exit 0/666 tests | modulegrens groen; geen stale-classafhankelijkheid; geen failure genegeerd |
| Reviewer | `d62d0d1` | normalisatie, overlap, bron-/entryfouten en Modulith; gerichte cleanup/architectuur; `mvn verify` | 12 juli 2026 00:25–00:28 CEST | alles exit 0; volledige Mavenpoort 666 tests | expliciet akkoord; kleine corebron, geen brede runtimefacade; root-/symlinkcontract behouden |
| Tester | `d62d0d1` | actieve oude map plus inactieve sibling, boundary, nested assistant, race en bronfout; gerichte cleanup/assistant; `mvn verify` | 12 juli 2026 00:28–00:31 CEST | alles exit 0; volledige Mavenpoort 666 tests | expliciet akkoord; alleen inactieve verlopen entries verdwijnen |
| Developer | post-merge blockerfix | planbrede `./quality/run.sh`; bestandsnaamreparatie; gerichte cleanup/Modulith | 12 juli 2026 00:39–00:41 CEST | quality 355→353; gericht 11 groen | twee nieuwe `MatchingDeclarationName`-findings niet geaccepteerd; semantiek ongewijzigd |
| Reviewer | `035e71c` | declaration-/modulegrenzen; `./quality/run.sh`; cleanup + Modulith | 12 juli 2026 00:41–00:42 CEST | qualityscore 353; 11 tests groen | expliciet akkoord; uitsluitend twee semantiekvrije bestandshernoemingen |
| Tester | `035e71c` | actieve/inactieve/boundary/race/symlinkcases opnieuw | 12 juli 2026 00:42 CEST | gerichte cleanup 10 tests groen | expliciet akkoord; runtimegedrag ongewijzigd |
| Post-merge | `f5c4791f6046400013c82832abe17966092ad14e` | cleanupdoeltests; `mvn verify`; quality; Flutter; images; quickstart; releasebot | 12 juli 2026 00:47–01:02 CEST | alles groen; Maven 666; quality 353; Flutter 14; quickstart 200/401/200 connected | blocker `SF-941` ontstond pas op uiteindelijke manifest-SHA en is afzonderlijk hersteld |

### REL-01 / SF-986 — Duurzame agent-completion met idempotente restart-recovery

| Veld | Verplichte inhoud |
| --- | --- |
| Werkpakket / story / titel | REL-01 / `SF-986` / Duurzame agent-completion met idempotente restart-recovery |
| Status / eigenaar | `AFGEROND`; huidige Codex-taak van Robbert van der Zon |
| Uitvoertaken / model / effort | Huidige Codex-taak; versnelde autonome uitvoering onder de gebruikersoverride |
| Baseline | `main` op `f1bcfb0`, 13 juli 2026, schone uitgangsbranch na afgerond plan 09 |
| Branch / PR | `codex/phase-04-durable-completion`; één fase-PR, direct gemerged na lokale fasegate |
| Designholdpoint | n.v.t.; afzonderlijke review-/evidence-PR vervalt onder de expliciete versnelde gebruikersoverride |
| Uiteindelijke story-SHA | fase-04-storycommit waarin deze overdracht, V16 en REL-01 samen zijn vastgelegd |
| Merge / post-merge | squashmerge naar `main`; GitHub-buildmonitoring bewust niet uitgevoerd onder de gebruikersoverride |
| Artifacts | `docs/factory/durable-completion.md`; Flyway V16; `AgentCompletionRecoveryE2eTest`; `qualityrun/2026-07-13T17-48-49/`; gegenereerde moduledependencydocumentatie |
| Architectuur-/contractbesluiten | Ruwe completion wordt vóór effecten met SHA-256 en agent-runidentity opgeslagen; gevalideerde payload blijft apart hervatbaar; twaalf stabiele geleasede stappen; usage en agent-events hebben database-idempotencykeys; identieke replay is geldig en conflicterende replay geeft 409; unfinished completion blokkeert vervolgdispatch |
| Grensstaat | MOD-01-allowlist blijft leeg; ARC-07-register ongewijzigd; qualityratchet 662 findings, 0 nieuw, productiesuppressies 1→1 |
| Open items / blokkades | geen |
| Volgende startgate | n.v.t.; plannen 05–09 waren al afgerond en het volledige traject is nu compleet |

| Rol | Exacte SHA | Command / gate | Datum/tijd | Exit / tellingen | Artifact / akkoord |
| --- | --- | --- | --- | --- | --- |
| Developer | fase-04-kandidaat | `AgentRunCompletionServiceTest`, `ModulithArchitectureTest`, `ModuleApiConventionTest`; `PipelineFlowsE2eTest`; documentatie-audit | 13 juli 2026 17:15–17:16 CEST | exit 0; gerichte backend-/Spring-/architectuurgates groen | durable-completionguard en Spring-injectie bewezen |
| Developer | fase-04-kandidaat | exacte `AgentCompletionRecoveryE2eTest` via Failsafe/Testcontainers/Postgres | 13 juli 2026 17:14 CEST | exit 0; 5 tests, 0 failures/errors/skips; alle 12 stappen × drie failurepoints | replay/conflict, echte usage/event-idempotency, lease/reclaim, retry/permanent/requeue, limieten en tombstone groen |
| Developer | fase-04-kandidaat | `tools/verify-repository`; na mechanisch hergenereren alleen de gestopte modulematrix en resterende vervolgchecks | 13 juli 2026 17:17–17:49 CEST | Maven 685 tests, 0 failures/errors/skips; quality 662/0 nieuw/1 suppressie; Flutter 17; modulematrix, mini-reactor, agent-buildstage en docs groen | één volledige lokale fasegate; 31-minuten-Maven niet onnodig herhaald |
| Reviewer / tester | fase-04-kandidaat | failure-injectionmatrix plus volledige lokale fasegate | 13 juli 2026 | gecombineerd lokaal bewijs groen onder de versnelde gebruikersoverride | akkoord voor directe fase-PR/merge |
| Post-merge | `main` na fase-PR | GitHub Repository verification | 13 juli 2026 | niet gemonitord op expliciet verzoek | lokale gate is het overdrachtsbewijs |

## Plan-07-taakfasering en MOD-03-modulemigraties

`MOD-03` is administratief één werkpakket, maar verplicht één Factory-story per module.

| Module/story | Verplichte Codexfasering | Design-SHA / reviewerholdpoint | Mechanische taak / bewijs |
| --- | --- | --- | --- |
| `telegram` / `MOD-02` | nieuwe Sol High API-design-/matrix-taak, daarna nieuwe Sol Medium mechanische taak op dezelfde storybranch | — | — |

| Module | Verplicht model / fasering | Status | Story | Design-SHA / review | Branch / PR | Bewijs |
| --- | --- | --- | --- | --- | --- | --- |
| `knowledge` | Sol Medium mechanisch | `NIET GESTART` | — | n.v.t. | — | — |
| `runtime` | Sol Medium mechanisch | `NIET GESTART` | — | n.v.t. | — | — |
| `config` | Sol Medium mechanisch | `NIET GESTART` | — | n.v.t. | — | — |
| `orchestrator` | Sol Medium mechanisch | `NIET GESTART` | — | n.v.t. | — | — |
| `nightly` | Sol Medium mechanisch | `NIET GESTART` | — | n.v.t. | — | — |
| `tracker` | Sol Medium mechanisch | `NIET GESTART` | — | n.v.t. | — | — |
| `core` | nieuwe Sol High volledige contract-/consumermatrix en reviewholdpoint, daarna nieuwe Sol Medium mechanische taak | `NIET GESTART` | — | — | — | — |
| `web` / `bridge` nacontrole | Sol Medium mechanisch | `NIET GESTART` | — | n.v.t. | — | — |

## Oorspronkelijke qualitybaseline en hotspotmatrix

De onveranderlijke machinebron is
[`baselines/quality-cc7cac2.json`](baselines/quality-cc7cac2.json), gemeten op auditcommit
`cc7cac2`: score 354, 353 findings en exact één productie-suppressie. De JSON bevat de volledige
lijst; onderstaande matrix volgt minimaal de in bronplan en refactorplannen genoemde hotspots. Vul
na iedere eigenaarstory en nogmaals op de eind-SHA pad/symbool, waarde, delta en artifact in. Een
rename zonder inhoudelijke daling blijft dezelfde hotspot.

| Oorspronkelijke hotspot | Waarde `cc7cac2` | Primaire eigenaarstory | Na-story SHA / pad / waarde | Eind-SHA / pad / waarde | Delta / artifact |
| --- | ---: | --- | --- | --- | --- |
| `DeploySubtaskHandler.kt` | 9 | `ARC-03` | — | — | — |
| `AgentDispatcher.kt` | 8 | `ARC-03` | — | — | — |
| `SubtaskExecutionCoordinator.kt` | 7 | `ARC-03` | — | — | — |
| `TelegramNotificationService.kt` | 6 | `MOD-02` | — | — | — |
| `ClaudeAssistantClient.kt` | 6 | `ARC-05` | — | — | — |
| `AgentRunCompletionService.kt` | 6 | `REL-01` | fase-04-kandidaat / opgesplitste step-executie | fase-04-kandidaat / 0 nieuwe qualityfindings | `qualityrun/2026-07-13T17-48-49/` |
| `FactoryDashboardService.kt` | 5 | `ARC-02` | — | — | — |
| `TelegramAssistantService.kt` | 5 | `MOD-02` | — | — | — |
| `BridgeRequestHandler.kt` | 4 | `ARC-01` | — | — | — |

| Qualitygrens | Oorspronkelijke staat | Actuele versioned staat | Regel |
| --- | --- | --- | --- |
| Productiesuppressies | 1: `BridgeRequestHandler.kt` / `@Suppress("unused")` | 1; exact dezelfde identiteit in `quality/baselines/plan-07-ratchet.json` | Alleen exact deze suppressie mag blijven of veilig naar 0 dalen; nooit vervangen of groeien |
| `MOD-01`-migratieallowlist | Wordt in `MOD-01` geïnventariseerd | exact leeg (`module-root-allowlist.txt`) | Alleen krimpen; vóór plan 08 exact leeg |
| `ARC-07` composition-root-boundaryregister | Wordt in `ARC-07` aangemaakt | versioned en exact; `tools/check-composition-roots` groen | Permanent exact architectuurregister, geen overtredingsallowlist; alleen krimpen |

## Voorbereiding

| Onderdeel | Status | Story | Bewijs |
| --- | --- | --- | --- |
| Audit en bronplan | `AFGEROND` | — | `docs/verbeterplan-onderhoudbaarheid-2026-07.md` |
| Qualitynulmeting op `cc7cac2` en initiële hotspotmatrix | `AFGEROND` | `SF-925` | `baselines/quality-cc7cac2.json` en matrix hierboven |
| Zelfstandige deelplannen en voortgangsstructuur | `AFGEROND` | `SF-925` | PR `#74` gemerged als `67290c1`; post-merge GitHub-run `29153099411` groen |

## Blokkades

Geen actuele blokkades. Voeg voor iedere blokkade een regel toe met datum, plan/werkpakket,
technische oorzaak, reeds onderzochte alternatieven, eigenaar en eerstvolgende concrete actie.

## Chronologisch log

| Datum/tijd | Plan / story | Gebeurtenis | Bewijs / vervolg |
| --- | --- | --- | --- |
| 2026-07-11 | voorbereiding / `SF-925` | Deelplanstructuur gestart; verbeterimplementatie bewust niet gestart | branch `verbeterpunten` |
| 2026-07-11 10:50 CEST | voorbereiding / `SF-925` | Negen contextvrije plannen, modelniveaus, vaste overdracht en auditbaseline gereed; drie onafhankelijke kruisreviews verwerkt | 25/25 pakketten exact eenmaal gedekt; links/codefences/JSON/commands groen |
| 2026-07-11 10:50 CEST | voorbereiding / `SF-925` | Volledige repositorysuite groen op storybranch | `mvn verify`: 85 rapporten, 621 tests, 0 failures, 0 errors, 0 skipped; volgende stap: commit, push, review en merge |
| 2026-07-11 10:53 CEST | voorbereiding / `SF-925` | Documentset gecommit, gepusht en ter review aangeboden; verbeterwerk niet gestart | inhoudcommit `afbbc99`; branch `verbeterpunten`; PR `#74`; volgende stap: groene PR-review/merge en post-mergegate |
| 2026-07-11 14:47 CEST | plan 01 / `SF-926` | FIX-01 als niet-gestarte Factory-story via lokale tracker-API aangemaakt; branch gestart na groene baseline | `main` `67290c1`; `mvn verify` 621/0/0/0; qualityscore 354; GitHub-run `29153099411` groen; branch `codex/SF-926-fix-01-merge-gate` |
| 2026-07-11 15:21 CEST | plan 01 / `SF-926` | Developer-, reviewer- en tester-overdracht groen; PR-check groen; geen reviewbevindingen | inhoud-SHA `18ca4a6`; PR #75; lokaal telkens 637 tests groen; quality 353; GitHub-run `29153868120` groen; volgende stap evidencecommit + eindgate + merge |
| 2026-07-11 15:30 CEST | plan 01 / `SF-926` | FIX-01 gemerged en post-merge lokaal plus protected check groen | merge `d4f3280`; lokaal 637 tests; GitHub-run `29154308271`; FIX-02-manifestpushprobleem expliciet buiten plan 01 |
| 2026-07-11 15:46 CEST | plan 01 / `SF-927` | Drie target-repos geïnventariseerd; externe configs via afzonderlijke PR's gemerged en productieparser groen; contract/runner/factory/e2e geïmplementeerd | personal-feed #176 `c0ff52c`; robberts-assistent #2 `c097db1`; gerichte gates 7+9+4+1 groen; volgende stap eindgates/review/test/PR |
| 2026-07-11 16:08 CEST | plan 01 / `SF-927` | Developer-eindgates groen; twee rode iteraties (e2e-assertie en te strenge clean-tree-eis) niet genegeerd en aantoonbaar hersteld | gericht 4+45+18+1; regressiematrix 22; volledig 656 tests groen; quality 353; volgende stap commit/push/PR/reviewer/tester |
| 2026-07-11 16:15 CEST | plan 01 / `SF-927` | Reviewerbevindingen op `dacd6af` verwerkt: child-process-timeout, output-reader fail-closed, duration- en rapportbegrenzing | gerichte runner 5 en AgentRunCompletion 18 groen; quality 353; nieuwe eind-SHA en volledige gates volgen |
| 2026-07-11 16:26 CEST | plan 01 / `SF-927` | Reviewer en onafhankelijke tester akkoord op uiteindelijke inhoud-SHA; drie actuele configs opnieuw productieparser-geldig | `b14ebea`; reviewer volledig 658 groen; tester 12+18+1 en volledig 658 groen; PR-run `29155791691` groen; volgende stap evidence-eindgate en merge |
| 2026-07-11 18:45 CEST | plan 02 / `SF-928` | FIX-02 gestart vanaf gemergede plan-01-SHA; monotone PR-releasebot en deterministische out-of-ordertest geïmplementeerd; developer-gates groen | baseline `223a6d2`; bare-repotest groen; `mvn verify` 658 tests groen; volgende stap inhoudcommit, review/test en PR |
| 2026-07-11 21:07 CEST | plan 02 / `SF-928` | FIX-02 volledig afgerond na echte workflow-, strict-branch- en parallelle-componentverificatie | eind-main `a5b6b76`; backend `29164368822`; frontend `29164368852`; bot-PR's #93/#94; manifests beide `sha-68e1ad0`; volgende stap FIX-03 |
| 2026-07-11 21:23 CEST | plan 02 / `SF-929` | Gedeeld mini-reactorpatroon, buildstage-CI en beide lokale eindimages geïmplementeerd; developer-gates groen | beide schone buildstages, agent/assistant-smokes en `mvn verify` 658 groen; volgende stap inhoudcommit/review/test |
| 2026-07-11 21:48 CEST | plan 02 / `SF-929` | FIX-03 volledig gemerged en post-merge lokaal/CI/imageworkflow groen | merge `a81f7d3`; CI agent-buildstage; backendrun `29165702981`; manifest-PR #97; volgende stap FIX-04 |
| 2026-07-11 22:05 CEST | plan 02 / `SF-930` | Canonieke Compose/SSO/bridgequickstart en redacted geïsoleerde smoke geïmplementeerd; developer-gates groen | healthz 200, unauth 401, auth 200 connected=true; Flutter analyze/14 tests; Maven 658; volgende stap commit/review/test |
| 2026-07-11 22:22 CEST | plan 02 / `SF-930` | FIX-04 gemerged en post-merge quickstart plus CI groen | merge `b69bd9b`; geïsoleerde smoke 200/401/200 connected; run `29166935313`; volgende stap FIX-05 |
| 2026-07-11 22:40 CEST | plan 02 / `SF-931` | FIX-05 gestart vanaf groene main; defect en qualitybaseline bevestigd | `ecf0574`; Maven groen; qualityscore 353; volgende stap exacte frame-/contracttests |
| 2026-07-11 22:46 CEST | plan 02 / `SF-931` | Getypeerde forceframes en lokale cache-omzeiling geïmplementeerd; developergates groen | missing/false/true exact; cachecalls 1→1→2; quality 353→353; Maven 662 groen; volgende stap commit/review/test |
| 2026-07-11 22:54 CEST | plan 02 / `SF-931` | Inhoud op `3b47c06` onafhankelijk gereviewd en getest | PR #101; reviewer en tester beide gerichte suites plus volledige Mavenpoort groen; volgende stap PR-CI/merge |
| 2026-07-11 23:07 CEST | plan 02 / `SF-931` | Rode PR-check onderzocht en E2E-resultaatrace gerepareerd | run `29167876627`: developerresultaat door sibling overschreven; unieke resultworkspace; gerichte 5 en volledige 662 tests groen; nieuwe review/test vereist |
| 2026-07-11 23:13 CEST | plan 02 / `SF-931` | Definitieve kandidaat `e2b2161` opnieuw onafhankelijk gereviewd en getest | reviewer en tester ieder volledige Mavenpoort 662 groen; volgende stap verse PR-head-CI |
| 2026-07-11 23:21 CEST | plan 02 / `SF-931` | FIX-05 gemerged en post-merge lokaal/CI groen | merge `2bde8b3`; Maven 662; run `29168546402`; volgende stap FIX-06 |
| 2026-07-11 23:35 CEST | plan 02 / `SF-939` | FIX-06 gestart vanaf groene main; defect en qualitybaseline bevestigd | `6fff5e9`; Maven 662 groen; qualityscore 353; volgende stap typed not-foundcontract en exacte E2E |
| 2026-07-11 23:39 CEST | plan 02 / `SF-939` | Typed not-foundcontract en twee-poll-Postgres-E2E geïmplementeerd | gerichte unitdoelen 28 en exacte E2E 1 groen; quality 353→353; volledige Mavenpoort loopt |
| 2026-07-11 23:49 CEST | plan 02 / `SF-939` | Kandidaat `e88723b` onafhankelijk gereviewd en getest | PR #104; reviewer en tester ieder volledige Mavenpoort 663 groen; volgende stap verse evidence-head-CI |
| 2026-07-11 23:59 CEST | plan 02 / `SF-939` | FIX-06 gemerged en post-merge lokaal/CI groen | merge `77a8c5a`; gerichte unit/E2E-gates en Maven 663 groen; run `29169615518`; volgende stap OPS-01 |
| 2026-07-12 00:09 CEST | plan 02 / `SF-940` | OPS-01 gestart vanaf volledig groene FIX-06-evidence-main | `5226cb4`; run `29169895430` groen; actieve bronnen en vier roots geïnventariseerd |
| 2026-07-12 00:24 CEST | plan 02 / `SF-940` | Actieve-workspacebescherming, retentiegrens, foutisolatie en docs geïmplementeerd | gericht 33; quality 353→353; clean en gewone Mavenpoort 666 groen; volgende stap commit/review/test |
| 2026-07-12 00:31 CEST | plan 02 / `SF-940` | Kandidaat `d62d0d1` onafhankelijk gereviewd en getest | PR #106; reviewer en tester ieder volledige Mavenpoort 666 groen; volgende stap verse evidence-head-CI |
| 2026-07-12 00:41 CEST | plan 02 / `SF-940` | Planbrede qualitygate heropende OPS-01 voor twee declaration-/bestandsnaammismatches | gemergede quality 355; blockerfix terug op 353; gerichte cleanup/Modulith 11 groen; nieuwe PR vereist |
| 2026-07-12 00:42 CEST | plan 02 / `SF-940` | Quality-blockerfix `035e71c` opnieuw gereviewd en getest | PR #107; quality 353; reviewer 11 en tester 10 gerichte tests groen; volgende stap verse PR-head-CI |
| 2026-07-12 01:05 CEST | plan 02 / `SF-941` | Uiteindelijke manifest-SHA-eindcheck heropende plan 02 op parallelle Telegram-testvolgorde | run `29171504423`: beide calls aanwezig maar geldige voltooiingsvolgorde omgekeerd; aparte blokkerstory gestart; niets genegeerd |
| 2026-07-12 01:13 CEST | plan 02 / `SF-941` | Concurrency-correcte kandidaat `4d986c7` gereviewd en getest | PR #110; tester 10× volledige TelegramPollerTest, reviewer 6/6 en volledige Maven 666 groen; quality 353; volgende stap PR-CI |
| 2026-07-12 01:23 CEST | plan 02 / `SF-941` | Blokker gemerged en uiteindelijke default-branch opnieuw volledig groen | merge `a3ee8c0`; TelegramPoller 6 groen; expliciete repositoryrun `29171926490` alle jobs groen |
| 2026-07-12 01:23 CEST | plan 02 | Planbrede eindverificatie afgerond; geen failure genegeerd | Maven 666; quality 353/1 suppressie; Flutter analyze + 14 tests; images/smokes groen; quickstart 200/401/200 connected; shelltest groen; workflowruns `29171178914`/`29171179525`, PR's #108/#109 en manifests `sha-f5c4791`; plan 03-startgate open |
| 2026-07-13 06:57 CEST | plan 05 / `SF-966`–`SF-969` | ARC-01 t/m ARC-04 sequentieel uitgevoerd en als vier Factory-stories op Done gezet; plan 04 bleef op dat moment uitgesteld | commits `20f1739`, `d35d140`, `6f92edb`, `a65acd8`, `4123859`; branch `codex/phase-05-application-domain` |
| 2026-07-13 06:57 CEST | plan 05 | Lokale fasegate en extra schone Mavencontrole volledig groen | `tools/verify-repository` groen: Maven, E2E, Flutter, Docker-buildstage en documentatie-audit; `mvn -q clean verify`: 95 hoofdrapporten/674 tests, 0 failures/errors/skips; qualityscore 366 met 365 findings en 1 suppressie; plan 06-startgate open onder gebruikersoverride |
| 2026-07-13 08:23 CEST | plan 08 / `SF-983`–`SF-984` | Repositorybrede fail-closed qualityratchet en expliciete Modulith-richtingen afgerond | commits `ce320bd`/`4fb8eab`; 5 gemeten Kotlinmodules, 637 findings, 1 suppressie; 20 moduleallowlists; `tools/generate-module-dependencies --check`, architectuurfixtures en gerichte tests groen |
| 2026-07-13 08:32 CEST | plan 09 / `SF-985` | Mechanische cleanup afgerond en story op Done | commit `2b31eb4`; `YouTrackModels`→`TrackerEntities` en `TrackerModels`→`WorkflowModels` beide 100%-rename; ongebruikte poll-/trackerparameters verwijderd; quality 636 findings/1 suppressie en gerichte suites groen |
| 2026-07-13 | plan 06 / `SF-970`–`SF-973` | ARC-05 t/m UI-01 sequentieel lokaal uitgevoerd en de vier Factory-stories op Done gezet | commits `c720da8`, `2170224`, `82b853f`, `e88bdb0`; branch `codex/phase-06-platform-ai-frontend` |
| 2026-07-13 | plan 06 | Schone Mavenpoort, canonieke repositorygate en fasespecifieke quality-/frontend-/Dockergate volledig groen | `mvn -q clean verify`; `tools/verify-repository`; `./quality/run.sh`; Flutter release-webbuild; productie-Dockerbuild; een eerste incrementele run met stale bytecode is volledig hersteld door de verplichte clean run |
| 2026-07-13 17:15 CEST | plan 04 / `SF-986` | REL-01 alsnog uitgevoerd: completion wordt vóór business-effecten duurzaam geaccepteerd en hervat per geleasede, idempotente stap | Flyway V16; 12 stabiele stappen; gerichte Postgres recovery-E2E 5 tests groen; branch `codex/phase-04-durable-completion` |

## Eindbewijs

- eindcandidate: fase-04-retrofit op `codex/phase-04-durable-completion`; squashmerge-SHA volgt uit de fase-PR
- `mvn clean verify`: groen via `tools/verify-repository`; 685 tests, 0 failures/errors/skips, inclusief de volledige REL-01 failure-injectionmatrix
- `flutter analyze`: groen
- `flutter test`: groen; 17 tests
- agent-image-buildstage en mini-reactor-smoke: groen; overige image-/Composebewijzen uit FIX-03/FIX-04 ongewijzigd
- documentatie-audit: `documentation-audit/v1: PASS`
- auditbaseline: onveranderlijk `cc7cac2`; ratchetbaseline `quality/baselines/plan-07-ratchet.json`
- quality-regressierapport: 662 structurele findings, geen nieuwe schuld, suppressiedelta 1→1
- Modulith dependencyverificatie en `tools/generate-module-dependencies --check`: groen
- `MOD-01`-migratieallowlist exact leeg: bevestigd
- permanent `ARC-07` composition-root-boundaryregister exact/niet gegroeid: sourcecheck groen
- GitHub-buildmonitoring: bewust niet uitgevoerd volgens de versnelde gebruikersoverride
- production compatibilityfacades en compatibilityshims: `geen`
- open blokkades binnen plannen 01–09: `geen`
- geaccepteerde uitzonderingen: alleen de vastgelegde gebruikersoverride om GitHub-buildmonitoring niet af te wachten
