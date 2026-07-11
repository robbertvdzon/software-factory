# Voortgang autonoom verbetertraject

**Trajectstatus:** `VOORBEREIDING BEZIG — documentatie gereed op branch, merge nog vereist`<br>
**Uitvoering verbeterpunten:** `NIET GESTART`<br>
**Afgeronde plannen:** 0 / 9<br>
**Afgeronde werkpakketten:** 0 / 25<br>
**Laatste update:** 11 juli 2026 10:50 CEST — voorbereidingsstory `SF-925`

Dit bestand is de duurzame voortgangsbron voor gemerged werk. Tijdens een actieve story zijn de
Factory-story en gepushte PR de realtime bron; werk dit bestand bij iedere overdracht mee bij.

## Planoverzicht

| Plan | Niveau | Status | Prerequisites | Actieve/afgeronde stories | Bewijs |
| --- | --- | --- | --- | --- | --- |
| [01](01-merge-en-testinvariant-high.md) | Sol High | `NIET GESTART` | plandocumentatie gemerged | — | — |
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
| FIX-01 | 01 | `NIET GESTART` | — | — | — |
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

Nog geen stories gestart. Voeg hier per story een sectie met beide vaste tabellen toe en link die
sectie vanuit het werkpakketoverzicht.

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
| Zelfstandige deelplannen en voortgangsstructuur | `BEZIG` — gereed voor review/merge | `SF-925` | branch `verbeterpunten`; `mvn verify`: 621 tests, 0 failures/errors/skips |

## Blokkades

Geen actuele blokkades. Voeg voor iedere blokkade een regel toe met datum, plan/werkpakket,
technische oorzaak, reeds onderzochte alternatieven, eigenaar en eerstvolgende concrete actie.

## Chronologisch log

| Datum/tijd | Plan / story | Gebeurtenis | Bewijs / vervolg |
| --- | --- | --- | --- |
| 2026-07-11 | voorbereiding / `SF-925` | Deelplanstructuur gestart; verbeterimplementatie bewust niet gestart | branch `verbeterpunten` |
| 2026-07-11 10:50 CEST | voorbereiding / `SF-925` | Negen contextvrije plannen, modelniveaus, vaste overdracht en auditbaseline gereed; drie onafhankelijke kruisreviews verwerkt | 25/25 pakketten exact eenmaal gedekt; links/codefences/JSON/commands groen |
| 2026-07-11 10:50 CEST | voorbereiding / `SF-925` | Volledige repositorysuite groen op storybranch | `mvn verify`: 85 rapporten, 621 tests, 0 failures, 0 errors, 0 skipped; volgende stap: commit, push, review en merge |

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
