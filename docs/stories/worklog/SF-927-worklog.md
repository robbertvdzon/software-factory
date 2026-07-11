# SF-927 — Testergoedkeuring vereist groen revisiongebonden testbewijs

## Story in eigen woorden

Een testerclaim is pas geldig wanneer de agentworker zelf alle versioned target-repocommands groen
uitvoert op exact dezelfde HEAD/worktree-tree en de factory dat bewijs onafhankelijk hercontroleert.

## Checklist

- [x]: FIX-01 post-merge lokaal en op de protected GitHub-check groen vastgelegd.
- [x]: alle actieve target-repositories en huidige verificatie geïnventariseerd.
- [x]: schema 1, veilige parser en productievalidator-CLI gebouwd.
- [x]: config via afzonderlijke PR gemerged in personal-feed en robberts-assistent.
- [x]: additive/backward-compatible AgentResultFile-evidence toegevoegd.
- [x]: agentworker voert argv-commands zelf uit met timeout, bounded output en revisionbinding.
- [x]: factory valideert ieder `tested` onafhankelijk en reset ongeldig bewijs fail-closed.
- [x]: exacte `TesterVerificationEvidenceE2eTest` bewijst rood/mismatch → development-reset → groen.
- [x]: agentinstructies, docs-skeleton, functional/technical/development-spec en runbook bijgewerkt.
- [x]: alle gerichte developer-gates, quality en volledige `mvn verify` op storycandidate.
- [ ]: reviewer- en testergate op uiteindelijke SHA.
- [ ]: PR-check, merge en post-mergegate groen.

## Besluiten

- Config zit in de target-repo (`.factory/verification.yaml`) zodat bewijs versioned met de geteste
  code meebeweegt. Alleen versie 1 wordt ondersteund; onbekend/missing is rood.
- Commanduitvoering gebruikt argv rechtstreeks, zonder shellinterpretatie. Working directories
  moeten bestaan en binnen de checkout blijven.
- Oude niet-testerpayloads blijven compatibel door optionele/defaulted velden. Een oude testerclaim
  `tested` mist bewijs en wordt bewust afgewezen.
- De factory vergelijkt tegen de actieve workspace vóór completion-persistence. Zo bereikt een
  gemanipuleerd resultaat noch de runrecord noch de tracker als `tested`.
- Een tijdelijk `GIT_INDEX_FILE` met `git add -A`/`git write-tree` bindt ook legitieme
  niet-gecommitte testinput exact, zonder de echte index of worktree te muteren.

## Rollout

Zie `docs/verbetertraject-2026-07/VER-01-rolloutmatrix.md`. Externe config-PR's: personal-feed #176
(`c0ff52c`) en robberts-assistent #2 (`c097db1`), beide vóór enforcement gemerged en opnieuw door de
productieparser gevalideerd. Software Factory draagt config en enforcement in dezelfde SF-927-PR.

## Testnotities

- Parser/contract: 7 tests groen.
- Agentworker/CLI: 9 tests groen.
- Factory evidencevalidator: 4 tests groen.
- Eerste exacte e2e-run vond een onjuiste testverwachting (twee reviewerrollen per cyclus); geen
  productiefout. Assertie gecorrigeerd en exact dezelfde gate daarna groen: 1 Failsafe-test.
- Eerste volledige `mvn verify` na de clean-tree-implementatie was rood: drie bestaande test-only
  loopbacktests werden terecht door die te strenge extra eis afgewezen. Geen failure genegeerd:
  revisionbinding gebruikt nu een tijdelijk Git-index en bindt de werkelijke worktree-tree zonder
  mutatie. De drie regressietests plus de nieuwe e2e (22 tests) en daarna de volledige suite zijn groen.
- Formele gerichte gates: AgentResultFile 4; agentworker-doelmodule 45; AgentRunCompletion 18;
  exacte Failsafe 1; alle 0 failures/errors/skips.
- `./quality/run.sh`: `qualityrun/2026-07-11T16-10-28/`, score 353, 352 findings, 1 suppressie;
  gelijk aan FIX-01-baseline, geen nieuwe finding of suppressie.
- `mvn verify`: 93 rapporten, 656 tests, 0 failures, 0 errors, 0 skipped; 2:38 min.
