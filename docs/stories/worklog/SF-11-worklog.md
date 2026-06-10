# SF-11 - Worklog

## Story (eigen woorden)
Subtask van SF-10 (auto approve). Voeg een `AUTO-APPROVE=on|off` story-trigger toe,
analoog aan het bestaande AI-supplier-patroon: een nieuwe `AutoApproveTrigger`, een
`TrackerField.AUTO_APPROVE`-veld en een `autoApprove`-veld (default uit) op
`TrackerIssueFields`. De YouTrack-client leest het veld uit (default uit als afwezig)
en `ManualCommandService` past de trigger toe via `setAutoApprove` (idempotent) en
neemt de trigger mee in de instruction-filter. Deze subtask levert alleen het
trigger/veld-fundament; de feitelijke auto-advance in de orchestrator valt buiten SF-11.

## Checklist
[x]: read issue en target docs
[x]: AutoApproveTrigger + TrackerField.AUTO_APPROVE + autoApprove-veld in TrackerModels.kt
[x]: AUTO-APPROVE=on|off parsing in TrackerCommentParser.kt
[x]: YouTrackClient leest veld (default uit) + schrijft als SingleEnum
[x]: ManualCommandService: filter + applyInstructions-tak + setAutoApprove (idempotent)
[x]: tests toegevoegd (parser + ManualCommandService)
[ ]: tests gedraaid -- maven niet beschikbaar in deze omgeving

## Gedaan / rationale
- `TrackerModels.kt`: enum-lid `AUTO_APPROVE("Auto-approve")`, `data class
  AutoApproveTrigger(enabled, sourceText)`, en `autoApprove: Boolean = false` op
  `TrackerIssueFields` (default uit -> bestaande stories ongewijzigd).
- `TrackerCommentParser.kt`: regex `\bAUTO-APPROVE\s*=\s*(on|off)\b` (case-insensitief),
  parsed naar `AutoApproveTrigger(enabled = on)`. Geplaatst tussen supplier en budget,
  exact mirror van het supplier-pattern.
- `YouTrackClient.kt`: leest het veld via `customFieldText(...AUTO_APPROVE...)`, true bij
  "on"/"true", anders/afwezig false. Schrijven via bestaande `SingleEnumIssueCustomField`-tak
  (waarde "on"/"off"), naast AI_SUPPLIER.
- `ManualCommandService.kt`: `AutoApproveTrigger` toegevoegd aan de instruction-filter,
  `applyInstructions`-tak roept `setAutoApprove(issue, enabled)` aan. Die schrijft via
  `updateIssueFields(AUTO_APPROVE to "on"/"off")` + in-memory copy, en is idempotent
  (skip-write als de waarde al gelijk is). Exhaustive `when` over `TrackerField` aangevuld.
- Tests: parser-test uitgebreid met `AUTO-APPROVE=on` + losse off-test; nieuwe
  ManualCommandService-test die het veld zet en idempotentie controleert.

## Aandachtspunten
- YouTrack-project moet een `Auto-approve` (bool/enum on|off) custom field hebben;
  ontbreken laat de mapping niet crashen (read default uit). `TrackerFieldMapping.fromDefinitions`
  wordt nergens aangeroepen, dus geen runtime-provisioning-crash.
- Maven (`mvn`) is niet beschikbaar in deze omgeving; unit tests zijn niet lokaal gedraaid.
- Alleen de `softwarefactory`-module is aangepast (orchestrator-zijde), conform de
  SF-11-omschrijving; `agentworker` heeft geen parser/ManualCommandService.

## Review (reviewer)
- [info] Diff exact gespiegeld op AI-supplier-patroon over alle 5 raakpunten (model, parser, client read/write, ManualCommandService apply+filter+exhaustive when).
- [info] `setAutoApprove` idempotent (skip-write bij gelijke waarde) + in-memory copy; default `autoApprove = false`.
- [info] Read-pad robuust: accepteert zowel enum ("on"/"off") als bool ("true"/"false"), default false bij afwezig.
- [info] Tests dekken parser (on/off) en service (set + idempotentie). Geen scope creep; alleen softwarefactory-module geraakt; geen secrets.
- [suggestie] Deployment: YouTrack-project moet `Auto-approve` custom field hebben; write-pad gebruikt SingleEnum on/off. Reeds genoteerd in aandachtspunten.
- Akkoord.
