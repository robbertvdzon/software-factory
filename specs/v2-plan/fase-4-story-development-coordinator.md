# Fase 4 — StoryDevelopmentCoordinator (subtaken sequencen)

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md).
> Deze fase staat op zichzelf en kan los gerefined en opgepakt worden.

## Doel

Op story-niveau de subtaken **één voor één** afwerken en, als alles klaar is, de
story afronden.

## Wijzigingen

- **`StoryDevelopmentCoordinator`** introduceren, **getriggerd door de tag
  `ai-development`** op de story (niet door een story-phase — die bestaat niet voor
  development; de mens zet de tag zelf na `PLANNING_APPROVED`, Optie B). Bij elke
  poll:
  - bepaal opnieuw de subtaken die nog niet `DONE` zijn (niet cachen!);
  - pak de eerste niet-afgeronde subtask in aanmaakvolgorde en geef die de tag
    **`ai-development`** (zodat de `SubtaskExecutionCoordinator` 'm oppikt);
  - poll tot die subtask `DONE` is, advance dan naar de volgende;
  - als **alle** subtaken `DONE` zijn → de story is klaar: **verwijder de
    `ai-development`-tag** van de story zodat 'ie uit de werk-set valt. Er is géén
    story-`DONE`-phase en géén story-niveau summarizer — de summary is de laatste
    subtask (type `summary`, SUMMARIZER-rol). De coördinator doet zelf geen
    agent-werk.

## Aandachtspunten

- Sequentieel is hier ook een **noodzaak**: alle subtaken delen één branch, dus er
  kan er maar één tegelijk draaien. De `isAnyAgentRunningForStory`-guard (op
  **parent-key**, fase 6) dekt dit af — die moet live zijn vóór fase 5 echt
  subtask-agents draait.
- **Recompute per poll:** de interne fix-loop (fase 5) verandert geen aantal
  subtaken, maar de manual-verify/vragen-loops kunnen de timing beïnvloeden;
  bepaal de niet-afgeronde set dus elke keer opnieuw i.p.v. een eenmalige lijst.
- Volgorde: respecteer de aanmaakvolgorde van de planner (fase 3). De
  story-brede review/test staan daardoor vanzelf achteraan.
- `manual`-subtaken hebben geen agent; de coördinator wacht tot de mens 'm op
  `DONE` zet (label/veld) voordat 'ie doorgaat.

## Los testbaar zonder fase 5

Sequencing kun je onafhankelijk valideren met **alleen `manual`-subtaken**: die
vereisen geen agent, dus de coördinator-logica (volgende pakken, summarize na de
laatste) is te testen vóór de `SubtaskExecutionCoordinator` bestaat.

## Betrokken bestanden

- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/orchestrator/services/OrchestratorService.kt`
  (+ nieuwe `StoryDevelopmentCoordinator`)
- `.../youtrack/clients/YouTrackClient.kt` (label per subtask zetten)

## Test

- Story met `[development, review, test]`-subtaken doorloopt ze in volgorde.
- Story met alleen `manual`-subtaken: sequencing + summarize werkt zonder agents.
- De `summary`-subtask draait als laatste subtask; daarna gaat de story → `DONE`.

## Klaar wanneer

Een story met tag `ai-development` werkt z'n subtaken sequentieel af (tag per
subtask, `summary` als laatste). Als alles klaar is verwijdert de coördinator de
`ai-development`-tag; er is geen story-`DONE`-phase en geen story-niveau
summarizer-agent.
