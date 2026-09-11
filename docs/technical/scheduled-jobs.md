# Scheduled jobs en achtergrondwerk

## Orchestrator

De orchestrator pollt als veiligheidsnet op `SF_POLL_INTERVAL_MS` en wordt daarnaast direct gewekt
door trackerwijzigingen. Hij kiest startbare stories/subtaken en respecteert rol- en totaalcaps.

## Runtime completion

`AgentRuntimeV2CompletionPoller` reconcilieert niet-terminale `runtime_job_id`'s op
`SF_AGENT_RUNTIME_POLL_MS`. Hij haalt Runtime-status/events op en publiceert terminale resultaten
via de durable completionlaag. Een herstart hervat dezelfde jobcorrelatie.

De durable completioncoördinator gebruikt leases, retry/backoff en fencing. Hierdoor kan een late
of dubbele callback geen tweede domeintransitie publiceren.

## Deployreconciliatie

`StoryDeployReconciler` controleert actieve deploymenttargets en schuift een story pas door wanneer
alle toepasselijke doelen gezond zijn. Preview/deploy is Factory-domeinlogica, geen Runtime-jobflow.

## Audits

`AuditScheduler` plant read-only Runtime-jobs volgens globale en projectspecifieke instellingen.
De minst recent uitgevoerde audit komt aan de beurt. Handmatig gestarte audits gebruiken dezelfde
pipeline. Een job kan eindigen met rapport, vraag of fout.

## Telegram

De Telegram-poller verwerkt updates; `TelegramResultNotifyPoller` levert duurzame notificaties en
herstelt na tijdelijke API-fouten. De conversationele assistent pollt alleen de eigen Runtime-job
binnen de request/threadafhandeling en ondersteunt cancel via `/stop`.

## Kosten en quota

`CostMonitorPoller` bewaakt op `SF_COST_MONITOR_INTERVAL_MS` de ingestelde budget-/creditgrenzen.
Runtimequota of ontbrekende capaciteit wordt op een zichtbare wachtstatus gemapt. De originele
Runtime-job/attempt blijft van de domeinretry onderscheiden.

## Retentie en cleanup

- `AgentEventRetentionPoller`: oude `agent_events` in begrensde batches;
- `AgentRunRetentionPoller`: oude terminale `agent_runs`; actieve/onafgeronde completion blijft;
- completionpayloadcleanup: verwerkte inboxpayloads na retentie;
- `WorkCleanupPoller`: achtervang voor overige tijdelijke bestanden onder `work/`; er zijn geen
  actieve agent- of storyworkspaces meer;
- `MaintenanceCleanupScheduler`: GitHubreleases en packageversies volgens `projects.yaml`;
- `RecentCommitsPoller`: recente commitprojectie voor het dashboard.

Cleanupresultaten worden fail-soft vastgelegd in `maintenance_cleanup_runs`. Een fout in logging mag
de eigenlijke cleanup niet terugdraaien. Destructieve cleanup gebruikt waar mogelijk een apart,
minimaal gescopeerd token of kubeconfig.

## Operationele regel

Pollers mogen hetzelfde werk opnieuw zien. Daarom moeten jobcreate, branch/PR-aanmaak, completion en
fasepublicatie idempotent of fenced zijn. Een scheduler mag nooit een nieuwe Runtime-job maken
alleen omdat de vorige HTTP-response verloren ging.
