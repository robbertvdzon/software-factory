# SF-335 - Worklog

## Story in eigen woorden

Voeg een enum-boolean veld `Silent` (default `false`, analoog aan `Paused`) op story-niveau toe en
weef het door de keten. Bij `Silent=true` loopt een story volledig autonoom: alle fases worden
automatisch doorgezet (silent impliceert auto-approve), er is geen handmatige goedkeur-poort,
onduidelijkheden (`*-with-questions`) worden een error i.p.v. een wachtmoment, en er gaat geen enkel
Telegram-bericht uit. Subtaken erven de waarde van de parent-story (parent-lookup), net als
`Auto-approve`. Niet-silent gedrag blijft volledig backwards compatible.

## Stappenplan / checklist

[x]: read issue and target docs
[x]: `TrackerField.SILENT` + `TrackerIssueFields.silent` in core/TrackerModels.kt
[x]: `YouTrackClient.mapIssue()` leest Silent als enum-boolean; `fieldUpdate()` schrijft `SingleEnumIssueCustomField`; FieldSpec geregistreerd voor schema-bootstrap
[x]: gedeelde helper `YouTrackApi.effectiveSilent(issue)` (parent-lookup, analoog autoApprove)
[x]: auto-approve-conditie `(autoApprove || silent)` in `SubtaskExecutionCoordinator.autoApproveActive` + `StoryRefinementCoordinator` + `FactoryDashboardService.autoApproveActive` (mirror)
[x]: `MANUAL_APPROVE` overslaan bij silent parent in `AgentRunCompletionService.materializeSubtasksIfPlanned()`
[x]: `*-with-questions` → `TrackerField.ERROR` met vragen als error-tekst bij silent (story + subtaak), i.p.v. waiting-for-user
[x]: error-categorisatie: `ErrorCategory.CLARIFICATION` (`[CLARIFICATION]`) vs `TECHNICAL`
[x]: `TelegramNotificationService` onderdrukt alle berichten voor silent stories/subtaken (parent-lookup)
[x]: tests in de stijl van bestaande tests
[x]: build + gerichte tests draaien (groen)
[x]: factory-specs bijgewerkt (functional-spec.md, technical-spec.md)

## Wat is er precies gedaan en waarom

**Model (core/TrackerModels.kt):** `TrackerField.SILENT("Silent")` en `TrackerIssueFields.silent: Boolean = false`
(default zodat bestaande, named-arg constructie backwards compatible blijft). Nieuw `ErrorCategory`-enum
(`CLARIFICATION`/`TECHNICAL`) met `of(errorText)` en `clarificationText(questions)` — de markering leeft als
leesbare prefix in de bestaande error-tekst; geen nieuw YouTrack-veld.

**YouTrackClient:** `mapIssue()` leest Silent als enum-boolean (`"true"`/`"on"` → `true`).
`fieldUpdate()` schrijft `TrackerField.SILENT` als `SingleEnumIssueCustomField`. `FieldSpec("Silent", "enum[1]",
…, values=["false","true"])` toegevoegd aan `factoryFieldSpecs` zodat het veld via schema-bootstrap
gegarandeerd in YouTrack bestaat. `ManualCommandService` `when(field)` kreeg de `SILENT`-tak (exhaustief).

**Gedeelde helper:** `YouTrackApi.effectiveSilent(issue)` (default-methode): eigen `silent` óf — voor een
subtaak — die van de parent-story (best-effort parent-lookup). Eén bron van waarheid voor coördinatoren,
notificaties en dashboard, identiek aan hoe auto-approve via de parent wordt bepaald.

**Auto-approve = (autoApprove || silent):** `SubtaskExecutionCoordinator.autoApproveActive` en
`FactoryDashboardService.autoApproveActive` (mirror) lezen nu beide vlaggen met dezelfde parent-lookup.
`StoryRefinementCoordinator` gebruikt een lokale `autoApproveOrSilent(issue)`.

**Geen manual-approve bij silent:** in `materializeSubtasksIfPlanned()` wordt de `MANUAL_APPROVE`-spec
overgeslagen als de parent-story silent is; merge/deploy/documentation blijven onveranderd.

**Vragen → error bij silent:** elke `*-with-questions`-tak (story: `refined`/`planned`; subtaak:
`developed`/`reviewed`/`tested`/`summary`/`documentation`) roept bij effectief silent een `questionsOutcome`-
helper aan die `TrackerField.ERROR` zet met `ErrorCategory.clarificationText(<laatste agent-comment>)` en
`Errored` teruggeeft. Niet-silent: ongewijzigd `Skipped("waiting-for-user")`.

**Telegram:** `notifyPending()` slaat een issue volledig over als `effectiveSilent(issue)` — geen enkel
bericht, ook geen error-melding.

## Tests

- `YouTrackClientTest`: Silent lezen (enum-boolean) + schrijven (`SingleEnumIssueCustomField`).
- `TelegramNotificationServiceTest`: silent story en subtaak-met-silent-parent → geen bericht; niet-silent ongewijzigd.
- `AgentRunCompletionServiceTest`: silent parent → geen `Handmatige goedkeuring`-subtaak (merge/deploy/docs blijven).
- `OrchestratorServiceTest`: silent story `refined` → auto-advance; silent story/subtaak `*-with-questions` → clarification-error; niet-silent blijft wachten; silent parent advanced developed-subtaak.
- `FakeYouTrackState` (e2e-seed) kreeg het `Silent`-veld zodat de schema-bootstrap-test groen blijft.

Gedraaid met `mvn -f softwarefactory/pom.xml test -Dtest=…`: alle gerichte suites groen
(YouTrackClient/Telegram/AgentRunCompletion/Orchestrator/FakeYouTrackServer + dashboard/manual/merge).

## Geraakte factory-specs

- `docs/factory/functional-spec.md`: nieuwe sectie "Silent — autonoom verwerken (SF-335)".
- `docs/factory/technical-spec.md`: nieuwe sectie "YouTrack custom fields" (enum-boolean, effectiveSilent, ErrorCategory).

## Review (SF-336, reviewer)

Statische review van de volledige story-diff `main...HEAD` (geen lokale mvn beschikbaar; build/tests via CI).

Bevindingen:
- [info] Alle 7 acceptatiecriteria zijn herleidbaar in de diff en gedekt door tests
  (veld lezen/schrijven + schema-seed, silent⇒auto-approve op story- én subtaak-niveau via
  `effectiveSilent`/parent-lookup, manual-approve-skip met behoud van merge/deploy/docs,
  `*-with-questions`→clarification-error i.p.v. wachten, `ErrorCategory.of` markering, Telegram-suppressie).
- [info] Geen busy-loop-risico: na het zetten van `TrackerField.ERROR` skipt de top-level error-guard
  in `StoryPipelineService` het issue (`reason="error"`, idle); `recoverRetryableIssueError` triggert
  alleen op de specifieke container-fout, dus de clarification-error blijft staan (niet-retrybaar, conform).
- [info] Telegram-guard staat boven in de `notifyPending`-loop (vóór alle send-paden: `sendMessage`,
  `notifySubtaskDone`, `tryNotifyMergeReady`, screenshots), dus silent-issues worden volledig onderdrukt.
- [suggestie] `ManualCommandService` parseert `SILENT` uit string alleen op `"true"`, terwijl
  `mapIssue()` zowel `"true"` als `"on"` accepteert. Functioneel niet kritiek (schemawaarden zijn
  `false`/`true`), maar de asymmetrie met `mapIssue` is een kleine inconsistentie. Geen blocker.
- [info] Specs (functional-spec.md, technical-spec.md) zijn consistent met de implementatie.
- [info] Geen scope creep; niet-silent paden blijven backwards compatible (default `silent=false`).

Conclusie: akkoord.
