# SF-926 - Worklog

Story: Projectbewuste groene merge-gate zonder bypass of pending-error

## Scope

Voer uitsluitend FIX-01 uit:

- centraliseer automatische en handmatige PR-merges in één publieke use-case;
- modelleer readiness als ready, pending en blocked;
- maak vereiste checks projectbewust en valideer de config bij opstart;
- bind groen bewijs en de merge atomair aan exact dezelfde PR-head-SHA;
- werk actuele configdocumentatie, specs, KDoc en runbook bij;
- dek beide entrypoints en alle negatieve readinessvarianten met unit- en e2e-tests.

Geen deploylogica, branch-protectionbypass of algemene pipeline-/commandrefactor.

## Baseline

- `main` `67290c1`, schone worktree, 11 juli 2026 14:44 CEST.
- `mvn verify`: exit 0; 621 tests, 0 failures, 0 errors, 0 skipped.
- `./quality/run.sh`: score 354 = 353 findings + 1 productiesuppressie.
- GitHub Repository verification-run `29153099411`: groen op `67290c1`.

## Tussentijdse keuzes

- `merge.PullRequestMergeService` is de publieke Modulith-root; de enige implementatie staat in
  `merge.internal` en is als enige productiecode bevoegd `GitHubApi.mergePullRequest` aan te roepen.
- De checkclient haalt `headRefOid` op en leest check-runs rechtstreeks voor die commit. Daardoor
  kan bewijs van een oudere SHA niet als groen voor de actuele head gelden.
- `gh pr merge --match-head-commit` maakt de geverifieerde SHA de server-side mergepreconditie.
- Alleen queued/in-progress is pending. Missing, skipped, cancelled, failed en API-/parsefouten
  zijn blocked. Een headrace wordt opnieuw pending.
- Een pending handmatig mergecommando wordt nog niet als verwerkt gemarkeerd, zodat een volgende
  orchestratorpoll dezelfde centrale gate opnieuw uitvoert.
- De lokale, gitignored `projects.yaml` gebruikt de actuele check-run-namen `validate`,
  `Backend verification` en `build-apk`; `projects.yaml.example` documenteert het contract.

## Tussentijds bewijs

- Gerichte unitgate: 49 tests, 0 failures/errors/skips.
- `MergePolicyE2eTest`: 2 tests, 0 failures/errors/skips; beide entrypoints dekken ready, pending,
  missing, skipped, cancelled, failed, API-fout en head A → B.
- Volledige unitgate `mvn -pl factory-common,softwarefactory -am test`: 520 tests,
  0 failures/errors/skips.
- Quality na implementatie: score 353 = 352 findings + 1 suppressie; delta -1 en geen nieuwe
  suppressie.
- Repositoryzoekactie toont de productie-mergecall alleen in
  `merge.internal.ProjectAwarePullRequestMergeService`; interface en CLI-implementatie zijn
  contract/adapter, geen applicatie-entrypoint.

## Nog uit te voeren

- onafhankelijke reviewer- en testerherhaling;
- commit, push, PR, groene GitHub-checks, merge en post-mergegate;
- volledige overdracht in `VOORTGANG.md` en Factory-storycomment.

## Developer-eindgate vóór reviewcommit

- `mvn verify`: `BUILD SUCCESS` in 2:39 min; 87 Surefire-/Failsafe-rapporten, 637 tests,
  0 failures, 0 errors, 0 skipped.
- Geen productie-aanroep van `mergePullRequest` buiten
  `merge.internal.ProjectAwarePullRequestMergeService`; GitHub-interface en CLI-adapter bevatten
  uitsluitend het contract en de implementatie van de atomische call.
- `git diff --check`: exit 0.
