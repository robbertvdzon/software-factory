# Software Factory — topologie naar OpenShift

Status: ontwerp vastgesteld, klaar om uit te voeren

Peildatum: 2026-09-12

Doelrepository: `softwarefactory`, met enkele stappen in `robberts-infrastructure`

Ingangseis: de overstap naar Agent Runtime v2 is afgerond en live bewezen, zie
[VOORTGANG.md](VOORTGANG.md). Die eis is op 2026-09-12 gehaald.

## Doel

De Software Factory draait vandaag in twee helften: een lokale orchestrator op de MacBook met de
database ernaast, en een dunne `dashboard-backend` op OpenShift die via een uitgaande WebSocket met
die lokale factory praat. Dit document beschrijft hoe die twee helften er één worden, op OpenShift,
zodat er niets van de Software Factory meer op een laptop hoeft te draaien.

Na de runtimewissel is de technische reden om lokaal te draaien weg: repositorywerk gebeurt in
tijdelijke checkouts van de Agent Runtime-worker, en die worker blijft gewoon op de MacBook. Wat er
in de factory overblijft is een gewone Spring-applicatie met een database en een map met bijlagen.

## Uitgangspunten

Deze afspraken zijn op 2026-09-12 vastgesteld en bepalen de vorm van het hele plan.

- **Big bang, met downtime.** De verhuizing gebeurt op een moment dat er geen jobs lopen. De lokale
  factory gaat uit vóór de overzet en de cluster-factory gaat pas aan als alles over is. De factory
  hoeft tussentijds niet te blijven werken en mag een tijd down zijn. Er is dus nooit een periode
  waarin twee factories tegelijk draaien, en er is geen hybride tussenstand nodig.
- **Geen back-up, geen rollback.** De lokale database en bijlagen blijven na de overzet gewoon
  staan totdat de cluster bewezen werkt, maar er wordt geen rollbackpad ingericht en geen
  back-upregime voor de cluster-database opgezet.
- **Migreren, niet herschrijven.** De code verhuist zoveel mogelijk zoals hij is. De enige echte
  ombouw is het verwijderen van de bridge, en die gebeurt volledig: het eindresultaat moet eruitzien
  alsof de factory voor het eerst op de cluster is gebouwd, zonder een laag die alleen door de
  laptophistorie bestaat.
- **Hele database mee.** Niet alleen afgeronde stories, maar het complete schema, inclusief
  configuratietabellen, kennis, Telegram-state en storynummering.
- **Bestaande clusterpatronen volgen.** Postgres en opslag worden ingericht zoals bij pvdd, hkh,
  product-factory en agent-runtime.
- **Rename en nieuwe host horen erbij**, maar komen als laatste, nadat de factory op de cluster
  onder de bestaande namen werkt.

## Huidige situatie

Gecontroleerd in de repo op 2026-09-12.

- Root-pom met vier modules: `factory-contracts`, `factory-common`, `softwarefactory` en
  `dashboard-backend`. De map `agentworker` staat nog op schijf maar is niet meer in git.
- `softwarefactory` is een volledige Spring Boot-applicatie met web, JDBC, Flyway en Postgres. Het
  bevat tracker, pipeline, orchestrator, audits, Telegram, kennis, maintenance, de dashboard-services
  en elf `@Scheduled`-pollers. Lokaal draait hij via `factory-loop.sh` met `mvn spring-boot:run`.
- `dashboard-backend` is dun: 12 Kotlin-bestanden, ruim 2.100 regels. Daarvan zijn vijf bestanden
  bridge (`BridgeApiController` 728 regels, `BridgeHub` 234, `BridgeWebSocketConfig` 32,
  `ProductFactoryIntegrationApi` 232, `ProductFactoryIntegrationV2Api` 569). De rest is
  Google-id-token-authenticatie (`GoogleIdTokenVerifier`, `AuthService`, `AuthController`),
  `HealthController`, `DashboardConfig` en de composition root.
- Aan de factorykant staan `BridgeClient` (219 regels) en `BridgeRequestHandler` (516 regels).
  `factory-contracts` bevat de frame-DTO's (`BridgeFrames`, `BridgeFrameReader`, `BridgeParams`) en
  `ProductFactoryMetadata`, dat ook door `AgentDispatcher` en `DashboardQueryService` wordt gebruikt.
- De Flutter-frontend toont bridgestatus en offline-afhandeling in tien Dart-bestanden.
- Postgres draait lokaal via `docker/docker-compose.yml`, volume `software-factory-postgres-data`.
  De database is 1,2 GB, waarvan 872 MB `agent_events` en 317 MB `agent_run_completions`; daar zit
  al retentie op. De tabel `issues` bevat 2.440 rijen.
- Bijlagen (tester-screenshots) staan als losse bestanden onder `SF_TRACKER_ATTACHMENTS_DIR`, nu
  `softwarefactory/attachments`: 466 bestanden, 29 MB, sinds juli 2026, groei circa 15 MB per maand.
  De tabel `issue_attachments` bewaart per bestand het absolute laptoppad in `local_path`. Er is geen
  retentie op bijlagen.
- **De factory doet zelf geen git-checkouts, commits of pushes meer.** Al het repositorywerk
  gebeurt in de Agent Runtime-worker op de MacBook. De factory praat met GitHub via `gh`
  (`GitHubCliClient` in factory-common en `AuditJobsReader`), inclusief het aanmaken en verwijderen
  van storybranches via `gh api`. `gh` authenticeert met de omgevingsvariabele `GH_TOKEN`, gevuld uit
  `SF_GITHUB_TOKEN`; er is geen `gh auth login` of ssh-sleutel nodig. De clone-, checkout- en
  push-methoden in `GitCommandClient` hebben geen aanroepers meer en zijn dode code uit de tijd vóór
  Runtime v2; alleen `repositorySlug` en `runCommand` worden nog gebruikt.
- `kubectl` wordt alleen aangeroepen door `KubectlDeploymentStatusProbe`, die `kubectl get
  application` doet op ArgoCD-Applications en optioneel `SF_KUBECONFIG` gebruikt. `oc` wordt
  aangeroepen door `OcPreviewEnvironmentCleaner` (factory-common) voor `oc delete project` van
  preview-namespaces, met `SF_PREVIEW_CLEANUP_KUBECONFIG`. Daarvoor bestaat in
  `robberts-infrastructure` al ServiceAccount `sf-preview-cleanup` in namespace `software-factory`
  met een ClusterRole die alleen namespaces en projects mag opvragen en wissen.
- `FactoryVersionService` leest branch en commit met `git log` uit de werkmap van het proces. Op een
  pod is er geen `.git`, dus het dashboard zou "onbekend" tonen.
- De dashboardknoppen Herstart en Stop werken via `FactoryProcessService`: Herstart is een exit 0
  die `factory-loop.sh` opnieuw laat starten; Stop schrijft `work/.factory-stop` zodat de loop
  stopt.
- Op OpenShift staan `softwarefactory-dashboard-backend` en `softwarefactory-dashboard-frontend` in
  namespace `software-factory` (`deploy/base/`), met één Sealed Secret
  `softwarefactory-dashboard-secrets`. De ArgoCD-Application staat in
  `robberts-infrastructure/manifests/root-app/apps/softwarefactory-dashboard-application.yaml`.
  Het backend-Dockerfile bouwt een mini-reactor van alleen `factory-contracts` en
  `dashboard-backend`. De Deployment heeft 256Mi request en 768Mi limit.
- De frontend praat met `dashboard.vdzonsoftware.nl`; `DASHBOARD_API_BASE_URL` staat in
  `.github/workflows/dashboard-frontend-image.yml`.
- De Agent Runtime staat op `https://agent-runtime.vdzonsoftware.nl` en is vanaf de cluster
  bereikbaar.

## Clusterpatronen die we volgen

Onderzocht in de andere repo's onder `~/git` op 2026-09-12.

- **Postgres.** Geen operator en geen centrale instantie. Elke service heeft zijn eigen Postgres als
  StatefulSet met image `quay.io/sclorg/postgresql-16-c9s`, Service `database`, een RWO-PVC op de
  enige storage class `local-path`, en een eigen ServiceAccount die via een RoleBinding in de eigen
  namespace aan ClusterRole `system:openshift:scc:local-path-postgresql` hangt. De SCC zelf staat al
  in `robberts-infrastructure/manifests/root-app/apps/postgresql-storage-scc.yaml`; daar hoeft niets
  bij. Voorbeeld om te kopiëren: `pvdd/deploy/base/database.yaml`. Beleid:
  `robberts-infrastructure/docs/postgresql-storage.md`.
- **Bestanden.** Geen MinIO, S3 of NFS. Per service een eigen RWO-PVC op `local-path`, zoals
  `product-factory-ai-artifacts` in `product-factory/deploy/base/artifact-storage.yaml`. De externe
  USB-schijf is alleen via hostPath met een aparte SCC bereikbaar en is voor de bijlagen niet nodig.
- **ArgoCD.** App-of-apps in `robberts-infrastructure/manifests/root-app/apps/`, cluster-scoped,
  productienamespace gelijk aan de appnaam. Leesrechten voor andere services op ArgoCD-resources
  staan daar ook, bijvoorbeeld `robberts-assistent-openshift-health-rbac.yaml`.

## Gewenste eindsituatie

- Eén deployable op OpenShift, de module `softwarefactory`, die de publieke API's, de authenticatie
  en de complete factorylogica bevat. De module `dashboard-backend` bestaat niet meer.
- Postgres in namespace `software-factory` met de volledige inhoud van de lokale database.
- Bijlagen op een PVC, met herschreven paden in de database.
- Geen bridge meer: geen WebSocket, geen frames, geen `factory-contracts`-module.
- Geen Herstart- en Stop-knop meer in het dashboard. Die bestonden alleen om via de lokale loop een
  nieuwe versie te starten; op de cluster doet de image-bump-deploy dat.
- `software-factory-backend` en `software-factory-frontend` als namen van artifact, image,
  Deployment, Service en workflow.
- `softwarefactory.vdzonsoftware.nl` als primaire host, met `dashboard.vdzonsoftware.nl` als redirect.
- De Agent Runtime-worker blijft op de MacBook; dat valt niet onder deze verhuizing.
- Lokaal ontwikkelen blijft mogelijk: `mvn spring-boot:run` tegen de lokale Docker-Postgres, met
  het dashboard erbij in hetzelfde proces.

## Randvoorwaarden die aandacht vragen

Deze zaken werken vandaag omdat de factory op een ingerichte laptop draait. Op de cluster moeten ze
expliciet geregeld worden:

- **`gh` en het GitHub-token.** Het runtime-image krijgt de `gh`-binary; `GH_TOKEN` wordt gevuld
  vanuit hetzelfde secret als `SF_GITHUB_TOKEN`. Er is geen interactieve `gh auth login` op een pod.
- **Git.** Geen aanvullende inrichting nodig: de factory clonet en pusht niet zelf, en `gh` werkt
  met het token in `GH_TOKEN`. De dode clone- en pushcode in `GitCommandClient` wordt in stap 1
  opgeruimd, zodat het image ook geen `git`-binary nodig heeft.
- **`kubectl`, `oc` en clusterrechten.** Het image krijgt de binaries `kubectl` en `oc`.
  `SF_KUBECONFIG` en `SF_PREVIEW_CLEANUP_KUBECONFIG` blijven leeg, zodat beide de in-cluster
  ServiceAccount gebruiken. De factory-pod draait als de bestaande ServiceAccount
  `sf-preview-cleanup`, die al namespaces mag wissen; daar komt in `robberts-infrastructure` een Role
  plus RoleBinding bij voor `get` en `list` op `applications.argoproj.io` in namespace `argocd`.
- **Versie-informatie.** Het Dockerfile bakt de commit-sha en branch in het image, bijvoorbeeld als
  `SF_BUILD_COMMIT` en `SF_BUILD_BRANCH` via build-args uit de workflow, en `FactoryVersionService`
  valt daarop terug als er geen `.git` is.
- **`projects.yaml` en de operationele secrets.** `projects.yaml` is nu gitignored, maar bevat geen
  geheime waarden: alleen repo-URL's, Runtime-aliassen, Telegram-chat-id's, deploydoelen, namen van
  omgevingsvariabelen en paden. Omdat de repository publiek is en de Telegram-chat-id's niet
  openbaar hoeven te zijn, blijft het bestand gitignored en gaat het als extra sleutel mee in het
  Sealed Secret, gemount als bestand, met `SF_PROJECTS_FILE` naar dat pad. `deploy/seal-secrets.sh`
  neemt het bestand mee. Een wijziging in de projectconfiguratie is daarmee opnieuw sealen, een
  commit en een ArgoCD-sync. De `private:`-lijsten
  met absolute laptoppaden hebben geen aanroeper meer en vervallen. Er is geen plan om
  `projects.yaml` naar de database te verhuizen; [stappenplan.md](stappenplan.md) legt vast dat het
  bestand de bron blijft. Secrets worden Sealed Secrets in dezelfde vorm als het bestaande
  `deploy/base/sealed-secret-dashboard.yaml`, aangemaakt met `deploy/seal-secrets.sh`.
- **Bijlagen.** PVC `software-factory-attachments` van 2Gi op `local-path`, gemount op
  `/var/lib/software-factory/attachments`, met `SF_TRACKER_ATTACHMENTS_DIR` naar dat pad. Bij dit
  groeitempo is dat voor jaren voldoende.
- **Uitgaande verbindingen.** Telegram, GitHub en de Agent Runtime moeten vanaf de pod bereikbaar
  zijn; dat is voor de andere services op de cluster al zo.
- **Tijdzone.** `MaintenanceCleanupScheduler` draait expliciet in UTC; de overige schedulers gebruiken
  de JVM-tijdzone. Zet `TZ=Europe/Amsterdam` op de pod zodat audittijden hetzelfde blijven als op de
  laptop.
- **Geheugen.** De huidige limiet van 768Mi is voor de dunne backend. Start de volledige factory met
  512Mi request en 2Gi limit en stel bij op basis van de gemeten heap.
- **Herstart en Stop.** Beide knoppen verdwijnen, met `FactoryProcessService`,
  `FactoryProcessControl`, het signaalbestand en het endpoint `POST /api/restart`. Een nieuwe versie
  komt via de image-bump-deploy binnen; een hangende pod herstart je met `oc rollout restart`.
  `GET /api/version` blijft, want het dashboard toont de versie.
- **Zelf-deploy.** In `projects.yaml` heeft het project `softwarefactory` nu drie deploydoelen: een
  `rest-restart`-doel `factory-self` dat via `/api/restart` de lokale loop opnieuw laat starten, en
  twee `openshift-watch`-doelen voor backend en frontend. Op de cluster vervalt `factory-self`: de
  `openshift-watch` op de backend-Deployment ís dan de zelf-deploy. De factory vervangt daarmee zijn
  eigen pod terwijl stories kunnen lopen; dat gebeurt nu ook al via de lokale herstart, en de
  bestaande recovery van actieve fases en completions dekt dit. Het staat als risico in de tabel.

Het projectlokale, gitignored `secrets.env` van targetprojecten blijft buiten beeld: dat werd al
niet door de factory gelezen en verandert hier niet.

## Stappen

| Stap | Naam | Lokale factory |
|---:|---|---|
| 1 | Bridge verwijderen en één deployable maken | draait door |
| 2 | Database en bijlagen-opslag op de cluster | draait door |
| 3 | Factory-image en deployment inrichten | draait door |
| 4 | Overzetten | uit vanaf hier |
| 5 | Rename | uit |
| 6 | Host en toegang | uit |
| 7 | Oplevering en opruimen | verwijderd |

Stap 1 tot en met 3 zijn voorbereiding en raken de draaiende factory niet. Stap 4 is het
downtimemoment. Stap 5 en 6 gebeuren als de cluster-factory al werkt; downtime tijdens die stappen
is toegestaan.

### Stap 1 — Bridge verwijderen en één deployable maken

Dit is het grootste stuk werk en het enige met echte code-ombouw. Het gebeurt op een branch en wordt
lokaal bewezen; de lokale factory draait ondertussen door op `main`.

1. Verplaats de niet-bridge-bestanden van `dashboard-backend` naar `softwarefactory`:
   `GoogleIdTokenVerifier`, `AuthService`, `AuthController`, `HealthController`, `ApiModels`,
   `DashboardConfig` en de secrets-uitlezing die daarbij hoort.
2. Herschrijf `BridgeApiController`, `ProductFactoryIntegrationApi` en
   `ProductFactoryIntegrationV2Api` tot gewone controllers in `softwarefactory` die
   `DashboardQueries`, `DashboardCommands`, `FactoryOperations`, `AttachmentPort` en
   `TelegramAssistantApi` rechtstreeks aanroepen. `BridgeRequestHandler` is daarbij de bron voor de
   mapping van operatie naar service-aanroep; de foutafhandeling (`NOT_FOUND`, `CONFLICT`,
   `INVALID_PARAMS`, `TOO_LARGE`) wordt gewone HTTP-statusafhandeling. Alle paden onder `/api/v1`
   en `/api/integrations/v2` blijven exact gelijk, inclusief de authenticatie-eisen per endpoint.
3. Verwijder `BridgeHub`, `BridgeWebSocketConfig`, `BridgeClient`, `BridgeRequestHandler`, de
   bridge-frames en -tests, de configuratie `SF_BRIDGE_TOKEN` en `SF_BRIDGE_URLS`, en de
   events-doorgifte via de bridge. De live-verversing van het dashboard (SSE) wordt rechtstreeks
   vanuit `DashboardEventBus` bediend.
4. Verplaats `ProductFactoryMetadata` naar `softwarefactory` en verwijder de modules
   `factory-contracts` en `dashboard-backend` uit de root-pom, inclusief
   `docker/prepare-mini-reactor.sh`.
5. Verwijder de Herstart- en Stop-knop uit de frontend, `FactoryProcessService`,
   `FactoryProcessControl`, het signaalbestand, `POST /api/restart` in `FactoryApiController`, en de
   bridgestatus en offline-afhandeling uit de frontend.
6. Verwijder `spring-boot-starter-websocket` uit `softwarefactory` als er geen andere gebruiker is,
   en verwijder de ongebruikte clone-, checkout-, commit- en pushmethoden uit `GitApi` en
   `GitCommandClient`, inclusief hun tests.
7. Laat `FactoryVersionService` terugvallen op `SF_BUILD_COMMIT` en `SF_BUILD_BRANCH` als de werkmap
   geen git-repository is.
8. Werk `docker/docker-compose.yml` bij: geen aparte backend-service meer; de frontend proxyt lokaal
   naar de factory zelf.
9. Bewijs lokaal: `mvn verify` groen, de Flutter-frontend werkt tegen de lokale factory op alle
   schermen, de Product Factory-integratie-endpoints antwoorden identiek, en één story draait
   lokaal end-to-end via deze branch.

**Klaar wanneer:** er geen enkele verwijzing naar bridge, frames of WebSocket meer in de repo staat,
de root-pom twee modules heeft (`factory-common` en `softwarefactory`), en het dashboard lokaal
volledig werkt tegen de factory in hetzelfde proces. De branch is gemerged naar `main` en de lokale
factory draait er weer op.

### Stap 2 — Database en bijlagen-opslag op de cluster

1. Voeg aan `deploy/base` toe, gekopieerd van `pvdd/deploy/base/database.yaml`: ServiceAccount
   `software-factory-database`, RoleBinding naar `system:openshift:scc:local-path-postgresql`, PVC
   `software-factory-database-data` van 10Gi, StatefulSet en Service `database` met database
   `software_factory` en gebruiker en wachtwoord uit het Sealed Secret.
2. Voeg PVC `software-factory-attachments` van 2Gi toe.
3. Neem `SF_DATABASE_URL` op als `jdbc:postgresql://database:5432/software_factory` en
   `SF_DATABASE_SCHEMA` gelijk aan het schema van de lokale database, zodat de dump één op één past.
4. Laat ArgoCD syncen en controleer dat Postgres draait en de PVC's gebonden zijn.

**Klaar wanneer:** een lege Postgres en een lege bijlagen-PVC in namespace `software-factory`
bestaan en bereikbaar zijn vanuit de namespace.

### Stap 3 — Factory-image en deployment inrichten

1. Vervang het Dockerfile: bouw de hele reactor met `-pl softwarefactory -am`, voeg in het
   runtime-image de binaries `gh`, `kubectl` en `oc` toe, en geef commit-sha en branch als build-args
   door vanuit de workflow.
2. Laat de factory-pod draaien als de bestaande ServiceAccount `sf-preview-cleanup`, en voeg in
   `robberts-infrastructure` een Role en RoleBinding toe in namespace `argocd` voor `get` en `list`
   op `applications.argoproj.io` voor die ServiceAccount.
3. Laat `deploy/seal-secrets.sh` `projects.yaml` als sleutel in het Sealed Secret opnemen en mount
   die als bestand. Haal daarbij het deploydoel `factory-self` uit het project `softwarefactory` (de
   `openshift-watch` op de backend-Deployment blijft als zelf-deploy) en schrap de `private:`-lijsten.
4. Breid het Sealed Secret uit met alle sleutels uit `secrets.env` die de factory nodig heeft:
   tracker, GitHub, database, Agent Runtime, Google-login, Product Factory-token, Telegram en
   `SF_DASHBOARD_BASE_URL`. `SF_KUBECONFIG`, `SF_PREVIEW_CLEANUP_KUBECONFIG`, `SF_BRIDGE_TOKEN` en
   `SF_BRIDGE_URLS` vervallen. Zet `GH_TOKEN` op dezelfde waarde als `SF_GITHUB_TOKEN`.
5. Werk de backend-Deployment bij: nieuwe ServiceAccount, envFrom voor secret en properties, mounts
   voor het projects-bestand uit het secret en de bijlagen-PVC, `TZ=Europe/Amsterdam`, geheugen 512Mi/2Gi, en
   `SF_TRACKER_ATTACHMENTS_DIR` naar het mountpad.
6. Werk `.github/workflows/dashboard-backend-image.yml` bij zodat hij het nieuwe Dockerfile bouwt;
   de imagenaam blijft in deze stap nog `softwarefactory-dashboard-backend`.
7. Laat de pipeline het image bouwen en ArgoCD de nieuwe Deployment uitrollen tegen de lege
   cluster-database. De lokale factory draait nog; zet daarom in deze uitrol Telegram uit via een
   leeg bot-token en `SF_TRACKER_PROJECTS` leeg, zodat de cluster-pod niets oppakt.
8. Controleer: Flyway migreert vanaf leeg, `/healthz` is `UP`, inloggen via Google werkt op het
   bestaande dashboard, en vanuit de pod (`oc rsh`) slagen `gh auth status`,
   `gh api repos/robbertvdzon/software-factory/branches/main`, `kubectl get application -n argocd`
   en `oc get project`.

**Klaar wanneer:** de volledige factory op de cluster start tegen de lege cluster-database, health
rapporteert, en `gh` en `kubectl` vanuit de pod bewezen werken.

### Stap 4 — Overzetten

Dit is het downtimemoment. Voorwaarde: geen lopende stories, audits of Runtime-jobs.

1. Stop de lokale factory via `factory-loop.sh` (Ctrl-C) en controleer dat er geen proces meer
   draait.
2. Schaal de cluster-Deployment naar 0.
3. Dump het schema uit de lokale Docker-Postgres met `pg_dump --format=custom --schema=<schema>` en
   zet hem over met `oc rsh` naar de database-pod en `pg_restore` in de lege cluster-database.
   Controleer de rijaantallen van `issues`, `issue_attachments`, `agent_role_execution_config`,
   `project_key_sequences` en `telegram_state` tegen de lokale database.
4. Kopieer `softwarefactory/attachments` naar de bijlagen-PVC met `oc rsync` via een tijdelijke pod
   of de gestopte factory-pod, en controleer het aantal bestanden (466 op peildatum).
5. Herschrijf `issue_attachments.local_path`: vervang het lokale prefix door het mountpad op de pod,
   en controleer met een steekproef dat de bestanden bestaan.
6. Zet in het Sealed Secret het echte Telegram-token en `SF_TRACKER_PROJECTS` terug, en schaal de
   Deployment naar 1.
7. Controleer: dashboard toont alle stories en screenshots, deeplinks naar bestaande stories werken,
   modelconfiguratie per rol staat er, Telegram meldt de start en reageert op een bericht, en een
   nieuwe story krijgt het eerstvolgende nummer na het hoogste bestaande.

**Klaar wanneer:** de cluster-factory draait tegen de volledige data, alle bestaande stories en
bijlagen zichtbaar zijn, en de lokale factory uit staat. Vanaf hier gaat de lokale factory niet meer
aan.

### Stap 5 — Rename

1. Hernoem artifact, image, Deployment, Service en workflow van `softwarefactory-dashboard-backend`
   naar `software-factory-backend`. De module heet al `softwarefactory`; dat blijft zo.
2. Hernoem `dashboard-frontend` naar `software-factory-frontend`: image, Deployment, Service, Route
   en workflow.
3. Werk `.github/scripts/bump-images.sh`, `deploy/base/kustomization.yaml` en `deploy/sno-local` bij.
4. Hernoem het Sealed Secret naar `software-factory-secrets` en de ArgoCD-Application in
   `robberts-infrastructure` naar `software-factory-application.yaml`. Werk de `argocdApp`,
   `namespace` en `deployment` van de `openshift-watch`-doelen in de projects-ConfigMap mee.
5. Behoud alle API-paden en deeplinks.

**Klaar wanneer:** de pipeline groen is, de nieuwe images gebouwd worden en de deployment onder de
nieuwe namen gezond draait.

### Stap 6 — Host en toegang

1. Voeg `softwarefactory.vdzonsoftware.nl` toe als Route met certificaat en regel de DNS in
   Cloudflare.
2. Voeg de nieuwe host toe als toegestane origin en redirect in de Google OAuth-console.
3. Zet `DASHBOARD_API_BASE_URL` in de frontend-buildworkflow en `SF_DASHBOARD_BASE_URL` in het
   Sealed Secret om naar de nieuwe host, zodat ook Telegram-deeplinks naar de nieuwe host wijzen.
4. Houd `dashboard.vdzonsoftware.nl` als redirect naar de nieuwe host, met behoud van pad en
   querystring.
5. Werk de Product Factory-configuratie bij naar de nieuwe host.
6. Test inloggen, deeplinks, bookmarks en de Product Factory-integratie op beide hosts.

**Klaar wanneer:** de nieuwe host volledig werkt en de oude host doorverwijst.

### Stap 7 — Oplevering en opruimen

1. Draai één echte story end-to-end vanaf de cluster, inclusief merge en deploy.
2. Draai een audit en controleer de Telegram-meldingen.
3. Controleer de Product Factory-integratie vanaf de cluster.
4. Verwijder `factory-loop.sh` en `factory-loop.command`, de map `agentworker`, en de lokale
   Docker-Postgres en bijlagen zodra stap 1 tot en met 3 van deze lijst groen zijn.
5. Werk `README.md`, `runbook.md`, `docs/installation.md`, `deploy/README.md` en
   `docs/ontwerp-bridge-dashboard.md` bij: een ontwikkellaptop is geen runtimeonderdeel meer, de
   bridge bestaat niet meer, en lokaal draaien is alleen nog voor ontwikkeling.

**Klaar wanneer:** er geen Software Factory-proces meer op een laptop draait, alleen de Agent
Runtime-worker daar nog staat, en één story, één audit en de Product Factory-integratie aantoonbaar
vanaf de cluster werken.

## Uitvoeringslog

- **2026-09-12, stap 1 t/m 4 uitgevoerd.** Bridge verwijderd (commit `32e5fe03`), cluster-manifesten
  (`16bf6c79`), overzet met volledige database (34 tabellen, tellingen gelijk) en 505 bijlagen. De
  lokale factory is gestopt; de LaunchAgent `nl.vdzon.factory-loop` is ontladen.
- **Les uit stap 4:** de bijlagen-PVC op `local-path` was pas schrijfbaar na de
  `hostmount-anyuid`-SCC voor de ServiceAccount plus `spc_t` op de pod, precies zoals product-factory
  dat doet. Een `pg_restore` van de Postgres 17-dump op de laptop werkt op de Postgres 16 van de
  cluster alleen met de nieuwere client via een port-forward.
- **Les uit stap 5:** het hernoemen van de ArgoCD-Application verwijderde alle resources in de
  namespace, inclusief de PVC's, ook nadat de finalizer van de oude Application was weggehaald. De
  database en bijlagen zijn daarna opnieuw uit de dump en het archief gezet. Hernoem een Application
  nooit meer zonder eerst de data veilig te stellen, of hernoem hem niet.

## Werk buiten deze repository

| Wat | Waar | Stap |
|---|---|---|
| Role en RoleBinding voor `get`/`list` op ArgoCD-Applications voor ServiceAccount `sf-preview-cleanup` | `robberts-infrastructure/manifests/root-app/apps/` | 3 |
| ArgoCD-Application hernoemen | `robberts-infrastructure/manifests/root-app/apps/` | 5 |
| DNS-record voor `softwarefactory.vdzonsoftware.nl` | Cloudflare | 6 |
| `https://softwarefactory.vdzonsoftware.nl` als Authorized JavaScript origin op de web-OAuth-client (Google Cloud Console, APIs & Services, Credentials); mag vooraf | Google Cloud Console | 6 |
| Software Factory-host bijwerken | Product Factory-configuratie | 6 |

De SCC voor Postgres bestaat al; de koppeling gebeurt met een RoleBinding in de eigen namespace en
vraagt geen wijziging in de infrastructuurrepo.

## Risico's

| Risico | Beheersing |
|---|---|
| Bridge-ombouw verandert API-gedrag | Stap 1 vergelijkt elk endpoint lokaal met het huidige gedrag; de Product Factory-tests in `dashboard-backend` verhuizen mee als controllertests. |
| `gh`, `kubectl` of `oc` werkt niet vanaf een pod | Stap 3 test alle drie vanuit de pod vóór de overzet, inclusief een echte GitHub-API-call met het token. |
| Deploys of previews falen door andere rechten | De ServiceAccount krijgt precies de ArgoCD-leesrechten die de probe gebruikt; een echte deploy naar een targetproject is onderdeel van stap 7. |
| Dump past niet of schema wijkt af | `SF_DATABASE_SCHEMA` is op de cluster gelijk aan lokaal; rijaantallen worden per tabel vergeleken. |
| Bijlagen niet gevonden na overzet | Paden worden in stap 4 herschreven en steekproefsgewijs gecontroleerd. |
| Storynummering botst | `project_key_sequences` gaat mee in de dump; de eerste nieuwe story wordt gecontroleerd. |
| Cluster-pod pakt werk op terwijl de laptop nog draait | In stap 3 draait de cluster-pod zonder Telegram-token en zonder trackerprojecten; die gaan pas aan in stap 4 nadat de laptop uit is. |
| Zelf-deploy herstart de pod tijdens een story | Bestaande recovery van actieve fases en completions; wordt in stap 7 bewust geraakt door de story van de factory zelf. |
| Rename breekt bookmarks of API-clients | API-paden blijven stabiel; de oude host blijft als redirect. |
| Inloggen breekt door de nieuwe host | Google OAuth-origin wordt in stap 6 toegevoegd en op beide hosts getest voordat de oude host doorverwijst. |
| Geheugen te krap voor de volledige factory | Start ruim op 2Gi limit en meet; de lokale JVM is de referentie. |

## Vervolg na de verhuizing

Niet onderdeel van dit plan, wel als idee vastgelegd op 2026-09-12:

- **Projectconfiguratie en operationele secrets in de database, beheerd via het dashboard.** Dan
  vervalt de cyclus sealen, committen en syncen voor elke wijziging in `projects.yaml` of een
  token, en kan alles vanaf het dashboard worden beheerd. Er is al een patroon voor: de
  modelconfiguratie per agentrol en de auditinstellingen staan in de database met een
  dashboardscherm. Wat altijd buiten de database blijft, is de bootstrap: databasecredentials,
  Google-client-id, e-mailallowlist en het remember-secret, want zonder die kun je niet inloggen om
  de rest te beheren. Dat blijft één klein Sealed Secret. Dit is een echte ombouw van
  `SecretsEnvLoader`, `FactorySecrets` en `ProjectConfiguration` plus een nieuw scherm, en hoort
  daarom na de verhuizing, niet erin.

## Buiten scope

- De agent runtime of zijn worker verplaatsen; die blijft op de MacBook.
- Het domeinmodel of de modulegrenzen wijzigen, anders dan het opheffen van `dashboard-backend` en
  `factory-contracts`.
- Een aparte deployable per capability.
- Back-ups, rollbackpaden en dubbeldraaien.
- Retentie op bijlagen.
