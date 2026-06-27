# SF-023 - Real Claude Code Agent Supplier

## Story

Implement real Claude Code support as the first non-dummy AI supplier, while
keeping the agent architecture supplier-neutral for future `openai` and
`microsoft` implementations.

The current factory already has a Kotlin orchestrator, Docker agent runtime,
story dispatching, YouTrack state handling, GitHub PR flow, agent tips,
completion reporting and database-backed observability. The old working
implementation in
`/Users/robbertvdzon/git/personal-news-feed-by-claude-code` should be used as
behavioral reference, not copied wholesale.

## Reference Implementation

Useful files in the old repo:

- `deploy/claude-runner/runner.sh`
- `deploy/claude-runner/Dockerfile`
- `deploy/claude-runner/parse-outcome.py`
- `deploy/claude-runner/factory-report.py`
- `deploy/jira-poller/poller.py`
- `deploy/claude-tester/Dockerfile`

The old `runner.sh` worked well, but it mixed orchestration, Git, Jira,
GitHub, prompt construction, Claude invocation, usage parsing and reporting in
one Bash script. In this project those responsibilities should remain mostly
in Kotlin. Treat the Bash/Python code as a source of proven behavior and prompt
details.

## Architecture Decision

Do not rebuild the old Bash runner as the main runtime.

Use the existing Kotlin structure:

- `OrchestratorService` keeps dispatching roles and controlling YouTrack state.
- `DockerAgentRuntime` keeps starting local Docker containers.
- `AgentCli.kt` remains the container entrypoint.
- `TargetRepositoryPreparer` / `DeveloperRepositoryFlow` keep Git and PR
  responsibilities where possible.
- `AgentRunCompletionService` keeps completion reporting and DB updates.
- `AiClient` becomes the provider abstraction.

Add provider implementations behind `AiClient`:

- `DummyAiClient` remains for tests and local safe runs via
  `SF_AI_SUPPLIER=mock` (`dummy`, `none`, and unset remain compatibility
  aliases inside the agent container).
- `ClaudeCodeAiClient` is added for `SF_AI_SUPPLIER=claude`.
- Later stories can add `OpenAiAiClient` for `openai`.
- Later stories can add `MicrosoftAiClient` for `microsoft`.

`AgentCli` must choose the implementation based on `SF_AI_SUPPLIER`.

## Claude Code Behavior To Reuse

The Claude implementation should reuse these behaviors from the old runner:

- Start Claude Code as a subprocess.
- Use `--append-system-prompt`.
- Use `--permission-mode bypassPermissions` inside the isolated agent
  container.
- Use `--verbose`.
- Use `--output-format stream-json`.
- Use `--print`.
- Pass `--model` when `SF_AI_MODEL` is set.
- Encode effort through the prompt when `SF_AI_EFFORT` is set.
- Capture every stream-json line as an agent event.
- Parse the final `result` event for:
  - final assistant text
  - input tokens
  - output tokens
  - cache-read tokens
  - cache-creation tokens
  - turn count
  - duration
  - estimated cost
- Redact secrets before storing events.
- Detect Claude/CLI failures and return a failed `AgentOutcome`.
- Preserve enough raw event data to debug runs from the dashboard.

## Authentication

Claude Code should use the user's Claude Code credentials, not an Anthropic
API key.

Preferred local setup:

- Use `SF_AI_OAUTH_TOKEN` when available and pass it to Claude Code as
  `CLAUDE_CODE_OAUTH_TOKEN`; do not mount `SF_AI_CREDENTIALS_DIR` in that case,
  so `/home/runner/.claude` stays writable for Claude runtime-state.
- If `SF_AI_CREDENTIALS_DIR` is used instead, mount it on
  `/home/runner/.claude` as writable, because Claude Code creates
  `session-env` and related files during tool execution.
- Do not pass `ANTHROPIC_API_KEY` for Claude Code runs, because that can switch
  usage to API billing instead of the user's Claude Code/Max route.

The implementation must fail clearly if supplier is `claude` and no usable
Claude credentials are available.

## Prompt Requirements

Keep prompts provider-neutral where possible, but Claude may have its own
adapter formatting.

The role-specific behavior from the old runner should be ported into Kotlin
prompt builders:

- Refiner:
  - reads story + comments + factory docs
  - asks only blocking questions
  - does not edit files
  - outputs a clear human summary
  - ends with parseable decision JSON
- Developer:
  - implements the story
  - reads reviewer/tester loopback comments first when present
  - commits local changes
  - produces a handover summary with `Samenvatting`, `Gedaan`, and
    `Niet gedaan / aangepast`
- Reviewer:
  - reviews PR/diff
  - does not edit files
  - uses `[blocker]`, `[bug]`, `[suggestie]`, `[info]`
  - ends with parseable decision JSON
- Tester:
  - tests behavior, not just code
  - uses real browser automation when preview context is available
  - creates screenshots where relevant
  - does not mutate production or infrastructure
  - ends with parseable decision JSON

The prompt must include:

- The enriched task markdown already built by `AgentCli`.
- Factory docs loaded from `docs/factory`.
- Agent tips from the factory DB.
- Preview context for tester runs.
- Developer loopback reason when applicable.
- Output contract for the role.

## Outcome Parsing

The old `parse-outcome.py` contains useful robustness rules. Port the behavior
to Kotlin instead of shelling out to Python:

- Strip markdown JSON fences.
- Normalize smart quotes/dashes where needed.
- Tolerate trailing commas and light JSON formatting mistakes where safe.
- Find the last phase JSON object when the model includes prose first.
- Fall back conservatively:
  - refiner: questions / user input needed
  - reviewer: feedback for developer
  - tester: feedback for developer
- Never mark a run successful if the required role decision cannot be parsed.

Map old phase names to the current factory phases:

- `refined` -> `refined-finished`
- `awaiting-po` -> `refined-with-questions-for-user`
- `reviewed-ok` -> `review-finished`
- `reviewed-changes` -> `reviewed-with-feedback-for-developer`
- `tested-ok` -> `tested-successfully`
- `tested-fail` -> `tested-with-feedback-for-developer`

## Docker Image Requirements

The existing `Dockerfile.agent-base` should be extended or a new Claude-capable
image should be introduced.

Claude-capable base image needs:

- Java runtime for `AgentCli`.
- Git.
- GitHub CLI.
- Node 22 or compatible Node version.
- Claude Code CLI installed with npm.
- Writable home for `runner`.
- Existing `/app/classes:/app/libs/*` classpath entrypoint.

Tester image still needs browser tooling:

- Playwright.
- Chromium.
- OpenShift CLI / kubectl where required by tester preview flow.
- `psql` for read-only preview DB checks.

Keep the image design compatible with local Docker. Kubernetes-specific bits
from the old repo are reference only.

## Completion Reporting

Completion should continue through the current Kotlin endpoint:

- `AgentCli` returns an `AgentOutcome`.
- `AgentCli` posts `/agent-run/complete`.
- Events are passed as `AgentRunEventPayload`.
- `AgentRunCompletionService` stores run usage and events.

Do not duplicate completion reporting in shell/Python.

## Acceptance Criteria

- `SF_AI_SUPPLIER=claude` causes `AgentCli` to use `ClaudeCodeAiClient`.
- `SF_AI_SUPPLIER=mock` uses the current dummy/test flow.
- `SF_AI_SUPPLIER` unset, `none`, or `dummy` still allows the dummy/test flow
  as compatibility behavior inside the agent container.
- `SF_AI_SUPPLIER=openai` and `SF_AI_SUPPLIER=microsoft` fail with a clear
  "not implemented yet" message until their adapters exist.
- Claude Code runs via the `claude` CLI and receives the correct role prompt.
- Claude stream-json events are stored in `agent_events` with secrets redacted.
- Token usage, cache usage, duration, turns and cost are copied into
  `agent_runs`.
- Refiner/reviewer/tester phase decisions are parsed and mapped to the current
  factory phases.
- Developer runs can still create commits/PRs through the existing Kotlin GitHub
  flow or an explicitly documented equivalent.
- Missing Claude credentials produce a clear failure and do not leave the story
  in a fake successful state.
- Tests cover:
  - supplier selection
  - Claude command construction
  - stream-json parsing
  - phase mapping
  - secret redaction
  - missing credentials

## Plan

[x]: create this story document
[x]: define supplier selection in `AgentCli`
[x]: add explicit `mock` supplier for the existing dummy flow
[x]: add `ClaudeCodeAiClient`
[x]: port role prompt builders from the old runner into Kotlin
[x]: port outcome parsing from `parse-outcome.py` into Kotlin
[x]: capture Claude stream-json events and usage
[x]: update Docker image for Claude Code CLI
[x]: wire credentials for local Docker runs
[x]: add tests for supplier selection, parsing and command construction
[ ]: run a real Claude test story end-to-end

## Work Log

- Created this story document and used it as the implementation checklist.
- Reviewed the old working Claude Code implementation and captured which parts
  should be ported to Kotlin instead of copied as Bash.
- Added `mock` as a first-class supplier value. The previous dummy behavior is
  now selected explicitly with `SF_AI_SUPPLIER=mock`; unset/`none`/`dummy`
  remain compatibility aliases inside the agent container.
- Added `ClaudeCodeAiClient`, supplier routing in `AgentCli`, Claude stream-json
  parsing, role outcome parsing, secret-redacted event capture and Claude Code
  CLI installation in `Dockerfile.agent-base`.
- Limited the artificial completion delay to the mock/dummy supplier so real
  Claude runs do not sleep for their already elapsed runtime after completion.
- Updated specs and YouTrack schema bootstrap so `AI-supplier` contains
  `none`, `mock`, `claude`, `openai`, and `microsoft`.
- Verified with `mvn -q test`.
- Verified the agent images with
  `docker build -f Dockerfile.agent-base -t agent-base:local .`,
  `docker build -f Dockerfile.agent-tester -t agent-tester:local .`, and
  `docker run --rm --entrypoint claude agent-base:local --version`.
- Did not run a real Claude story end-to-end yet; that remains a separate
  acceptance step because it mutates the sample repo/YouTrack story and uses
  real Claude execution.
