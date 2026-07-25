# SF-1281 - Worklog

Story-context bij eerste pickup:
Developer-escalatie-contract en PO-voorrangsregel in agent-prompts

In agentworker/src/main/kotlin/nl/vdzon/softwarefactory/agent/ai/shared/AgentPromptContracts.kt: (1) RolePrompts.developerPrompt() uitbreiden met de escalatie-instructie (niet stilzwijgend uitvoeren/negeren bij onenigheid met review/test-bevinding of scope-twijfel; stoppen en escaleren naar PO) en het JSON-fasecontract {"phase":"developed"} / {"phase":"developed-with-questions","questions":[...]}; (2) optioneel retryExample() voor AgentRole.DEVELOPER aanpassen naar deze developed/developed-with-questions-variant; (3) AgentPromptBuilder.systemPrompt() een regel toevoegen dat PO-antwoorden in de issue-comments voorrang hebben boven de refined story/description, met ruimte voor vervolgvragen via het rol-specifieke -with-questions-contract. In docs/factory/agents/developer.md dezelfde strekking (fasecontract + escalatie-instructie) toevoegen, consistent met de tekst in AgentPromptContracts.kt. Voeg een unit-test toe (nieuw of bestaand testbestand) die aantoont dat de developer-prompt de developed-with-questions-optie bevat. Geen wijzigingen aan SubtaskPhase, AgentRole, AgentOutcomeParser.mapPhase of SubtaskExecutionCoordinator - die plumbing bestaat al en accepteert developed-with-questions. Draai mvn verify vanaf de repo-root en zorg voor exitcode 0, 0 failures, 0 errors.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## SF-1282 (development-subtaak)

- `agentworker/.../agent/ai/shared/AgentPromptContracts.kt`:
  - `RolePrompts.developerPrompt()` uitgebreid met de escalatie-instructie (bij onenigheid met
    een review-/test-bevinding of scope-twijfel: niet stilzwijgend uitvoeren of negeren, maar
    stoppen en escaleren naar de PO) en het expliciete JSON-fasecontract
    `{"phase":"developed"}` / `{"phase":"developed-with-questions","questions":[...]}`.
  - `retryExample()` voor `AgentRole.DEVELOPER` aangepast van het generieke `{"phase":"..."}`
    naar de developed/developed-with-questions-variant (optionele acceptatiecriterium-tweak).
  - `AgentPromptBuilder.systemPrompt()` kreeg een gedeelde regel (voor alle AI-rollen) dat
    PO-antwoorden in de issue-comments leidend zijn boven de refined story/description, met
    ruimte voor vervolgvragen via het rol-specifieke `-with-questions`-contract.
  - `SubtaskPhase`, `AgentRole`, `AgentOutcomeParser.mapPhase` (accepteerde
    `developed-with-questions` al) en `SubtaskExecutionCoordinator` zijn niet gewijzigd, conform
    de scope-beperking in de story.
- `docs/factory/agents/developer.md`: dezelfde strekking toegevoegd (escalatie-instructie +
  PO-voorrangsregel + het JSON-fasecontract), consistent met de tekst in
  `AgentPromptContracts.kt`.
- Nieuwe test `agentworker/src/test/kotlin/.../agent/AgentPromptContractsTest.kt`: toont aan dat
  de developer-prompt zowel `developed` als `developed-with-questions` bevat, dat de developer-
  prompt een escalatie-instructie naar de PO bevat, dat de gedeelde system-prompt voor alle
  AI-rollen de PO-voorrangsregel bevat, dat de retry-reminder voor DEVELOPER de nieuwe varianten
  toont, en dat `AgentOutcomeParser` `developed-with-questions` voor de developer mapt.
- Geen wijzigingen aan `docs/factory/functional-spec.md`/`technical-spec.md`/UX-docs nodig: dit
  betreft alleen agent-prompttekst, geen gedrag/architectuur dat daar beschreven staat.
- Bewijs: `mvn verify` vanaf de repo-root — `BUILD SUCCESS`, alle modules (factory-contracts,
  factory-common, softwarefactory, agentworker, softwarefactory-dashboard-backend) groen,
  inclusief de softwarefactory e2e-/Testcontainers-tests. 0 failures, 0 errors.
