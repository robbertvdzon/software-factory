# SF-018 - YouTrack Pickup And Project Repo Resolution

## Story

Change the orchestrator to poll YouTrack issues in `Stage = Develop`, skip empty
or `none` `AI-supplier` values, and resolve the target git repository from
the YouTrack project description.

## Plan

[x]: document the story and implementation steps
[x]: poll Develop issues from configured YouTrack projects
[x]: skip issues with empty or `none` `AI-supplier`
[x]: resolve `factory.repo` from the YouTrack project description
[x]: pass `SF_AI_SUPPLIER` to agent containers
[x]: update orchestrator tests

## Work Log

- Created this story document before changing orchestrator behavior.
- `findWorkIssues()` now discovers YouTrack projects, searches issues with
  `Stage: Develop`, and filters out empty or `none` `AI-supplier` values.
- Target repositories are resolved from the YouTrack project description,
  preferring `factory.repo=...`, accepting legacy `factory.githubRepo=...`, and
  falling back to the first git URL in the description.
- The orchestrator now passes `AI-supplier` into `AgentDispatchRequest`, task
  context and Docker env as `SF_AI_SUPPLIER`.
- Added `SUPPLIER=none|claude|openai|microsoft` comment-trigger support for
  manual supplier changes.
- Updated orchestrator, manual-command, Docker runtime and e2e tests for
  `Develop`/`AI-supplier` pickup.
- Verification: `mvn -q clean test`.
