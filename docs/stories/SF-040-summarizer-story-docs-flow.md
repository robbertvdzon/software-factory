# SF-040 - Summarizer en story-document flow

## Story

Als product owner wil ik dat agents tijdens ontwikkeling een worklog kunnen
bijhouden, maar dat na een succesvolle tester-run een schoon definitief
story-document wordt geschreven met alleen de actuele YouTrack-story en een
eindsamenvatting. Daarna moet de factory stoppen en wachten op mijn handmatige
test en merge.

## Stappenplan

[x]: voeg summarizer role en AI phases toe
[x]: route tested-successfully naar summarizer en summary-finished naar wachtstand
[x]: schrijf worklogs naar docs/stories/worklog
[x]: schrijf definitieve story na succesvolle summarizer
[x]: werk specs en README procesdiagrammen bij
[x]: draai relevante tests

## Gedaan en waarom

- De phase-flow uitgebreid met `summarizing` en `summary-finished`, zodat
  `tested-successfully` niet langer het eindstation is maar de trigger voor
  de eindsamenvatting.
- Worklogs verplaatst naar `docs/stories/worklog/<key>-worklog.md`, zodat het
  definitieve `docs/stories/<key>-<slug>.md` schoon kan blijven.
- De definitieve story wordt door de factory opgebouwd uit de actuele
  YouTrack-story en de summarizer-output. Daardoor worden PO-wijzigingen die
  tussen agent-runs zijn gedaan meegenomen.
- `mvn -q test` en `git diff --check` zijn succesvol uitgevoerd.
