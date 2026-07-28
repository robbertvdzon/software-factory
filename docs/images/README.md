# Images for the README

The README uses these as sequences rather than a gallery: **work you ask for**
(`story-new` → `story-refining` → `story-question`), **work the factory finds itself**
(`dashboard-audits` → `audit-report` → `dashboard-stories`), then `dashboard-agents`, then the
Telegram section. Replacing one means keeping its sequence readable — the captions refer to what is
actually visible in the shot, for instance story SF-1438 running through the first three.

Empty slots stay a `<!-- SCREENSHOT: ... -->` comment in the README rather than a real image tag
until the PNG exists: a missing file renders as a broken image on a public repository.

Currently missing: **`telegram-progress.png`** and **`telegram-ask.png`**.

Two things to check before adding one, because this repository is public: crop away the browser
chrome (the URL bar and your bookmarks bar give away more than you think — the Telegram shot
needed exactly that), and read the visible text for anything private.

Take them at a browser width of roughly 1000-1200px so the content fills the frame; the dashboard
keeps its content in a narrow left column, so crop away the empty space on the right.

| File | Sequence | What it should show |
|---|---|---|
| `story-new.png` | you ask, 1/3 | The "Nieuwe story" dialog, filled in with a deliberately vague request. The point is how little you have to type. |
| `story-refining.png` | you ask, 2/3 | The Stories screen with that same story at the top, phase `refining` — it started on its own. |
| `story-question.png` | you ask, 3/3 | That same story at `refined-with-questions`: the refiner's question with the answer box under it. The answer box is the point — it shows the loop is closed, not just that a question exists. |
| `dashboard-audits.png` | it finds, 1/3 | Audits screen, audit types per project with their last run time and the story they filed. |
| `audit-report.png` | it finds, 2/3 | An audit report opened from that screen: score plus one-line justification at the top, rendered markdown below. Shows what an audit produces, not just that it ran. |
| `dashboard-stories.png` | it finds, 3/3 | Stories screen, filter **Klaar** enabled, showing merged `[Audit]` stories. This is the money shot: it proves the loop closes. |
| `dashboard-agents.png` | closing | Agents screen while a run is actually in progress, so the live runtime is visible. |
| `telegram-question.png` | telegram, 1/3 | Telegram, with an agent asking about a story. Crop off the browser chrome and check the visible text: this repository is public. |
| `telegram-progress.png` | telegram, 2/3 | A thread of what the factory reports back while it works — progress and "klaar"-style messages. Shows you are kept informed without asking. |
| `telegram-ask.png` | telegram, 3/3 | You asking the assistant something about the code or a story, and getting a real answer. Shows it is a conversation, not a fixed command list. |

Note: the dashboard UI is in Dutch while the README is in English. That is deliberate — the app is
Dutch, the README is the public-facing story and stays English. So don't "fix" the screenshots by
switching the app's language; instead make sure each caption explains what the reader is looking
at, the way the ones above do.
