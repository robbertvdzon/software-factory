# Technische specificatie

## Systeemgrenzen

De repository bevat vier Mavenmodules en één Flutter-app:

- `factory-contracts`: gedeelde wiretypes;
- `factory-common`: projectconfiguratie en gedeelde integratiecode;
- `softwarefactory`: hoofdapp en domeinworkflow;
- `dashboard-backend`: remote dashboard-API/WebSocketbridge;
- `dashboard-frontend`: Flutter-webclient.

Alle AI- en repositoryjobuitvoering ligt achter Agent Runtime v2. De verwijderde lokale runner,
agentimages, resultbestanden en per-storyworkspaces zijn geen compatibilitypad.

## Hoofdflow

1. De poller of een state-change-event kiest een startbare story/subtaak.
2. `AgentDispatcher` maakt of hervindt de duurzame `agent_runs`-correlatie.
3. `AgentRuntimeV2Adapter` bouwt een Runtime-request uit rol, prompt, projectconfig en attachments.
4. `AgentRoleExecutionConfigService` resolveert `vendorId`, `model` en `mode`.
5. `AgentRuntimeV2HttpClient` maakt idempotent een job; grote input gaat via
   `AgentRuntimeInputUploadService`.
6. `AgentRuntimeV2CompletionPoller` reconcilieert jobstatus, events en resultaat.
7. `AgentRunCompletionService` valideert en publiceert het domeinresultaat en schuift de workflow
   door.

Een procesrestart gebruikt de opgeslagen `runtime_job_id`; hij maakt geen vervangende job als de
bestaande job nog bestaat of terminaal resultaat heeft.

## Runtime-request

Niet-repositoryrollen gebruiken `APPLICATION_WORK`. Gestructureerde rollen gebruiken
`STRUCTURED_GENERATION` met een expliciet JSON-schema. Repositorylezers gebruiken
`REPOSITORY_AGENT` met publicatiemodus `NONE`; developer/documenter gebruiken `REPOSITORY_WORK`,
`REPOSITORY_AGENT` en `COMMIT_AND_PUSH`.

Software Factory geeft voor repositorywerk alleen een geregistreerde alias en bestaande branch
door. Geen URL, checkoutpad, base-branchverwachting, Gitcredential of verwachte input-SHA. De
Runtime controleert aliasbeschikbaarheid bij workerselectie.

Inputattachments worden vooraf gereserveerd, in chunks geüpload en met grootte/SHA gevalideerd.
Resultaten en artifacts worden vóór domeinpublicatie gecontroleerd op schema, declaratie, MIME,
grootte en hash.

## Jobresultaat

De consumer behandelt drie delen afzonderlijk:

- AI-resultaat: het getypeerde rolbesluit;
- `repositoryResult`: checkout, begin-/eind-SHA en publicatie-uitkomst;
- `verificationResult`: configversie, commandresultaten, status, rondes en evidence.

Een geslaagde HTTP-call is geen domeinsucces. Status, repositoryalias, branch, publicatiemodus,
commitbewijs en voor de rol verplichte verificatie moeten allemaal kloppen. `BRANCH_CHANGED`,
ambigu resultaat, ontbrekend bewijs en stale `checkoutCommitSha` falen dicht.

## Git-eigenaarschap

Software Factory:

- resolveert project en base branch;
- reserveert idempotent één remote storybranch;
- maakt idempotent één pull request na de eerste succesvolle push;
- bewaakt checks, preview, approval, merge en deploy.

Agent Runtime-worker:

- maakt per job een verse tijdelijke checkout;
- voert fetch/checkout en deterministische verificatie uit;
- commit/pusht muterend werk na groen bewijs;
- ruimt de checkout op;
- reconcilieert een crash rond publicatie.

AI-proces:

- leest en wijzigt bestanden;
- mag read-only `git status`, `diff` en `log` gebruiken;
- maakt geen branch, commit, push of PR.

De remote storybranch is het enige overdrachtspunt tussen jobs. Er is nooit meer dan één muterende
repositoryjob per story tegelijk.

## Verificatie

`.factory/verification.yaml` bevat versioned argv-commands met optionele pathscope,
working-directory en timeout. De Runtime-worker draait de toepasselijke commands buiten het
AI-proza om. Bij rood bewijs volgt binnen dezelfde job maximaal
`SF_AGENT_RUNTIME_MAX_REPAIR_ATTEMPTS` herstelrondes. Alleen een groene terminale verificatiestatus
staat publicatie toe.

Iedere command draait in een eigen geïsoleerde executioncontainer. Afhankelijke stappen moeten
daarom als één versioned executable worden aangeboden. De Flutter-gate verwijst naar
`tools/verify-dashboard-frontend`, zodat `pub get`, `analyze --no-pub` en `test --no-pub` binnen
dezelfde container en packagecache draaien; de YAML bevat geen impliciete shell-string.

De Software Factory-validator controleert dat het bewijs compleet is en hoort bij
`checkoutCommitSha`. Reviewer/tester op een oudere branchstand kunnen geen fase publiceren.

## Persistence

Belangrijke tabellen:

- `issues`, `issue_comments`, `issue_attachments`: tracker en gebruikersinput;
- `story_runs`: branch, PR, preview en workflowcorrelatie;
- `agent_runs`: rol, gekozen uitvoering, `runtime_job_id`, status, usage en kosten;
- `agent_events`: geprojecteerde Runtime-events voor log/UI;
- completiontabellen: duurzame, geleasede en retrybare domeinpublicatie;
- `agent_role_execution_config`: globale/per-project modelkeuze;
- `agent_runtime_input_uploads`: hervatbare inputoverdracht;
- audit-, Telegram-, knowledge- en maintenance-tabellen.

Historische nullable kolommen zoals `workspace_path`, `container_name`, `level` en oude rate-limit-
velden blijven leesbaar om bestaande data niet te migreren, maar sturen nieuwe dispatch niet.

## Modelconfiguratie

De configuratiesleutel is `(role, project_key)`; een projectoverride wint van de globale rolregel.
De UI valideert keuzen tegen Runtime execution options. De adapter legt de daadwerkelijk gebruikte
vendor, model en mode op de run vast. `aiLevel` en statische `AiRouting` bestaan niet meer.

## Quota, retries, timeout en cancel

Runtime-attempts zijn technische uitvoeringspogingen binnen één job. Software Factory-loopbacks en
retries zijn domeinbeslissingen en blijven apart geteld. Capaciteit/quota wordt naar de bestaande
wachtstatus gemapt. Jobtimeout gaat expliciet in het request; de limiet kan per taaktype/aanroep
verschillen. Cancel wordt aan Runtime doorgegeven en late completion wordt door lease/fencing niet
als succes gepubliceerd.

## Telegram-assistent

`RuntimeAssistantClient` maakt per beurt een structured-generationjob en pollt die synchroon voor
het Telegramantwoord. De begrensde conversatiehistorie wordt in de instructie opgenomen. Foto's
worden tijdelijk gedownload, direct als inputobject geüpload en lokaal verwijderd. De response heeft
een streng schema met `text` en `tips`; usage/kosten komen uit Runtime. Er is geen checkout,
toolmount of providercredential.

## Modulith en afhankelijkheden

De hoofdapp gebruikt Spring Modulith. Root-API's/named interfaces vormen de toegestane grenzen;
implementaties horen in subpackages. `telegram` mag de publieke `runtime`-API gebruiken voor de
assistent. Zie [`../technical/module-dependencies.md`](../technical/module-dependencies.md) en de
architectuurtests.

## Huidige en toekomstige topologie

In de huidige tussenfase draait `softwarefactory` lokaal en verbindt hij uitgaand met
`dashboard-backend` op OpenShift. Frontend en backend zijn al remote. Het verplaatsen van de
orchestrator naar de backend, verwijderen van de socket/bridge, databasemigratie en rename naar
`software-factory-backend`/`software-factory-frontend` wordt uitsluitend uitgevoerd via
[`../software-factory-v2/topologie-naar-openshift.md`](../software-factory-v2/topologie-naar-openshift.md).
