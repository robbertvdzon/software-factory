# Fase 4 — StoryDevelopmentCoordinator (subtaken sequencen)

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md).
> Deze fase staat op zichzelf en kan los gerefined en opgepakt worden.

## Doel

Op story-niveau de subtaken **één voor één** afwerken en, als alles klaar is, de
story afronden.

## Wijzigingen

- **`StoryDevelopmentCoordinator`** introduceren. Op `StoryPhase.DEVELOPING`:
  - vind de subtaken van de story;
  - pak de eerste die niet `done` is, en zet die in de juiste actieve
    `SubtaskPhase` afhankelijk van het type (development/review/test/manual);
  - poll tot die subtask `done` is, advance dan naar de volgende;
  - als **alle** subtaken `done` zijn → `SUMMARIZING` → `DONE` (hergebruik de
    bestaande summarizer op story-niveau).

## Aandachtspunten

- Sequentieel is hier ook een **noodzaak**: alle subtaken delen één branch, dus er
  kan er maar één tegelijk draaien. De bestaande `isAnyAgentRunningForStory`-guard
  dekt dit af.
- Volgorde van subtaken: respecteer de volgorde waarin de planner ze aanmaakte
  (fase 3), tenzij je later expliciete ordering toevoegt.
- Houd er rekening mee dat fase 5 dynamisch nieuwe subtaken kan toevoegen
  (findings-loopback) — de coördinator moet bij elke poll opnieuw de
  niet-afgeronde subtaken bepalen, niet een eenmalige lijst cachen.

## Betrokken bestanden

- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/orchestrator/services/OrchestratorService.kt`
  (+ nieuwe `StoryDevelopmentCoordinator`)

## Test

- Story met `[development, review, test]`-subtaken doorloopt ze in volgorde.
- De summarizer draait pas na de laatste subtask.
- Een tussentijds toegevoegde subtask wordt meegenomen vóór afronding.

## Klaar wanneer

Een story op `DEVELOPING` werkt z'n subtaken sequentieel af en gaat daarna
automatisch naar `SUMMARIZING` → `DONE`.
