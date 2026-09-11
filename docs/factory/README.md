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

De huidige app-/bridge-topologie is tijdelijk. De verhuizing van de orchestrator naar OpenShift en
de rename staan in
[`../software-factory-v2/topologie-naar-openshift.md`](../software-factory-v2/topologie-naar-openshift.md)
en vallen buiten de Runtime-refactor.
