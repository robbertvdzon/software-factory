# Scheduled jobs

De `@Scheduled` jobs (cost monitor, agent result completion, de nightly scheduler — die zelf twee
`@Scheduled`-methodes heeft: de hoofd-tick en de AI-verrijking-tick — en de work-cleanup poller)
staan aan via `@EnableScheduling` in `SoftwareFactoryApplication`. De orchestrator poller en de
Telegram poller zijn geen `@Scheduled` jobs, maar eigen daemon-threads (zie hieronder).

## 1. Orchestrator poller

- Klasse: `orchestrator/schedulers/OrchestratorPoller.kt`
- Methode: `loop()` / `runOnce()`
- Schedule: geen `@Scheduled`, maar een daemon-thread (`orchestrator-poller`) die op
  `ApplicationReadyEvent` start en slaapt met een wekbare sleep.
- Cadans: vast interval (`SF_POLL_INTERVAL_MS`, default `60000` ms) als vangnet. Elke schrijf-
  operatie in `PostgresTrackerClient` (`createStory`, `createSubtask`, `updateIssueFields`,
  `updateIssueSummary`, `updateIssueDescription`, `transitionIssue`, `postComment`) publiceert
  direct na de write een `FactoryStateChangedEvent` dat de wachtende sleep meteen wekt, zodat de
  keten zonder vertraging doorzet; het vaste poll-interval is dan alleen nog het vangnet wanneer er
  geen events binnenkomen.
- Idempotentie-guard (SF-903/SF-904): `transitionIssue` en `updateIssueFields` slaan de `UPDATE`
  (en dus het publiceren van het event en de `updated_at`-bump) over wanneer de opgegeven
  waarde(n) al gelijk zijn aan de huidige rij (`... WHERE issue_key = ? AND (... IS DISTINCT
  FROM ?)`). Zo blijft `updated_at` van een reeds afgeronde (terminale) subtask/story ongewijzigd
  en valt die uit de `findAiIssues`-window, zodat ze zichzelf niet langer eeuwig opwekt via een
  no-op transitie. `advanceSubtaskChain` (`SubtaskExecutionCoordinator`) roept `transitionIssue`
  daarnaast alleen nog aan wanneer de subtask/parent-story niet al de doelstatus heeft.
- Altijd actief zodra de applicatie draait.

Verantwoordelijkheid:

- Zoekt werkbare tracker-issues (fase-gate: lege fase = niet starten, `start` = oppakken).
  `PostgresTrackerClient.findAiIssues` combineert hiervoor de top-N op `updated_at DESC` met alle
  issues in een niet-terminale `subtask_phase` (begrensd via `PENDING_SUBSET_LIMIT`, 500), zodat een
  wachtende (sub)taak (bv. `manual-approve-needed`) niet buiten de LIMIT kan vallen en een geldig
  `@factory:command:approve`-comment altijd bij de eerstvolgende poll wordt verwerkt.
- Done-filter (SF-918): de top-N-tak sluit rijen met een afgeronde `status` uit
  (`core.FinishedStatus` — `done`/`fixed`/`verified`/`closed`/`resolved`, lowercase-genormaliseerd;
  dezelfde set als `StoryStatusPresenter.classifyStatus`), zodat een afgeronde story niet telkens
  opnieuw wordt opgehaald zolang er geen event binnenkomt. De niet-terminale-`subtask_phase`-tak
  filtert niet op `status`, dus een nog actieve subtaak van een al-op-Done-gezette story blijft
  bereikbaar.
- Past handmatige commands toe.
- Controleert budget, pauzes, errors en concurrency.
- Dispatcht de agent-rollen van het twee-laags model: refiner/planner op story-niveau,
  developer/reviewer/tester/summarizer/documenter op subtaak-niveau; de merge- en
  deploy-subtaken worden zonder agent afgehandeld.
- Monitort actieve pull requests op merge-status en nieuwe `@factory` comments.

## 1b. Telegram poller

- Klasse: `telegram/TelegramPoller.kt`
- Schedule: geen `@Scheduled`, maar een daemon-thread (`telegram-poller`) die op
  `ApplicationReadyEvent` start; assistent-gesprekken draaien op een aparte thread-pool
  (`telegram-assistant`).
- Alleen actief met geconfigureerde Telegram-secrets.

Verantwoordelijkheid:

- Leest updates van de Telegram Bot API (long polling) en vertaalt replies naar antwoorden op
  vragen, `@factory`-commands en assistent-gesprekken.

## 2. Cost monitor poller

- Klasse: `orchestrator/schedulers/CostMonitorPoller.kt`
- Methode: `poll()`
- Schedule: `@Scheduled(fixedDelayString = "#{@orchestratorSettings.costMonitorInterval.toMillis()}")`
- Default interval: `SF_COST_MONITOR_INTERVAL_MS`, default `300000` ms
- Altijd actief zodra de applicatie draait.

Verantwoordelijkheid:

- Controleert alle actieve stories op token- en kostenbudget.
- Werkt budgetvelden in de tracker-database bij.
- Kan stories of het systeem pauzeren als budget- of creditsgrenzen geraakt worden.

## 3. Agent result file completion poller

- Klasse: `runtime/services/AgentResultFileCompletionPoller.kt`
- Methode: `poll()`
- Schedule: `@Scheduled(fixedDelayString = "\${softwarefactory.agent-result-poll-ms:2000}")`
- Default interval: `2000` ms

Verantwoordelijkheid:

- Zoekt actieve agent runs in PostgreSQL.
- Wacht zolang de bijbehorende Docker-container nog draait.
- Leest na container-exit `/work/agent-result.json` uit de workspace.
- Roept `RuntimeApi.complete(...)` aan zodat usage, events, tracker-updates, PR metadata en knowledge updates centraal worden verwerkt.

## 4. Nightly scheduler (uitgezet — vervangen door de audit-scheduler, zie 4c)

- Klasse: `nightly/NightlyScheduler.kt`
- `tick()` en `startManualRun()` zijn bewust no-ops geworden (loggen alleen); nightly jobs pasten
  zelf code aan (tot en met automerge/deploy) en zijn vervangen door read-only audits. De
  content-migratie verving `story.md`/`subtasks.yaml` door `prompt.md` in `.factory/nightly/`, dus
  een nog draaiende nightly job zou nu een lege/kapotte story maken.
- `stopActiveRun()` blijft werken — nuttig om een eventuele, van vóór deze migratie nog openstaande
  run netjes te sluiten.
- De klasse en de bijbehorende dashboard-schermen/-bridge-operaties (`nightly.*`) zijn met opzet nog
  niet verwijderd; dat is een losse opruimstap.

## 4b. Nightly AI-verrijking (uitgezet, zie 4)

- Klasse: `nightly/NightlyScheduler.kt`
- `aiEnrichmentTick()` blijft technisch aan staan, maar heeft niets meer te doen zodra er geen
  nieuwe nightly-runs meer bijkomen (zie 4).

## 4c. Audit scheduler

- Klasse: `audit/services/AuditScheduler.kt`
- Methode: `tick()` (delegeert naar `runOnce()`)
- Schedule: `@Scheduled(fixedDelayString = "\${sf.audit.tick-ms:30000}", initialDelayString = "\${sf.audit.initial-delay-ms:30000}")`
- Default interval: `30000` ms

Verantwoordelijkheid:

- Leest elke tick de hele run-status uit de DB (geen in-memory state) en laat de pure `AuditPlanner`
  de acties bepalen — zelfde restart-veilige opzet als de oude nightly scheduler.
- Maakt op de start-tijd (`audit_settings.start_time`, default 08:00) één automatische run per
  kalenderdag aan. Bij het seeden kiest de scheduler **per project hoogstens 1** enabled audit: die
  met de oudste `audit_report.generated_at` (nooit gedraaid = oudste) — dat garandeert vanzelf
  "max 1 audit + max 1 voorgestelde vervolg-story per project per nacht", alle geconfigureerde
  audits komen om beurten aan bod.
- Dispatcht per gekozen audit rechtstreeks een agent-container via `AgentRuntime` (`AuditGateway`/
  `AuditGatewayAdapter`, in `dashboard/services/`) — **geen** tracker-story, **geen**
  `AgentDispatcher`/Subtask-koppeling. Rol `AUDITOR` (zie `core.AgentRole`); prompt + JSON-
  outputcontract in `agentworker` (`AgentPromptContracts.RolePrompts.auditorPrompt()`).
- Zodra de container stopt: leest `agent-result.json` (uitgebreid met `auditScore`/
  `auditScoreLabel`/`proposedStoryTitle`/`proposedStoryDescription`), upsert eventuele
  memory-tips via het bestaande `knowledge`-domein (rol `auditor`, category = audit-type — dus
  automatisch weer meegenomen in `agent-tips.md` bij de volgende run, zonder extra code), maakt
  desgevraagd de voorgestelde vervolg-story aan (`questionsAllowed=true`, fase `start-next` — zie
  `StoryPhase.START_NEXT`) en persisteert het rapport in `audit_report`.
- Geen digest-stap: rapporten staan meteen in het dashboard (`docs/factory/*`, FE nog te bouwen).

Zie ook `.factory/nightly/README.md` voor het volledige audit-verhaal.

## 5. Work cleanup poller (achtervang)

- Klasse: `runtime/workspaces/WorkCleanupPoller.kt`
- Methode: `poll()` (delegeert naar `cleanupOnce()`)
- Schedule: `@Scheduled(fixedDelayString = "\${softwarefactory.work-cleanup-poll-ms:3600000}")`
- Default interval: `3600000` ms (1 uur)
- Uit te zetten via `SF_WORK_CLEANUP_ENABLED` (default `true`).

Verantwoordelijkheid:

- Scant elke tick de vier `work/`-subroots die de runtime zelf aanmaakt:
  `work/agent-workspaces/<story>-<role>-<random>/`, `work/stories/<storyKey>/repo`,
  `work/assistant-checkouts/<naam>/repo` en `work/assistant/<chatId>/<sessionId>/work/{in,out}`.
- Verwijdert per top-level entry recursief zodra de meest recente mtime binnenin ouder is dan
  `SF_WORK_CLEANUP_RETENTION_DAYS` (default `7` dagen). Exact op de grens geldt als verlopen.
- Vraagt vóór iedere leeftijdsbepaling alle actieve paden op uit story-/agent-runs in Postgres en
  het runtime-register van lopende assistantsessies. Een actief pad, zijn ancestors/top-level entry
  en zijn descendants worden ongeacht mtime overgeslagen. Als een actieve bron faalt, wordt die
  hele cleanup-tick fail-safe overgeslagen.
- Entryfouten en verdwenen racekandidaten worden gelogd en isoleren de overige entries. Paden
  worden genormaliseerd en recursieve verwijdering volgt geen symlinks buiten de beheerde root.
- Is een achtervang bovenop de bestaande event-gedreven cleaners (`AgentWorkspaceCleaner`,
  `StoryWorkspaceService.cleanup`), die alleen bij succesvolle run-completion of expliciete
  purge/merge opruimen en dus weesmappen achterlaten na crashes of gekilde processen.
- Logt elke verwijdering (pad + berekende leeftijd) voor traceerbaarheid.
- Raakt `attachments/`, `logs/`, `qualityrun/` en `target/` niet aan — die worden niet door de
  Kotlin-runtime als agent-workmap beheerd.

Zie ook `docs/factory/technical-spec.md` (achtervang work-cleanup) en `docs/factory/secrets-local.md`
voor de env-var-defaults.

## 6. Telegram-resultaatmelding poller (SF-1134 / SF-1261)

- Klasse: `telegram/services/TelegramResultNotifyPoller.kt`
- Methode: `poll()`
- Schedule: `@Scheduled(fixedDelayString = "\${softwarefactory.telegram-result-notify-poll-ms:60000}")`
- Default interval: `60000` ms
- Alleen actief met geconfigureerde Telegram-secrets (`telegramClient.enabled`).

Verantwoordelijkheid:

- Stuurt een aparte Telegram-melding zodra het eindresultaat van een story écht extern
  zichtbaar/live is, in plaats van de gewone `als-klaar`-melding van `TelegramNotificationService`.
- "Alleen pollen wanneer nodig": stopt direct zonder cluster-/GitHub-calls zodra geen enkele story
  `notify_mode=als-klaar-en-gedeployed` heeft staan (SF-1261; vervangt de vroegere losse
  `telegram_result_notify`-vlag). Omdat dit dezelfde enum is als `meldingen=geen`, respecteert de
  poller die stand nu inherent (voorheen een losse boolean-inconsistentie).
- Hergebruikt de bevestiging die `DeploySubtaskHandler` (`pipeline`) al doet zodra de DEPLOY-subtaak
  `deploy-approved` bereikt, en voegt alleen de ontbrekende externe check toe: een HTTP-200 op het
  optionele `deploy.liveUrl` (openshift-watch) of een nieuwe `.apk`-release na de deploy-referentietijd
  (projecten zonder deploy-config, via de poort `ApkReleaseProbe`/adapter `GitHubApkReleaseProbe`).
- Opgeef-timeout van 4 uur na de deploy-referentietijd: alleen een warn-logregel, geen Telegram-
  bericht en geen foutmelding; de story wordt wel als afgehandeld gemarkeerd.
- Idempotent via `TelegramStore` (DB-backed, signature `"result-notify"`), overleeft een herstart.

Zie ook `docs/factory/technical-spec.md` §Telegram-resultaatmelding voor het volledige verhaal.
