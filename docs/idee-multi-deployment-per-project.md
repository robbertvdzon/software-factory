# Idee: één project, meerdere deployments

Nog geen story — aantekening zodat dit niet verloren gaat, ontstaan tijdens het onderzoek naar
SF-1133/SF-1134 (2026-07-20).

## Het gat

`projects.yaml` kent per project-naam precies **één** `deploy:`-blok. De code ondersteunt dat ook
letterlijk zo: `ProjectDeploymentSettings.deployConfigFor(projectName): DeployConfig` geeft één
`DeployConfig`-object terug (`Skip | RestRestart | OpenshiftWatch`), geen lijst. `DeploySubtaskHandler
.process()` haalt die ene config op via het `Project`-veld van de parent-story en bewaakt daarmee
altijd exact één OpenShift-deployment (of doet één rest-restart) — ongeacht welke bestanden de
story daadwerkelijk wijzigde. Het bestaande commentaar in `projects.yaml` bij `robberts-assistent`
noemt dit al expliciet:

> "DeploySubtaskHandler ondersteunt maar 1 deployment tegelijk"

## Waarom dat pijn doet

De repo `robberts-assistent` bevat inmiddels vijf losstaande deploybare/verspreidbare onderdelen
(backend, robberts-assistent-frontend, groentetuin-frontend, notities-APK, wind-APK), maar heeft
in `projects.yaml` maar één project-entry ("robberts-assistent") met één deploy-doel
(`robberts-assistent-frontend`, openshift-watch). Gecheckt in de factory-database: alle 10
bestaande stories op deze repo staan onder dat ene Project-veld — er is domweg geen andere keuze.

Gevolg: een story die alléén `groentetuin/`, `notities/` of `wind/` wijzigt, laat de DEPLOY-subtaak
tóch wachten op de ArgoCD-status van `robberts-assistent-frontend` — een deployment die door zo'n
wijziging niet verandert. Dat geeft in het beste geval een onnodige timeout, in het slechtste geval
een misleidende (toevallige) bevestiging als er tegelijk ook een frontend-deploy loopt.

Dezelfde beperking raakt ook de nieuwe `TelegramResultNotifyPoller` (SF-1133, in review): die
vertrouwt voor openshift-watch-projecten op precies dezelfde per-project deploy-config, dus het
gat plant zich door naar elke toekomstige projectgebonden verificatie.

## Geen goede workaround

Losse project-namen aanmaken die dezelfde `repo:` delen (bv. `robberts-assistent-groentetuin`,
`robberts-assistent-notities`, `robberts-assistent-wind`) lost het zichtbaar op, maar is een
omweg: het simuleert "meerdere deployments" door te doen alsof het aparte projecten zijn, terwijl
het conceptueel echt één project/repo is met meerdere deploybare onderdelen. Bewust niet gekozen.

## Richting voor een echte oplossing

Naar analogie van `pathPrefixes` dat al bestaat op `VerificationCommand`
(`.factory/verification.yaml`, SF-1068-vervolg): `deploy:` in `projects.yaml` zou een **lijst**
van deploy-doelen moeten worden, elk met een eigen `matchPaths`-achtig veld. De DEPLOY-subtaak kijkt
dan naar de story-diff (`git diff origin/<base>...HEAD`, mechanisme bestaat al) en kiest/bewaakt
alleen de deploy-doelen waarvan een `matchPaths`-prefix geraakt is. Een project zonder path-match
(bv. een docs-only wijziging) valt terug op "niets te bewaken" (`deploy-approved` zonder wachten),
net zoals `Skip` nu al werkt.

Aandachtspunten voor een latere uitwerking:
- Wat als een story **meerdere** deploy-doelen tegelijk raakt (bv. backend + frontend in één PR)?
  Waarschijnlijk: op alle geraakte doelen wachten, pas `deploy-approved` als ze allemaal groen zijn.
- `liveComponents` (informatief, dashboard "live versie + uptime") heeft dezelfde
  één-naar-veel-behoefte en zou dezelfde structuur kunnen hergebruiken.
- Achterwaartse compatibiliteit: bestaande `deploy:`-blokken (enkelvoudig object) moeten blijven
  werken — dit wordt een optionele lijst-vorm, geen breaking change.

## Wanneer oppakken

Niet urgent — vandaag draaien de vijf robberts-assistent-onderdelen prima zonder dat er ooit een
DEPLOY-subtaak écht fout ging op dit gat (waarschijnlijk toeval / weinig groentetuin-of-APK-only
stories tot nu toe). Wél de moeite waard zodra er meer stories komen die uitsluitend zo'n
secundair onderdeel raken.

## Vervolg (2026-07-24): deploy-zichtbaarheid, los van dit routeringsgat

Uit een gesprek over drie losse wensen van Robbert, ontstaan tijdens onderzoek naar wat er nog
mist rond deploys:

1. Telegram-melding "deploy klaar" komt soms te vroeg (bijv. vóórdat een APK er daadwerkelijk is).
2. Project-tab: per artifact zien of een build loopt en of de huidige versie achterloopt op main
   / up-to-date is op OpenShift of laptop.
3. Story-detail: per onderdeel zien wat er gebuild wordt, en de status (APK klaar? service op
   OpenShift live?), zowel tijdens de PR-fase als na de merge terwijl we op productie-deploy
   wachten.

Onderzoek (code-exploratie 2026-07-24) laat zien dat dit **drie losse mechanismen** zijn — de
multi-deployment-lijst hierboven lost alleen wens 3 (deels) op, niet wens 1 of 2.

### Wens 1 — te vroege Telegram-melding: apart bugje, niet dit gat

Er lopen nu twee onafhankelijke meldingspaden naast elkaar:

- `DeploySubtaskHandler.process()` zet bij `DeployConfig.Skip` (de typische APK-only projecten)
  de DEPLOY-subtaak **synchroon en instant** op `DEPLOY_APPROVED` zodra de subtaak start, zonder
  enige artifact-check (`DeploySubtaskHandler.kt:79-99`).
- `TelegramNotificationService.classifySubtaskDone()` stuurt de "✅ klaar"-melding zodra een
  subtaak een terminale fase bereikt (`TelegramNotificationService.kt:366-369`;
  `DEPLOY_APPROVED`/`DEPLOY_FAILED` zijn terminaal, `SubtaskPhase.kt:83-92`). Voor Skip-projecten
  vuurt dit dus meteen af — vóór de APK er is.
- Er bestaat al een mechanisme dat wél correct wacht: `TelegramResultNotifyPoller` pollt via
  `confirmApk()` op een nieuwe GitHub-release-APK ná de referentietijd
  (`TelegramResultNotifyPoller.kt:80-133`, `ApkReleaseProbe`/`GitHubApkReleaseProbe`). Maar dit is
  een **los, opt-in kanaal** (`telegramResultNotify`-vlag) náást de subtaak-DONE-melding, geen
  vervanging ervan — vandaar het dubbele (en deels premature) bericht.

Fix hoort in `DeploySubtaskHandler` zelf: voor Skip/APK-achtige deploy-doelen pas
`DEPLOY_APPROVED` zetten ná een echte artifact-check (zelfde soort probe als `ApkReleaseProbe`),
in plaats van instant. Dit kan los van de multi-deployment-lijst, maar past er wel bij als de
DEPLOY-subtaak toch wordt heringericht rond meerdere doelen.

### Wens 2 — project-tab: grotendeels al gebouwd

Verrassend veel bestaat al:
- Build-actief-badges: `ProjectBuildStatus.mainBuildActive/prBuildActive`
  (`FactoryDashboardModels.kt:207-215`; UI `projects_screen.dart:359-364`).
- Achterloop-op-main: `BuildSyncStatus.IN_SYNC/OUT_OF_SYNC`, berekend in `buildStatusFor()`
  (`DashboardQueryService.kt:476-495`; UI-badge `projects_screen.dart:377-378`).
- Live OpenShift-componenten met image + pod-uptime + sync-status: `liveComponentsFor()`
  (`ProjectConfiguration.kt:42-46, 222-237`), opgehaald via `fetchLiveComponents()`
  (`DashboardQueryService.kt:414-430`), getoond in `_LiveComponentRow`
  (`projects_screen.dart:240-282`).

Het gat: **APK's** hebben alleen een platte downloadlijst (naam/grootte/datum, `_DownloadRow`,
`projects_screen.dart:294-334`) zonder sync-check t.o.v. main, terwijl de OpenShift-liveComponents
die wel hebben. Kleine, losstaande uitbreiding: geef de APK-rij dezelfde sync-badge als
liveComponents.

### Wens 3 — story-detail: bestaat niet, hangt wél aan de multi-deployment-lijst

`StoryDetailPageData` / `_SubtasksPanel` (`FactoryDashboardModels.kt:135-154`,
`story_detail_screen.dart:505-543`) toont per subtaak alleen key/samenvatting/type/fase — geen
opsplitsing naar backend/frontend/APK, geen aparte "PR open vs gemerged, wachtend op productie"-
indicator los van de generieke MERGE-subtaakfase (`MergeSubtaskHandler.kt`).

Dit is waar de multi-deployment-lijst hierboven wél een randvoorwaarde voor is: zodra `deploy:`
een lijst met `matchPaths` wordt, heeft de DEPLOY-subtaak vanzelf een lijst deploy-doelen met elk
een eigen status — exact de data die de story-detail-UI nodig heeft. Zonder die structuur is er
geen backend-model om zo'n UI op te bouwen.

## Vervolg (2026-07-24): story-lifecycle loskoppelen van "echt live"

Aanvullend inzicht: na een merge (of bij de software factory zelf: na de herstart van de
factory-service) draait de deploy verder buiten de factory om (GitHub Actions / ArgoCD). Als daar
daarna nog iets misgaat, kan de factory daar toch niks meer aan doen — dat wordt hooguit bij de
eerstvolgende deploy (of een nachtelijke job, zie `.factory/nightly/`) alsnog zichtbaar/gefixed.
Conclusie: de story mag naar **Done** zodra gemerged (resp. factory herstart), onafhankelijk van
of de deploy al daadwerkelijk volledig live staat. Dat "echt live"-gegeven wordt apart bijgehouden
en gemonitord, niet als blokkerende voorwaarde voor het afronden van de story — anders herhaal je
precies het patroon uit wens 1 (afronding koppelen aan iets wat de factory niet zelf kan afdwingen
of timen).

Afgesproken ontwerp:

- **`deployedAt`-veld op de story** (timestamp, nullable): wordt losstaand van het story-proces
  gezet, ná Done.
- **Backend-reconciler** (naam: `StoryDeployReconciler`) die periodiek stories bekijkt met
  state = Done en `deployedAt = null`, en per geraakt deploy-doel checkt of het al live staat.
  Zodra alles klopt: `deployedAt` zetten.
  - **Ancestry-check i.p.v. SHA-gelijkheid**: gebruik `git merge-base --is-ancestor
    <merge-commit-van-story> <huidige-live-SHA>` (zelfde soort git-vergelijking als
    `BuildSyncStatus` nu al doet, alleen met de story's merge-commit als linkerkant i.p.v. "laatste
    main-build"). Dit lost vanzelf het geval op waarin een latere story al gemerged én
    gedeployed is: die latere live-SHA is dan automatisch een ancestor-nakomeling, dus de oudere
    story wordt terecht ook als "live" herkend — geen aparte speciale-geval-logica nodig.
  - Voor APK-achtige (Skip-)doelen: "live" = nieuwe release gevonden ná de merge-tijd, zelfde
    mechanisme als `ApkReleaseProbe` nu al gebruikt.
  - **Hangt vast aan de multi-deployment-lijst hierboven**: bij meerdere geraakte deploy-doelen
    per story is "volledig deployed" pas waar als élk doel z'n check haalt. Zonder de
    multi-deployment-lijst weet de reconciler niet welke doelen een story concreet raakte.
- **Frontend**: geen nieuw sidebar-tabje met een technische naam — in plaats daarvan een
  **"Rollout"**-tabje (of, als alternatief, een status-kolom/badge in het bestaande "Merged"-
  tabje, wat de naamgevingsvraag meteen omzeilt omdat `deployedAt` toch alleen relevant is voor
  al-gemergede stories). Zolang `deployedAt` niet gezet is: link(s) naar de build(s) en hun status
  per geraakt onderdeel tonen.

Openstaande knoop: Rollout als eigen tabje naast "Merged", of als kolom/badge binnen het
bestaande "Merged"-tabje — nog niet definitief gekozen.
