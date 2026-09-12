# Technisch overzicht

## Componenten

- `softwarefactory`: Spring Boot/Modulith-app met tracker, pipeline, Runtime-consumer, audits,
  Telegram, maintenance, de dashboard-API (Google-login) en de Product Factory-integratie. Dit is
  de enige deployable en draait op OpenShift als `software-factory-backend`.
- `dashboard-frontend`: Flutter-webapp (`software-factory-frontend`).
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

De duurzame hoofdappdata gebruikt de opeenvolgende Flyway-migraties `V1`–`V39`.

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

Hoofdapp, frontend en PostgreSQL draaien in namespace `software-factory` op OpenShift; alleen de
Agent Runtime-worker staat buiten de cluster. De verhuizing staat beschreven in
`docs/software-factory-v2/topologie-naar-openshift.md`.
