# Technisch overzicht

## Componenten

- `softwarefactory`: Spring Boot/Modulith-app met tracker, pipeline, Runtime-consumer, audits,
  Telegram en maintenance.
- `dashboard-backend`: Spring Boot API en in de huidige topologie een WebSocketbridge naar de
  lokaal draaiende hoofdapp.
- `dashboard-frontend`: Flutter-webapp.
- PostgreSQL: duurzame tracker-, run-, audit-, Telegram- en configuratiedata.
- Agent Runtime v2: externe jobservice en workers voor AI, tijdelijke checkouts, verificatie,
  artifacts en Gitpublicatie.

## Agentflow

```text
trackerwijziging
  → orchestrator/pipeline
  → AgentDispatcher
  → AgentRuntimeV2Adapter
  → POST Runtime-job / uploads
  → Runtime worker + AI + verificatie + optionele push
  → AgentRuntimeV2CompletionPoller
  → AgentRunCompletionService
  → trackerfase / vraag / fout / volgende stap
```

De factory bewaart `runtime_job_id` voor herstel na restart. Runtime-events worden naar
`agent_events` geprojecteerd voor de bestaande UI. AI-resultaat, repositoryresultaat en
verificatieresultaat blijven afzonderlijk gevalideerd.

## Repositoryflow

De factory maakt per story één remote branch vanaf de base branch en na de eerste succesvolle push
één PR. Iedere Runtime-job haalt de branch schoon op. De worker doet checkout, verificatie, commit
en push; de AI-agent doet geen muterende Git-acties. Een vervolgstap deelt geen filesystem en leest
de actuele branch opnieuw.

Reviewer- en testerbewijs is gebonden aan `checkoutCommitSha`. `BRANCH_CHANGED`, verkeerde alias,
verkeerde branch, rood bewijs of een ambigue publicatie faalt dicht. De PR blijft de drager van
checks en preview en wordt door de factory gemerged.

## Uitvoeringstypen

- refiner/planner/summarizer/Telegram: `APPLICATION_WORK`, doorgaans structured generation;
- developer/documenter: `REPOSITORY_WORK` + `REPOSITORY_AGENT` + `COMMIT_AND_PUSH`;
- reviewer/tester/auditor: read-only repositoryjob met publicatiemodus `NONE`.

De modelkeuze is databasegestuurd per rol en optioneel per project. Runtime beheert provider-
credentials, attempts, leases, execution image, transcript, usage en kosten.

## Topologie

Frontend en dashboard-backend draaien al op OpenShift; de hoofdapp draait in de huidige tussenfase
nog lokaal en verbindt uitgaand via WebSocket. De aparte vervolgstap verhuist de hoofdappfunctionaliteit
naar OpenShift, verwijdert de bridge en voert de rename uit. Dat werk staat in
`docs/software-factory-v2/topologie-naar-openshift.md`.
