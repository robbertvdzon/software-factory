# Factory-documentatie

De Software Factory beheert stories in PostgreSQL en stuurt de vaste refine-, plan-, develop-,
review-, test-, summary-, document-, merge- en deployketen aan. Alle AI-uitvoering loopt via Agent
Runtime v2; deze repository bevat geen eigen agentworker meer.

Lees voor actueel gedrag:

- [`functional-spec.md`](functional-spec.md) — wat de factory functioneel doet;
- [`technical-spec.md`](technical-spec.md) — architectuur en Runtime-/Git-protocol;
- [`development.md`](development.md) — bouwen, testen en projectstructuur;
- [`secrets-local.md`](secrets-local.md) — lokale configuratie en secretgrenzen;
- [`../../runbook.md`](../../runbook.md) — bediening en storingen;
- [`../software-factory-v2/VOORTGANG.md`](../software-factory-v2/VOORTGANG.md) — bewijs van de
  Runtime-v2-migratie.

De agentinstructies onder [`agents/`](agents/) zijn bronmateriaal voor de prompts. Targetprojecten
kunnen eigen factorydocumentatie en `.factory/verification.yaml` toevoegen.

De hoofdapp draait als `software-factory-backend` samen met de Flutter-frontend en PostgreSQL op
OpenShift. Agent Runtime v2 blijft een afzonderlijke externe component. Zie
[`../../deploy/README.md`](../../deploy/README.md) voor de actuele deployment.
