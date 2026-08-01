# SF-1562 - Worklog

Story-context bij eerste pickup:
Tekstcorrecties manualApprove-opruiming (KDoc + agent-instructies + overview)

Zuivere tekstwijziging, geen gedrag. (1) softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/core/contracts/WorkflowModels.kt regel 58 en 61: vervang op ApprovalMode.MANUAL_GATE_ONLY en ApprovalMode.EVERY_STEP de zinsnede 'de manual-approve-poort volgt de project-config' door de geldende regel: bij deze waarde wordt de manual-approve-poort aangemaakt (bij AUTOMATIC overgeslagen). Sluit qua formulering aan bij docs/factory/functional-spec.md:108-112 en docs/factory/technical-spec.md:239-240. KDoc op AUTOMATIC blijft ongewijzigd; de fail-safe uit SubtaskPlanMaterializer.manualApproveSpecs niet herhalen. Geen enumnaam, trackerValue of signatuur wijzigen. (2) docs/factory/agents/planner.md regel 12: verwijder '(indien geconfigureerd)' achter `manual-approve`; opsomming documentation/manual-approve/merge/deploy en de instructie dat de planner die niet declareert blijven intact. (3) factory-common/src/main/resources/docs-skeleton/docs/factory/agents/planner.md regel 12: woordelijk dezelfde wijziging; verifieer met `diff` dat beide bestanden daarna exact gelijk zijn (lege diff). (4) docs/technical/overview.md regel 74 en 76: vervang `manual-gate-only` door `alleen-manual-poort` en `every-step` door `elke-stap`, gelijk aan regel 62-63 in hetzelfde document; rest van beide zinnen ongewijzigd. Laat docs/stories/** ongemoeid (archief). Controleer na afloop repo-breed dat manualApprove/project-config in combinatie met de manual-approve-poort niet meer voorkomt in **/src/main/**, docs/factory/** en docs/technical/**, en dat de strings manual-gate-only/every-step niet meer in docs/technical/overview.md staan. Sluit af met een eigen review van de diff en een groene `mvn verify` over de reactor. Geen nieuwe of gewijzigde tests nodig.

In eigen woorden:
De projectvlag die vroeger bepaalde of de `manual-approve`-poort werd aangemaakt bestaat niet
meer; alleen de goedkeuringsstand (`Goedkeuring`) van de story bepaalt dat nog. Op vier plekken
staat die oude uitleg er nog, plus op één plek Engelse enumnamen i.p.v. de tracker-waarden.
Deze story werkt alleen die teksten bij — geen gedragswijziging.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes (KDoc ApprovalMode, beide planner.md's, overview.md)
[x]: verify acceptance criteria (diff-check, greps)
[x]: run full vangnet (`mvn clean verify` + `tools/audit-documentation`)
[x]: update story-log with results

Done / rationale (SF-1670, development):
1. `softwarefactory/.../core/contracts/WorkflowModels.kt` — KDoc op `ApprovalMode.MANUAL_GATE_ONLY`
   en `ApprovalMode.EVERY_STEP`: "de manual-approve-poort volgt de project-config" vervangen door
   "de manual-approve-poort wordt aangemaakt". Formulering sluit aan op
   `docs/factory/functional-spec.md:108-112` en `docs/factory/technical-spec.md:239-240`. KDoc op
   `AUTOMATIC` ongewijzigd; de fail-safe uit `SubtaskPlanMaterializer.manualApproveSpecs` bewust
   niet herhaald (implementatiedetail van de materializer, niet de betekenis van de enumwaarde).
   Geen enumnaam, `trackerValue` of signatuur aangeraakt.
2. `docs/factory/agents/planner.md` — "(indien geconfigureerd)" achter `manual-approve` geschrapt;
   de opsomming `documentation`/`manual-approve`/`merge`/`deploy` en de instructie dat de planner
   die niet zelf declareert blijven intact (regelafbreking opnieuw gezet binnen dezelfde
   regelbreedte).
3. `factory-common/src/main/resources/docs-skeleton/docs/factory/agents/planner.md` — woordelijk
   dezelfde wijziging; `diff` tussen beide bestanden is leeg (geverifieerd).
4. `docs/technical/overview.md` regel 74/76 — `manual-gate-only` -> `alleen-manual-poort` en
   `every-step` -> `elke-stap`, gelijk aan regel 63 in hetzelfde document. Rest van beide zinnen
   ongewijzigd.

Verificatie:
- `diff docs/factory/agents/planner.md factory-common/src/main/resources/docs-skeleton/docs/factory/agents/planner.md`
  -> leeg (AC4).
- `grep -n "manual-gate-only\|every-step" docs/technical/overview.md` -> geen treffers (AC5).
- Repo-brede grep op `manualApprove`/`project-config` in `**/src/main/**`, `docs/factory/**` en
  `docs/technical/**`: geen enkele treffer koppelt de manual-approve-poort nog aan project-config.
  Resterende treffers zijn onverwant: `project-config` in `ProjectConfiguration.kt` (400/507) en
  `AgentRunCompletionService.kt:62` gaat over `projects.yaml` in het algemeen, en
  `manualApprove*` zijn functie-/constantnamen (`SubtaskExecutionCoordinator`,
  `SubtaskPlanMaterializer`, `ManualCommandService`) — geen tekst over een projectvlag (AC2).
- `mvn -B --no-transfer-progress clean verify` vanaf repo-root: **BUILD SUCCESS**, exitcode 0,
  0 failures / 0 errors over alle modules (softwarefactory incl. Testcontainers-e2e's), 04:12 min.
- `tools/audit-documentation`: `documentation-audit/v1: PASS` (exit 0).

Geen nieuwe of gewijzigde tests: de wijziging bestaat volledig uit KDoc-commentaar en markdown en
heeft geen enkele runtime-uiting die getest kan worden. De bestaande suite dekt het gedrag van
`ApprovalMode`/`manualApproveSpecs` onveranderd af.

Aangeraakte specs in `docs/factory/`: alleen `docs/factory/agents/planner.md` (de instructietekst
die letterlijk aan de planner-agent wordt meegegeven). `functional-spec.md` en `technical-spec.md`
beschreven het huidige gedrag al correct en zijn daarom niet aangepast.

Review (SF-1670, reviewer, 01-08-2026): akkoord.
- Volledige story-diff (`git diff main...HEAD`) beslaat exact de 4 in scope genoemde bestanden plus
  dit worklog; geen scope creep, geen enkele Kotlin-declaratie/`trackerValue`/signatuur geraakt.
- AC1 herverifieerd tegen de bron: `SubtaskPlanMaterializer.manualApproveSpecs` (r162-175) laat de
  poort alleen weg bij `ApprovalMode.AUTOMATIC`, dus "de manual-approve-poort wordt aangemaakt"
  klopt voor `MANUAL_GATE_ONLY` en `EVERY_STEP`. Consistent met `functional-spec.md:108-112` en
  `technical-spec.md:239-241`.
- AC2/AC4/AC5 zelf nagelopen: `diff` van beide `planner.md`'s leeg; geen treffers op
  `manual-gate-only`/`every-step` in `docs/technical/overview.md`; resterende `project-config`- en
  `manualApprove*`-treffers in `**/src/main/**` zijn onverwant (YAML-parsing resp. functienamen).
- AC3: opsomming en "declareer die niet" intact in beide kopieën.
- AC6: geen eigen herhaling van het vangnet; de diff bevat uitsluitend commentaar en markdown en
  heeft geen runtime-uiting, en het developerbewijs (`mvn clean verify` BUILD SUCCESS) is
  revisiegebonden geverifieerd.

Test (SF-1671, tester, 01-08-2026): akkoord.
- Volledige vangnet: `mvn -B --no-transfer-progress clean verify` vanaf repo-root -> BUILD SUCCESS,
  exit 0, 4m14. 938 tests over 5 modules (contracts 16, common 52, softwarefactory 685 unit +
  74 e2e failsafe, agentworker 60, dashboard-backend 51), 0 failures / 0 errors / 0 skipped,
  0 `[ERROR]`-regels. Geen flakes deze ronde (o.a. FactoryApiControllerTest,
  TesterVerificationRunnerTest en de e2e-klassen groen).
- `tools/audit-documentation` -> `documentation-audit/v1: PASS`, exit 0 (dit commando heeft geen
  pathPrefixes en draait altijd mee).
- AC1: KDoc op `MANUAL_GATE_ONLY`/`EVERY_STEP` luidt nu "de manual-approve-poort wordt aangemaakt";
  geverifieerd tegen de bron `SubtaskPlanMaterializer.manualApproveSpecs` (poort alleen weggelaten
  bij `AUTOMATIC`). KDoc op `AUTOMATIC` ongewijzigd.
- AC2: geen enkele `manualApprove*`/`project-config`-treffer in `**/src/main/**`, `docs/factory/**`
  of `docs/technical/**` die nog naar de project-config voor de poort verwijst; resterende hits
  zijn functienamen (`manualApproveSpecs`, `manualApproveSubtask`) en `ProjectConfiguration`-
  YAML-parsing, beide onverwant.
- AC3/AC4: `(indien geconfigureerd)` weg in beide kopieën; `diff docs/factory/agents/planner.md
  factory-common/src/main/resources/docs-skeleton/docs/factory/agents/planner.md` is leeg.
- AC5: `grep -n "manual-gate-only\|every-step" docs/technical/overview.md` -> geen treffers;
  regel 74/76 gebruiken `alleen-manual-poort`/`elke-stap`, rest van de zinnen ongewijzigd.
- Gedragsbewijs voor de agent-instructie (de enige tekst met runtime-uiting): de skeleton wordt als
  classpath-resource verpakt; uit `factory-common/target/factory-common-0.0.1-SNAPSHOT.jar` de
  entry `docs-skeleton/docs/factory/agents/planner.md` uitgepakt en woordelijk identiek bevonden
  aan `docs/factory/agents/planner.md` -> een nieuwe uitrol geeft de planner de gecorrigeerde
  tekst mee. `.manifest` bevat de entry (regel 8), dus `DocsSkeletonInstaller` installeert 'm.
- Geen preview-omgeving voor deze repo (SF_PREVIEW_URL leeg); browser-/E2E-scenario's n.v.t. bij
  een pure tekstdiff. Geen code, tests of infra gewijzigd; werkboom verder schoon.
