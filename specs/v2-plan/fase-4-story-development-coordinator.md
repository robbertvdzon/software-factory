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
- **Advance bij eindstatus:** een subtask is "klaar" als 'ie z'n terminale
  `*-approved` (`review-approved`/`test-approved`/`summary-approved`) of
  `manual-action-done` bereikt. Zodra de poller dat ziet:
  1. vind de parent-story (Subtask `INWARD`-link);
  2. bepaal de subtaken in aanmaakvolgorde;
  3. pak de **eerstvolgende niet-afgeronde** subtask en zet daar `ai-development`;
  4. **haal de tag van de afgeronde** subtask af.
  Geen volgende meer → alleen de tag weghalen; de story is klaar (geen story-`DONE`-phase,
  `PLANNING_APPROVED` blijft de markering).
- Hierdoor is er **altijd hooguit één subtask per story getagd** → samen met de
  parent-key-guard (fase 6) draait er nooit meer dan één agent op de gedeelde branch.

## Waar leeft de advance-logica

De terminale status (`*-approved` / `manual-action-done`) wordt door de **mens** gezet (of
auto-approve), niet door een agent die klaar is. De keten is daarom
**poll-gedreven**:

- **Poll-pad (primair):** de `OrchestratorPoller` behandelt een **getagde subtask
  die z'n eindstatus heeft** als "advance" (tag volgende, haal eigen tag weg).
  Idempotent, en dekt alle gevallen:
  - **AI-subtaken** (de mens keurt de laatste stap goed → poller ketent);
  - **manual-subtaken** (geen agent-run; mens zet `manual-action-done` → poller ketent);
  - **recovery** (crash ná goedkeuring maar vóór het taggen: de afgeronde subtask
    draagt de tag nog, de poll ziet eindstatus+tag en ketent alsnog).
- **Snelle pad (alleen bij auto-approve):** als de laatste stap op auto-approve
  staat, kan de completion-handler de afronding + keten meteen doen i.p.v. te
  wachten op de volgende poll.

## Aandachtspunten

- **Subtaken erven de AI-supplier van de story** (README §7). Vereist voor fase 4: de
  `processIssue`-supplier-check staat vóór de router, dus een subtask zónder supplier
  wordt overgeslagen en bereikt de keten/uitvoering nooit. Daarom zet `createSubtask`
  (fase 3) `AI-supplier` = die van de parent. Model/effort per subtask blijven
  optioneel (planner).
- Bepaal de "eerstvolgende niet-afgeronde" **dynamisch** (niet cachen). De interne
  fix-loop (fase 5) maakt geen nieuwe subtaken, dus de set ligt vast bij planning,
  maar her-evalueer toch elke keer.
- Volgorde = aanmaakvolgorde van de planner (fase 3); de story-brede review/test
  en de `summary`-subtask staan daardoor vanzelf achteraan.
- De sequencing doet **zelf geen agent-werk**.

## Los testbaar

Met **alleen `manual`-subtaken** is de keten te testen zonder agents: tag de
eerste, zet 'm handmatig op `manual-action-done`, en controleer dat de volgende
automatisch getagd wordt — t/m de laatste.

## Betrokken bestanden

- `.../orchestrator/services/OrchestratorService.kt` (poll-pad: getagde subtask in
  eindstatus → tag volgende sibling, haal eigen tag weg)
- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/runtime/services/AgentRunCompletionService.kt`
  (alleen bij auto-approve: afronding + keten meteen)
- `.../youtrack/clients/YouTrackClient.kt` (tag zetten/weghalen, siblings via parent-link)

## Test

- Story met `[development, review, test, summary]`-subtaken: keten loopt in
  volgorde; telkens precies één subtask getagd.
- De `summary`-subtask draait als laatste; daarna is niets meer getagd.
- Manual-subtask: keten loopt door zodra de mens 'm op `manual-action-done` zet.
- Recovery: crash ná de eindstatus → volgende subtask wordt bij de eerstvolgende
  poll alsnog getagd (geen dubbel taggen).

## Klaar wanneer

De subtaken van een story worden sequentieel afgewerkt via de keten (één tag
tegelijk), de story blijft op `PLANNING_APPROVED`, en er is geen story-polling of
story-niveau summarizer-agent.
