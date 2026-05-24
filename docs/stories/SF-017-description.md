# SF-017 - YouTrack Client And Schema Bootstrap

## Story

Implement the YouTrack REST client and idempotent schema bootstrap that creates
or attaches the factory custom fields when they are missing.

## Plan

[x]: document the story and implementation steps
[x]: implement YouTrack REST client for projects, issues, comments, reactions and commands
[x]: implement startup schema bootstrap for factory fields
[x]: support `AI-supplier` enum values and no separate AI stage
[x]: add fake YouTrack tests for read/write and bootstrap behavior

## Work Log

- Created this story document ahead of implementation. The client will use the
  existing `secrets.env` values without logging tokens.
- Added `YouTrackClient` for project discovery, issue search, issue updates,
  summary updates, comments, comment reactions and command-based stage
  transitions.
- Added startup bootstrap through `YouTrackSchemaStartup`, backed by
  `ensureConfiguredProjects()`, so factory fields are created/attached
  idempotently when missing.
- The bootstrap manages `AI-supplier` with values `none`, `claude`, `openai`
  and `microsoft`, keeps `Stage` as the normal YouTrack workflow field, and
  verifies that `Develop` exists.
- Replaced the old Atlassian/Jira HTTP tests with a fake YouTrack server that
  covers schema creation, issue mapping, updates, comments, reactions and
  commands.
- Verification: `mvn -q clean test`.
