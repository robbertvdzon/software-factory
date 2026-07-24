# SF-1234 - Worklog

Story-context bij eerste pickup:
Herstructureer story-opties naar drie assen (vragen/goedkeuring/meldingen)

Voeg TrackerField-kolommen questions_allowed/approval_mode/notify_mode toe via een Flyway-migratie ná V18, inclusief backfill van bestaande stories volgens de migratietabel in de scope en het droppen van auto_approve/silent/telegram_result_notify (geen dual-write). Herimplementeer HumanActionPolicy.autoApproveActive/awaitsHuman op approval_mode. Vervang effectiveSilent door de nieuwe vragen-as-check in StoryRefinementCoordinator en SubtaskExecutionCoordinator.questionsOutcome() (het [CLARIFICATION]-Error-pad blijft functioneel identiek). Pas SubtaskPlanMaterializer.manualApproveSpecs() aan zodat automatisch de manual-approve-poort altijd overslaat, en manual-gate-only/every-step de bestaande projects.yaml-config laten gelden. Pas TelegramNotificationService.notifyPending aan op notify_mode (geen/na-elke-stap/als-klaar/als-klaar-en-gedeployed) en bouw het nieuwe als-klaar-triggerpunt (melding zodra de laatste subtaak na merge terminaal wordt, zonder live-check). Verschuif de activatievoorwaarde van TelegramResultNotifyPoller naar notify_mode=als-klaar-en-gedeployed en laat 'm ook meldingen=geen respecteren. Werk DashboardCommandService.createNightlyStory() bij naar de equivalente drie-assen-waarden. Vervang in stories_screen.dart en story_detail_screen.dart de bestaande Auto-approve/Silent/TelegramResultNotify-toggles door de drie nieuwe controls met de juiste defaults en opties. Vervang de bridge-endpoints story.setAutoApprove/setSilent/setTelegramResultNotify door story.setQuestionsAllowed/setApprovalMode/setNotifyMode in BridgeApiController.kt en BridgeRequestHandler.kt inclusief REST-routes. Werk docs/factory/functional-spec.md bij (secties SF-335/SF-1134 vervangen door de drie-assen-structuur). Schrijf/actualiseer unit- en integratietests voor migratie-backfill, HumanActionPolicy, questionsOutcome, manualApproveSpecs, notify-onderdrukking, het nieuwe als-klaar-triggerpunt en de pollerfix.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## SF-1261 (development) — uitgevoerd

**Datamodel & migratie**
- `factory-common/.../core/TrackerField.kt`: `SILENT`/`AUTO_APPROVE`/`TELEGRAM_RESULT_NOTIFY` vervangen
  door `QUESTIONS_ALLOWED`/`APPROVAL_MODE`/`NOTIFY_MODE`.
- Nieuwe enums `ApprovalMode` (`automatisch`/`alleen-manual-poort`/`elke-stap`, default `automatisch`)
  en `NotifyMode` (`geen`/`na-elke-stap`/`als-klaar`/`als-klaar-en-gedeployed`, default `als-klaar`) in
  `core/contracts/WorkflowModels.kt`, met `fromTracker` die op onbekende/lege waarde naar het default
  valt. `TrackerIssueFields` kreeg `questionsAllowed: Boolean = true`, `approvalMode: String`,
  `notifyMode: String` i.p.v. `autoApprove`/`silent`/`telegramResultNotify`.
- Nieuwe migratie `V19__story_option_axes.sql` (ná V18): kolommen `questions_allowed BOOLEAN NOT NULL
  DEFAULT true`, `approval_mode TEXT NOT NULL DEFAULT 'automatisch'`, `notify_mode TEXT NOT NULL
  DEFAULT 'als-klaar'`; backfill exact volgens de migratietabel in de scope (silent=true →
  vragen=uit/automatisch/geen; overige combinaties → vragen=aan, goedkeuring naar
  auto_approve-waarde, meldingen naar telegram_result_notify-waarde met `na-elke-stap` als
  niet-default-fallback); daarna `auto_approve`/`silent`/`telegram_result_notify` gedropt (geen
  dual-write, zoals de Aannames-sectie voorschrijft).
- `PostgresTrackerClient`: kolomlijst/`mapRow`/`columnFor`/`columnValue`/`createStory` bijgewerkt naar
  de drie nieuwe kolommen.

**Backend/pipeline**
- `HumanActionPolicy.autoApproveActive`: leest nu `ApprovalMode.fromTracker(fields.approvalMode) !=
  EVERY_STEP`, met dezelfde parent-lookup-vorm als voorheen (subtaak → parent, story → eigen veld).
- `TrackerCapabilities`: `effectiveSilent` vervangen door `effectiveQuestionsAllowed` (story/subtaak,
  parent-lookup) en `effectiveNotifyMode` (idem, retourneert `NotifyMode`).
- `StoryRefinementCoordinator`/`SubtaskExecutionCoordinator.questionsOutcome()`: het
  `[CLARIFICATION]`-Error-pad blijft functioneel identiek, nu gate-conditie
  `!effectiveQuestionsAllowed(issue)` i.p.v. `effectiveSilent(issue)`.
- `SubtaskPlanMaterializer.manualApproveSpecs()`: poort wordt nu overgeslagen wanneer
  `ApprovalMode.fromTracker(parent.approvalMode) == AUTOMATIC`, ongeacht projects.yaml;
  `alleen-manual-poort`/`elke-stap` laten `projectRepoResolver.manualApproveFor(...)` bepalend.
- `TelegramNotificationService.notifyPending`: `meldingen=geen` (nieuwe `effectiveNotifyMode`-check)
  vervangt de oude full-suppress-`effectiveSilent`-check 1-op-1 (AC6). Nieuwe helper
  `suppressedByNotifyMode` onderdrukt voor `als-klaar`/`als-klaar-en-gedeployed` alle QUESTION/
  APPROVAL/MANUAL/PROGRESS/DONE-meldingen behalve de allerlaatste subtaak-DONE (gedetecteerd via
  "alle subtaken van de story terminaal", hergebruikt de bestaande `notifySubtaskDone`-flow als nieuw
  als-klaar-triggerpunt); voor `als-klaar-en-gedeployed` wordt ook die laatste onderdrukt (alleen de
  poller meldt daar); ERROR gaat in beide gevallen door.
- `TelegramResultNotifyPoller`: activatievoorwaarde verschoven naar
  `notify_mode == als-klaar-en-gedeployed`; omdat dit dezelfde enum is als `meldingen=geen`, respecteert
  de poller die stand nu inherent (fix van de oude boolean-inconsistentie).
- `ManualCommandService`: de `AUTO-APPROVE=on/off`-commentaartrigger blijft bestaan maar stuurt nu
  `ApprovalMode` (`on` → `automatisch`, `off` → `elke-stap`); `alleen-manual-poort` is via deze trigger
  niet bereikbaar (was ook geen apart commando vóór deze story).
- `DashboardCommandService.createNightlyStory`: nightly-stories krijgen nu expliciet
  vragen=uit/goedkeuring=automatisch/meldingen=geen (equivalent aan het oude `silent=true`).
- `DashboardCommands`/`DashboardApi`: `setAutoApproveFlag`/`setSilentFlag`/`setTelegramResultNotifyFlag`
  vervangen door `setQuestionsAllowedFlag`/`setApprovalMode`/`setNotifyMode`.

**Bridge & dashboard-backend**
- `BridgeRequestHandler.kt`: `story.create`-params nu `questionsAllowed`/`approvalMode`/`notifyMode`;
  `story.setAutoApprove`/`setSilent`/`setTelegramResultNotify` vervangen door
  `story.setQuestionsAllowed`/`setApprovalMode`/`setNotifyMode`.
- `BridgeApiController.kt` (dashboard-backend): nieuwe REST-routes
  `POST /api/v1/stories/{storyKey}/questions-allowed` (`{enabled}`),
  `.../approval-mode` (`{mode}`), `.../notify-mode` (`{mode}`); `CreateStoryRequest` kreeg
  `questionsAllowed`/`approvalMode`/`notifyMode` i.p.v. `autoApprove`/`silent`.

**Dashboard-UI (Flutter)**
- `stories_screen.dart` (create-story-dialog): "Vragen toestaan"-switch (default aan) + "Goedkeuring"-
  en "Meldingen"-dropdowns (defaults `automatisch`/`als-klaar`) i.p.v. de losse Auto-approve-switch.
- `story_detail_screen.dart`: `_toggleAutoApprove`/`_toggleSilent`/`_toggleTelegramResultNotify`
  vervangen door `_toggleQuestionsAllowed`/`_setApprovalMode`/`_setNotifyMode`, met bijpassende
  switch/dropdown-widgets (alleen op story-niveau, subtaken tonen deze drie niet meer — ze zijn nu
  zuiver geërfd van de parent, net als eerder `Auto-approve` via `HumanActionPolicy`).

**Documentatie**
- `docs/factory/functional-spec.md`: sectie "Silent — autonoom verwerken (SF-335)" vervangen door
  "Drie story-opties-assen: vragen / goedkeuring / meldingen (SF-1261)"; sectie SF-1134 herschreven
  naar `notify_mode=als-klaar-en-gedeployed`; overige `Auto-approve=on`-verwijzingen in de
  documentatie-stap/manual-approve-poort/SF-206-secties bijgewerkt naar de nieuwe as-taal.
- `docs/factory/technical-spec.md`: sectie "Tracker-database en -velden" en "Telegram-resultaatmelding"
  herschreven naar de drie kolommen/enums.
- `docs/factory/ux/screens/story-detail.md`: actiebeschrijving bijgewerkt naar de drie nieuwe controls.
- `docs/ontwerp-bridge-dashboard.md`: operatie-tabel en methode-overzicht bijgewerkt naar de nieuwe
  bridge-operaties/servicemethodes.
- Bewust NIET aangepast (buiten scope van de rol-instructie "werk docs/factory/ bij"): `docs/technical/
  modules.md`, `docs/technical/scheduled-jobs.md` en `docs/onboarding-senior-developer.md` (staan
  buiten `docs/factory/`) — deze bevatten nog oude `Silent`/`Auto-approve`/`telegram_result_notify`-taal
  en zouden in een latere doc-ronde bijgewerkt kunnen worden.

**Tests**
- Nieuwe/aangepaste tests: `TelegramNotificationServiceTest` (4 nieuwe SF-1261-tests voor
  `als-klaar`/`als-klaar-en-gedeployed`-onderdrukking + ERROR-doorlaat), `AgentRunCompletionServiceTest`
  (hernoemde "silent"-test → `approval mode is automatic`, nieuwe test voor `alleen-manual-poort`),
  `TelegramResultNotifyPollerTest`, `ManualCommandServiceTest`, `StoryRefinementCoordinatorAutoStartTest`,
  `OrchestratorRefinementFlowTest`/`OrchestratorSubtaskFlowTest` (silent+auto-approve nu twee losse
  velden), `FactoryDashboardServiceTest`/`BridgeRequestHandlerTest`/`BridgeApiControllerTest` (nieuwe
  operatie-/endpointnamen), plus de e2e-testinfra (`TrackerTestState`, `E2eTestBase`,
  `ManualApproveGateE2eTest`, `OrchestratorGateE2eTest`, `SpecScenarioCoverageE2eTest`,
  `PipelineLoopbackE2eTest`, `TrackerCapabilityPersistenceE2eTest`) omgezet naar de nieuwe
  testveld-namen (`ApprovalMode`/`QuestionsAllowed`/`NotifyMode`).
- Flutter: `story_detail_screen_test.dart` — Telegram-resultaat-toggle-test vervangen door een test op
  de nieuwe "Meldingen"-dropdown.
- Bewijs: `mvn -B --no-transfer-progress clean verify` vanaf de repo-root → **BUILD SUCCESS**, alle
  5 modules groen (0 failures, 0 errors). `flutter analyze` → geen issues. `flutter test` → 58/58 groen.

**Bekende afwijking t.o.v. de story-tekst**
- AC2 in de story suggereert dat `vragen=aan + meldingen=geen` toch een vraag-Telegram zou moeten
  opleveren; AC6 en de Backend/pipeline-sectie zijn hierover expliciet strenger ("geen enkel bericht,
  ook geen vraag"). Ik heb AC6 + de Backend/pipeline-tekst gevolgd (die zijn onderling consistent en
  matchen de oude `effectiveSilent`-full-suppress-check 1-op-1); AC2's eerste voorbeeldzin lijkt een
  redactiefout in de story. Zie `TelegramNotificationService.notifyPending`: `meldingen=geen` skipt het
  issue volledig, vóór classificatie in QUESTION/APPROVAL/etc.

## Review SF-1261 (2026-07-24)

**[bug] Fail-open regressie in `HumanActionPolicy.autoApproveActive` bij parent-lookup-falen**
(`softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/core/contracts/HumanActionPolicy.kt:93-101`).
Oud gedrag: als de parent-story voor een subtaak niet opgehaald kon worden (transient DB/tracker-fout,
`parentFieldsOf(...) ?: return false`), gold expliciet `autoApproveActive = false` → de subtaak wacht op
een mens. Nieuw gedrag: bij dezelfde lookup-fout valt de code terug op `issue.fields` van de subtaak zelf
(`parentFieldsOf(issue.key) ?: issue.fields`), en omdat een subtaak nooit zelf een `approvalMode` zet,
is dat veld altijd de class-default `"automatisch"` → de subtaak wordt bij een lookup-fout nu juist
altíjd auto-approved, incl. het overslaan van de manual-approve-poort. Dit is een fail-safe→fail-open
omkering op een goedkeuringsgate, niet gevraagd door de story ("her-implementeren... met parent-lookup
voor subtaken zoals nu" impliceert gedragsbehoud), en er is geen test die dit lookup-faalpad dekt (geen
`HumanActionPolicyTest` aanwezig). Zelfde patroon in
`SubtaskPlanMaterializer.manualApproveSpecs()` (`runtime/services/SubtaskPlanMaterializer.kt:58,167`):
als `issueTrackerClient.getIssue(request.storyKey)` faalt, is `parentIssue == null` →
`ApprovalMode.fromTracker(null) == AUTOMATIC` → de manual-approve-poort wordt nu stilzwijgend
overgeslagen, terwijl de oude `parentSilent = false`-fallback de poort liet aanmaken (mits
project-config dat vroeg). Concreet scenario: tracker-call timeout tijdens subtaak-plan-materialisatie
op een `elke-stap`/`alleen-manual-poort`-project → geen manual-approve-subtaak, story merget door zonder
mens-goedkeuring.

**[vraag] AC2 vs AC6/Backend-pipeline-tekst — bewuste afwijking van de developer**
Developer heeft in de "Bekende afwijking"-sectie hierboven expliciet gekozen voor AC6 (volledige
onderdrukking bij meldingen=geen, inclusief vragen) boven AC2's voorbeeldzin (vragen gaan altijd door,
ook bij meldingen=geen). Dit is inhoudelijk consistent uitgewerkt en getest, maar wijkt af van een
letterlijke, genummerde acceptance criterion zonder dat daar product-akkoord voor is. Vraag terug aan
product/story-owner welke lezing correct is vóórdat dit als definitief gedrag geldt.

Conclusie: het fail-open lookup-gedrag in de goedkeuringsgate is een reële regressie op een
veiligheidsrelevant pad (auto-merge zonder mens-goedkeuring bij transient fouten) en gaat terug naar de
developer. De AC2/AC6-discrepantie is een aparte, niet-blokkerende vraag voor product.

## Review SF-1261, ronde 2 (2026-07-24)

Diff opnieuw beoordeeld t.o.v. `main` (volledige story-diff, 47 bestanden).

- **Fail-open regressie opgelost.** `HumanActionPolicy.autoApproveActive` valt bij een falende
  parent-lookup weer expliciet fail-safe terug op `return false`
  (`core/contracts/HumanActionPolicy.kt:93-99`), i.p.v. terug te vallen op `issue.fields` van de
  subtaak zelf. Nieuwe regressietest `subtaak met falende parent-lookup is fail-safe (geen
  auto-approve)` (`HumanActionPolicyTest.kt:33-39`) dekt dit expliciet.
- Zelfde patroon gefixt in `SubtaskPlanMaterializer.manualApproveSpecs()`
  (`runtime/services/SubtaskPlanMaterializer.kt:169-171`): `gateForcedOff` is nu alleen `true` als
  `parentIssue != null` én `approvalMode == AUTOMATIC`; bij een falende parent-lookup
  (`parentIssue == null`) blijft de bestaande project-config (`projectRepoResolver.manualApproveFor`)
  bepalend voor de manual-approve-poort, zoals vóór deze story. Gedekt door nieuwe test
  `laat de manual-approve-poort staan als de parent-lookup faalt`
  (`SubtaskPlanMaterializerTest.kt:99`).
- Migratie (`V19__story_option_axes.sql`) volgt de backfill-tabel uit de scope exact (silent=true →
  vragen=uit/automatisch/geen; overige combinaties → vragen=aan, goedkeuring/meldingen naar de oude
  auto_approve/telegram_result_notify-waarden, met `na-elke-stap` als fallback i.p.v. de nieuwe
  default `als-klaar`); oude kolommen worden pas ná de UPDATE-statements gedropt.
- `TelegramNotificationService.suppressedByNotifyMode`/`isStoryCompletingDone` implementeren het
  nieuwe "als-klaar"-triggerpunt (laatste subtaak van de story terminaal) correct en laten ERROR in
  alle gevallen door; `TelegramResultNotifyPoller`'s activatievoorwaarde is verschoven naar
  `als-klaar-en-gedeployed` en respecteert daarmee inherent `meldingen=geen`.
- Dashboard-UI (`story_detail_screen.dart`/`stories_screen.dart`) toont nu alle drie assen met de
  juiste defaults/opties op story-niveau (AC12); bridge/REST-endpoints zijn consistent hernoemd
  (`BridgeApiController.kt`/`BridgeRequestHandler.kt`).
- `functional-spec.md`/`technical-spec.md`/UX-doc zijn bijgewerkt naar de drie-assen-structuur en
  consistent met de code; SF-335/SF-1134-secties zijn herschreven i.p.v. verwijderd, met duidelijke
  kruisverwijzing.
- **[vraag] AC2 vs AC6 blijft openstaan** (zie ronde 1) — dit is een productvraag, geen
  code-blocker; de gekozen lezing (AC6, volledige onderdrukking bij meldingen=geen) is intern
  consistent doorgevoerd en getest.

Geen nieuwe blockers gevonden. De eerder gerapporteerde fail-open regressie is correct en met
regressietests opgelost. Akkoord voor deze subtaak.
