# Modules

## Mavenreactor

| Module | Verantwoordelijkheid |
| --- | --- |
| `factory-contracts` | Wiretypes die hoofdapp en dashboard-backend delen. |
| `factory-common` | Projectcatalogus, configuratie, GitHub-/previewprimitives en gedeeld domein. |
| `softwarefactory` | Tracker, workflow, Runtime-consumer, Telegram, audits en maintenance. |
| `dashboard-backend` | Dashboard-API, login en huidige WebSocketbridge. |

`dashboard-frontend` gebruikt een eigen Flutterbuild. Een lokale agentworker is geen module meer.

## Spring Modulith in `softwarefactory`

- `core`: domeintypes en centrale poorten;
- `config`: configuratie en composition root;
- `tracker`: issues, comments en attachments;
- `orchestrator`: polling, caps, pauzes en handmatige acties;
- `pipeline`: story-/subtaskfaseovergangen en dispatch;
- `runtime`: Runtime-v2-adapter, completion, events en retentie;
- `github`/`merge`: branch, PR, checks en merge;
- `preview`: previewstatus en cleanup;
- `audit`: auditplanning, resultaat en vervolgstory;
- `telegram`: meldingen, vragen en Runtime-assistent;
- `knowledge`: herbruikbare agenttips;
- `maintenance`: release-/packagecleanup en log;
- `dashboard`/`bridge`/`web`: projectie en externe adapters;
- `verification`: verificatieconfig- en evidencevalidatie;
- `support`: technische leafprimitives.

Publieke module-API's staan in de moduleroot of named interfaces. Concrete implementaties horen in
subpackages. De architectuurtests bewaken de dependencyallowlist en module-API-conventie.

## Runtime-module

`runtime/v2` bevat:

- HTTP-contracten/client;
- requestadapter en prompt-/inputobjectopbouw;
- databasegestuurde executionkeuze;
- completionpoller en statusmapping;
- artifact-, repository- en verificatievalidatie.

Legacy databasevelden blijven soms in modellen voor bestaande rijen, maar er is geen
Docker-runtime, agentworkspace of result-file-completionpad meer.

## Dashboardgrens

`dashboard-backend` is nu nog een remote makelaar: de hoofdapp levert state via de bridge. Het is
niet een tweede frontend. `dashboard-frontend` is de enige webclient. Na het topologieplan worden
backend/orchestrator samengevoegd en later hernoemd.
