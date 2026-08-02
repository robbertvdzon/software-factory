# Functional Spec

De software-factory automatiseert tracker-issues (eigen Postgres-tracker-database) via een lokale
agent-pijplijn:

1. Refiner scherpt een story aan of stelt vragen (story-niveau, `Story Phase`).
2. Planner maakt een implementatieplan en declareert de subtaken (story-niveau); de
   factory materialiseert die subtaken (`Subtask Type` + `Subtask Phase`).
3. Developer implementeert de story en houdt een worklog bij.
4. Reviewer beoordeelt de PR.
5. Tester test de preview-deploy.
6. Summarizer maakt na een succesvolle test de eindsamenvatting.
7. Documenter werkt de relevante documentatie bij obv de story.

De orchestrator:

- Pollt alle issues van de geconfigureerde tracker-projecten (`SF_TRACKER_PROJECTS`, of alle
  project_key's die al in de tracker-database voorkomen als die leeg is) en filtert op `AI-supplier` niet
  leeg/niet `none`. Er is geen `Stage = Develop`-veldfilter meer; de fase-gate in de
  orchestrator (lege `Story Phase`/`Subtask Phase` = niet starten, `start` = oppakken)
  bepaalt of een issue daadwerkelijk wordt opgepakt.
- Stuurt op `AI-supplier`, `Story Phase`/`Subtask Phase`, `Paused` en `Error`.
- Start agent-runs in Docker-containers.
- Houdt run-state en tokengebruik bij in Postgres.
- Ondersteunt budget-pauzes, credit-pauzes en handmatige comment-commands.

## Automatisch wachten op Claude-quota (SF-1775)

Een mislukte Claude-run met een expliciet blokkerend `rate_limit_event`, of met de tekst
`usage limit reached`, `quota` of `credit balance`, wordt niet als gewone fout afgehandeld. De
actieve story-/subtaakfase blijft staan, `Error` blijft leeg en het absolute tracker-tijdstip
`RetryAfter` wordt gevuld. Dit is een automatische wachtstatus en gebruikt nadrukkelijk niet de
handmatige `Paused`-vlag.

- De agentworker bewaart van het laatste bruikbare Claude-event `status`, `resetsAt` en
  `overageResetsAt` in het additieve agentresultaatcontract. `allowed` en `allowed_warning` maken
  een geslaagde of anders mislukte run op zichzelf niet tot quota.
- `RetryAfter` is de toekomstige `resetsAt` plus één minuut veiligheidsmarge; zonder bruikbare
  toekomstige reset probeert de factory na vijftien minuten opnieuw.
- Vóór dat tijdstip skipt de orchestrator het issue vóór dispatch en vóór de hard-timeoutcontrole.
  Op of na het tijdstip wordt dezelfde rol met een vers starttijdstip opnieuw gedispatcht.
- Quota-runs tellen niet mee voor de transient-retrycap en breken de telling van omliggende echte
  transient failures niet. Het gestructureerde quota-signaal blijft daarvoor ook in de persistente
  agent-runhistorie bewaard. Gewone `rate limit`-tekst blijft zonder quota-signaal transient.
- Wachtende stories én subtaken blijven buiten de recente top-N in de pollset. Dashboardlijsten en
  storydetail tonen “Gepauzeerd wegens Claude-quota tot <tijdstip>” als wachtstatus, nooit als fout.
- Alleen meldingen=`na-elke-stap` krijgt per ingesteld wachttijdstip één DB-idempotente,
  informatieve Telegram-melding; de drie andere meldingenstanden onderdrukken deze status.

Een succesvolle/terminale completion en handmatige reset-/re-implementatiepaden wissen een oud
`RetryAfter`, zodat een verouderde quota-wachtstatus nooit een volgende run blokkeert.

`AI-supplier=mock` gebruikt dummy agents zodat de workflow end-to-end kan
werken zonder echte AI CLI. `AI-supplier=claude` gebruikt Claude Code. Daarnaast
worden `openai` (Codex CLI) en `copilot` (GitHub Copilot CLI) ondersteund;
`none` (of leeg) laat een issue ongemoeid. De dashboard-keuzelijst biedt
`none`/`mock`/`claude`/`openai`/`copilot`/`microsoft`, maar `microsoft` is (nog) níet
geïmplementeerd (`AiClientFactory.create` mapt het op een niet-uitvoerbare client) en
levert dus geen werkende agent op.

## Drie story-opties-assen: vragen / goedkeuring / meldingen (SF-1261)

Elke story heeft drie onafhankelijke instellingen (subtaken erven ze van de parent-story via
parent-lookup; ze hebben geen eigen velden). Dit vervangt de vroegere, elkaar overlappende
`Auto-approve`/`Silent`/`TelegramResultNotify`-vlaggen (zie hieronder de historische SF-335/SF-1134
-secties die deze structuur vervangt).

**As 1 — Vragen toestaan** (boolean `QuestionsAllowed`, default AAN):

- **AAN** — elke `*-with-questions`-uitkomst (story: `refined`/`planned`; subtaak:
  `developed`/`reviewed`/`tested`/`summary`/`documentation`) gaat **altijd** via Telegram naar de
  gebruiker, ONGEACHT de meldingen-instelling (as 3, ook bij `geen`/`als-klaar`/
  `als-klaar-en-gedeployed`) — een vraag is geen "melding" maar de enige manier waarop een
  blokkerende `*-with-questions`-fase ooit een antwoord kan krijgen (zonder Telegram-bericht blijft
  de keten anders voor altijd wachten, zonder dat de gebruiker dat ooit merkt). De keten wacht op
  antwoord (bestaand gedrag).
- **UIT** — dezelfde uitkomst wordt direct omgezet in een `[CLARIFICATION]`-gemarkeerde `Error`,
  zonder te wachten op een mens (het vroegere silent-clarification-pad). Bij `vragen=uit` komt er
  dus sowieso nooit een QUESTION-Telegram: de fase is al omgezet vóórdat de meldingen-as ter sprake
  komt.
- Deze as is verder losgekoppeld van de meldingen-as: "vragen uit" onderdrukt alléén de
  vraag-fases, niet de status-Telegram-meldingen.

**As 2 — Goedkeuring** (enum `ApprovalMode`, default `automatisch`):

- `automatisch` — alle AI-subtaken (development/review/test/summary/documentation) lopen
  automatisch door **en** de vaste `manual-approve`-poort wordt vóór de merge overgeslagen.
- `alleen-manual-poort` — AI-subtaken lopen automatisch door, maar de `manual-approve`-poort blijft
  staan en wacht op een mens.
- `elke-stap` — elke AI-subtaak wacht op handmatige goedkeuring vóór de volgende fase start; de
  `manual-approve`-poort vóór de merge blijft eveneens staan.

**As 3 — Meldingen** (enum `NotifyMode`, default `als-klaar`):

- `geen` — geen enkel status- of error-Telegram-bericht voor deze story.
- `na-elke-stap` — een Telegram-status-melding bij elke terminale subtaak (bestaand
  standaardgedrag).
- `als-klaar` — geen per-stap-status-meldingen; precies één melding zodra de laatste subtaak (na de
  merge) terminaal wordt, zonder te wachten op externe live-verificatie.
- `als-klaar-en-gedeployed` — als `als-klaar`, maar de melding wacht op het daadwerkelijke, extern
  zichtbare live-resultaat (zie "Telegram-melding bij écht live/klaar eindresultaat" hieronder),
  éénmalig en DB-backed idempotent.

Een QUESTION vormt bij **alle vier** standen de uitzondering (zie As 1): die gaat, als vragen=aan
staat, altijd door — ongeacht de meldingen-instelling, want anders is er geen enkele manier waarop
de gebruiker ooit op de vraag kan reageren.

Een door een audit voorgestelde vervolg-story (`AuditGatewayAdapter.proposeStoryIfAny`) is juist
géén silent story: vragen zijn toegestaan (`questionsAllowed = true`) en de story start in de
wachtrij (`StoryPhase.START_NEXT`) in plaats van meteen, zie hierboven onder "Audits".

## Documentatie-stap (SF-213)

Elke story krijgt een vaste, factory-afgedwongen subtaak `documentation` (titel
"Werk documentatie bij"), uitgevoerd door de AI-rol DOCUMENTER. De subtaak wordt bij het
materialiseren van het plan automatisch aangemaakt, ná de planner-subtaken (dus ná `summary`)
en vóór de manual-approve-poort. De ketenvolgorde wordt daarmee:
`development → review → test → summary → documentation → manual-approve → merge → deploy`.

- De documenter werkt alle relevante documentatie bij (README's, `docs/`, runbook/changelogs,
  API-docs e.d.) zodat die klopt met wat in de story is gedaan, en bepaalt zelf welke docs geraakt
  zijn obv de story en de diff. Er is geen vaste lijst van te wijzigen bestanden.
- De levenscyclus spiegelt die van `summary`/`test`:
  `documenting → documented → (documentation-with-questions ↔ documentation-questions-answered) →
  documentation-approved`, met `documentation-approved` als terminale fase. Er is géén
  `documentation-rejected`/loopback-tak.
- Bij goedkeuring=`automatisch`/`alleen-manual-poort` loopt de subtaak vanzelf door (zoals
  review/test/summary); bij `elke-stap` vraagt 'ie — net als de andere AI-stappen — om goedkeuring
  vóór doorgaan.
- De subtaak is altijd aan; alleen de goedkeuringsstand bepaalt of er daarna een manual-approve-poort komt.
  Een eventueel door de planner meegestuurde `documentation`-spec wordt eruit gefilterd, zodat er
  nooit een dubbele documentatie-subtaak ontstaat (zelfde patroon als merge/deploy).

## Handmatige goedkeur-poort (SF-192)

Vlak vóór de merge zit een vaste, niet-AI subtaak `manual-approve`: een handmatige
goedkeur-poort. De story-eigenschap Goedkeuring is de enige bron van waarheid: bij
`alleen-manual-poort` en `elke-stap` wordt de poort aangemaakt; bij `automatisch` wordt zij
overgeslagen.

- De poort wordt bij het materialiseren van het plan precies één keer aangemaakt, ná de
  documentatie-stap (SF-213) en vóór de merge-subtaak.
- Op de poort wacht de keten op een mens. Goedkeuren/afkeuren loopt via het bestaande
  `@factory:command`-mechanisme (dashboard-knoppen én Telegram): `approve` laat de keten door
  naar de merge, `reject` neemt een afkeurreden mee.
- Afkeuren reset de hele story: alle subtaken terug naar todo, de eerste subtaak weer op
  `start`, en de afkeurreden in een gemarkeerd blok in de story-description zodat
  developer/reviewer/tester de feedback meekrijgen.
- De poort vraagt altijd om een mens zodra hij gematerialiseerd is (goedkeuring=`alleen-manual-poort`
  of `elke-stap`), óók als de AI-subtaken automatisch doorlopen.

## Projectbewuste, groene mergegate (SF-244 / FIX-01)

Na de goedkeur-poort beoordeelt de `merge`-subtaak automatisch de projectpolicy uit
`projects.yaml`. Het handmatige `@factory:command:merge`-pad gebruikt exact dezelfde gate.
Iedere projectentry heeft een niet-lege `merge.requiredChecks`-lijst; een onvolledige policy
blokkeert het opstarten van de factory.

- `Ready` betekent dat alle vereiste check-runs groen zijn op exact de actuele PR-head. Alleen
  die geverifieerde SHA mag via GitHub worden gemerged; een tussentijdse push maakt de poging
  opnieuw pending.
- `Pending` betekent queued/in-progress: geen merge en geen `Error`; automatisch en handmatig
  proberen bij een volgende poll opnieuw.
- `Blocked` betekent ontbrekend, skipped, cancelled, failed of onbetrouwbaar/API-bewijs: geen
  merge en een duidelijke fout voor menselijke triage.
- Lukt de merge, dan gaat de keten ongewijzigd door naar de `deploy`-subtaak.
- De handmatige controle vóór de merge zit volledig in de voorafgaande `manual-approve`-poort.

## Robuuste deploy-verificatie (SF-771)

De `deploy`-subtaak verifieert op de daadwerkelijk live SHA i.p.v. blind op een herstart-tijdstip
of een niet-lege image te wachten, zodat een geslaagde uitrol niet ten onrechte op `deploy-failed`
belandt:

- **rest-restart** — na de restart pollt de factory `versionUrl` (`/api/version`) tot het gerapporteerde
  `commitHash` prefix-matcht met de verwachte merge-SHA (de HEAD van de base-branch ná merge, opgehaald
  via de GitHub API). Blijft de oude build live, dan matcht de SHA nooit en loopt de stap netjes in de
  timeout. Rapporteert `/api/version` geen `commitHash` of is de verwachte SHA niet bepaalbaar, dan valt
  de verificatie terug op het bestaande "service opnieuw opgestart"-gedrag.
- **openshift-watch** — zijn `argocdApp` + `argocdNamespace` geconfigureerd, dan is ArgoCD de
  waarheidsbron: de deploy geldt pas als geslaagd bij `sync.status=Synced` **én** `health.status=Healthy`
  **én** `operationState.phase=Succeeded` op de verwachte revisie (via `kubectl get application`). Zonder
  die velden blijft het bestaande "image niet-leeg"-gedrag gelden (geen regressie).
- **Ruimere timeout** — de default deploy-timeout is verhoogd van 10 naar 20 minuten (`timeoutMinutes`,
  per project overschrijfbaar). Pas ná de timeout wordt `DEPLOY_FAILED` gezet.
- **Tester-preview** — de HTTP-200-wachtstap gebruikt dezelfde ruimere default (1200s), instelbaar via
  `SF_PREVIEW_WAIT_TIMEOUT_SECONDS`; de foutmelding noemt de werkelijke timeout.

## Telegram-melding bij écht live/klaar eindresultaat (SF-1134 / SF-1261)

Meldingen=`als-klaar-en-gedeployed` (as 3, zie hierboven; vroeger de losse
`telegram_result_notify`-vlag) stuurt een aparte, latere Telegram-melding zodra het eindresultaat
écht extern zichtbaar is — in plaats van de gewone `als-klaar`-melding, die alleen bevestigt dat de
factory zelf klaar is met de laatste subtaak (bv. `deploy-approved`), niet dat de nieuwe versie ook
echt bereikbaar is.

- **Wanneer** — de melding gaat pas uit ná de bestaande deploy-bevestiging (zie "Robuuste
  deploy-verificatie" hierboven), plus een extra, projecttype-afhankelijke check:
  - **openshift-watch** — ArgoCD Synced/Healthy/Succeeded (of de image-heuristiek) is al bevestigd;
    is er een `liveUrl` geconfigureerd, dan wacht de melding bovendien op een HTTP-200 daarop.
  - **rest-restart** — de SHA-gebaseerde `/api/version`-bevestiging is al voldoende.
  - **projecten zonder deploy-config** (bv. losse APK-apps) — een nieuwe `.apk`-release die ná de
    deploy is gepubliceerd (GitHub Releases), met downloadlink in het bericht.
- **Precies één keer** — de melding is idempotent: ook bij herhaalde polls of een herstart van de
  factory verschijnt hij hooguit één keer per story (DB-backed, niet in-memory).
- **Opgeven zonder ruis** — bevestigt het eindresultaat zich niet binnen enkele uren, dan stopt de
  factory stilletjes met wachten: geen Telegram-bericht, geen foutmelding aan de gebruiker, alleen
  een logregel.
- **Alleen wanneer nodig** — zijn er geen stories die op hun eindresultaat wachten, dan doet de
  achtergrond-poller aantoonbaar geen cluster-/GitHub-calls.

## Test-bevinding reset de keten (SF-200)

De test-subtaak test alleen en oordeelt; de tester voert zelf geen gerichte fix meer uit.

Een tester kan alleen `tested` bereiken met machine-verifieerbaar bewijs uit de actuele checkout:

- iedere actieve target-repo bevat versioned `.factory/verification.yaml` met stabiele command-id's,
  argv zonder impliciete shell, relatief working directory en timeout;
- na de AI-run voert agentworker alle verplichte commands zelf uit en schrijft per command tijden,
  duur, exitcode, tooling-/timeoutstatus en begrensde output in `AgentResultFile`;
- het bewijs bevat de geteste HEAD en een Git-treehash van de werkelijke worktree (via tijdelijk
  index, zonder de echte index te muteren); de factory vergelijkt config, commandset en
  revision opnieuw met de nog actieve testerworkspace;
- missing/unknown config, ontbrekend of handgeschreven bewijs, missing tool, timeout, non-zero en
  iedere HEAD/worktree-tree-mismatch worden fail-closed `test-rejected`.

- Bij een bevinding (`test-rejected`) start de tester géén developer-loopback. In plaats daarvan
  wordt de hele subtaak-keten gereset op exact dezelfde manier als bij een handmatige reject via de
  goedkeur-poort: alle subtaken terug naar todo, de eerste subtaak weer op `start`, op dezelfde
  story-branch.
- De testreden van de laatste tester-run komt in een eigen, herhaalbaar te overschrijven gemarkeerd
  blok (`<!-- test-feedback:start -->`) in de story-description, zodat developer/reviewer/tester die
  feedback bij de herstart meekrijgen. Een volgende bevinding vervangt het blok (stapelt niet).
- Een cap (`SF_MAX_TEST_CHAIN_RESETS`, default 3) voorkomt oneindig herstarten. Zolang de cap niet
  bereikt is, reset een bevinding de keten opnieuw. Bij het bereiken van de cap volgt geen reset maar
  komt de story in `Error` (handmatige triage nodig). De cap telt de TESTER-runs op de gedeelde
  story-run en kent — anders dan de developer-loopback-cap — géén resume-increment: enkel `Error`
  legen herstart niets (de volgende poll loopt direct opnieuw in de cap). Werkende herstelpaden zijn
  `Paused = true` + parkeren, of `re-implement` op de story (verse story-run → teller reset).

## Telegram-melding bij afgeronde test-subtaak (SF-206)

Wanneer een **test**-subtaak terminaal wordt bij actieve auto-approve (goedkeuring=`automatisch`/
`alleen-manual-poort`), breidt de bestaande
'subtaak klaar'-Telegram-melding (`TelegramNotificationService.notifySubtaskDone`) zich uit met
test-specifieke context. Voor alle andere subtaaktypen blijft de melding ongewijzigd.

- **Testrapport** — de samenvatting van de laatste TESTER-agent-run op de parent-story
  (`FactoryOperationsService.testerReportFor`), eerst ontdaan van trailing JSON-controleblokken
  (`{"phase":...}`/`{"agent_tips_update":...}`, gedeelde `support.ControlJsonStripper` uit
  `factory-common`, SF-1446) en dán afgekapt op ~1200 tekens.
- **Preview-/test-URL** — voor projecten mét preview (`previewUrlTemplate` gezet, zoals News Feed)
  staat de preview-link (dezelfde als de 'Test op preview'-knop, via
  `DashboardQueryService.previewUrlFor`) als klikbare regel in het bericht; projecten zonder
  preview (bv. softwarefactory zelf) laten die regel weg.
- **Screenshots** — de tester-screenshots (tracker-attachments met prefix
  `factory-tester-screenshot__` op de parent-story) worden als foto's in hetzelfde projectkanaal
  verstuurd via `TelegramClient.sendPhoto`. Maximaal 10 als foto; de rest komt als link(s) in de
  tekst.
- **Volgorde & idempotentie** — eerst de tekstmelding (met rapport + preview-link), dan wordt de
  bestaande `TelegramStore`-signature vastgelegd, daarna pas de foto's. Zo triggert een gefaalde
  `sendPhoto` (return false) geen herverzending van de tekstmelding.
- **Robuust degraderen** — een ontbrekend rapport, een ontbrekende preview-URL of een gefaalde
  screenshot-download blokkeert de rest niet; tracker-calls, attachment-download en `sendPhoto`
  zitten in `runCatching`/return-false.

## Telegram-assistent — conversationeel kanaal

Naast de eenrichtings-meldingen draait de factory een conversationele assistent op
hetzelfde Telegram-kanaal (`TelegramAssistantService`, gevoed door `TelegramPoller`).
Je stelt vrije vragen in natuurlijke taal en de assistent antwoordt als reply.

- **Per-project context.** Het kanaal van een project (`projectRepoResolver.projectNameForChatId`)
  bepaalt waar de assistent tegenaan praat; het algemene kanaal is projectloos. De relevante
  repo-code en `private`-secrets/config staan read-only in de container klaar.
- **Threads.** Elke reply-keten is een aparte `claude`-sessie: een niet-reply-bericht zet de
  laatste actieve thread voort, een reply zet die specifieke thread voort, en een prefix
  (`nieuw:`, `new:`, `story:`, …) start een nieuw, los gesprek. `/stop` (als reply) breekt een
  lopend gesprek af; `/help` toont de uitleg.
- **Tools.** De assistent draait geïsoleerd in een Docker-container (`Dockerfile.assistant`,
  `SF_ASSISTANT_IMAGE`, default `assistant:local`) en heeft `sf-story` (story-status opzoeken,
  stories aanmaken/aanpassen/verwijderen — nooit auto-starten zonder bevestiging), een browser
  (`sf-browser`/Playwright) en read-only cluster-toegang (`oc`/`kubectl`). Een door de gebruiker
  gestuurde foto belandt in `/work/in/`; output-afbeeldingen in `/work/out/` stuurt de factory terug.
- **Kennis.** De assistent leert tips onder de rol `ASSISTANT` (`AgentRole.ASSISTANT`,
  `KnowledgeApi`), op dezelfde manier als de werk-agents, en krijgt eerder geleerde tips weer mee.
- **Aan/uit.** De assistent is alleen actief met een Claude-token (`SF_AI_OAUTH_TOKEN`); zonder
  token meldt 'ie dat 'ie uitstaat. Een beurt wordt na `SF_ASSISTANT_TIMEOUT_SECONDS` (default
  3600s) hard afgebroken.

## Audits — nachtelijke read-only agent-runs

Elke nacht (default 08:00) draait de factory per project een audit: een read-only AI-agent-run die
géén code aanpast, maar onderzoekt en een rapport schrijft. Per project draait doorgaans hoogstens
1 audit per nacht (instelbaar per project); de scheduler kiest telkens de audit die het langst niet
gedraaid heeft, zodat alle geconfigureerde audits om beurten aan bod komen. Een audit stelt
hoogstens 1 kleine vervolg-story voor om het belangrijkste gevonden probleem op te lossen — een
gewone (niet-silent) story die vragen mag stellen en in de wachtrij (`start-next`) start, niet
automatisch en meteen. De navigatie heeft hiervoor het item "Audits" (`AuditScreen`), met per
project/audit-type de laatste status, het rapport en (indien aanwezig) de score-trend.

Configuratie, structuur en het exacte agent-contract staan in `.factory/nightly/README.md` (single
source of truth voor het `.factory/nightly/<audit>/job.yaml` + `prompt.md`-formaat).
