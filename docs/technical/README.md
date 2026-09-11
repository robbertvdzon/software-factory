# Technische referentie

Actuele ingangspunten:

- [`overview.md`](overview.md) — componenten en hoofdflow;
- [`modules.md`](modules.md) — Maven-/Modulithmodules;
- [`module-dependencies.md`](module-dependencies.md) — toegestane grenzen;
- [`external-systems.md`](external-systems.md) — Runtime, GitHub, database, Telegram en OpenShift;
- [`scheduled-jobs.md`](scheduled-jobs.md) — pollers, reconcilers en cleanup;
- [`endpoints.md`](endpoints.md) — relevante HTTP-/bridge-API's;
- [`../factory/technical-spec.md`](../factory/technical-spec.md) — normatieve technische
  specificatie.

De reactor heeft vier Mavenmodules: `factory-contracts`, `factory-common`, `softwarefactory` en
`dashboard-backend`. `dashboard-frontend` is Flutter. Alle agentuitvoering loopt via Agent Runtime
v2; oude agentworker-, Docker-runner-, resultbestand- en workspacebeschrijvingen zijn historisch en
horen niet in deze actuele referentie.
