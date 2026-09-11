# Ontwerpbesluiten voor de Runtime-vervanging

Status: vastgesteld voor implementatie

Datum: 2026-09-11

Dit document is de concrete uitwerking van stap 1 uit
[`stappenplan.md`](stappenplan.md). Het introduceert geen nieuw domeinmodel.

## Vervanging per bestaand onderdeel

| Bestaand onderdeel | Besluit | Vervanging/eindtoestand |
|---|---|---|
| `DockerAgentRuntime` | weg | `AgentRuntimeV2Client` maakt en beheert `/v2`-jobs. `AgentRuntime` blijft tijdelijk de domeinpoort en krijgt jobsemantiek in plaats van containersemantiek. |
| `runtime/workspaces` en `StoryWorkspaceApi` | weg | De remote storybranch plus expliciete domeincontext zijn het overdrachtspunt. Runtime maakt per job een tijdelijke clone. |
| `AgentResultFileCompletionPoller` | weg | Een periodieke Runtime-reconciler leest jobstatus, events en het terminale resultaat. SSE mag alleen de zichtbaarheid versnellen. |
| `DurableCompletionCoordinator` | vervangen | De bestaande idempotente domeinside-effects blijven, maar worden gestart vanuit een duurzaam gecorreleerde Runtime-job in plaats van een resultbestand. |
| `AgentRunCompletionService` | blijft, wordt versmald | Interpretatie van rolresultaten en domeintransities blijft. Invoer wordt een Runtime-resultaatadapter; workspace-, resultbestand- en containeropruiming verdwijnen. |
| `TesterVerificationRunner` | weg | Runtime voert `.factory/verification.yaml` uit en retourneert `verificationResult`. |
| `TesterVerificationEvidenceValidator` | blijft, wordt data-gebaseerd | Valideert het getypeerde Runtime-bewijs en `checkoutCommitSha`, zonder lokale checkout. |
| `TargetRepositoryFlow` | weg | Checkout, Gitmetadata-afscherming, commit en push zijn Runtime-workerverantwoordelijkheid. |
| AI-CLI-clients en `CliProcessRunner` | weg | Runtime kiest de provider op basis van expliciete `vendorId`, `model` en `mode`. |
| `TaskFileManager` en `AgentPromptContracts` | contract blijft | De promptinhoud verhuist naar `CreateJobRequest.input.instruction`; filegebaseerde overdracht verdwijnt. |
| `agent_runs` | blijft, wordt correlatie/projectie | Eén rij per logische agentstap met Runtime-job-ID, gekozen execution, domeinstatus, usage en samenvatting. Geen containereigenaarschap. |
| `agent_events` | blijft, wordt projectie | Begrensde Runtime-events worden idempotent gespiegeld voor bestaande dashboards; Runtime blijft bron voor transcript/attemptdetail. |
| `GitHubCliClient` | blijft en groeit | Branch aanmaken via GitHub-API zonder checkout; na eerste `PUSHED` idempotent één PR maken; bestaande merge/polling blijft. |
| `AiRouting` en `aiLevel` | weg | Databaseconfiguratie per rol, met optionele projectoverride, kiest exact Runtime execution option. |

## Jobcorrelatie en herstel

1. Iedere logische agentstap krijgt vóór de externe call een duurzame `agent_runs`-rij en een
   stabiele idempotentiesleutel. De sleutel is afgeleid van de interne run-ID en verandert niet bij
   netwerkherstel of een procesrestart.
2. De create-response wordt niet als enige waarheid gebruikt. Als die verloren gaat, wordt dezelfde
   create met dezelfde sleutel herhaald en retourneert Runtime dezelfde job.
3. Het Runtime-job-ID wordt op `agent_runs` opgeslagen. De statusreconciler pollt alle niet-terminale
   jobs. SSE mag dezelfde projectie sneller bijwerken, maar polling is de herstelroute.
4. Runtime-attempts worden niet omgezet in nieuwe domeinruns. Alleen een expliciete Software
   Factory-loopback maakt een nieuwe logische agentstap en dus een nieuwe Runtime-job.
5. Resultaatpublicatie in het Software Factory-domein blijft idempotent per agent-run. Een restart
   tussen resultaatophaling en een tracker-, PR- of database-effect herhaalt het effect veilig.
6. `BRANCH_CHANGED` maakt een zichtbare domeintoestand. Alleen de bestaande begrensde
   developer-loopback mag vervolgens een nieuwe logische job op de actuele branch starten.
7. Annuleren gebruikt `POST /v2/jobs/{jobId}/cancel`; na een verloren response blijft polling de
   terminale toestand reconciliëren.

## Promptcontract

Iedere Runtime-instructie is zelfstandig en bevat de huidige trackercontext, relevante comments,
de rolregels en het gewenste JSON-resultaatschema. Repositoryrollen krijgen daarnaast expliciet:

- werk uitsluitend in de voorbereide `/work`-checkout;
- laat relevante tests groen achter;
- voer geen checkout, branch, commit, push, PR, merge of credentialinspectie uit;
- gebruik alleen read-only Gitinspectie zoals `status`, `diff`, `log` en `show` wanneer nodig;
- schrijf het afgesproken resultaat en artifacts, ook wanneer geen codewijziging nodig is.

Een herstelronde wordt door Runtime aangevuld met het verificatiebewijs. Software Factory probeert
die ronde niet zelf te modelleren als een tweede agentrun.

## Branch- en PR-eigenaarschap

- `ProjectRepositoryCatalog` vertaalt de projectkeuze naar zowel de bestaande provideridentiteit als
  een geregistreerde Runtime-alias. Er wordt geen repository-URL naar Runtime gestuurd.
- Bij het openen van een story-run reserveert Software Factory deterministisch één branchnaam.
- `GitHubApi.ensureRemoteBranch(targetRepo, branch, baseBranch)` maakt de ref idempotent via de
  provider-API. Er is geen clone of lokale map nodig.
- Na de eerste `repositoryResult.publicationStatus=PUSHED` roept Software Factory
  `GitHubApi.ensurePullRequest(targetRepo, branch, baseBranch, ...)` aan. Eerst wordt een bestaande
  open PR voor head/base gezocht; alleen bij afwezigheid wordt er één gemaakt.
- `story_runs` bewaart branch, base branch, PR-nummer en PR-URL. Iedere volgende repositoryjob krijgt
  alleen alias en die branch.
- Runtime opent of merget nooit een PR. De bestaande mergegate, previewidentiteit en deployketen
  blijven eigenaar van de PR.

## Workspace-aannames en vervanging

| Aanname | Vindplaats | Vervanging |
|---|---|---|
| Workspace voorbereiden vóór iedere dispatch | `AgentDispatcher`, `StoryRefinementCoordinator`, `ManualCommandService`, `AuditScheduler` | Branch alleen bij repositorywerk voorbereiden; niet-repositorywerk heeft geen workspace. |
| Resultaatbestand in workspace | `AgentResultFileCompletionPoller`, e2e-runtime | Runtime-resultaatendpoint plus statusreconciler. |
| Screenshots uit `<workspace>/screenshots` | `AgentRunCompletionService` | Gedeclareerde Runtime-artifacts downloaden/registreren. |
| Verificatieconfig uit lokale repo lezen | `TesterVerificationEvidenceValidator` | Getypeerd Runtime-resultaat vergelijken met jobaanvraag en actuele branch-SHA. |
| Workspacepad tonen en linken | dashboardmodellen en `AgentDispatcher` | Runtime-job-ID, status, events, artifacts en branch/commit tonen. |
| Workspace opruimen bij purge/retentie | `StoryPurgeService`, `runtime/workspaces`, cleanupservices | Alleen domeinrecords/artifactreferenties opruimen; Runtime beheert attemptdirectories. |
| Auditworkspace bewaren | `AuditScheduler`, auditrepository/model | Read-only Runtime-job op base branch; rapport en artifacts worden domeindata. |
| Telegram-assistentworkspace | Telegrammodule | Buiten deze refactor: dit is geen Software Factory repository-agentflow en blijft ongewijzigd. |

## Modelconfiguratie

Er komt één tabel `agent_role_execution_config` met:

- `role` (verplicht);
- `project_key` (nullable; `NULL` is de standaard voor die rol);
- `vendor_id`, `model`, `mode`;
- `updated_at` en `updated_by`;
- uniek op `(role, project_key)` met een aparte unieke index voor de nullable standaard.

Resolutie is exact: projectoverride, anders rolstandaard. Ontbrekende configuratie is een zichtbare
configuratiefout; er is geen modelfallback. Bij opslaan wordt de combinatie gecontroleerd tegen
`GET /v2/execution-options?taskType=...`. De dispatcher leest de configuratie direct vóór
jobaanmaak, zodat een wijziging voor de eerstvolgende job geldt en een lopende job onveranderd
blijft. Het beheerscherm toont alleen actuele Runtime-options en markeert een opgeslagen maar niet
meer beschikbare combinatie als onbeschikbaar.

`aiLevel`, `AiRouting` en de oude supplier/model-afleiding uit trackerdata verdwijnen. Bestaande
kolommen mogen tijdens de refactor eerst ongebruikt blijven en worden in stap 4 verwijderd zodra
alle readers zijn omgezet.

## Quota en beschikbaarheid

- Een Runtime-job die wacht op een geschikte worker blijft een actieve, zichtbare wachtrijstatus en
  telt niet als nieuwe domeinretry.
- Providerquota of tijdelijk ontbrekende capaciteit wordt gemapt op de bestaande wachtstatus met
  `retryAfter` wanneer Runtime die informatie biedt.
- Technische Runtime-attempts blijven Runtime-eigendom. Software Factory telt alleen expliciete
  domeinloopbacks voor de bestaande caps.
- Een terminale niet-retrybare Runtime-fout wordt via de bestaande fout-/blockerroute zichtbaar.
- De statusreconciler gebruikt begrensde backoff, maar blijft binnen de bestaande orchestratorpoll
  iedere open job controleren zodat een restart geen handmatige wake-up vereist.

## Rolmapping

| Rol | Jobsoort | Repository | Publicatie | Verificatie |
|---|---|---|---|---|
| Refiner, planner, summarizer | `APPLICATION_WORK` / `STRUCTURED_GENERATION` | geen | — | geen |
| Developer, documenter | `REPOSITORY_WORK` / `REPOSITORY_AGENT` | actuele storybranch | `COMMIT_AND_PUSH` | `REPOSITORY_CONFIG` |
| Reviewer, tester | `APPLICATION_WORK` / `REPOSITORY_AGENT` | actuele storybranch | `NONE` | geen |
| Auditor | `APPLICATION_WORK` / `REPOSITORY_AGENT` | base branch | `NONE` | geen |
| Telegram-assistent | `APPLICATION_WORK` / `STRUCTURED_GENERATION` | geen | — | geen |

