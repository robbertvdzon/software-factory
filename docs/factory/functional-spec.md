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
  transient failures niet, ook niet bij een onbeperkt lange reeks quotaruns of een geconfigureerde
  retrycap boven 999. Het gestructureerde quota-signaal blijft daarvoor ook in de persistente
  agent-runhistorie bewaard. Alleen mislukte quotaruns worden uit die telling gefilterd: een
  succesvolle run met quotatekst of een blokkerend signaal blijft de transientreeks onderbreken.
  Gewone `rate limit`-tekst blijft zonder quota-signaal transient.
- Wachtende stories én subtaken blijven buiten de recente top-N in de pollset. Dashboardlijsten en
  storydetail tonen “Gepauzeerd wegens Claude-quota tot <tijdstip>” als wachtstatus, nooit als fout.
  Bij quota op een subtaak leidt het dashboard deze status read-only af voor de parent-story; het
  persistente `RetryAfter` blijft alleen op het getroffen issue staan zodat hervatting de juiste rol
  dispatcht.
- Alleen meldingen=`na-elke-stap` krijgt per ingesteld wachttijdstip één DB-idempotente,
  informatieve Telegram-melding; de idempotentiesleutel hangt uitsluitend van `RetryAfter` af en
  niet van tijdelijk beschikbare story-/subtaakcontext. De drie andere meldingenstanden
  onderdrukken deze status.

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

**As 3 — Meldingen** (enum `NotifyMode`, aanmaakdefault `als-klaar-en-gedeployed`):

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

De meldingen-default geldt alleen voor nieuw aangemaakte stories, ongeacht of die via dashboard,
bridge-operatie `story.create`, tracker-API/Telegram of een auditvoorstel ontstaan. Bestaande
stories houden hun opgeslagen stand; een bij aanmaken expliciet gekozen andere waarde wordt
ongewijzigd opgeslagen.

**As 4 — Hotfix** (boolean `Hotfix`, default UIT, SF-1959):

- **UIT** (default) — de story doorloopt de volledige keten (refine, plan, development, review,
  test, summary, documentation, eventueel manual-approve, merge, deploy).
- **AAN** — de story is bedoeld voor een kleine, niet-kritische wijziging en slaat refine, plan,
  review, test, summary en documentatie over. Zodra de story op `start` komt ontstaan er precies
  drie subtaken — `hotfix`, `merge`, `deploy` — en gaat de story direct naar `in-progress`. De
  hotfix-subtaak draait één AI-stap (rol DEVELOPER, met de bestaande developer-instructies): code
  aanpassen, de bestaande projecttests draaien en de wijziging aanbieden. Zijn die tests rood, dan
  wordt de subtaak automatisch afgekeurd (`development-rejected` met een
  `[FACTORY VERIFICATION]`-diagnose), loopt de developer terug tot de bestaande loopback-cap en
  wordt er nooit gemerged of gedeployed. Is het groen, dan lopen merge en deploy volledig
  ongewijzigd door (inclusief de CI-controle op de actuele PR-head en de deploy-verificatie).
  `ApprovalMode` telt binnen een hotfix niet mee: er ontstaat geen manual-approve-poort en de
  goedkeuring is automatisch. Vragen werken wél gewoon volgens As 1.
- Deze as is uitsluitend bij het **aanmaken** van een story te zetten — via het dashboarddialoog,
  `sf-story create --hotfix`, `POST /api/tracker/stories` en de bridge-operatie `story.create`.
  Zonder expliciete waarde is een story géén hotfix; bestaande stories en auditvoorstellen worden
  het nooit alsnog, en de vlag is achteraf niet te wijzigen.

Een door een audit voorgestelde vervolg-story (`AuditGatewayAdapter.proposeStoryIfAny`) is juist
géén silent story: vragen zijn toegestaan (`questionsAllowed = true`), hij is nooit een hotfix
(`hotfix = false`) en de story start in de wachtrij (`StoryPhase.START_NEXT`) in plaats van meteen,
zie hierboven onder "Audits".

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
- **Wat er in het bericht staat (SF-1830, kop uitgebreid in SF-1858)** — de kop
  `🚀 Story <KEY>: <TITEL> is deployed!` (zonder titel: `🚀 Story <KEY> is deployed!`; een erg lange
  titel wordt op 120 tekens afgekapt met `…`), daaronder een korte functionele samenvatting in
  gewone taal (max. ~3 zinnen, geschreven door de summarizer;
  ontbreekt die, dan de `## Samenvatting` uit de story zelf) en daaronder de link (live-URL of
  APK-download) als die er is. Geen technische bevestigingszin en geen subtaaklijst. Is er geen
  samenvatting én geen link, dan bestaat het bericht alleen uit de kop.
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

Een auditor die niet verder kan zonder een menselijke beslissing eindigt met een **vraag** in plaats
van een rapport. Die auditjob is daarmee klaar (status `asked`) en levert géén rapport — dat is dus
geen vastloper, maar een audit die op een antwoord wacht. De vraag komt als Telegram-melding binnen
en staat op het Audits-scherm; je beantwoordt hem daar of met een reply op de melding. Daarna plant
de factory zelf een vervolgrun in die de vraag, het antwoord en de eerdere bevindingen meekrijgt en
de audit alsnog afmaakt; handmatig herstarten is niet nodig. Zie `runbook.md` voor de triage.

Configuratie, structuur en het exacte agent-contract staan in `.factory/nightly/README.md` (single
source of truth voor het `.factory/nightly/<audit>/job.yaml` + `prompt.md`-formaat).

## Opruimen: alle opruimrondes in één overzicht (SF-1913 / SF-1921 / SF-1938 / SF-1939)

Elke nacht ruimt de factory per project oude releases en container-images op. Die opruimronde
meldde zichzelf in Telegram; dat is vervallen. In plaats daarvan wordt elke ronde bewaard als
historie en zichtbaar gemaakt in de dashboard-app: onder "Meer" staat het scherm **Opruimen**.

Sinds SF-1939 is dat scherm per *actie* ingedeeld in plaats van één lange lijst met alle rondes door
elkaar. Je ziet een blok per opruimactie — `github-releases`, `agent-events`, `agent-runs`,
`completion-payloads` en `workspaces`, alle vijf altijd zichtbaar — met daarin hoe de laatste ronde
afliep: hoeveel er is verwijderd, hoeveel er blijft staan, hoe lang die ronde duurde (bijvoorbeeld
`1 m 7 s`, of `< 1 s` bij een heel korte ronde) en wanneer hij liep, plus de badges `dry-run`,
`handmatig` en `fout` waar die van toepassing zijn. Bij `github-releases` staat er een regel per
project met een gelogde ronde. Heeft een actie nog nooit iets gelogd, dan staat er rustig
"laatste ronde: geen wijzigingen gelogd" — geen foutmelding en geen leeg blok. Per actie zitten twee
knoppen: **Nu draaien** start die ene opruimer meteen, en **Runs bekijken** opent de historie van
alléén die actie (nieuwste eerst, met dezelfde regels en badges als voorheen). Tikken op een ronde
opent nog steeds dezelfde detailpagina. Bovenin staat één knop **Alles draaien**.

Sinds SF-1921 gaat het niet alleen om de GitHub-rondes, maar om élk opruimmechanisme van de factory.
Let op het verschil in wat "geen rij" betekent: de nachtelijke GitHub-cleanup schrijft élke ronde
weg, ook als er niets op te ruimen viel, dus daar betekent een ontbrekende rij "niet gedraaid".
De vier factory-brede opruimers draaien elk
uur of vaker en schrijven alleen wanneer ze iets verwijderd hebben of wanneer het misging; daar
betekent geen rij simpelweg "niets te doen". Een mislukte registratie laat de opruiming zelf altijd
gewoon slagen.

Een ronde die alleen zou opruimen (dry-run) krijgt een `dry-run`-badge, een mislukte ronde een
`fout`-badge; een fout in één project houdt de andere projecten niet tegen. Tikken op een ronde
opent een volledige detailpagina met de aantallen, de eventuele foutmelding en — bij een
GitHub-ronde — de verwijderde release-tags en package-versions. Het overzicht per actie kijkt
bewust verder terug dan de historielijst, zodat een drukke opruimer de laatste ronde van een rustige
opruimer nooit uit beeld duwt.

Daarnaast worden de agent-runs zelf nu automatisch opgeruimd: een afgeronde run verdwijnt na de
bewaartermijn (standaard 90 dagen, ruimer dan de 30 dagen van de losse logregels) samen met zijn
logregels, zodat het agent-log-scherm geen runs meer toont waarvan de inhoud allang weg is. Een run
die nog loopt of waarvan de afhandeling nog niet af is, blijft altijd staan — hoe oud hij ook is.

Sinds SF-1928 hoef je niet meer op de cron of de poller te wachten: elke actie heeft een
"Nu draaien"-knop, plus één "Alles draaien" bovenin. Een klik start de ronde op de achtergrond en
antwoordt meteen — ook een lange GitHub-ronde blokkeert de UI niet. Het scherm ververst daarna vanzelf
en blijft licht doorpollen zolang er nog een ronde loopt; de afgeronde ronde verschijnt met een
`handmatig`-badge, ook als hij niets opruimde. Draait die soort al (handmatig of via het schema),
dan meldt het scherm "draait al" en staat de knop uit; staat de opruimer uit, dan meldt het dat en
gebeurt er niets. Een mislukte handmatige ronde komt als foutregel in de lijst en op de detailpagina
terecht. De dry-run-stand geldt ook voor een handmatige ronde.

Sinds SF-1938 werkt één ronde de volledige achterstand weg. Daarvóór keek de opruimer maar naar de
eerste 100 releases of images die GitHub teruggaf, waardoor het bij een grote achterstand dagen duurde
voordat de ingestelde bewaarregels klopten. Nu worden alle pagina's doorlopen (met een ruime
bovengrens van 2000 items per lijst, instelbaar). Gaat er halverwege iets mis bij GitHub, dan ruimt de
ronde op wat al opgehaald was en verschijnt daar een waarschuwing over in de log. Eén uitzondering:
lukt het niet om volledig vast te stellen welke images bij een lopende preview-PR horen, dan blijven
de images van dat project deze ronde helemaal staan en krijgt die ronde een `fout`-badge — beter een
ronde overslaan dan een draaiende preview slopen.

De historie wordt niet oneindig bewaard: rondes ouder dan de retentiegrens (default 90 dagen)
verdwijnen automatisch, voor alle soorten. Er is geen paginering in het scherm; het
opruim-algoritme voor releases en packages zelf is ongewijzigd. Zie
`docs/factory/technical-spec.md` §Opruimen en `docs/technical/scheduled-jobs.md` §7 en §8.
