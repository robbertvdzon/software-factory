# Modules

## Mavenreactor

| Module | Verantwoordelijkheid |
| --- | --- |
| `factory-common` | Projectcatalogus, configuratie, GitHub-/previewprimitives en gedeeld domein. |
| `softwarefactory` | Tracker, workflow, Runtime-consumer, Telegram, audits en maintenance. |

`softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory` is de hoofdapp met 19 directe packages. De
eerste in alfabetische volgorde zijn `audit`, `config`, `core`, `dashboard`, `docs`; de
Modulithmodules hieronder vormen daarbinnen de bewaakte architectuurgrenzen.

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
- `dashboard`/`web`: projectie, dashboard-API en Product Factory-integratie;
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

De `web`-module van de hoofdapp levert de dashboard-API rechtstreeks uit de dashboard-poorten.
`dashboard-frontend` is de enige webclient.
