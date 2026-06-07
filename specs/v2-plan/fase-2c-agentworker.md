# Fase 2c — Agentworker: refiner + planner emitten `Story Phase` + `--type=planner`

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md)
> en de fase-2 overview: [fase-2-refinement-loskoppelen.md](./fase-2-refinement-loskoppelen.md).
> Bouwt voort op [fase-2a](./fase-2a-refine-orchestrator.md) + [fase-2b](./fase-2b-plan-orchestrator.md).

## Doel

De **agentworker**-kant: de echte refiner- en planner-agents laten samenwerken met
de orchestrator-flow uit 2a/2b, zodat een story **end-to-end** door
refine → plan loopt. Tot nu toe leverden de test-fakes de status-waarden; nu doet
de agent dat.

## Wijzigingen

- **`--type=planner`** als agent-type toevoegen (`agentworker` CLI + routing):
  - de orchestrator dispatcht al rol `PLANNER` (2b); de agentworker moet dat type
    herkennen en de juiste flow/prompt draaien.
- **Refiner emit `Story Phase`-waarden** in `agent-result.json`:
  - klaar zonder vragen → `refined`;
  - klaar met vragen → `refined-with-questions` (vragen als comment op de story).
- **Planner-agent** produceren:
  - maakt het **implementatieplan in de story-body** (geen subtaken — dat is fase 3);
  - emit `planned` (klaar) of `planned-with-questions` (vragen).
- **Prompts** per rol aanscherpen: refiner = story-tekst verbeteren + vragen
  (consistentie met specs, acceptatiecriteria, risico-analyse, geraakte modules);
  planner = implementatieplan.
- Bij `*-rejected`/`*-questions-answered`-herdispatch leest de agent de
  mens-feedback/antwoorden uit de story-comments en/of de aangepaste description.

## Aandachtspunten

- De agent bepaalt de vervolgstatus (`agent-result.json.phase`); de
  completion-handler (2a/2b) schrijft die naar `Story Phase`. Zorg dat de
  ge-emitte waarden exact de `StoryPhase`-trackerValues zijn.
- Supplier-keuze (claude/openai/copilot/mock) en model/effort komen uit de
  issue-velden (fase 0); de planner mag een lichter model gebruiken dan de refiner.

## Betrokken bestanden

- `agentworker/.../cli/AgentCli.kt` + `agent/AiClient.kt` (`--type=planner` routing)
- `agentworker/.../flows/` (refiner-flow → Story Phase; nieuwe planner-flow)
- prompt-bronnen voor refiner/planner

## Test

- E2e: een story doorloopt `refining → refined → (approve) → planning → planned →
  (approve) → planning-approved` met de echte agents (of een realistische harness).
- Refiner met vragen → `refined-with-questions`; na `questions-answered` → opnieuw.
- Planner schrijft een plan in de story-body en emit `planned`.

## Klaar wanneer

Een story draait end-to-end door refine → plan met de echte refiner/planner-agents;
de ge-emitte statussen sturen de orchestrator-flow uit 2a/2b correct aan.
