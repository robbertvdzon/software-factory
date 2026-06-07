# Fase 4 — Subtask-sequencing (keten op subtask-completion)

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md).
> Deze fase staat op zichzelf en kan los gerefined en opgepakt worden.

## Doel

De subtaken van een story **één voor één** afwerken via een **keten** (Optie A),
**zonder de story te pollen**. De story blijft op `PLANNING_APPROVED`; development
speelt zich volledig op subtask-niveau af.

## Mechanisme (Optie A — keten)

- **Start:** ná `PLANNING_APPROVED` zet de **mens** de tag `ai-development` op de
  **eerste** (niet-afgeronde) subtask. Dat is de development-start (Optie B uit
  fase 2). De story krijgt nooit deze tag.
- **Verwerken:** de poller pikt de getagde subtask op → `SubtaskExecutionCoordinator`
  (fase 5) draait z'n pipeline.
- **Advance bij `DONE`:** zodra een getagde subtask `DONE` is, doet de orchestrator:
  1. vind de parent-story (Subtask `INWARD`-link);
  2. bepaal de subtaken in aanmaakvolgorde;
  3. pak de **eerstvolgende niet-afgeronde** subtask en zet daar `ai-development`;
  4. **haal de tag van de afgeronde** subtask af.
  Geen volgende meer → alleen de tag weghalen; de story is klaar (geen story-`DONE`-phase,
  `PLANNING_APPROVED` blijft de markering).
- Hierdoor is er **altijd hooguit één subtask per story getagd** → samen met de
  parent-key-guard (fase 6) draait er nooit meer dan één agent op de gedeelde branch.

## Waar leeft de advance-logica

- **Snelle pad (agent-subtaken):** in de completion-afhandeling
  (`AgentRunCompletionService`) wanneer een subtask-run op `DONE` uitkomt.
- **Poll-pad (robuust + manual + recovery):** de orchestrator behandelt een
  **getagde subtask die al `DONE` is** óók bij een gewone poll als "advance". Dit
  dekt:
  - **manual-subtaken** (geen agent-run, dus geen completion-trigger; de mens zet
    'm op `DONE`, de poll pikt dat op en ketent door);
  - **recovery** (crash ná `DONE` maar vóór het taggen van de volgende: de
    afgeronde subtask draagt de tag nog, de poll ziet `DONE`+tag en ketent
    alsnog — idempotent).

## Aandachtspunten

- Bepaal de "eerstvolgende niet-afgeronde" **dynamisch** (niet cachen). De interne
  fix-loop (fase 5) maakt geen nieuwe subtaken, dus de set ligt vast bij planning,
  maar her-evalueer toch elke keer.
- Volgorde = aanmaakvolgorde van de planner (fase 3); de story-brede review/test
  en de `summary`-subtask staan daardoor vanzelf achteraan.
- De sequencing doet **zelf geen agent-werk**.

## Los testbaar

Met **alleen `manual`-subtaken** is de keten te testen zonder agents: tag de
eerste, zet 'm handmatig op `DONE`, en controleer dat de volgende automatisch
getagd wordt — t/m de laatste.

## Betrokken bestanden

- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/runtime/services/AgentRunCompletionService.kt`
  (advance bij agent-`DONE`)
- `.../orchestrator/services/OrchestratorService.kt` (poll-pad: getagde DONE-subtask → advance)
- `.../youtrack/clients/YouTrackClient.kt` (tag zetten/weghalen, siblings via parent-link)

## Test

- Story met `[development, review, test, summary]`-subtaken: keten loopt in
  volgorde; telkens precies één subtask getagd.
- De `summary`-subtask draait als laatste; daarna is niets meer getagd.
- Manual-subtask: keten loopt door zodra de mens 'm op `DONE` zet.
- Recovery: crash ná `DONE` → volgende subtask wordt bij de eerstvolgende poll
  alsnog getagd (geen dubbel taggen).

## Klaar wanneer

De subtaken van een story worden sequentieel afgewerkt via de keten (één tag
tegelijk), de story blijft op `PLANNING_APPROVED`, en er is geen story-polling of
story-niveau summarizer-agent.
