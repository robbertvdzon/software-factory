# SF-019 - Agent Runtime, Docs, And End-To-End Verification

## Story

Finish the YouTrack migration across the agent runtime, docs, examples, tests,
and verify against the real sample project issue.

## Plan

[x]: document the story and implementation steps
[x]: update agent CLI to write results through YouTrack
[x]: update runtime task context and docs skeleton wording
[x]: run the full test suite
[x]: smoke test YouTrack bootstrap and the sample Develop issue
[x]: record final verification results

## Work Log

- Created this story document before the finishing pass.
- Updated the agent CLI completion path so dummy agent results write back
  through `YouTrackClient`.
- Updated runtime task context and docs skeleton text to say issue/YouTrack
  instead of Jira, and kept the per-story developer log contract intact.
- Ran `mvn -q clean test`; the full unit/e2e suite passed.
- Ran a real YouTrack smoke using `secrets.env`: schema bootstrap succeeded
  for project `SP`, the project repo was resolved from the project
  description, and `SP-3` was returned by `findWorkIssues()` after setting its
  `AI-supplier` to `claude`.
- No push was performed.
