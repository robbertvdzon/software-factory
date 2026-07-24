# SF-1234 - Story-opties herstructureren: vragen / goedkeuring / meldingen

## Story

Story-opties herstructureren: vragen / goedkeuring / meldingen

<!-- refined-by-factory -->

## Scope

Herstructureer de drie huidige, overlappende story-opties (`Auto-approve`, `Silent`, `TelegramResultNotify`) naar drie onafhankelijke assen op story-niveau. Subtaken blijven de waarden van de parent-story erven (zoals nu bij `Auto-approve`/`Silent`), er komen geen eigen velden per subtaak.

**As 1 — Vragen toestaan** (boolean, default AAN)
- AAN: elke `*-with-questions`-uitkomst (refine/plan/develop/review/test/summary/documentation) gedraagt zich zoals vandaag bij een niet-silent story: de vraag gaat altijd via Telegram naar de gebruiker en de keten wacht op antwoord.
- UIT: elke `*-with-questions`-uitkomst wordt — exact zoals de huidige silent-logica (`questionsOutcome()` / `StoryRefinementCoordinator`-clarification-pad) — direct omgezet in een `[CLARIFICATION]`-Error, zonder te wachten op een mens.
- Deze as is volledig losgekoppeld van de meldingen-as: "vragen uit" onderdrukt alléén de vraag-fases, niet de status-Telegram-meldingen.

**As 2 — Goedkeuring** (enum, één van drie, default `automatisch`)
- `automatisch`: alle AI-subtaken (development/review/test/summary/documentation) worden automatisch goedgekeurd (huidige `Auto-approve`-gedrag) **en** de vaste `manual-approve`-poort vóór merge wordt voor deze story overgeslagen, ongeacht de project-config in `projects.yaml`.
- `alleen-manual-poort`: AI-subtaken worden automatisch goedgekeurd, maar de vaste `manual-approve`-poort blijft staan (mits ook `projects.yaml`/`manualApprove` voor het project aan staat — projectniveau blijft de poort verder kunnen uitzetten).
- `elke-stap`: elke AI-subtaak vraagt om handmatige goedkeuring (huidig gedrag zonder `Auto-approve`); de `manual-approve`-poort volgt de bestaande project-config.

**As 3 — Meldingen** (enum, één van vier, default `als-klaar`)
- `geen`: geen enkel Telegram-bericht voor deze story (huidig "Nul Telegram"-gedrag van silent), inclusief vraag- en foutmeldingen.
- `na-elke-stap`: huidig standaardgedrag — een Telegram-melding bij elke terminale subtaak (`TelegramNotificationService.notifyPending`/`notifySubtaskDone`).
- `als-klaar`: alleen een melding zodra de story klaar is (na de merge/laatste subtaak wordt terminaal); geen per-stap-meldingen daarvoor.
- `als-klaar-en-gedeployed`: bestaand SF-1134-gedrag (`TelegramResultNotifyPoller`) — de melding wacht op het daadwerkelijke, extern zichtbare live-resultaat (ArgoCD Synced/Healthy, SHA-match op `/api/version`, of nieuwe APK-release), éénmalig, DB-backed idempotent.

**Datamodel & migratie**
- Nieuwe `TrackerField`-entries + kolommen (analoog aan bestaand `Silent`/`Auto-approve`-patroon: enum-achtige boolean/string-kolommen, Flyway-migratie) voor de drie assen; de bestaande kolommen `auto_approve`, `silent`, `telegram_result_notify` vervallen na migratie (geen dual-write, dit is een intern factory-datamodel zonder externe consumers).
- Backfill van bestaande stories volgens onderstaande tabel (in dezelfde migratie of een eenmalig migratiescript, uitgevoerd vóór de kolommen verwijderd worden):

| oude staat | vragen | goedkeuring | meldingen |
|---|---|---|---|
| `silent=true` | uit | automatisch | geen |
| `silent=false, auto_approve=true, telegram_result_notify=true` | aan | automatisch | als-klaar |
| `silent=false, auto_approve=true, telegram_result_notify=false` | aan | automatisch | na-elke-stap |
| `silent=false, auto_approve=false, telegram_result_notify=true` | aan | elke-stap | als-klaar |
| `silent=false, auto_approve=false, telegram_result_notify=false` | aan | elke-stap | na-elke-stap |

**Backend/pipeline**
- `HumanActionPolicy.autoApproveActive` / `awaitsHuman`: her-implementeren op de nieuwe goedkeuring-as (`automatisch`/`alleen-manual-poort` → AI-stappen auto; `elke-stap` → handmatig), met parent-lookup voor subtaken zoals nu.
- `SubtaskExecutionCoordinator.questionsOutcome()` en het story-niveau-equivalent in `StoryRefinementCoordinator`: overstappen van `effectiveSilent` naar de nieuwe "vragen toestaan"-as.
- `SubtaskPlanMaterializer.manualApproveSpecs()`: de `manual-approve`-subtaak wordt overgeslagen wanneer goedkeuring=`automatisch` (naast de bestaande project-config-check); bij `alleen-manual-poort` en `elke-stap` blijft de bestaande project-config (`projectRepoResolver.manualApproveFor`) bepalend.
- `TelegramNotificationService.notifyPending`: de huidige `effectiveSilent`-check wordt de "meldingen=geen"-check; voor `na-elke-stap` blijft het gedrag ongewijzigd; voor `als-klaar`/`als-klaar-en-gedeployed` worden per-stap-meldingen (QUESTION/APPROVAL/MANUAL/PROGRESS/DONE, behalve de allerlaatste) onderdrukt.
- Nieuwe "als-klaar"-melding (na merge/laatste subtaak, zonder live-check) toevoegen als apart triggerpunt, naast de bestaande `TelegramResultNotifyPoller` voor `als-klaar-en-gedeployed` (die ongewijzigd blijft, alleen z'n activatie-conditie verschuift van `telegramResultNotify` naar meldingen=`als-klaar-en-gedeployed`).
- Fix meegenomen: `TelegramResultNotifyPoller` moet — net als `TelegramNotificationService` — de meldingen=`geen`-toestand respecteren (vandaag stuurt hij ongeacht `silent` een bericht als `telegramResultNotify=true`, wat een bestaande inconsistentie is die door deze herstructurering wordt opgelost doordat beide assen straks uit dezelfde meldingen-enum lezen).

**Nightly jobs**
- `DashboardCommandService.createNightlyStory()` blijft voor nightly-stories: vragen=uit, goedkeuring=automatisch, meldingen=geen (equivalent aan de huidige hardcoded `silent=true`). Het ongebruikte `NightlyJob.silent`-veld uit `job.yaml` blijft ongebruikt (geen scope-uitbreiding hier).

**Dashboard-UI**
- Create-story-dialog (`stories_screen.dart`, `_CreateStoryDialogState`): toont voortaan alle drie assen (nu alleen `Auto-approve`), met de nieuwe defaults (vragen=aan, goedkeuring=automatisch, meldingen=als-klaar) voorgeselecteerd.
- Story-detail-scherm (`story_detail_screen.dart`): de drie losse switches (`_toggleAutoApprove`/`_toggleSilent`/`_toggleTelegramResultNotify`) vervangen door: één "vragen toestaan"-switch, één goedkeuring-keuze (3 opties) en één meldingen-keuze (4 opties).
- Bridge-endpoints in `BridgeApiController.kt`/`BridgeRequestHandler.kt`: `story.setAutoApprove`/`story.setSilent`/`story.setTelegramResultNotify` vervangen door `story.setQuestionsAllowed`, `story.setApprovalMode`, `story.setNotifyMode` (of gelijkwaardig), met bijpassende REST-routes.

**Documentatie**
- `docs/factory/functional-spec.md` bijwerken: de secties "Silent — autonoom verwerken (SF-335)" en "Telegram-melding bij écht live/klaar eindresultaat (SF-1134)" herschrijven naar de nieuwe drie-assen-structuur.

## Acceptance criteria

1. Een nieuwe story krijgt zonder verdere actie: vragen=aan, goedkeuring=automatisch, meldingen=als-klaar.
2. Vragen-as en meldingen-as zijn onafhankelijk instelbaar: een story met vragen=aan + meldingen=geen krijgt wél vraag-Telegrams als er een vraag is (want vragen gaan altijd via Telegram zodra ze toegestaan zijn) maar geen enkele status-melding; een story met vragen=uit + meldingen=na-elke-stap krijgt per-stap-status-meldingen maar nooit een vraag-Telegram (vragen worden direct `[CLARIFICATION]`-Error).
3. Goedkeuring=`automatisch`: alle AI-subtaken lopen automatisch door én er wordt geen `manual-approve`-subtaak gematerialiseerd, ook niet als het project `manualApprove: true` (default) heeft.
4. Goedkeuring=`alleen-manual-poort`: AI-subtaken lopen automatisch door, maar de `manual-approve`-subtaak wordt gematerialiseerd (mits project dit niet expliciet uitzet) en blokkeert tot een mens `approve`/`reject` geeft.
5. Goedkeuring=`elke-stap`: elke AI-subtaak (development/review/test/summary/documentation) wacht op handmatige goedkeuring vóór de volgende fase start.
6. Meldingen=`geen`: geen enkel Telegram-bericht (status, vraag, noch error) verlaat de factory voor deze story.
7. Meldingen=`na-elke-stap`: bij elke terminale subtaak gaat een Telegram-status-melding uit (huidig standaardgedrag).
8. Meldingen=`als-klaar`: geen per-stap-meldingen; precies één melding zodra de laatste subtaak (na merge) terminaal wordt, zonder te wachten op externe live-verificatie.
9. Meldingen=`als-klaar-en-gedeployed`: precies één, DB-backed idempotente melding, pas ná bevestigde externe live-status (bestaand SF-1134-gedrag), inclusief de bestaande project-type-afhankelijke checks (openshift-watch/rest-restart/APK-release).
10. Bestaande stories zijn na de migratie 1-op-1 gedragsequivalent aan hun oude staat, volgens de migratietabel in de scope.
11. Nightly-stories (via de Nightly-knop of automatische scheduler) gedragen zich ongewijzigd: autonoom, geen vragen, geen Telegram, geen manual-poort.
12. Zowel het create-story-formulier als het story-detail-scherm tonen en laten alle drie assen bewerken, met de juiste huidige waarde en de juiste opties/labels per as.
13. `docs/factory/functional-spec.md` beschrijft de nieuwe drie-assen-structuur; de oude `Silent`/SF-335- en `telegram_result_notify`/SF-1134-secties zijn vervangen of expliciet gemarkeerd als vervangen door deze story.

## Aannames

- De drie oude kolommen/velden (`auto_approve`, `silent`, `telegram_result_notify`) worden na backfill volledig vervangen (geen backwards-compatible dual-write), omdat dit een intern datamodel is zonder externe API-consumers buiten deze mono-repo (dashboard-frontend en bridge worden in dezelfde story meeveranderd).
- Voor bestaande niet-silent stories zonder `telegram_result_notify` wordt gemigreerd naar meldingen=`na-elke-stap` (niet het nieuwe default `als-klaar`), om het huidige gedrag exact te behouden; alleen écht nieuwe stories krijgen de nieuwe default `als-klaar`.
- Voor `silent=true`-stories wordt goedkeuring altijd naar `automatisch` gemigreerd (ongeacht de oude `auto_approve`-kolomwaarde), omdat `Silent` vandaag sowieso auto-approve + het overslaan van de manual-poort impliceert.
- De nieuwe meldingen-optie `als-klaar` (na merge, zonder live-check) is functioneel nieuw: er bestaat vandaag geen notificatiepunt exact op "laatste subtaak terminaal, vóór/zonder live-verificatie" los van de per-stap-melding of de SF-1134-live-melding; dit vereist een nieuw triggerpunt (bijv. bij het terminaal worden van de laatste subtaak in de keten), geen hergebruik van een bestaande poller.
- `alleen-manual-poort` en `elke-stap` laten de bestaande project-config (`projects.yaml` `manualApprove`) als extra voorwaarde staan voor de `manual-approve`-poort; alleen `automatisch` forceert het overslaan ervan op story-niveau, ongeacht project-config.
- Exacte veldnamen (TrackerField-sleutels, kolomnamen, endpoint-paths, enum-waarden) zijn implementatiedetail voor de developer, mits ze het drie-assen-model uit deze scope volgen.

## Eindsamenvatting

Ik heb genoeg context uit `.task.md` en de worklog om de eindsamenvatting te schrijven. Geen code aangeraakt, alleen gelezen.

## Eindsamenvatting SF-1234 — Story-opties herstructureren: vragen / goedkeuring / meldingen

**Wat is gebouwd**
De drie oude, overlappende story-opties (`Auto-approve`, `Silent`, `TelegramResultNotify`) zijn vervangen door drie onafhankelijke assen op story-niveau:
- **Vragen toestaan** (aan/uit) — bepaalt of een `*-with-questions`-uitkomst via Telegram naar de gebruiker gaat of direct als `[CLARIFICATION]`-error wordt afgehandeld.
- **Goedkeuring** (`automatisch` / `alleen-manual-poort` / `elke-stap`) — bepaalt of AI-subtaken en de manual-approve-poort vóór merge automatisch doorlopen.
- **Meldingen** (`geen` / `na-elke-stap` / `als-klaar` / `als-klaar-en-gedeployed`) — bepaalt welke Telegram-status-meldingen uitgaan.

Doorgevoerd in datamodel (nieuwe Flyway-migratie `V19__story_option_axes.sql` met backfill en drop van de oude kolommen `auto_approve`/`silent`/`telegram_result_notify`), backend/pipeline (`HumanActionPolicy`, `SubtaskPlanMaterializer`, `TelegramNotificationService`, `TelegramResultNotifyPoller`, nightly-story-aanmaak), bridge/REST-endpoints, en dashboard-UI (create-dialoog en story-detailscherm tonen nu alle drie assen met de juiste defaults). Documentatie (`functional-spec.md`, `technical-spec.md`, UX- en bridge-ontwerpdocs) is bijgewerkt naar de nieuwe structuur.

**Belangrijke keuzes**
- Tijdens review is een fail-open regressie gevonden en gefixt: bij een falende parent-lookup viel de nieuwe code terug op auto-approve i.p.v. fail-safe naar handmatige goedkeuring. Dit is teruggedraaid naar fail-safe gedrag, met regressietests.
- Er ontstond een discrepantie tussen AC2 (vragen gaan altijd via Telegram, ook bij meldingen=geen) en AC6 (meldingen=geen onderdrukt alles, ook vragen). Product/story-owner heeft dit expliciet beslist: **AC2 is leidend** — een vraag gaat altijd via Telegram, omdat er anders geen enkele manier is om te reageren op een blokkerende `waiting-for-user`-toestand. Alle overige meldingen (status/error) blijven bij meldingen=`geen` volledig onderdrukt. Deze beslissing is als laatste stap verwerkt en getest.

**Wat is getest**
Volledige backend-testsuite (`mvn clean verify`, 5 modules) groen, inclusief nieuwe/aangepaste tests voor migratie-backfill, `HumanActionPolicy`, `manualApproveSpecs`, notify-onderdrukking per meldingenstand, de nieuwe "als-klaar"-triggerlogica en de pollerfix. Flutter: `flutter analyze` schoon, `flutter test` 58/58 groen. Eén flaky e2e-test (niet in de story-diff) is geïsoleerd herdraaid en bevestigd als flake.

**Bewust niet gedaan**
Documentatie buiten `docs/factory/` (o.a. `docs/technical/modules.md`, `docs/technical/scheduled-jobs.md`, `docs/onboarding-senior-developer.md`) is niet bijgewerkt — die bevatten nog oude terminologie en vallen buiten de scope van deze rol-instructie; dit is expliciet genoteerd voor een latere doc-ronde.
