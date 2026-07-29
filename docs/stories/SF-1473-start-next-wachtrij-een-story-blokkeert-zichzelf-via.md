# SF-1473 - start-next-wachtrij: een story blokkeert zichzelf via z'n eigen achtergebleven story-run

## Story

start-next-wachtrij: een story blokkeert zichzelf via z'n eigen achtergebleven story-run

Waargenomen op 2026-07-29 met SF-1446 (repo software-factory).

Wat er gebeurde:
- 07:07:43 promootte de orchestrator SF-1446 van `start-next` naar `start`.
- 07:07:44 werd story-run 2193202 aangemaakt met target_repo = https://github.com/robbertvdzon/software-factory.
- 07:11:52 stond SF-1446 weer op `start-next`, maar run 2193202 bleef open (ended_at NULL).

Vanaf dat moment kwam de story niet meer vooruit: `OrchestratorService.promoteQueuedStories` slaat een
repo over zodra `storyRunRepository.activeRunForRepo(targetRepo)` een openstaande run teruggeeft, en
sluit de kandidaat zelf niet uit. De enige openstaande run voor die repo was SF-1446's eigen run, dus de
story blokkeerde haar eigen promotie. Elke poll logde `SF-1446=Skipped(queued-start-next)`; dat lost zich
nooit vanzelf op.

De bestaande vangnetten dekken dit niet:
- `DashboardCommandService.closeDanglingRun` sluit zo'n verweesde run wel (final_status `requeued`), maar
  draait alleen via de handmatige "Queue story"-actie — en die knop is in de UI alleen zichtbaar bij een
  LEGE story-fase (`story_detail_screen.dart`: showQueueStory), dus niet bij `start-next`.
- `warnIfQueueBlockedTooLong` logt pas na 4 uur (OrchestratorSettings.blockedQueueWarnThreshold) en
  deblokkeert niets.
- SF-1453 ging over een verwante maar andere variant (lege story-fase na een onderbroken refining).

Gevraagd:
1. Sluit de kandidaat zijn eigen runs uit bij het bepalen van de blokkerende run in promoteQueuedStories,
   OF sluit de openstaande run af op het moment dat een story naar `start-next` wordt teruggezet — kies wat
   het beste past bij de bedoelde invariant (hoogstens 1 actieve story per repo).
2. Zorg dat een story die naar de wachtrij terugkeert nooit een open story_run achterlaat, ongeacht via
   welk pad dat gebeurt (automatische recovery, niet alleen de handmatige queue-actie).
3. Voeg een regressietest toe die precies dit scenario vastlegt: story in `start-next` met een eigen open
   story_run voor dezelfde repo moet gepromoot worden.

Overweeg ook of de 4-uursdrempel van de blocked-queue-warning niet lager moet, of dat de UI zo'n
geblokkeerde wachtrij zichtbaar moet maken in plaats van alleen een logregel.

## Eindsamenvatting

## Eindsamenvatting SF-1473: start-next-wachtrij blokkeerde zichzelf via eigen achtergebleven story-run

**Probleem:** een story kon in `start-next` blijven hangen doordat de orchestrator bij het promoveren van wachtende stories per doel-repo checkte op een openstaande `story_run` — inclusief een eigen, achtergebleven run van de story zelf (waargenomen bij SF-1446). Bestaande vangnetten losten dit niet automatisch op: de handmatige "Queue story"-actie werkte alleen bij een lege story-fase, en de 4-uurs waarschuwing deblokkeerde niets.

**Wat is gebouwd:**
- `OrchestratorService.promoteQueuedStories` sluit nu, vóórdat de blokkerende run per repo wordt bepaald, eerst alle nog openstaande `story_runs` af die toebehoren aan stories in de huidige `start-next`-batch zelf (nieuwe helper `closeOwnDanglingRuns`, `final_status = requeued`). Dit draait elke pollcyclus, ongeacht via welk pad de story in `start-next` belandde.
- Gekozen aanpak: de eigen dangling run sluiten vóór de bestaande `activeRunForRepo`-check, in plaats van de signatuur van `activeRunForRepo` te wijzigen. Daardoor waren geen aanpassingen nodig aan `StoryRunRepository`, de JDBC-implementatie of `InMemoryStoryRunRepository` — een bewuste scope-beperking t.o.v. de story-beschrijving, die dit expliciet als voorwaardelijk noemde.
- Echt actief werk van ándere stories voor dezelfde repo blijft ongewijzigd blokkeren: alleen runs waarvan de `storyKey` in de huidige batch zit worden gesloten.
- `warnIfQueueBlockedTooLong` (4-uursdrempel) is bewust ongewijzigd gelaten: nu de zelf-blokkade hersteld is, is die drempel weer een redelijk vangnet voor écht andermans werk. Verlagen van de drempel of dit zichtbaar maken in de UI viel buiten de scope van deze fix en is niet opgepakt.
- Regressietest toegevoegd (`QueuedStoryPromotionTest`): een story met eigen open run voor dezelfde repo wordt na `pollOnce()` alsnog gepromoot, en de eigen run wordt afgesloten. Bestaand scenario ("andere story blokkeert echt") blijft gedekt en groen.

**Getest:** reviewer en tester hebben de diff onafhankelijk beoordeeld en goedgekeurd. Volledig vangnet `mvn verify` vanaf de repo-root: BUILD SUCCESS over alle modules, 0 failures/0 errors in alle surefire/failsafe-rapporten (incl. e2e-tests). `tools/audit-documentation` → PASS. De Flutter-frontend viel buiten scope, want de wijziging raakte alleen `softwarefactory/` en het worklog.

**Bewust niet gedaan:** geen wijziging aan de repository-interfaces (niet nodig gebleken), geen verlaging van de 4-uurs warn-drempel, geen UI-zichtbaarheid voor geblokkeerde wachtrijen — deze punten uit de oorspronkelijke story zijn expliciet als out-of-scope beoordeeld voor deze bugfix.
