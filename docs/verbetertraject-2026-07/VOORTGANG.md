# Voortgang autonoom verbetertraject

**Trajectstatus:** `IN UITVOERING — plan 02 / FIX-02`<br>
**Uitvoering verbeterpunten:** `BEZIG`<br>
**Afgeronde plannen:** 0 / 9<br>
**Afgeronde werkpakketten:** 1 / 25<br>
**Laatste update:** 11 juli 2026 18:39 CEST — FIX-02 `SF-928` gestart vanaf groene `main` `223a6d2`

Dit bestand is de duurzame voortgangsbron voor gemerged werk. Tijdens een actieve story zijn de
Factory-story en gepushte PR de realtime bron; werk dit bestand bij iedere overdracht mee bij.

## Planoverzicht

| Plan | Niveau | Status | Prerequisites | Actieve/afgeronde stories | Bewijs |
| --- | --- | --- | --- | --- | --- |
| [01](01-merge-en-testinvariant-high.md) | Sol High | `AFGEROND` | plandocumentatie gemerged | `SF-926`, `SF-927` | FIX-01 `d4f3280`; VER-01 gemerged `223a6d2` |
| [02](02-directe-reparaties-medium.md) | Sol Medium | `BEZIG` | plandocumentatie gemerged | `SF-928` | baseline `main` `223a6d2`: `mvn verify` groen |
| [03](03-ci-documentatie-en-moduleborging-high.md) | Sol High | `NIET GESTART` | plan 01 en 02 | — | — |
| [04](04-duurzame-agent-completion-ultra.md) | Sol Ultra | `NIET GESTART` | plan 03 | — | — |
| [05](05-application-en-domeinrefactors-high.md) | Sol High | `NIET GESTART` | plan 04 | — | — |
| [06](06-platform-ai-en-frontendrefactors-high.md) | Sol High | `NIET GESTART` | plan 05 | — | — |
| [07](07-modulemigraties-medium.md) | Sol Medium per mechanische module; Sol High voor Telegram-/core-holdpoint | `NIET GESTART` | plan 06 | — | — |
| [08](08-architectuur-en-kwaliteitsgates-high.md) | Sol High | `NIET GESTART` | plan 07 | — | — |
| [09](09-cleanup-en-eindverificatie-light.md) | Sol Light | `NIET GESTART` | plan 08 | — | — |

## Werkpakketten

| Werkpakket | Plan | Status | Story | Branch / PR | Laatste groene bewijs |
| --- | --- | --- | --- | --- | --- |
| FIX-01 | 01 | `AFGEROND` | `SF-926` | `codex/SF-926-fix-01-merge-gate` / [PR #75](https://github.com/robbertvdzon/software-factory/pull/75) | merge `d4f3280`: lokaal 637 tests; GitHub `29154308271` groen |
| VER-01 | 01 | `AFGEROND` | `SF-927` | `codex/SF-927-ver-01-tester-evidence` / [PR #76](https://github.com/robbertvdzon/software-factory/pull/76) | gemerged `223a6d2`; post-merge baseline volledig groen |
| FIX-02 | 02 | `BEZIG` | `SF-928` | `codex/SF-928-fix-02-releasebot` / PR volgt | baseline `223a6d2`: 11 juli 2026 18:35–18:38 CEST `mvn verify` groen |
| FIX-03 | 02 | `NIET GESTART` | — | — | — |
| FIX-04 | 02 | `NIET GESTART` | — | — | — |
| FIX-05 | 02 | `NIET GESTART` | — | — | — |
| FIX-06 | 02 | `NIET GESTART` | — | — | — |
| OPS-01 | 02 | `NIET GESTART` | — | — | — |
| VER-02 | 03 | `NIET GESTART` | — | — | — |
| DOC-01 | 03 | `NIET GESTART` | — | — | — |
| MOD-01 | 03 | `NIET GESTART` | — | — | — |
| REL-01 | 04 | `NIET GESTART` | — | — | — |
| ARC-01 | 05 | `NIET GESTART` | — | — | — |
| ARC-02 | 05 | `NIET GESTART` | — | — | — |
| ARC-03 | 05 | `NIET GESTART` | — | — | — |
| ARC-04 | 05 | `NIET GESTART` | — | — | — |
| ARC-05 | 06 | `NIET GESTART` | — | — | — |
| ARC-06 | 06 | `NIET GESTART` | — | — | — |
| ARC-07 | 06 | `NIET GESTART` | — | — | — |
| UI-01 | 06 | `NIET GESTART` | — | — | — |
| MOD-02 | 07 | `NIET GESTART` | — | — | — |
| MOD-03 | 07 | `NIET GESTART` | — | — | — |
| QLT-01 | 08 | `NIET GESTART` | — | — | — |
| ARC-08 | 08 | `NIET GESTART` | — | — | — |
| CLN-01 | 09 | `NIET GESTART` | — | — | — |

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
| Status / eigenaar | `BEZIG`; huidige Codex-taak van Robbert van der Zon |
| Uitvoertaken / model / effort | Huidige Codex-taak; GPT-5 / Medium volgens plan 02; developer-eindgate groen, review/test volgen op inhoud-SHA |
| Baseline | `main` op `223a6d2`, 11 juli 2026 18:35–18:38 CEST, schone worktree; `mvn verify` exit 0, 658 tests groen |
| Branch / PR | initiële branch `codex/SF-928-fix-02-releasebot` / [PR #77](https://github.com/robbertvdzon/software-factory/pull/77); reparatiebranch `codex/SF-928-fix-02-gh-token` / [PR #78](https://github.com/robbertvdzon/software-factory/pull/78); reparatie-inhoud-SHA `0de40b2` |
| Designholdpoint | n.v.t.; FIX-02 schrijft geen afzonderlijk designholdpoint voor |
| Uiteindelijke story-SHA | initiële inhoud-SHA `b433adcc0009e8086943953e6a2c41ab88e483d9`; reparatie-inhoud-SHA `0de40b2` door developer/tester volledig groen, reviewer-CI volgt |
| Merge / post-merge | volgt |
| Artifacts | initiële PR-runs `29160519897`/`29160708572` groen; post-merge repositoryrun `29160847555` groen; echte imagebuilds `29160847558`/`29160847522` bouwden images groen en bewezen ontbrekende CLI-env; reparatie developer/tester volledig 658 groen, gerichte race 2×3 tests groen |
| Architectuur-/contractbesluiten | Component- en rungebonden botbranches/PR's; versioned `run_id`/`source_sha` per component als monotone arbiter; expliciete `verify.yml`-dispatch omdat PR's van `GITHUB_TOKEN` geen recursieve workflow-event starten; vervolgmerge wacht op required checks en gebruikt de exacte head-SHA zonder bypass |
| Grensstaat | MOD-01-allowlist nog niet aangemaakt; ARC-07-register nog niet aangemaakt; productiesuppressies blijven 1 |
| Open items / blokkades | PR #80 aangevuld: nieuwere run sluit nu proactief alle zichtbare oudere component-PR's, ook wanneer de oude run nooit hervat; developer/tester volledig groen; nieuwe inhoud-SHA/PR-check, merge en echte workflowherhaling volgen |
| Volgende startgate | FIX-03 pas starten vanaf gemergede, lokaal en op GitHub groene FIX-02-SHA |

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
| Post-merge | — | backend-/frontendworkflowdispatch, bump-PR's, manifests en required checks | — | volgt | — |

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
| `AgentRunCompletionService.kt` | 6 | `REL-01` | — | — | — |
| `FactoryDashboardService.kt` | 5 | `ARC-02` | — | — | — |
| `TelegramAssistantService.kt` | 5 | `MOD-02` | — | — | — |
| `BridgeRequestHandler.kt` | 4 | `ARC-01` | — | — | — |

| Qualitygrens | Oorspronkelijke staat | Actuele versioned staat | Regel |
| --- | --- | --- | --- |
| Productiesuppressies | 1: `BridgeRequestHandler.kt` / `@Suppress("unused")` | 1 vóór uitvoering | Alleen exact deze suppressie mag blijven of veilig naar 0 dalen; nooit vervangen of groeien |
| `MOD-01`-migratieallowlist | Wordt in `MOD-01` geïnventariseerd | nog niet aangemaakt | Alleen krimpen; vóór plan 08 exact leeg |
| `ARC-07` composition-root-boundaryregister | Wordt in `ARC-07` aangemaakt | nog niet aangemaakt | Permanent exact architectuurregister, geen overtredingsallowlist; alleen krimpen |

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

## Eindbewijs

Vul dit pas na plan 09 in:

- gemergede eind-SHA: —
- `mvn clean verify`: —
- `flutter analyze`: —
- `flutter test`: —
- agent-/assistant-image-smoke: —
- Compose-/bridge-smoke: —
- documentatie-audit: —
- auditbaseline `cc7cac2`, ratchetbaseline en volledig ingevulde hotspotmatrix: —
- quality-regressierapport en suppressiedelta 1→1 of 1→0: —
- Modulith dependencyverificatie en diagram: —
- `MOD-01`-migratieallowlist exact leeg: —
- permanent `ARC-07` composition-root-boundaryregister exact/niet gegroeid: —
- production compatibilityfacades en compatibilityshims: `geen` (nog te verifiëren)
- open blokkades: `geen` (nog te verifiëren)
- geaccepteerde uitzonderingen: `geen` (uitzonderingen zijn niet toegestaan)
