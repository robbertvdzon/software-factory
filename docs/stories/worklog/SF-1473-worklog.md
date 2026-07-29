# SF-1473 - Worklog

Story-context bij eerste pickup:
Sluit zelf-blokkade van start-next-wachtrij op en ruim achtergebleven story_runs automatisch op

In OrchestratorService.promoteQueuedStories: sluit bij het bepalen van de blokkerende run per targetRepo de open runs uit die toebehoren aan de start-next-kandidaten van diezelfde repo-groep zelf, zodat een story niet langer door haar eigen achtergebleven run geblokkeerd wordt. Sluit die achtergebleven eigen run(s) automatisch af (final_status 'requeued') voordat de winnaar gepromoot wordt, zodat dit elke pollcyclus opnieuw gebeurt, ongeacht via welk pad de story in start-next belandde (niet alleen via de handmatige 'Queue story'-actie). Pas StoryRunRepository (core/contracts/RunRepositories.kt), de JDBC-implementatie (orchestrator/repositories/RunRepositories.kt) en InMemoryStoryRunRepository (testsupport) aan indien de signatuur van activeRunForRepo wijzigt. Ander, echt actief werk voor dezelfde repo moet nog steeds blokkeren (bestaand gedrag + warnIfQueueBlockedTooLong ongewijzigd). Voeg een regressietest toe in QueuedStoryPromotionTest die precies het scenario uit de story vastlegt: een story in start-next met een eigen open story_run voor dezelfde repo wordt gepromoot, en die eigen run wordt afgesloten.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: run `mvn verify` (volledig vangnet)
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `OrchestratorService.promoteQueuedStories` (orchestrator/services/OrchestratorService.kt) sluit nu
  vóór het bepalen van de blokkerende run per targetRepo eerst alle nog open `story_runs` van de
  huidige `start-next`-batch zelf af (nieuwe private helper `closeOwnDanglingRuns`, final_status
  `requeued`, via de bestaande `storyRunRepository.activeRuns()` + `close(...)`). Daarna wordt
  `activeRunForRepo(targetRepo)` bevraagd zoals voorheen — omdat de eigen run al gesloten is, ziet die
  aanroep 'm vanzelf niet meer, dus geen signatuurwijziging nodig op `activeRunForRepo` (en dus ook
  geen aanpassing van `StoryRunRepository`/`JdbcStoryRunRepository`/`InMemoryStoryRunRepository`
  nodig — de story-beschrijving noemde dat expliciet als "indien de signatuur wijzigt").
  Dit draait elke pollcyclus voor élke story in `start-next`, ongeacht via welk pad (automatische
  recovery zoals SF-1446, of de handmatige "Queue story"-actie die al langer via
  `DashboardCommandService.closeDanglingRun` werkte) — dekt dus punt 2 uit de story (nooit meer een
  open run achterlaten bij terugkeer naar de wachtrij).
  Ander, echt actief werk van ándere stories voor dezelfde repo blijft ongewijzigd blokkeren:
  `closeOwnDanglingRuns` sluit alleen runs waarvan de `storyKey` zelf in de huidige `start-next`-batch
  zit. `warnIfQueueBlockedTooLong` (4-uursdrempel) is ongewijzigd gelaten — nu de zelf-blokkade
  hersteld is, is een 4-uurs WARN voor écht andermans openstaande werk nog steeds een redelijke
  drempel; dit niet verder verlaagd of naar de UI verplaatst, want dat viel buiten de kern van deze
  story (self-blocking-fix) en zou een aparte UX-afweging vergen.
- Regressietest toegevoegd in `QueuedStoryPromotionTest`
  (`poll promotes a queued story that is blocked only by its own dangling run and closes that run`):
  SF-6 staat op `start-next` met een eigen open `story_run` voor dezelfde repo; na `pollOnce()` wordt
  SF-6 alsnog gepromoot naar `start` én is de eigen run gesloten met `final_status = requeued`.
  Bestaande tests (`QueuedStoryPromotionTest`, overige orchestrator-tests) blijven ongewijzigd groen —
  het scenario "andere story blokkeert nog echt" (SF-1 open, SF-3 mag niet promoten) is ongemoeid.
- Documentatie: geen wijziging in `docs/factory/technical-spec.md`/`functional-spec.md` nodig — het
  bestaande `activeRunForRepo`-Kdoc en de bestaande `promoteQueuedStories`-Kdoc in
  `OrchestratorService.kt` beschreven het per-repo-slot-gedrag al correct; de zelf-blokkade was een
  bug in de implementatie, geen documentatie-gat. Wel een Kdoc-blok toegevoegd op de nieuwe
  `closeOwnDanglingRuns`-helper zelf.
- Verificatie: `mvn -o -pl softwarefactory -am test -Dtest=QueuedStoryPromotionTest` groen (4/4).
  Volledig vangnet `mvn verify` vanaf de repo-root: exitcode 0, geen `[ERROR]`/`BUILD FAILURE`-regels
  (Docker was in deze sandbox niet beschikbaar volgens `docker info`, maar de Testcontainers-afhankelijke
  e2e-tests draaiden desondanks mee en slaagden — zie ook bestaande agent-tip
  `sf1047-reverified-no-changes`).

## Review-notities (reviewer, SF-1480)

- Diff nagelopen (`OrchestratorService.closeOwnDanglingRuns` + regressietest + worklog).
  Aanpak dekt de gevraagde invariant (hoogstens 1 actieve story per repo) via de simpelere
  route (sluiten bij elke pollcyclus i.p.v. `activeRunForRepo`-signatuur wijzigen), consistent
  met het bestaande `closeDanglingRun`-patroon in `DashboardCommandService`.
- Zelf herbevestigd: `mvn -o -pl softwarefactory -am test -Dtest=QueuedStoryPromotionTest` →
  4/4 groen (log toont expliciet dat de eigen dangling run van SF-6 gesloten wordt vóór promotie).
  `mvn -o -pl softwarefactory -am test-compile` compileert schoon.
- Geen spec-inconsistenties, geen scope creep, geen blockers. Goedgekeurd.

## Test-notities (tester, SF-1481)

- Diff geverifieerd tegen story-eisen: `OrchestratorService.closeOwnDanglingRuns` sluit vóór het
  bepalen van de blokkerende run per repo alle nog open `story_runs` af waarvan `storyKey` zelf in de
  huidige `start-next`-batch zit (final_status `requeued`), draait elke pollcyclus, ongeacht via welk
  pad de story in `start-next` belandde. Ander, echt actief werk van andere stories voor dezelfde repo
  blijft ongewijzigd blokkeren (`activeRunForRepo`-check ongewijzigd, alleen eigen dangling runs eruit
  gefilterd).
- Nieuwe regressietest `QueuedStoryPromotionTest` ("poll promotes a queued story that is blocked only
  by its own dangling run and closes that run") dekt precies het scenario uit de story: SF-6 op
  `start-next` met eigen open run voor dezelfde repo → na `pollOnce()` gepromoot naar `start` én eigen
  run gesloten met `requeued`. Bestaand scenario "SF-1 open blokkeert SF-3 echt" blijft ongemoeid.
- Volledig vangnet vanaf repo-root: `mvn -B --no-transfer-progress clean verify` → BUILD SUCCESS,
  reactor: factory-contracts/factory-common/softwarefactory/agentworker/softwarefactory-dashboard-backend
  allemaal SUCCESS. Alle surefire/failsafe-rapporten (119 bestanden, incl. e2e) tonen 0 failures/0 errors.
  `tools/audit-documentation` → PASS. dashboard-frontend niet geraakt door de diff (alleen
  `softwarefactory/` + worklog), dus Flutter-vangnet buiten scope (pathPrefixes in
  `.factory/verification.yaml`).
- Akkoord: `tested`.
