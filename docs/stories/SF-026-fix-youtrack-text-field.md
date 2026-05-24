# SF-026 - Fix Claude Job Processing

## Story

Als de orchestrator een foutmelding op een issue zet, moet het YouTrack
`Error` text custom field correct worden bijgewerkt. Nu faalt een Claude-job al
voor de agent start, omdat YouTrack de waarde voor dit text field in een
`TextFieldValue` object verwacht.

Tijdens het opnieuw draaien van de Claude-job bleek daarna dat de orchestrator
ook nog het oude mock-model `dummy-ai-client` doorgaf aan Claude Code. Voor
echte Claude-runs moet de factory geen mock-model als `--model` doorgeven; de
Claude CLI gebruikt dan zijn eigen default/config, totdat er een echte
supplier-specifieke modelmatrix is.

## Stappenplan

[x]: reproduce failing Claude job startup
[x]: fix YouTrack text custom field payload
[x]: add unit coverage for non-empty Error updates
[x]: run tracker tests
[x]: inspect agent-events for the next Claude failure
[x]: stop passing the mock model to the Claude supplier
[x]: rebuild agent images with the tracker/model fixes
[x]: rerun SP-3 through the Claude refiner job
[x]: reproduce read-only Claude home failure in developer job
[x]: keep Claude home writable when OAuth token is used
[x]: smoke-test Claude Bash tool with writable home

## Uitvoering

- De service lokaal gestart met polling aan.
- Reproductie: de poll op `SP-3` faalt bij `POST /api/issues/SP-3` met
  `Due to a type mismatch... TextFieldValue?-type value`.
- Oorzaak: `YouTrackClient` stuurde `Error` als plain string, terwijl
  YouTrack voor text custom fields `{"text":"...","$type":"TextFieldValue"}`
  verwacht.
- Tweede oorzaak na re-run: de agent-container startte Claude Code met
  `--model dummy-ai-client`. Dat is alleen bedoeld voor de mock-supplier en is
  geen geldig Claude Code model.
- Derde oorzaak tijdens de developer-run: `~/.claude` was read-only gemount op
  `/home/runner/.claude`, waardoor Claude Code geen
  `/home/runner/.claude/session-env/...` kon aanmaken en Bash/git/test-tools
  faalden.
- Validatie na de mount-fix: dezelfde `agent-base:local` image gestart zonder
  `.claude` mount, met alleen `CLAUDE_CODE_OAUTH_TOKEN` via een tijdelijke
  env-file. Claude Code kon de Bash tool uitvoeren en gaf `sf-bash-ok` terug.
