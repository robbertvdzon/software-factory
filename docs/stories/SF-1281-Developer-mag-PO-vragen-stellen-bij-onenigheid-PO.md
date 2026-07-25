# SF-1281 - Developer mag PO-vragen stellen bij onenigheid + PO-antwoorden zijn leidend boven refined story

## Story

Developer mag PO-vragen stellen bij onenigheid + PO-antwoorden zijn leidend boven refined story

<!-- refined-by-factory -->

## Scope
Twee prompt-aanpassingen in de agent-flow (geen wijzigingen aan enums, state machine of coordinator; de vragen-poort en antwoord-context bestaan al volledig).

### 1. Developer mag stoppen en de PO een vraag stellen
Voeg op beide plekken (zelfde strekking) toe:
- `agentworker/src/main/kotlin/nl/vdzon/softwarefactory/agent/ai/shared/AgentPromptContracts.kt`, functie `RolePrompts.developerPrompt()`.
- `docs/factory/agents/developer.md`.

Regel: als de developer het oneens is met een review-/test-bevinding of iets buiten scope acht, mag hij dat niet stilzwijgend uitvoeren en niet stilzwijgend negeren, maar stopt hij en escaleert naar de PO. Neem het ontbrekende JSON-fasecontract op, consistent met de andere rollen (reviewer/tester/etc.): exact 1 JSON-object op de laatste regel — `{"phase":"developed"}` (klaar) of `{"phase":"developed-with-questions","questions":["vraag 1"]}` (stop + vraag aan PO).

Optioneel: pas `retryExample()` voor `AgentRole.DEVELOPER` in `AgentPromptContracts.kt` aan naar de developed/developed-with-questions-variant in plaats van het generieke `{"phase":"..."}`.

### 2. PO-antwoorden zijn leidend boven de refined story
Voeg een regel toe aan de gedeelde system-prompt (`AgentPromptBuilder.systemPrompt` in dezelfde file), zodat die voor alle AI-rollen geldt: antwoorden van de PO in de issue-comments (al aanwezig in de task-context onder '### Relevant Issue Comments') zijn leidend en gaan voor de story-description/refinement waar ze botsen. Roept een PO-antwoord een vervolgvraag op, dan mag de agent die opnieuw stellen via het bijbehorende rol-specifieke `...-with-questions`-contract.

## Acceptance criteria
- De developer-prompt (zowel in `AgentPromptContracts.kt` als in `docs/factory/agents/developer.md`) noemt expliciet het JSON-fasecontract met de opties `developed` / `developed-with-questions`, én de instructie om bij onenigheid met reviewer/tester of scope-twijfel te escaleren naar de PO in plaats van stilzwijgend door te gaan of te negeren.
- De gedeelde system-prompt (`AgentPromptBuilder.systemPrompt`) bevat een regel dat PO-antwoorden in de issue-comments voorrang hebben op de refined story, en dat vervolgvragen zijn toegestaan via het rol-specifieke `-with-questions`-contract.
- `SubtaskPhase`, `AgentRole`, `AgentOutcomeParser.mapPhase` en `SubtaskExecutionCoordinator` blijven ongewijzigd (behalve de optionele `retryExample`-tweak voor DEVELOPER).
- Bestaande tests blijven groen; er wordt een test toegevoegd (of aangevuld) die aantoont dat de developer-prompt de `developed-with-questions`-optie bevat.
- Volledig testvangnet uit `docs/factory/development.md` draait groen (exitcode 0, 0 failures, 0 errors).

## Aannames
- Alleen prompt-/roldoc-tekst plus optioneel de `retryExample`-aanpassing; het gedrag van de bestaande vragen-loop (plumbing) blijft ongewijzigd.
- Voor niet-silent stories geldt de escalatie als 'wacht op PO'; voor silent/nightly-stories blijft het bestaande gedrag (clarification-error) gehandhaafd.
- Er bestaat nog geen dedicated testbestand voor `AgentPromptContracts`/`RolePrompts`; de developer maakt er zo nodig één aan (of breidt een bestaand relevant testbestand uit) om de acceptatiecriteria aantoonbaar te maken.

## Eindsamenvatting

Ik heb voldoende context uit `.task.md` en het worklog. De implementatie, review en test zijn alle drie afgerond en groen. Ik stel nu de eindsamenvatting op.

**Wat gebouwd is:** Twee prompt-aanpassingen in `agentworker/.../AgentPromptContracts.kt`: (1) de developer-prompt kreeg een expliciete escalatie-instructie (bij onenigheid met een review-/test-bevinding of scope-twijfel niet stilzwijgend uitvoeren of negeren, maar stoppen en de PO een vraag stellen) plus het ontbrekende JSON-fasecontract `{"phase":"developed"}` / `{"phase":"developed-with-questions","questions":[...]}`, consistent met de andere rollen; (2) de gedeelde `systemPrompt()` (geldt voor alle AI-rollen) kreeg de regel dat PO-antwoorden in de issue-comments leidend zijn boven de refined story/description, met ruimte voor vervolgvragen via het rol-specifieke `-with-questions`-contract. Dezelfde strekking is toegevoegd aan `docs/factory/agents/developer.md`. Als optionele meegenomen verbetering is ook `retryExample()` voor `AgentRole.DEVELOPER` omgezet van het generieke `{"phase":"..."}` naar de specifieke developed/developed-with-questions-variant.

**Wat getest is:** Nieuwe unit test `AgentPromptContractsTest.kt` toont aan dat de developer-prompt beide phase-opties en de escalatie-instructie bevat, dat de gedeelde system-prompt de PO-voorrangsregel bevat voor alle rollen, en dat `AgentOutcomeParser` `developed-with-questions` correct mapt. Het volledige testvangnet (`mvn clean verify` vanaf de repo-root) is groen: alle modules (factory-contracts, factory-common, softwarefactory, agentworker, dashboard-backend) SUCCESS, 0 failures, 0 errors, inclusief de e2e-/Testcontainers-tests.

**Bewust niet gedaan:** `SubtaskPhase`, `AgentRole`, `AgentOutcomeParser.mapPhase` en `SubtaskExecutionCoordinator` zijn ongewijzigd gelaten, conform de scope-beperking — die plumbing ondersteunde `developed-with-questions` al. Er waren geen relevante wijzigingen aan `functional-spec.md`/`technical-spec.md`/UX-docs nodig; `dashboard-flutter-*`-verificatiecommando's zijn niet uitgevoerd omdat er geen wijzigingen onder `dashboard-frontend/` zaten.

Nu wordt de samenvatting weggeschreven naar `docs/stories/<issue-key>-<korte-omschrijving>.md` door de factory zelf; ik heb hierboven geen implementatiebestanden gewijzigd.
