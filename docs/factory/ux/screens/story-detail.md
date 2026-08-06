# Story Detail

## Purpose

Provide one operational control and audit page for a story.

## Layout

- Back button and issue key.
- Story summary.
- Current state banner:
  - healthy/running: pale purple/blue.
  - stuck/error: pale red.
  - completed: pale green/purple.
- Link panel.
- Command panel when commands are allowed.
- Deploy/preview panel.
- Budget panel.
- Overview key/value table.
- Agent-runs list.

Title/summary, description, error banners, agent questions, and comment/timeline
items are selectable/copyable text. The description panel and the `AI-supplier`/
`AI-model` rows each have an edit button that opens a dialog to change them
in place (saved via `POST /api/v1/stories/{storyKey}/edit`, a partial update that
only changes the fields provided).

## Data

- Issue key, project, summary.
- `AI-supplier`, `AI-model`, `AI Phase`, status, error, paused flag.
- `NotificationEvents`: acht onafhankelijke checkboxes, inclusief ondersteuning voor een lege set.
- PR number and URL.
- Preview URL and namespace.
- Branch name and base branch.
- Budget, tokens, estimated cost.
- Run totals: count, CPU time, wallclock, token categories.
- Agent runs with role, outcome, timestamps, tokens, duration, cost.

## Actions

- Open PR.
- Open preview.
- Open briefing.
- Open screenshots.
- Pause.
- Resume.
- Merge.
- Delete.
- Re-implement.
- Beantwoord een documentervraag in `documentation-with-questions` en keur afgeronde documentatie
  in `documented` goed. Dezelfde actiekaarten staan ook in **My actions**; zij sturen door naar
  `documentation-questions-answered` respectievelijk `documentation-approved`.
- Toggle `Vragen toestaan` (story-only, on by default), choose `Goedkeuring`
  (`automatisch`/`alleen-manual-poort`/`elke-stap`) and independently toggle all eight concrete
  melding-events — story-only, three independent
  axes (SF-1261) replacing the old `Auto-approve`/`Silent`/`TelegramResultNotify` toggles.
  When `Goedkeuring=elke-stap` is selected without `APPROVAL_REQUIRED`, show a non-blocking
  warning; saving the selection, including an empty event set, remains possible.
  The fourth axis, `Hotfix` (SF-1959), is deliberately **not** editable here: it can only be set
  when the story is created (see `stories.md`). On a hotfix story the chain shown is
  `hotfix → merge → deploy`; the approval axis is ignored inside that chain.
  `DEPLOYED` enables a separate Telegram notification once the deploy result is
  externally confirmed (live URL reachable, a new APK release, or a confirmed rest-restart) — see
  `technical-spec.md` §Telegram-resultaatmelding.

## States

- No PR yet.
- No preview yet.
- Merged story.
- Stuck active phase without active container.
- Paused by user or cost monitor.
- Error field filled.
- Claude quota wait: amber `quota-wacht` badge plus “Gepauzeerd wegens Claude-quota tot
  YYYY-MM-DD HH:mm (lokale tijd)”; show it on the story and on every affected subtask row, never as
  an error banner and independently from the manual paused state. The story header aggregates this
  presentation state from its subtasks; the affected subtask row still shows its own wait state.

## Safety

Destructive actions (`Delete`, `Re-implement`, `Merge`) require confirmation.
Confirmation text must include the issue key and what will be cleaned up.

## Notes

The first visible section should answer: what is happening now, is it healthy,
and what can the user do next?
