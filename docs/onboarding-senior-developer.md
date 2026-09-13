# Onboarding voor senior developers

## Het mentale model

Software Factory is een workflow-orchestrator, geen AI-runner. De hoofdapp bepaalt welk domeinwerk
nodig is; Agent Runtime v2 voert één technische job uit.

```text
tracker/state-change
        ↓
orchestrator + pipeline
        ↓
AgentDispatcher → AgentRuntimeV2Adapter
        ↓
Agent Runtime v2 job
        ↓
completion polling + validatie
        ↓
AgentRunCompletionService
        ↓
volgende fase / vraag / fout / merge / deploy
```

Voor repositorywerk is de remote storybranch het enige gedeelde geheugen. Elke job krijgt een verse
Runtime-checkout. Er is geen lokale `agentworker`, Docker-agentcontainer, resultbestand of gedeelde
storyworkspace meer.

## Waar begin je in de code?

1. `orchestrator/schedulers/OrchestratorPoller.kt`: kiest werk en reageert op wake-events.
2. `pipeline/service/StoryRefinementCoordinator.kt`: storyfasen refine/plan.
3. `pipeline/service/SubtaskExecutionCoordinator.kt`: subtaskfasen en loopbacks.
4. `pipeline/service/AgentDispatcher.kt`: maakt de duurzame agentrun en dispatchrequest.
5. `runtime/v2/AgentRuntimeV2Adapter.kt`: vertaalt het domeinrequest naar Runtime v2.
6. `runtime/v2/AgentRuntimeV2CompletionPoller.kt`: reconcilieert jobs.
7. `runtime/services/AgentRunCompletionService.kt`: valideert/persisteert resultaat en beweegt de
   workflow.
8. `github/` en `merge/`: branch, PR, checks en merge.
9. `dashboard/`, `telegram/`, `audit/`, `maintenance/`: gebruikers- en beheercapabilities.

## Domein versus Runtime

Software Factory is eigenaar van:

- story/subtaskfasen, vragen en approvals;
- rol- en modelkeuze;
- één branch/PR per story;
- loopbackcaps, mergegates, preview en deploy;
- Product Factory- en Telegramcontracten.

Agent Runtime is eigenaar van:

- workerselectie, lease, attempt en technische retry;
- execution image en providercredential;
- tijdelijke checkout en Gitpublicatie;
- verificatierondes binnen de job;
- events, transcript, artifacts, usage en kosten.

Voeg geen storykennis aan Runtime toe en bouw geen uitvoeringsdetail terug in Software Factory.

## Het repositoryprotocol

1. Factory maakt idempotent een remote branch vanaf de project-basebranch.
2. Een muterende job ontvangt alleen repositoryalias, bestaande branch en `COMMIT_AND_PUSH`.
3. Runtime-worker checkt vers uit en geeft de worktree aan de AI-agent.
4. De AI-agent wijzigt bestanden maar commit/pusht niet.
5. Runtime controleert Gitmetadata, draait `.factory/verification.yaml`, laat maximaal de ingestelde
   herstelrondes uitvoeren en pusht alleen bij groen.
6. Factory verwerkt AI-resultaat, `repositoryResult` en `verificationResult` apart.
7. Na de eerste push maakt Factory één PR. Volgende jobs pullen dezelfde remote branch.
8. Read-only reviewer/testerbewijs bevat `checkoutCommitSha`; een latere push maakt het stale.

SHA's zijn bewijs, niet het coördinatiemechanisme. Coördinatie gebeurt via alias + branch.

## Idempotentie en herstel

Een logische agentstap heeft één idempotentiesleutel en één opgeslagen `runtime_job_id`. Een timeout
van de create-call betekent niet dat de create mislukt is. Reconcileer eerst. Hetzelfde geldt rond
push en PR-aanmaak.

Houd drie retrylagen uit elkaar:

- Runtime-attempt: technische retry binnen dezelfde job;
- verificatieherstelronde: dezelfde agent krijgt rood commandobewijs;
- Software Factory-loopback: domeinbeslissing naar developer/reviewer/tester.

Cancel, timeout en late completion moeten via leases/fencing veilig blijven.

## Resultaatvalidatie

Vertrouw nooit alleen het laatste agentbericht. Valideer:

- terminale Runtime-status;
- rolgebonden JSON-schema;
- gedeclareerde artifacts, MIME, grootte en SHA;
- repositoryalias, branch en publicatiemodus;
- `repositoryResult` en commitbewijs;
- `verificationResult` en `checkoutCommitSha`;
- correlation/idempotency tegen de actieve domeinrun.

Een ontbrekend of ambigu onderdeel is geen succes. Force-push is geen herstelpad.

## Modelconfiguratie

`agent_role_execution_config` bevat per rol een globale keuze en optioneel projectoverride voor
`vendorId`, `model` en `mode`. De UI haalt geldige opties bij Runtime op. Een wijziging geldt vanaf
de volgende job. Er is geen `aiLevel` of statische supplierroutering meer.

## Telegram-assistent

`TelegramAssistantService` beheert threads, `/stop`, inputfoto's en knowledge-tips.
`RuntimeAssistantClient` maakt per beurt een structured-generationjob. De assistent heeft bewust
geen tracker-, repository-, browser-, cluster- of secrettools en kan alleen adviseren. Wijzig dit
niet impliciet: muterende chatcommands vereisen een apart productbesluit en autorisatiemodel.

## Tests

```bash
mvn -B --no-transfer-progress test
mvn -B --no-transfer-progress verify
./quality/run.sh

cd dashboard-frontend
flutter analyze
flutter test
```

`TestAgentRuntime` simuleert het v2-protocol. `LocalGitRemote` en `FakeGitHubApi` bewijzen de echte
branch-/PR-/mergeflow zonder productierepository. Test minimaal succes, vraag/hervatting,
NO_CHANGES, BRANCH_CHANGED, rode verificatie, stale bewijs, cancel, timeout en restart.

## Configuratie en secrets

Lees [`factory/secrets-local.md`](factory/secrets-local.md). Belangrijk:

- Software Factory heeft een Runtime-tenanttoken, geen AI-providercredential;
- Runtime-worker bezit Git- en providercredentials;
- targetproject-`secrets.env` blijft lokaal bij de eigenaar en wordt nooit gemount of verstuurd;
- `SF_GITHUB_TOKEN` blijft nodig voor Factory-eigen branch/PR/merge.

## Reviewchecklist

- [ ] Blijft domeinlogica in Factory en technische uitvoering in Runtime?
- [ ] Is er precies één duurzame jobcorrelatie per logische stap?
- [ ] Is repositoryoverdracht uitsluitend alias + remote branch?
- [ ] Kan een stale SHA, verkeerde alias of verkeerde branch nooit publiceren?
- [ ] Zijn AI-, repository- en verificatieresultaat apart gevalideerd?
- [ ] Zijn secrets afwezig uit request, prompt, event, artifact, log en fixture?
- [ ] Zijn cancel/restart/verloren-responsepaden getest?
- [ ] Is een nieuwe moduledependency expliciet toegestaan en getest?
- [ ] Beschrijft actuele documentatie alleen geïmplementeerd gedrag?

## Topologiegrens

De hoofdapp draait als `software-factory-backend` samen met de Flutter-frontend en PostgreSQL op
OpenShift. Agent Runtime v2 blijft een afzonderlijke externe component en verzorgt buiten deze
clusteropstelling de AI-uitvoering, tijdelijke repositorycheckouts, verificatie en Gitpublicatie.
