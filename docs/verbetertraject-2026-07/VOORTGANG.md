# Voortgang autonoom verbetertraject

**Trajectstatus:** `IN UITVOERING — plan 01 / FIX-01`<br>
**Uitvoering verbeterpunten:** `BEZIG`<br>
**Afgeronde plannen:** 0 / 9<br>
**Afgeronde werkpakketten:** 0 / 25<br>
**Laatste update:** 11 juli 2026 15:21 CEST — FIX-01 `SF-926` developer/reviewer/tester groen; PR #75 groen

Dit bestand is de duurzame voortgangsbron voor gemerged werk. Tijdens een actieve story zijn de
Factory-story en gepushte PR de realtime bron; werk dit bestand bij iedere overdracht mee bij.

## Planoverzicht

| Plan | Niveau | Status | Prerequisites | Actieve/afgeronde stories | Bewijs |
| --- | --- | --- | --- | --- | --- |
| [01](01-merge-en-testinvariant-high.md) | Sol High | `BEZIG` | plandocumentatie gemerged | `SF-926` | baseline `67290c1`: lokale `mvn verify` en GitHub-run `29153099411` groen; qualityscore 354 |
| [02](02-directe-reparaties-medium.md) | Sol Medium | `NIET GESTART` | plandocumentatie gemerged | — | — |
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
| FIX-01 | 01 | `BEZIG` | `SF-926` | `codex/SF-926-fix-01-merge-gate` / [PR #75](https://github.com/robbertvdzon/software-factory/pull/75) | inhoud-SHA `18ca4a6`: 637 tests groen; qualityscore 353; CI groen |
| VER-01 | 01 | `NIET GESTART` | — | — | — |
| FIX-02 | 02 | `NIET GESTART` | — | — | — |
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
| Status / eigenaar | `BEZIG`; Codex-taak van Robbert van der Zon |
| Uitvoertaken / model / effort | Huidige Codex-taak; GPT-5 / High volgens plan 01; implementatie, review en test worden afzonderlijk vastgelegd op de uiteindelijke SHA |
| Baseline | `main` op `67290c1`, 11 juli 2026 14:44–14:47 CEST, schone worktree; `mvn verify` exit 0 en `qualityrun/2026-07-11T14-46-50/quality-score.json` |
| Branch / PR | `codex/SF-926-fix-01-merge-gate`; [PR #75](https://github.com/robbertvdzon/software-factory/pull/75); inhoudelijke PR-head `18ca4a64b8a0b9ed99659bfd0d0777de964589ad` |
| Designholdpoint | n.v.t.; FIX-01 schrijft geen afzonderlijk designholdpoint voor |
| Uiteindelijke story-SHA | inhoudelijke developer-/reviewer-/tester-SHA `18ca4a64b8a0b9ed99659bfd0d0777de964589ad`; uiteindelijke evidence-only PR-head volgt |
| Merge / post-merge | volgt na groene PR-checks en merge |
| Artifacts | baseline GitHub-run `29153099411`; developer/reviewer/tester Mavenrapporten: telkens 637 tests groen; quality `qualityrun/2026-07-11T15-02-49/` score 353; PR-run `29153868120` groen |
| Architectuur-/contractbesluiten | Publieke `merge.PullRequestMergeService` met interne enige GitHub-mergecaller; projectpolicy `merge.requiredChecks`; check-runs op exact `headRefOid`; atomische `--match-head-commit`; pending handmatig commando blijft ongeprocessed voor retry |
| Grensstaat | MOD-01-allowlist nog niet aangemaakt; ARC-07-register nog niet aangemaakt; productiesuppressies 1→1 bij baseline |
| Open items / blokkades | geen blokkade; alleen evidence-only eindcommit, herhaalde eindgate, merge en post-mergecontrole resteren |
| Volgende startgate | VER-01 mag pas starten vanaf de gemergede, post-merge groene FIX-01-SHA |

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
