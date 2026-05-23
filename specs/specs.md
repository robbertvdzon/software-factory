# Software Factory — Specs

## 1. Doel

De **software factory** is een autonome pijplijn die Jira-stories door een
keten van AI-agents loodst: van refinen → ontwikkelen → reviewen → testen.
Een ticket dat in Jira de status **AI** krijgt, wordt door de factory
opgepakt en doorloopt automatisch alle fases tot het ticket succesvol
getest is — of vastloopt op een vraag voor de gebruiker, of het budget
opraakt.

De factory is **één centraal systeem** dat **meerdere target-repo's**
kan bouwen. De factory-code zelf leeft in een eigen repo, de
applicatie-repo's die de factory bouwt (`personal-news-feed`,
eventueel meer in de toekomst) staan los daarvan. Per Jira-ticket
wordt aangegeven welke target-repo de fix/feature moet krijgen
(zie §3.2 en §4).

Eén Jira-board (één Jira-project `KAN`) bedient alle target-repo's —
nodig omdat het gratis Jira-account maar één board ondersteunt.

**De factory draait volledig lokaal op de laptop van de gebruiker.**
De orchestrator is een lokaal Spring Boot-proces. Agents draaien als
Docker containers via de lokale Docker daemon. Geen Kubernetes, geen
OpenShift voor de factory zelf — OpenShift is alleen relevant als
**deploy-target** van de target-apps (waar de tester naartoe praat
om preview-deploys te testen). De AI-licentie waarmee de gebruiker
op zijn laptop is ingelogd wordt door alle agents hergebruikt.

---

## 2. Architectuur op hoofdlijnen

- **Taal/stack:** Kotlin, JDK 21, Spring Boot, Maven,
  Spring Modulith.
- **Orchestrator:** één Spring Boot-proces dat lokaal op de laptop
  draait (`mvn spring-boot:run` of `java -jar`). Hij polt Jira,
  dispatcht agent-containers via Docker, doet recovery, en biedt
  lokale HTTP-endpoints voor de agents (usage-rapportage,
  tips-database).
- **Jira-detectie:** polling (geen webhooks). De orchestrator polt elke
  **15 seconden** alle tickets met status `AI`.
- **Agent-runtime:** elke agent draait als losse **Docker container**
  (één container per agent-run) die wordt gestart door de orchestrator
  via de lokale Docker daemon (`docker run --rm …` of de Docker SDK
  voor Java). Container exit = agent klaar. Twee images: een
  **agent-base** (refiner/developer/reviewer) en een **agent-tester**
  die extra browser- en cluster-tooling meebrengt.
- **Isolatie per ticket:** elke agent-run werkt op een eigen
  shallow git-clone van de target-repo (uit het `Target Repo`-veld
  van het ticket) in een tempdir op de laptop, die als volume in de
  container gemount wordt.
- **Multi-repo:** de factory weet vooraf niets van welke repo's
  bestaan. De target-repo-URL komt per ticket binnen via Jira en
  wordt direct gebruikt voor de clone. Elke target-repo bevat een
  vaste documentatie-map `docs/factory/` waarin per-repo-config
  staat (preview-URL, deploy-info, agent-instructies — zie §4).
- **AI-aanroep:** agents praten met een AI-model via een CLI tool
  (placeholder; concrete tool wordt in een latere iteratie vastgelegd
  — zie §8). De CLI gebruikt de **lokale gebruikers-licentie** die
  via een gemounte credentials-directory of env-var token wordt
  doorgegeven aan elke container — zie §13 en §17.
- **Persistentie:** **Neon Postgres**, één database, eigen schema
  `software_factory`. Dit moet expliciet **niet** het schema
  `factory` zijn, omdat dat schema al door een ander systeem gebruikt
  wordt. Schema-migraties via Flyway. Connection-string en schema-naam
  via env-vars op de orchestrator én op elke agent-container.
- **Geen Kubernetes-orkestratie.** Als de laptop slaapt of de factory
  herstart: de orchestrator pakt op via DB- en Jira-state (idempotent).
  Lopende containers die geïnterrumpeerd zijn worden via stuck-detection
  hersteld (zie §6.3).

---

## 3. Jira-integratie

### 3.1 Status

De factory gebruikt **één Jira-status: `AI`**. Zolang een ticket op
`AI` staat, monitort de orchestrator het. Wat er precies gebeurt
binnen die status wordt bepaald door de custom fields uit §3.2
(vooral `AI Phase`, `Paused`, en `Error`).

**De orchestrator wijzigt nooit uit eigen beweging de Jira-status.**
Status-wisselen is altijd een mensactie. Wanneer de factory klaar
is met haar werk (phase = `tested-successfully`) doet de orchestrator
niets meer met het ticket — de gebruiker test desgewenst zelf, mergt
zelf, en zet zelf de status op `Done` (of geeft via een
comment/PR-mention extra feedback waardoor het ticket terug de loop
ingaat — zie §5 en §11).

**Twee uitzonderingen — beide reageren op een mensactie:**

1. De gebruiker plaatst een expliciet `@factory:command:delete`- of
   `@factory:command:merge`-comment (§11.1). De orchestrator voert
   dat commando uit inclusief de Jira-status-transitie naar `Done`.
2. De gebruiker mergt zelf de PR via GitHub. De orchestrator
   detecteert dat (§6.2) en zet de Jira-status op `Done`.

In beide gevallen is de status-wijziging een mensactie — de mens
delegeert hem alleen aan de orchestrator, ofwel via een comment,
ofwel impliciet via de merge in GitHub.

Alle andere Jira-statussen (`Todo`, `In Progress`, etc.) zijn buiten
scope van de factory: een ticket moet eerst handmatig op `AI` gezet
worden voordat de factory 'm oppakt.

### 3.2 Custom fields

| Veld              | Type                         | Default | Doel                                                                |
|-------------------|------------------------------|---------|---------------------------------------------------------------------|
| `Target Repo`     | free-text (git URL)          | leeg    | Welke target-repo de factory moet clone'n en bewerken. Verplicht.   |
| `AI Phase`        | enum (zie §5)                | leeg    | Fijnmazige state binnen de factory. Bepaalt wat de orchestrator doet.|
| `AI Level`        | number 0–10                  | 0       | Welke `(model, effort)`-matrix de agents gebruiken (zie §8).        |
| `AI Token Budget` | number                       | 40000   | Hard cap op totaal token-verbruik (alle agents samen, zie §15).     |
| `AI Tokens Used`  | number                       | 0       | Lopend totaal, onderhouden door orchestrator/cost-monitor.          |
| `AgentStartedAt`  | timestamp                    | leeg    | Wanneer de huidige actieve agent startte (voor hang-detectie).      |
| `Paused`          | boolean                      | false   | Als `true`: orchestrator slaat dit ticket over tot 'ie weer `false` is. |
| `Error`           | text                         | leeg    | Als gevuld: ticket is in fout-toestand en orchestrator pakt 'm niet op. Wordt door agents geschreven; gebruiker leegt na oplossen. |

`AI Phase`, `AgentStartedAt` en `Error` zijn systeem-velden die door
agents en orchestrator worden gezet (gebruiker mag ze handmatig
overschrijven, vooral `Error` leegmaken om hervatten). `Target Repo`,
`AI Level`, `AI Token Budget` en `Paused` worden door de gebruiker
gezet of bewerkt; `AI Tokens Used` is informatief.

**`Target Repo`-veld** is een free-text URL (bv.
`git@github.com:robbertvdzon/personal-news-feed-by-claude-code.git`
of `https://github.com/…/other-app.git`). Bij een ontbrekende of
ongeldige URL: de eerste agent (refiner) schrijft een uitleg in
`Error` en stopt. Alle URL's die de gebruiker invult worden
geaccepteerd (er is geen allowlist — alleen vertrouwde gebruikers
hebben toegang tot het Jira-board).

**`Paused`-veld** is een handgrepen-noodknop: zet 'm op `true` om
een ticket "stil" te leggen (lopende containers worden niet gekild,
maar als de huidige agent klaar is wordt er niet verder
gedispatched). De cost-monitor zet 'm ook automatisch op `true` bij
budget-overschrijding (§15). Hervatten = `false` zetten.

**`Error`-veld** is voor onherstelbare blokkades. Voorbeelden:
target-repo niet kunnen clone'n, `deployment.md`-frontmatter
onleesbaar of incompleet, GitHub-PAT expired, push naar de branch
geweigerd. Een agent die zo'n probleem detecteert schrijft een
korte beschrijving in `Error` en stopt. De orchestrator dispatcht
**niets** zolang `Error` gevuld is. Hervatten = gebruiker leegt het
veld (eventueel na een fix in de repo / config).

Een ontbrekende `docs/factory/`-map is **géén** Error — dat is een
soft-signaal dat de developer als onderdeel van zijn PR oplost
(zie §4.4 en §7.3).

### 3.3 Comment-conventie

Elke agent prefixt z'n Jira-comments met z'n rol — mensvriendelijk én
filterbaar door de orchestrator:

```
[REFINER]      Vraag over de export-feature: …
[DEVELOPER]    Implementatie klaar, branch ai/KAN-42, PR #123 open.
[REVIEWER]     Op regel 42 mist een try/catch rond …
[TESTER]       Reproductie: open /admin/users, klik "+", crasht …
[COST-MONITOR] Budget bereikt: 47K/40K tokens. Verhoog of bevestig.
[ORCHESTRATOR] Tijdelijk gepauzeerd: AI-credits uitgeput, retry over 47 min.
```

De orchestrator herkent agent-comments aan deze prefix en negeert ze
bij eventuele user-input-detectie (anders triggert een reviewer-comment
de developer steeds opnieuw).

### 3.4 Feedback van de gebruiker — comment-tracking met reacties

De refiner kan vragen stellen aan de gebruiker. De gebruiker
antwoordt in een Jira-comment. Bij re-spawn moet de agent kunnen
onderscheiden welke comments hij al verwerkt heeft en welke nieuw
zijn — anders raakt hij de draad kwijt bij meerdere antwoord-rondes.

**Mechanisme:** zodra een agent een gebruiker-comment heeft gelezen
én verwerkt, zet hij een **👀-reactie** op die comment via de
Jira REST API. Bij een volgende run weet hij: alleen comments
**zonder** zijn 👀 zijn nieuw. Comments waarop hij al heeft gereageerd
zijn afgehandeld.

- De 👀-reactie is **visueel zichtbaar** voor de gebruiker, zodat
  die weet dat zijn antwoord is opgepikt.
- Als de Jira REST API in onze instance om wat voor reden ook geen
  reacties accepteert, valt de agent terug op een tabel in de
  factory-DB (`software_factory.processed_comments`, §14.1) die per
  (ticket, comment_id, role) een `processed_at`-record bijhoudt.
- Dezelfde regel geldt voor de **developer** bij `[REVIEWER]`- en
  `[TESTER]`-comments: hij plaatst een reactie zodra hij die feedback
  heeft verwerkt, zodat hij bij een volgende loopback alleen nieuwe
  feedback hoeft te lezen.

Alleen de refiner stelt vragen aan de PO. Andere agents die ergens
niet uitkomen, schrijven in plaats daarvan in het `Error`-veld
(§3.2) en stoppen. De PO leest het probleem, lost het op (bv. door
de target-repo te corrigeren), en leegt `Error` om verder te gaan.

---

## 4. Target-repo's & repo-documentatie (`docs/factory/`)

De factory weet vooraf niets van welke target-repo's er bestaan. Per
ticket levert de gebruiker een git-URL aan in het `Target Repo`-veld
(§3.2). De orchestrator clone't die en verwacht in **elke** target-repo
een vaste documentatie-structuur waaruit agents hun context halen en
de factory zijn per-repo-config leest.

### 4.1 Standaard documentatie-structuur

Elke target-repo MOET de volgende map hebben op de root:

```
<repo-root>/
  docs/
    stories/
      KAN-42-description.md
    factory/
      README.md           ← index/inhoudsopgave + globale repo-context
      secrets-local.md    ← welke secrets/env-vars nodig zijn voor lokaal
                            draaien, en waar ze vandaan komen (bv. 1Password,
                            .env.example, cluster-secret)
      deployment.md       ← hoe de repo gedeployd wordt; YAML-frontmatter
                            met preview-URL- en namespace-templates voor de
                            factory (zie §4.3)
      development.md      ← build- en testcommando's, repo-structuur,
                            conventies; alles wat een developer moet
                            weten om lokaal te kunnen werken
      functional-spec.md  ← functionele specs (mag een sub-map worden
                            functional-spec/ als de inhoud te groot
                            wordt)
      technical-spec.md   ← welke technieken, frameworks, versies en
                            code-conventies te gebruiken
      agents/
        refiner.md        ← rol-specifieke aanwijzingen aan de refiner
        developer.md      ← idem voor developer
        reviewer.md       ← idem voor reviewer
        tester.md         ← idem voor tester (bv. login-flow, testdata,
                            hoe E2E te testen, welke pagina's belangrijk
                            zijn, hoe een `oc`-recept naar de preview-DB
                            te draaien)
```

Bestanden mogen leeg blijven als er nog niets te zeggen valt, maar
ze moeten **bestaan** — anders weet een agent niet of er info
ontbreekt of dat de repo nog niet factory-ready is.

Naast `docs/factory/` houdt de developer per Jira-story een
story-log bij onder `docs/stories/`:

```
docs/stories/<jira-key>-description.md
```

Voorbeeld: `docs/stories/KAN-42-description.md`.

Dit bestand hoort bij de PR van die story en bevat:

1. Een korte beschrijving van de story in eigen woorden.
2. Een concreet stappenplan dat de developer wil uitvoeren.
3. Onder het stappenplan: wat de developer precies gedaan heeft en
   waarom.

Het stappenplan gebruikt bewust een simpele checklist-notatie:

```markdown
[x]: create dummy controller
[ ]: implement controller
[ ]: create unit tests
```

De developer maakt dit bestand aan aan het begin van zijn eerste
developer-run voor de story. Elke keer dat hij een stap afrondt,
werkt hij dezelfde checklist bij door `[ ]` naar `[x]` te wijzigen.
Bij loopbacks vanuit review of test blijft hetzelfde story-document
de bron van het actuele plan en de uitvoeringstoelichting.

### 4.2 Hoe agents de docs gebruiken

Elke agent krijgt aan het begin van zijn run **dezelfde index van
alle aanwezige docs** + de **inhoud van zijn eigen
`agents/<role>.md`**. Op die manier weet hij van alle bestanden
*dat ze er zijn* (zonder ze allemaal volledig in de prompt te
proppen), én heeft hij zijn rol-specifieke aanwijzingen direct
beschikbaar.

Voorbeeld van wat de agent in z'n prompt-context krijgt:

```
docs/factory/ — repo-documentatie voor de software factory.
  README.md           — globale repo-context
  secrets-local.md    — secrets voor lokaal draaien
  deployment.md       — deploy-info + factory-config (preview-URL,
                        preview-namespace, …)
  development.md      — build/test-commando's, repo-structuur
  functional-spec.md  — wat de app doet
  technical-spec.md   — welke technieken/conventies te gebruiken
  agents/refiner.md   — instructies voor de refiner
  agents/developer.md — instructies voor de developer
  agents/reviewer.md  — instructies voor de reviewer
  agents/tester.md    — instructies voor de tester

Lees deze bestanden via je file-tools als je extra context nodig
hebt. Hieronder volgen je eigen rol-specifieke instructies:

<<inhoud van docs/factory/agents/<role>.md>>
```

De agent-base image krijgt een Kotlin-helper `loadFactoryDocs(role)`
die de index opbouwt (op basis van wat er fysiek bestaat in de
clone) en de eigen rol-doc inleest. Agents geven dit blok als
extra system-prompt mee aan de AI CLI. Wat ze daarna verder lezen,
bepalen ze zelf via hun gewone Read-tool.

In de praktijk kies elke rol typisch:

- **Refiner** → `functional-spec.md`
- **Developer** → `development.md` + `technical-spec.md`
- **Reviewer** → `technical-spec.md`
- **Tester** → `deployment.md` + `secrets-local.md`

Maar agents zijn vrij om verder te lezen als de story dat vraagt.

### 4.3 Machine-leesbare config in `deployment.md`

De orchestrator heeft per target-repo enkele waardes nodig die niet
in Jira passen (en niet door de gebruiker overgetypt moeten worden):
preview-URL-template, preview-namespace-template, base-branch,
branch-prefix. Die staan als YAML-frontmatter bovenaan `deployment.md`:

```markdown
---
default_base_branch: main
branch_prefix: ai/
preview_url_template: "https://pnf-pr-{pr_num}.vdzonsoftware.nl"
preview_namespace_template: "pnf-pr-{pr_num}"
preview_db_secret_recipe: |
  oc -n {preview_namespace} get secret newsfeed-database \
    -o jsonpath='{.data.PNF_DATABASE_URL}' | base64 -d
---

# Deployment

(Verdere vrije tekst voor agents — vooral de tester — om te weten
waar de app draait, hoe ArgoCD het oppakt, etc.)
```

De orchestrator parseert deze frontmatter bij elke story-pickup
(eventueel gecached per `Target Repo`-URL + commit) en gebruikt de
waardes om de juiste env-vars aan de agent-container mee te geven.
`preview_db_secret_recipe` is optioneel — als de tester de preview-DB
nodig heeft, draait hij dit shell-recept zelf in zijn container met
zijn gemounte kubeconfig.

### 4.4 Wat als `docs/factory/` ontbreekt

Bij een nieuwe target-repo waar de map nog niet bestaat: **gewoon
doorgaan**. Het is een soft-signaal, geen blokkade.

1. De runner detecteert bij het clone'n dat `docs/factory/`
   ontbreekt en voegt een **bootstrap-notice** toe aan de task-context
   die naar de agent gaat:

   > Deze repo heeft nog geen `docs/factory/`-map. Er is dus nog
   > geen extra info over deze codebase beschikbaar buiten wat in
   > de Jira-story staat. De **developer** wordt geacht de map en
   > de standaardbestanden aan te maken op basis van de skeleton-
   > template (gemount op `/usr/local/share/factory/docs-skeleton/`)
   > en aan te vullen met informatie uit deze story en de
   > bestaande repo-structuur, als onderdeel van zijn PR.
2. De **refiner** neemt dit op als extra acceptance-criterion in
   zijn refined story ("plus: maak `docs/factory/` aan en vul de
   bestanden").
3. De **developer** voert dat criterion samen met de feature uit
   in dezelfde PR — `docs/factory/` aanmaken vanuit de skeleton-
   template, en de bestanden vullen met wat hij weet over de repo
   en de story.
4. Vanaf de volgende story heeft die repo z'n docs en gedraagt 'ie
   zich normaal.

De skeleton-template (`/usr/local/share/factory/docs-skeleton/` in
de agent-image) bevat alle bestanden uit §4.1 met placeholder-content
en commentaarregels die de developer helpen invullen.

### 4.5 `factory init-repo` — bootstrap-commando

De orchestrator-jar bevat een klein CLI-commando dat de skeleton in
een bestaande repo aanlegt:

```
factory init-repo .
```

Dit zet alle bestanden uit §4.1 neer met placeholder-content en de
juiste YAML-frontmatter-keuzes uit een paar interactieve prompts
(of via flags). Bedoeld om eenmalig aan te roepen per nieuwe
target-repo.

---

## 5. Phase state machine

### 5.1 Phase-waardes

**Active phases** (er draait een agent — orchestrator zet deze):

| Phase        | Betekenis                  |
|--------------|----------------------------|
| `refining`   | Refiner draait.            |
| `developing` | Developer draait.          |
| `reviewing`  | Reviewer draait.           |
| `testing`    | Tester draait.             |

**Completed phases** (agent klaar — agents zetten deze):

| Phase                                  | Betekenis                                              | Orchestrator dispatcht hierna |
|----------------------------------------|--------------------------------------------------------|-------------------------------|
| `refined-with-questions-for-user`      | Refiner heeft openstaande vragen — wacht op gebruiker. | niets (wacht op antwoord)     |
| `refined-finished`                     | Refinement klaar, klaar om te ontwikkelen.             | developer → `developing`      |
| `developed`                            | Developer-code in branch + PR open.                    | reviewer → `reviewing`        |
| `reviewed-with-feedback-for-developer` | Reviewer heeft op- of aanmerkingen.                    | developer → `developing`      |
| `review-finished`                      | Review akkoord.                                        | tester → `testing`            |
| `tested-with-feedback-for-developer`   | Tester vond bug(s).                                    | developer → `developing`      |
| `tested-successfully`                   | Factory is klaar. Orchestrator doet niets meer met dit ticket. | niets — gebruiker test zelf, mergt, en zet Jira-status op `Done`. Of geeft feedback (zie hieronder). |

**Speciale phase:**

| Phase                                   | Betekenis                                                                                  |
|-----------------------------------------|--------------------------------------------------------------------------------------------|
| `questions-answered-for-refinement`     | Gebruiker heeft de refiner-vragen beantwoord (eventueel via comment-trigger of handmatig de phase gezet). Orchestrator dispatcht de refiner opnieuw. |

### 5.2 Transitietabel (door de orchestrator, behalve waar anders aangegeven)

```
(leeg)                                  → start refiner    → phase=refining
refined-with-questions-for-user         → (wachten — gebruiker antwoordt en zet phase=questions-answered-for-refinement)
questions-answered-for-refinement       → start refiner    → phase=refining
refined-finished                        → start developer  → phase=developing
developed                               → start reviewer   → phase=reviewing
reviewed-with-feedback-for-developer    → start developer  → phase=developing
review-finished                         → start tester     → phase=testing
tested-with-feedback-for-developer      → start developer  → phase=developing
tested-successfully                      → (orchestrator stopt; gebruiker is aan zet — zie §5.3)
```

Agents zetten zelf de "klare" phase (`developed`, `review-finished`,
etc.) bij voltooiing. De orchestrator detecteert die bij de volgende
poll, schrijft de bijbehorende `*ing`-phase en start de volgende agent.

**Geen aparte phase voor "wacht op PO" buiten de refiner.** Andere
agents die ergens niet uitkomen schrijven in `Error` en stoppen
(zie §3.2 / §3.4). De orchestrator pakt op zodra `Error` weer leeg is.

### 5.3 Na `tested-successfully`

De orchestrator stopt met dit ticket — er gebeurt niets meer
automatisch. De gebruiker heeft nu drie opties:

1. **Accepteren:** zelf de PR mergen en de Jira-status op `Done`
   zetten. Klaar.
2. **Feedback geven via Jira-comment:** een comment schrijven en
   de phase terug zetten naar `tested-with-feedback-for-developer`
   (of `reviewed-with-feedback-for-developer`). Orchestrator pakt
   weer op en stuurt naar de developer.
3. **Feedback geven via PR-comment:** een `@factory`-mention in
   de PR plaatsen. De PR-comment-iteratie-flow (§11.3) pakt 'm op.

---

## 6. Orchestrator

### 6.1 Polling

- **Poll-interval:** 15 seconden (Spring Scheduled-task).
- **Globale checks vooraf** (per cyclus, één keer voor alle stories):
    - Staan we in een **AI-credits-pauze** (§16)? Zo ja → niets dispatchen
      deze ronde.
- **Per ticket** (alle Jira-tickets met status `AI`):
    1. **Skip** als `Paused = true` (§3.2).
    2. **Skip** als `Error` gevuld (§3.2).
    3. Bepaal aan de hand van `AI Phase` wat de volgende actie is
       (zie §5.2).
    4. Als een agent gestart moet worden (concurrency-cap toelaat —
       zie §6.4): zet `AI Phase` op de actieve waarde (`refining`,
       `developing`, …), zet `AgentStartedAt` op nu, start de
       bijbehorende Docker container (zie §13).
    5. Als Phase een `*ing`-waarde is (er hoort een agent te draaien):
       check via de Docker daemon of de container nog bestaat en actief
       is. Zo niet → §6.3 stuck-detection.
    6. Als Phase `refined-with-questions-for-user` is: niets doen.
    7. Als Phase `tested-successfully` is: niets doen — gebruiker is
       aan zet (zie §5.3).

### 6.2 Merge-detectie

Naast de phase-driven dispatch monitort de orchestrator open PR's
van actieve stories. Zodra hij ziet dat een PR gemerged is, zet hij
de Jira-status op `Done` en sluit het story-run-record af
(`ended_at` + `final_status`).

Dit is niet in strijd met de regel "orchestrator wijzigt nooit uit
eigen beweging de Jira-status" (§3.1): het mergen zelf is een
**mensactie** (gebeurt via GitHub), en `Done` is het logische gevolg
daarvan. De orchestrator volgt hier de mens, hij neemt geen eigen
beslissing.

Naast deze detectie kan de gebruiker ook expliciet via Jira-comments
naar `Done` springen — zie `@factory:command:merge` en
`@factory:command:delete` in §11.1.

### 6.3 Stuck-detection & recovery

Phase staat op `*ing` maar er draait geen container (gecrashte agent,
laptop opnieuw opgestart, Docker daemon herstart, etc.). De
orchestrator scant elke cyclus op deze inconsistentie en herstelt:

- **Forward-recovery** — als er een succesvolle agent-run in de DB
  staat voor de huidige phase maar de phase nog niet bijgewerkt is:
  zet de bijbehorende completed-phase. (Voorbeeld: refiner liep
  succesvol, postte een comment, maar crashte voor hij `AI Phase`
  kon updaten → orchestrator zet `refined-finished` alsnog.)
- **Backward-retry** — als de laatste agent-run gefaald is met een
  transient fout (HTTP 429, "API error 500", "rate limit", "timeout"
  in de samenvatting): zet phase terug naar de vorige completed-phase
  zodat dezelfde agent opnieuw start. Hard cap: **max 2 opeenvolgende
  transient retries** per rol per story; daarna schrijft de
  orchestrator naar `Error` met een uitleg.
- **Hard timeout** — `AgentStartedAt` ouder dan **60 minuten** zonder
  voortgang (configureerbaar): log + schrijf naar `Error`.

### 6.4 Concurrency

Caps per rol (configureerbaar, defaults):

```
SF_MAX_PARALLEL_REFINER   = 1
SF_MAX_PARALLEL_DEVELOPER = 2
SF_MAX_PARALLEL_REVIEWER  = 2
SF_MAX_PARALLEL_TESTER    = 1   # tester is duur door browser-container
SF_MAX_PARALLEL_TOTAAL    = 4   # globale veiligheid (passen op laptop-resources)
```

Bij hitting van een cap: stories die gedispatched zouden worden
blijven gewoon op `AI` staan met hun huidige phase; volgende
poll-tick probeert opnieuw.

Per **PR** geldt bovendien een cap van **1 actieve agent**, om te
voorkomen dat twee dispatch-paden tegelijk dezelfde branch verbouwen.

### 6.5 Developer-loopback cap

Om te voorkomen dat een ticket eindeloos heen-en-weer kaatst tussen
developer ↔ reviewer of developer ↔ tester, is er een harde cap van
**5 developer-loopbacks** per story. De orchestrator telt in
`software_factory.agent_runs` hoe vaak hij voor deze story al `developing`
heeft gedispatcht (excl. de eerste, initiële run). Bij de zesde
keer dispatcht 'ie niet opnieuw maar schrijft naar `Error`:

```
[ORCHESTRATOR] Developer-loopback cap bereikt (5×). Handmatige
triage nodig. Geef feedback en leeg `Error` om opnieuw te proberen,
of zet `Paused = true` en parkeer dit ticket.
```

Cap is configureerbaar via env-var (`SF_MAX_DEVELOPER_LOOPBACKS`,
default 5).

---

## 7. Agents

### 7.1 Algemeen

Alle agents:

- Lezen het ticket (inclusief comments + reacties) uit Jira.
- Lezen de per-rol `docs/factory/`-documenten uit de target-repo
  (zie §4.2).
- Hebben toegang tot de tips-database (lezen + schrijven, alleen
  eigen rol — zie §9).
- Hebben toegang tot een AI-model via een CLI tool (placeholder).
  De auth gaat via de **lokale gebruikers-licentie** die de
  orchestrator in de container mount of als env-var doorgeeft —
  zie §17.
- Werken aan een eigen shallow git-clone van de target-repo in
  een tempdir op de laptop, die als volume in de container gemount
  is.
- Schrijven hun resultaat terug naar Jira: nieuwe Phase + eventuele
  comment (met `[ROLE]`-prefix).
- Plaatsen reacties op user-comments die ze hebben verwerkt (§3.4).
- Schrijven bij een onherstelbare blokkade naar het `Error`-veld
  (§3.2 / §3.4) en stoppen.
- Rapporteren bij voltooiing token-usage + events naar de
  orchestrator (HTTP `POST /agent-run/complete` op
  `http://host.docker.internal:<port>`) — voor cost-monitor en
  observability.
- Exit-code 0 = succes, non-zero = fout (orchestrator logt en
  triggert stuck-detection bij volgende cyclus).

### 7.2 Refiner

- Input: ruwe story + alle prior comments (met respect voor reacties
  uit §3.4 — eerder verwerkte comments mag hij negeren) +
  `docs/factory/functional-spec.md` van de target-repo.
- Output: opgeschoonde story (acceptatie-criteria, scope-afbakening)
  → Phase `refined-finished`,
  óf openstaande vragen als comment → Phase
  `refined-with-questions-for-user`.
- **Tool-allowlist:** alleen Jira-API + read op de repo; **geen**
  edit/write tools (zodat de refiner per ongeluk geen code schrijft).
- Belangrijk: bij een tweede ronde refinen leest hij **alleen**
  user-comments zonder zijn reactie. Comments waarop hij al
  gereageerd heeft zijn afgehandeld.
- Als `docs/factory/` ontbreekt in de target-repo: de runner heeft
  een bootstrap-notice in `task.md` gezet (zie §4.4). De refiner
  neemt die op als extra acceptance-criterion in zijn refined story
  ("plus: maak `docs/factory/` aan vanuit de skeleton-template") en
  gaat normaal door — geen `Error`, geen blokkade.

### 7.3 Developer

- Input: refined story + review- en test-feedback uit comments
  (alleen die zonder zijn reactie zijn nieuw) +
  `docs/factory/development.md` + `docs/factory/technical-spec.md`.
- Krijgt via env-var `SF_DEVELOPER_LOOPBACK_REASON` een hint mee als hij
  vanuit een review- of test-loopback is gespawnd: "lees eerst het
  laatste `[REVIEWER]`/`[TESTER]`-comment".
- Output: code-wijzigingen in een branch (`<branch-prefix><ticket-key>`,
  bv. `ai/KAN-42`; doorgegeven als `SF_BRANCH_PREFIX` +
  `SF_TICKET_KEY`), commit + push, GitHub PR open of bestaande PR
  updaten → Phase `developed`.
- Maakt aan het begin van de eerste developer-run voor deze story
  een story-document in de target-repo:
  `docs/stories/<jira-key>-description.md` (bv.
  `docs/stories/KAN-42-description.md`). Dit document bevat de story
  in eigen woorden, een checklist-stappenplan met `[ ]:` / `[x]:`,
  en daaronder een toelichting op wat hij precies gedaan heeft en
  waarom.
- Werkt het story-document tijdens de implementatie actief bij:
  afgeronde stappen worden van `[ ]` naar `[x]` gezet, nieuwe
  inzichten of extra stappen worden toegevoegd, en bij review/test-
  loopbacks wordt hetzelfde document verder bijgewerkt in plaats van
  een nieuw document te maken.
- Markeert verwerkte reviewer-/tester-comments met een reactie (§3.4).
- Bij blokkade (bv. merge-conflict op de basebranch): schrijft naar
  `Error` en stopt.

### 7.4 Reviewer

- Input: de PR-diff (via `gh pr diff` of equivalent) + refined story
    + `docs/factory/technical-spec.md`.
- **Tool-allowlist:** read-only op repo, `gh` CLI, Jira-API; **geen**
  edit/write, **geen** git push.
- Output: review-feedback als comment (met `[REVIEWER]`-prefix) →
  Phase `reviewed-with-feedback-for-developer`,
  óf goedkeuring → Phase `review-finished`.

### 7.5 Tester

De gevaarlijkste agent qua blast-radius — verdient extra grenzen.

- Image: **agent-tester** (zie §12).
- Input: refined story + PR-diff + `docs/factory/deployment.md` +
  `docs/factory/secrets-local.md` + `docs/factory/agents/tester.md`.
- **Tooling in de container:** Playwright + Chromium voor headless
  browser, `psql` tegen de preview-DB, `kubectl`/`oc` tegen de
  cluster via een **gemounte kubeconfig** (zie §13 en §17).
- **Toegang tot OpenShift:** de tester praat met de remote cluster
  vanaf de laptop met de kubeconfig die de gebruiker zelf gebruikt.
  Wat hij wel/niet mag is dus dezelfde set rechten als de gebruiker
  in zijn `oc whoami` heeft — er is geen aparte ServiceAccount of
  cluster-RBAC voor de tester.
- Wacht aan het begin tot de **preview-deploy** van de PR live is
  (HTTP 200 op de preview-URL die door de orchestrator uit
  `deployment.md` getemplate't is, max 10 min polling per 15 s — zie
  §10).
- Krijgt `SF_PREVIEW_URL`, `SF_PREVIEW_NAMESPACE` en (optioneel)
  `SF_PREVIEW_DB_URL` als env-vars mee. Als `SF_PREVIEW_DB_URL` niet
  vooraf bekend is, voert hij het `preview_db_secret_recipe` uit
  `deployment.md` zelf uit.
- Output: bij bug(s) → comment met reproductie-stappen + logs →
  Phase `tested-with-feedback-for-developer`. Bij OK → Phase
  `tested-successfully`.
- **System-prompt-grenzen** (omdat er geen cluster-RBAC is, leunen
  we op de prompt — let hier dus extra op):
    - **MAG NIET**: infrastructuur muteren in productie-namespaces,
      git-commits maken, secrets aanpassen, prod-namespace muteren.
    - **MAG WEL**: lezen van alles in de cluster waar de gebruikers-
      kubeconfig toegang toe heeft, `oc exec` in de preview-pods,
      DB-queries lezen + schrijven in de preview-DB, een pod-restart
      forceren in de preview-namespace.

---

## 8. AI-aanroep — dummy-implementatie

De keuze voor een concrete AI-CLI (Claude Code CLI, Codex, of iets
anders) is **uitgesteld**. Elke agent doet zijn werk via een
**dummy-implementatie** die random gedrag genereert. Dat is genoeg
om de hele factory-flow (orchestrator, phase-machine, Docker-runner,
cost-monitor, tips-DB, error-handling) end-to-end te laten draaien
zonder AI-credits te verbruiken of vast te zitten aan een CLI-keuze.
Zodra een AI-CLI gekozen is wordt de dummy vervangen door een echte
implementatie zonder dat de orchestratie eromheen verandert.

### 8.1 Interface

De agent-code definieert een Kotlin-interface `AiClient` met één
concrete implementatie: `DummyAiClient`. Een echte implementatie
(`ClaudeCliClient`, `CodexCliClient`, …) implementeert dezelfde
interface zodat hij plug-and-play wisselbaar is.

### 8.2 Dummy-gedrag per rol

Iedere agent doet het volgende met de dummy:

| Rol       | Gedrag                                                                                                                          |
|-----------|---------------------------------------------------------------------------------------------------------------------------------|
| Refiner   | 70 % → `phase=refined-finished` + comment `[REFINER] (dummy) refinement OK`. 30 % → `phase=refined-with-questions-for-user` + comment `[REFINER] (dummy) vraag aan PO: …`. |
| Developer | Altijd: maak/update `docs/stories/<jira-key>-description.md` met een dummy-story, checklist en toelichting; voeg daarnaast een placeholder-regel toe aan een bestand in de repo (bv. een timestamp in `docs/factory/.dummy-log`), commit + push, open of update PR, `phase=developed`, comment `[DEVELOPER] (dummy) placeholder-wijziging gepushed`. |
| Reviewer  | 70 % → `phase=review-finished` + comment `[REVIEWER] (dummy) review OK`. 30 % → `phase=reviewed-with-feedback-for-developer` + comment `[REVIEWER] (dummy) feedback: …`. |
| Tester    | 70 % → `phase=tested-successfully` + comment `[TESTER] (dummy) tests OK`. 30 % → `phase=tested-with-feedback-for-developer` + comment `[TESTER] (dummy) bug: …`. |

De dummy:

- Rapporteert **fake token-tellingen** via `POST /agent-run/complete`
  (random input 1.000–5.000, output 500–2.000) zodat de cost-monitor
  een realistisch beeld krijgt en je de budget-pauze-flow kunt testen.
- Negeert `SF_AI_LEVEL`, `SF_AI_MODEL` en `SF_AI_EFFORT` env-vars
  (deze blijven wel gezet zodat de orchestrator-flow ongewijzigd is).
- Slaapt **5–15 s** voordat hij rapporteert, om realistische timing
  te simuleren.
- Kan via env-var `SF_DUMMY_FORCE_OUTCOME=ok|questions|feedback|bug|error`
  geforceerd worden tot een specifieke uitkomst — handig voor
  integratie-tests waarbij je een bepaalde flow wilt valideren.

### 8.3 Toekomstige model-routing

Wanneer een echte AI-CLI wordt aangesloten komt er een level-matrix
die per `AI Level` (0–10) en per rol een `(model, effort)`-paar
oplevert. Voorbeeld-ontwerp (concretiseren bij CLI-keuze):

- Per level 0..10 een mapping naar een tier (`cheap`, `mid`,
  `premium`, …).
- Per tier een concreet model + effort/thinking-budget.
- Goedkoper voor lage levels, duurder/diepgaander voor hogere.

Zolang de dummy-implementatie actief is worden `SF_AI_LEVEL`,
`SF_AI_MODEL` en `SF_AI_EFFORT` wel netjes doorgegeven aan de
container (om de plumbing te valideren), maar de dummy negeert ze.

### 8.4 Override via comment

De gebruiker kan `AI Level` op elk moment aanpassen via het
Jira-veld, of via een comment-trigger `LEVEL=N` (zie §11.2). Het
veld wordt netjes opgeslagen en doorgegeven, maar heeft pas effect
wanneer de echte AI-CLI is aangesloten.

---

## 9. Tips-database (agent-knowledge)

Agents bewaren herbruikbare kennis. Bv. de tester ontdekt hoe je in
een specifieke applicatie inlogt — die kennis slaat hij op zodat hij
dat de volgende keer niet opnieuw hoeft uit te zoeken.

- **Tips zijn per target-repo geïsoleerd.** De refiner-tips van repo
  X zijn niet zichtbaar voor agents die aan repo Y werken. Iedere
  repo bouwt z'n eigen kennisbestand op per rol.
- **Eén tabel** `software_factory.agent_knowledge` met
  `(target_repo, role, category, key)` als unieke sleutel.
  Upsert-semantiek: last-writer-wins binnen dezelfde repo+rol+key.
- **Toegang:** elke agent leest/schrijft alleen records van zijn
  eigen rol én zijn eigen target-repo. Afgedwongen door de
  HTTP-endpoints (`GET /agent-knowledge?target_repo=<repo>&role=<role>`
  en `POST /agent-knowledge/update` met `target_repo` + `role` in
  de body) die de orchestrator serveert. De runner geeft beide
  waarden mee uit de env-vars `SF_REPO_URL` + `SF_AGENT_TYPE`.
  De orchestrator normaliseert `SF_REPO_URL` (strip protocol +
  `.git`-suffix → bv. `github.com/robbertvdzon/personal-news-feed-by-claude-code`)
  voordat 'ie 'm als `target_repo` opslaat in de DB, zodat HTTPS-
  en SSH-URL's naar dezelfde repo niet twee aparte tips-buckets
  opleveren.
- **Flow:** runner pakt aan het begin de tips op (geserialiseerd als
  markdown) en zet ze in `/work/repo/.agent-tips.md` zodat de
  AI-CLI ze in z'n context kan opnemen. Aan het einde schrijft de
  agent eventuele nieuwe/gewijzigde tips als JSON-blok in zijn
  output; de runner POST't die naar de orchestrator.
- **Velden** (zie ook §14): `id`, `target_repo`, `role`, `category`,
  `key`, `content`, `created_at`, `updated_at`, `updated_by_story`.

Tips die over de AI CLI of de factory zelf gaan (bv. "Playwright in
deze image kan flaky zijn met `goto()` zonder waitFor") en dus voor
alle repo's gelden, horen niet in de tips-DB maar in de
`docs/factory/agents/<rol>.md` van de **factory-repo zelf**, of als
hard-coded richtlijn in de agent-system-prompt.

---

## 10. PR-flow & preview-deploy

### 10.1 Branch & PR

- Branch-naam: `<branch-prefix><ticket-key>`, bv. `ai/KAN-42`.
  De env-vars heten `SF_BRANCH_PREFIX` en `SF_TICKET_KEY`.
  `SF_BRANCH_PREFIX` komt uit `docs/factory/deployment.md`-
  frontmatter van de target-repo, default `ai/`.
- Developer doet `git clone --depth 50` van de URL uit `Target Repo`
  en checkt de branch uit (of maakt 'm aan vanaf `SF_BASE_BRANCH` —
  default `main`, override via deployment.md-frontmatter).
- Bij voltooiing: `git push` + (indien nog niet bestaand)
  `gh pr create`. Bestaande PR wordt vanzelf bijgewerkt door de push.
- Bij loopback (review/test → developer): dezelfde branch en PR
  worden hergebruikt — geen nieuwe PR per iteratie.

### 10.2 Preview-deploy per PR

Voor elke open PR wordt automatisch een **preview-deploy** opgeslingerd
in een eigen namespace in de OpenShift cluster (door externe tooling
per target-repo; buiten scope van deze spec, maar de factory leunt
erop).

- De **preview-URL en preview-namespace komen uit
  `docs/factory/deployment.md`** van de target-repo via het
  `preview_url_template` / `preview_namespace_template`-veld in de
  YAML-frontmatter (zie §4.3). De orchestrator vult `{pr_num}` in en
  geeft de resulterende strings als `SF_PREVIEW_URL` en
  `SF_PREVIEW_NAMESPACE` env-vars mee aan de tester-container.
- De tester krijgt ook `SF_PREVIEW_DB_URL` als env-var (apart
  Postgres-schema per PR). Als de URL niet direct beschikbaar is,
  voert de tester `preview_db_secret_recipe` uit `deployment.md`
  uit (zie §4.3).
- De tester wacht tot de preview HTTP 200 geeft voor hij begint.
- Opruimen van de preview-namespace bij merge of bij een handmatig
  commando (delete/re-implement — zie §11) gebeurt door de
  orchestrator via remote `oc delete project` met de gemounte
  kubeconfig (zie §17).

---

## 11. Handmatige bediening via comments

De gebruiker kan op elk moment via Jira-comments ingrijpen. De
orchestrator scant alle actieve stories per poll-cyclus op
commando-comments en triggers, en is idempotent (een verwerkte
comment krijgt een marker-reactie of marker-suffix zodat 'ie maar
één keer wordt uitgevoerd).

### 11.1 Commando's

| Comment                         | Effect                                                                                                                |
|---------------------------------|-----------------------------------------------------------------------------------------------------------------------|
| `@factory:command:pause`        | Zet `Paused = true`. Lopende containers blijven draaien tot ze klaar zijn; daarna geen nieuwe dispatch.               |
| `@factory:command:resume`       | Zet `Paused = false` (en leegt `Error` als die gevuld is door cost-monitor). Story wordt weer opgepakt.               |
| `@factory:command:kill`         | Kill lopende container (`docker kill`) en zet `Paused = true`. Voor wanneer een agent moet stoppen, niet alleen na completion. |
| `@factory:command:re-implement` | Kill containers, sluit PR, delete preview-namespace, delete agent-comments, wis `AI Phase` (factory start opnieuw vanaf begin). Jira-status blijft `AI`. |
| `@factory:command:delete`       | Kill containers, sluit PR + branch, delete preview-namespace, prepend `(CANCELLED)` aan de titel, **status → `Done`**. |
| `@factory:command:merge`        | Squash-merge de PR, kill containers, delete preview-namespace, **status → `Done`**.                                   |

De gebruiker kan natuurlijk ook gewoon het `Paused`- of `Error`-veld
in Jira direct bewerken — comments zijn er voor 't gemak.

**Uitzondering op de regel "orchestrator wijzigt nooit de Jira-status"
(§3.1):** de `delete`- en `merge`-commando's zijn expliciete
mensacties die via de comment-syntax worden gedelegeerd aan de
orchestrator. Omdat de gebruiker zelf het commando typt, mag de
orchestrator in **deze twee gevallen** de Jira-status naar `Done`
zetten als onderdeel van het commando. Verder gebeurt status-wisselen
nog steeds door de mens zelf.

### 11.2 Triggers in vrije comments

| Patroon       | Effect                                                                                                          |
|---------------|-----------------------------------------------------------------------------------------------------------------|
| `LEVEL=N`     | Zet `AI Level` op N (0–10).                                                                                     |
| `BUDGET=N`    | Zet `AI Token Budget` op N tokens (absoluut) en zet `Paused = false` als de story door cost-monitor gepauzeerd was. |
| `CONTINUE`    | Verhoogt `AI Token Budget` met +50% en zet `Paused = false`. Alleen actief op stories die door cost-monitor gepauzeerd zijn. |

### 11.3 PR-comment iteratie

Na het openen van een PR kan iemand in de PR-comments verder
ingrijpen met een `@factory`-mention. De orchestrator scant open
PR's op zulke triggers:

- Idempotentie via GitHub-comment-reacties:
    - 👀 = "claimed" (orchestrator heeft 'm opgepikt)
    - 🚀 = "done" (developer succesvol verwerkt)
    - 😕 = "failed"
- Context-build: alle PR-comments sinds de laatste 🚀-reactie worden
  als task-bundel doorgegeven aan een developer-container in
  `mode=comment`.
- Comments met agent-prefix (`[REVIEWER]`, `[TESTER]`, …) worden
  expliciet **genegeerd** als trigger — anders zou de developer
  zichzelf loopen.

---

## 12. Docker images

Twee images voor de agents — de orchestrator zelf wordt **geen** image
en draait gewoon als JVM-proces op de laptop.

| Image          | Basis                      | Extra                                                                | Wie gebruikt 'm                  |
|----------------|----------------------------|----------------------------------------------------------------------|----------------------------------|
| `agent-base`   | `eclipse-temurin:21-jdk`   | Node 22, git, GitHub CLI (`gh`), `jq`, AI CLI, Kotlin agent-CLI.     | refiner, developer, reviewer     |
| `agent-tester` | `agent-base`               | Playwright + Chromium, `psql`, `kubectl`, `oc`, screenshot-helper.   | tester                           |

Notities:

- **Eén entrypoint per image, rol via env-var.** `agent-base` heeft
  als ENTRYPOINT de Kotlin agent-CLI; `SF_AGENT_TYPE`
  (`refiner`/`developer`/`reviewer`) bepaalt welke prompt + tool-set
    + completion-phase de agent gebruikt.
- **`agent-tester` erft de ENTRYPOINT** van `agent-base`; alleen de
  rol-detectie + extra tooling is anders.
- Lokaal opgeslagen images (geen registry strict nodig): `docker build`
  in de factory-repo bouwt beide images met tags `agent-base:local` en
  `agent-tester:local`. Voor remote-builds kan CI ze ook naar een
  registry pushen, maar dat is niet vereist voor de laptop-flow.
- Multi-stage builds voor het Kotlin agent-CLI jar (Maven-cache als
  layer, fat-jar in een slanke final layer).
- Beide Dockerfiles wonen in de factory-repo, naast de Kotlin-modules.

---

## 13. Lokale Docker-runner

Per agent-run start de orchestrator een container via de lokale Docker
daemon. Conceptueel:

```bash
docker run --rm \
  --name factory-<ticket>-<role>-<ts> \
  --label app=factory-agent \
  --label story-key=<KAN-XX> \
  --label role=<role> \
  -v <workspace-tempdir>:/work \
  -v ~/.claude:/home/runner/.claude:ro          # AI-licentie van de gebruiker
  -v ~/.kube/config:/home/runner/.kube/config:ro # alleen voor tester
  --env-file <factory-secrets-env>              # zie §17
  -e SF_TICKET_KEY=KAN-42 \
  -e SF_AGENT_TYPE=developer \
  -e SF_AI_LEVEL=3 \
  -e SF_AI_MODEL=claude-sonnet-4-6 \
  -e SF_AI_EFFORT=quick \
  -e SF_REPO_URL=git@github.com:… \
  -e SF_BASE_BRANCH=main \
  -e SF_BRANCH_PREFIX=ai/ \
  -e SF_ORCHESTRATOR_URL=http://host.docker.internal:8080 \
  -e SF_PREVIEW_URL=… \
  -e SF_PREVIEW_NAMESPACE=… \
  -e SF_PREVIEW_DB_URL=… \
  -e SF_PR_NUMBER=… \
  -e SF_DEVELOPER_LOOPBACK_REASON=… \
  agent-base:local
```

Concrete punten:

- **Implementatie:** orchestrator gebruikt de Docker Engine SDK voor
  Java (of `ProcessBuilder` met `docker run`). `--rm` zorgt dat
  containers automatisch worden opgeruimd.
- **Workspace-mount:** per agent-run maakt de orchestrator een
  tempdir aan (bv. `~/.cache/software-factory/workspaces/<ticket>-<ts>`),
  daar gebeurt de git-clone door de agent. De tempdir wordt
  opgeruimd nadat de container is geëindigd (configureerbaar: bewaar
  bij fail voor debug).
- **AI-licentie:** de credentials-directory van de AI CLI van de
  gebruiker (`~/.claude` voor Claude Code CLI; pad is configureerbaar)
  wordt **read-only** in de container gemount. Alternatief: één
  env-var met een OAuth-token (`SF_AI_OAUTH_TOKEN`). Als de gekozen
  CLI een eigen variabelenaam vereist, vertaalt de runner deze waarde
  intern naar die CLI-specifieke naam (bijvoorbeeld
  `CLAUDE_CODE_OAUTH_TOKEN` voor Claude Code CLI). Concrete keuze
  hangt af van de gekozen CLI (§8) — beide paden zijn voorzien in §17.
- **Kubeconfig (alleen tester):** `~/.kube/config` wordt read-only
  in de tester-container gemount zodat hij met `oc`/`kubectl` tegen
  de cluster kan praten met dezelfde rechten als de gebruiker.
- **Network:** containers communiceren met de orchestrator via
  `http://host.docker.internal:8080` (op macOS en Windows out of
  the box; op Linux via `--add-host=host.docker.internal:host-gateway`).
- **Logs:** Docker captured stdout/stderr; orchestrator volgt de log
  via de SDK en bewaart zo nodig in `software_factory.agent_events`. Geen
  TTL-issue zoals bij K8s — de DB is de bron van waarheid.
- **Resource-limieten:** optioneel via `--memory=2g --cpus=2`. Niet
  per se nodig op een laptop; alleen instellen als concurrency knelt.
- **Task-payload:** in plaats van K8s ConfigMaps schrijft de
  orchestrator de samengestelde context (`task.md`: story + comments
    + tips + `docs/factory/`-documenten) als bestand in de
      workspace-tempdir voordat de container start. De agent leest
      `/work/task.md`.

De runner-flow zelf (in de container): lees `/work/task.md` →
`git clone` naar `/work/repo` → `docs/factory/`-documenten lezen →
tips ophalen via HTTP → AI CLI aanroepen → output verwerken → Jira
bijwerken (phase + comment + reacties op verwerkte user-comments, of
`Error`-veld bij blokkade) → `POST /agent-run/complete` naar de
orchestrator → exit.

---

## 14. Persistentie (Neon Postgres)

Eén Neon-database, eigen schema `software_factory`. Dit schema is
bewust anders dan `factory`, omdat `factory` al door een ander systeem
gebruikt wordt en door deze applicatie niet aangeraakt mag worden.
Migraties via Flyway (versioned SQL onder `db/migration/` in de
factory-repo). Connection-string via env-var `SF_DATABASE_URL`
(`postgresql://…`-formaat, zie §17). De schema-naam staat expliciet
in `SF_DATABASE_SCHEMA` en moet voor deze applicatie
`software_factory` zijn.

### 14.1 Tabellen

```sql
CREATE SCHEMA IF NOT EXISTS software_factory;

-- Eén row per pipeline-run van een ticket.
CREATE TABLE software_factory.story_runs (
  id                          BIGSERIAL PRIMARY KEY,
  story_key                   TEXT NOT NULL,
  target_repo                 TEXT NOT NULL,        -- waarde uit Jira `Target Repo`
  started_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
  ended_at                    TIMESTAMPTZ,
  final_status                TEXT,                 -- 'Done', 'paused', 'budget-exceeded', 'error', ...
  total_input_tokens          INTEGER NOT NULL DEFAULT 0,
  total_output_tokens         INTEGER NOT NULL DEFAULT 0,
  total_cache_read_tokens     INTEGER NOT NULL DEFAULT 0,
  total_cache_creation_tokens INTEGER NOT NULL DEFAULT 0,
  total_cost_usd_est          NUMERIC(10,4) NOT NULL DEFAULT 0.0
);

-- Eén row per agent-run (lokale container).
CREATE TABLE software_factory.agent_runs (
  id                          BIGSERIAL PRIMARY KEY,
  story_run_id                BIGINT NOT NULL REFERENCES software_factory.story_runs(id) ON DELETE CASCADE,
  role                        TEXT NOT NULL,        -- 'refiner' | 'developer' | 'reviewer' | 'tester'
  container_name              TEXT NOT NULL,
  model                       TEXT,
  effort                      TEXT,
  level                       SMALLINT,
  started_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
  ended_at                    TIMESTAMPTZ,
  outcome                     TEXT,                 -- 'success' | 'failed' | 'questions' | 'killed' | 'credits-exhausted'
  input_tokens                INTEGER NOT NULL DEFAULT 0,
  output_tokens               INTEGER NOT NULL DEFAULT 0,
  cache_read_input_tokens     INTEGER NOT NULL DEFAULT 0,
  cache_creation_input_tokens INTEGER NOT NULL DEFAULT 0,
  num_turns                   INTEGER NOT NULL DEFAULT 0,
  duration_ms                 INTEGER NOT NULL DEFAULT 0,
  cost_usd_est                NUMERIC(10,4) NOT NULL DEFAULT 0.0,
  summary_text                TEXT                  -- wat de agent als comment op Jira plaatste
);

-- Eén row per stream-event uit de AI CLI (debug/replay).
CREATE TABLE software_factory.agent_events (
  id              BIGSERIAL PRIMARY KEY,
  agent_run_id    BIGINT NOT NULL REFERENCES software_factory.agent_runs(id) ON DELETE CASCADE,
  ts              TIMESTAMPTZ NOT NULL DEFAULT now(),
  kind            TEXT NOT NULL,
  payload         JSONB NOT NULL
);

-- Tips per (target-repo, rol). Per repo bouwt elke rol z'n eigen
-- kennisbestand op (zie §9).
CREATE TABLE software_factory.agent_knowledge (
  id                BIGSERIAL PRIMARY KEY,
  target_repo       TEXT NOT NULL,                -- waarde uit Jira `Target Repo`
  role              TEXT NOT NULL,                -- 'refiner' | 'developer' | 'reviewer' | 'tester'
  category          TEXT NOT NULL,
  key               TEXT NOT NULL,
  content           TEXT NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by_story  TEXT,
  UNIQUE (target_repo, role, category, key)
);

-- Verwerkte user-comments (fallback voor §3.4 als Jira-reacties
-- onbruikbaar zijn).
CREATE TABLE software_factory.processed_comments (
  id            BIGSERIAL PRIMARY KEY,
  story_key     TEXT NOT NULL,
  comment_id    TEXT NOT NULL,
  role          TEXT NOT NULL,
  processed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (story_key, comment_id, role)
);

-- Globale systeem-state (één row). Wordt door de orchestrator bij
-- start gegarandeerd via INSERT … ON CONFLICT DO NOTHING. Zie §16.
CREATE TABLE software_factory.system_state (
  id                     SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  credits_paused_until   TIMESTAMPTZ,            -- NULL of in 't verleden = niet gepauzeerd
  credits_paused_reason  TEXT
);
```

### 14.2 Secret-redactie

Vóór events naar `agent_events.payload` geschreven worden, draait een
regex-filter dat de volgende patronen vervangt door `<REDACTED>`:

- `sk-ant-(api03|oat01)-[A-Za-z0-9_-]+` (Anthropic-keys)
- `ghp_[A-Za-z0-9]{36,}` (GitHub PATs)
- `eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+` (JWT's)
- `postgresql://[^/]*:[^@]*@` (credentials in connection-strings)

Niet waterdicht, maar reduceert het ongelukken-risico aanzienlijk.
Bewaartermijn: vooralsnog ongelimiteerd.

---

## 15. Cost-monitor & token-budget

### 15.1 Wat hij doet

- Sommeert per actieve story het token-verbruik uit `software_factory.agent_runs`.
- Vergelijkt met `AI Token Budget` (default 40.000).
- Drempels:
    - **≥ 75 %** → comment `[COST-MONITOR] 75% bereikt …`, geen veld-wijziging.
    - **≥ 90 %** → comment `[COST-MONITOR] 90% bereikt …`, geen veld-wijziging.
    - **≥ 100 %** → comment `[COST-MONITOR] 100% bereikt — pauzeer.`
        + `Paused = true`. PO bepaalt of hij budget verhoogt (`BUDGET=N`
          of `CONTINUE`) of dat het ticket gepauzeerd blijft.

Idempotent: bestaande `[COST-MONITOR] N%`-markers blokkeren
herhaalde posts van dezelfde drempel.

### 15.2 Twee triggers

- **Realtime** — de runner POST't z'n usage naar
  `POST /agent-run/complete` op de orchestrator direct na completion.
  De orchestrator update `software_factory.agent_runs` + draait meteen de
  budget-check.
- **Periodieke check** — een Spring `@Scheduled`-taak in dezelfde JVM
  draait elke 5 minuten over alle actieve `story_runs` als sanity-net
  voor het geval een runner crasht vóór hij kan rapporteren.

### 15.3 Hervatten

- `BUDGET=N` in een comment op een gepauzeerde story: zet
  `AI Token Budget` absoluut en `Paused = false`, orchestrator
  hervat in de volgende cyclus.
- `CONTINUE` zonder argument: `AI Token Budget *= 1.5` en
  `Paused = false`.

---

## 16. AI-credits & systeem-pauze

Anthropic-licenties hebben een gebruiks-window (bv. de 5-uur-quota
op Claude Pro/Max). Als dat window vol zit, faalt elke nieuwe
AI-call met een rate-limit-error tot het reset. Dit is een
**systeem-brede** beperking, niet per ticket — alle agents zijn dan
tijdelijk onbruikbaar.

### 16.1 Detectie

- De runner detecteert in zijn AI CLI-output specifieke fout-signalen
  (HTTP 429 met body die op "credit exhausted" of "rate limit"
  duidt, of een `Retry-After`-header in de response).
- Hij zet `outcome = "credits-exhausted"` op zijn `agent_runs`-row
  en eindigt met exit-code ≠ 0.

### 16.2 Reactie van de orchestrator

Bij ontvangst van een `credits-exhausted`-outcome:

1. Lees uit de AI CLI-output (indien beschikbaar) een retry-time;
   anders default **30 minuten** (configureerbaar).
2. Schrijf naar `software_factory.system_state`:
    - `credits_paused_until = now() + retry_duration`
    - `credits_paused_reason = "<korte uitleg + bron-ticket>"`
3. Plaats een `[ORCHESTRATOR]`-comment op het bron-ticket met de
   verwachte wachttijd.
4. **Vanaf dat moment:** in elke poll-cyclus checkt de orchestrator
   eerst `system_state.credits_paused_until` (zie §6.1). Zolang
   `now() < credits_paused_until`: geen enkele nieuwe agent-dispatch
   over alle tickets heen. Lopende containers blijven draaien (en
   zullen waarschijnlijk ook falen — de stuck-detection regelt
   herstart na de pauze).
5. Na het verstrijken van de wachttijd: orchestrator pakt automatisch
   weer op vanaf de eerstvolgende cyclus.

### 16.3 Handmatige override

- De gebruiker kan via een CLI-commando (`factory credits resume`)
  de pauze direct opheffen door `credits_paused_until = NULL` te
  zetten.
- Andersom: `factory credits pause --until "2026-05-23T20:00:00Z"`
  om handmatig een pauze in te stellen (bv. als de gebruiker weet
  dat hij straks tokens nodig heeft voor andere doeleinden).

### 16.4 Verschil met per-ticket pauze

| Pauze-type        | Veld / state                           | Reset                                              |
|-------------------|----------------------------------------|----------------------------------------------------|
| Per ticket (PO)   | `Paused = true` in Jira (§3.2)         | PO zet `Paused = false` of comment `resume`        |
| Per ticket (budget) | `Paused = true` door cost-monitor    | PO comment `BUDGET=…` of `CONTINUE`                |
| Systeem-breed (credits) | `system_state.credits_paused_until` | Automatisch na de tijd, of `factory credits resume`|

---

## 17. Secrets & lokale config

De factory draait op de laptop en heeft een handvol credentials nodig.
Die staan **niet in git** en **niet** in Jira — ze leven lokaal in
de root van de factory-repo en worden bij start ingelezen door de
orchestrator.

Alle environment variables die door de software-factory zelf gelezen
of aan agent-containers doorgegeven worden, krijgen de projectprefix
`SF_`. Daardoor botsen ze niet met env-vars van target-apps of van
andere lokale tools. CLI-specifieke namen van externe tools blijven
alleen intern een adapterdetail; de factory-config blijft `SF_*`.

### 17.1 Welke secrets

| Naam (env-var)            | Doel                                                              | Bron / hoe te verkrijgen                                                  |
|---------------------------|-------------------------------------------------------------------|---------------------------------------------------------------------------|
| `SF_JIRA_BASE_URL`           | Endpoint van Jira (bv. `https://vdzon.atlassian.net`).            | Niet echt een secret, maar wel config — staat in hetzelfde bestand.       |
| `SF_JIRA_EMAIL`              | Account-e-mail waarmee de API-key is gegenereerd.                 | Idem.                                                                     |
| `SF_JIRA_API_KEY`            | API-token voor Jira (lezen + schrijven van tickets/comments).     | https://id.atlassian.com/manage-profile/security/api-tokens               |
| `SF_GITHUB_TOKEN`            | PAT met scopes `repo` + `read:org`. Clone + push + PR + comments. | https://github.com/settings/tokens (classic of fine-grained).             |
| `SF_DATABASE_URL`    | Neon Postgres-URL.                                                | Neon-dashboard → Connection details → "Pooled connection".                |
| `SF_DATABASE_SCHEMA` | Postgres-schema voor deze app. Moet `software_factory` zijn.      | Lokale keuze binnen dezelfde database; niet `factory`, want dat is bezet. |
| `SF_KUBECONFIG`              | Pad naar een kubeconfig voor OpenShift (deploy-monitoring + tester). | `oc login` op de laptop schrijft `~/.kube/config`; meestal niet overschrijven. |
| `SF_AI_CREDENTIALS_DIR`      | Pad naar de credentials-dir van de AI CLI (bv. `~/.claude`).      | Wordt aangemaakt door `claude login` op de laptop.                        |
| `SF_AI_OAUTH_TOKEN`          | Alternatief voor de credentials-dir: één OAuth-token-string.      | `claude setup-token` (Claude Code CLI specifiek).                         |

Naar keuze gebruikt de factory `SF_AI_CREDENTIALS_DIR` (volume-mount) of
`SF_AI_OAUTH_TOKEN` (env-var) — de gekozen AI CLI uit §8 bepaalt welke
van de twee werkt.

`SF_KUBECONFIG` is alleen écht nodig voor de tester en voor de cleanup-
acties (delete preview-namespace bij merge). Als je nooit een tester
draait, kun je 'm weglaten.

### 17.2 Waar de secrets staan

Eén bestand, niet gecommit, in de root van de factory-repo:

```
<factory-repo-root>/secrets.env
```

Dit bestand staat in `.gitignore`. Aanbevolen permissies:
`chmod 600 secrets.env`. Inhoud `KEY=value`-paren, één per regel.
Voorbeeld:

```env
SF_JIRA_BASE_URL=https://vdzon.atlassian.net
SF_JIRA_EMAIL=robbert@vdzon.com
SF_JIRA_API_KEY=ATATT...
SF_GITHUB_TOKEN=ghp_...
SF_DATABASE_URL=postgresql://user:pass@host/db
SF_DATABASE_SCHEMA=software_factory
SF_KUBECONFIG=/Users/robbertvdzon/.kube/config
SF_AI_CREDENTIALS_DIR=/Users/robbertvdzon/.claude
```

In de factory-repo staat daarnaast een `secrets.env.example` met
dezelfde keys + placeholders, gecommit zodat nieuwe gebruikers weten
wat ze moeten invullen.

### 17.3 Hoe de orchestrator de secrets leest

- De applicatie leest bij start standaard `./secrets.env` uit de
  current working directory. De factory wordt daarom vanuit de root
  van de factory-repo gestart.
- Per key geldt: waarde uit `./secrets.env` wint; als die key daar
  ontbreekt of leeg is, valt de applicatie terug op de system
  environment variable met dezelfde naam.
- Als een verplichte key in beide bronnen ontbreekt of leeg is,
  start de applicatie niet en meldt hij welke keys ontbreken.
- Voor afwijkende lokale runs kan het pad naar de secrets-file
  overschreven worden met `SF_SECRETS_FILE`.
- De orchestrator giet de relevante subset door naar elke agent-
  container via `--env-file` of expliciete `-e KEY=value`-flags.
- Voor de tester worden bovendien volumes gemount:
    - `${SF_KUBECONFIG}` → `/home/runner/.kube/config` (read-only)
    - `${SF_AI_CREDENTIALS_DIR}` → `/home/runner/.claude` (read-only),
      óf `SF_AI_OAUTH_TOKEN` als env-var.

### 17.4 Wat NIET in de secrets-file hoort

- Per-target-repo credentials zoals een aparte GitHub-token per repo.
  We gaan uit van **één** PAT die toegang heeft tot alle
  target-repo's van de gebruiker.
- Preview-DB-credentials. Die haalt de tester zelf op uit de cluster
  via `preview_db_secret_recipe` in `docs/factory/deployment.md`
  (zie §4.3) — niet uit een lokale file.
- AI-API-keys. We gebruiken de **logged-in gebruiker** via z'n CLI,
  geen losse API-key.

---
