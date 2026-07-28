# Images for the README

The README has four screenshot slots, marked with `<!-- SCREENSHOT: ... -->` comments. They are
comments rather than real image tags on purpose: a missing PNG would otherwise render as a broken
image on a public repository. Drop the file in here, replace the comment with the matching
markdown line below, and it appears.

Take them at a browser width of roughly 1000-1200px so the content fills the frame; the dashboard
keeps its content in a narrow left column, so crop away the empty space on the right.

| File | What it should show | Markdown to use |
|---|---|---|
| `dashboard-stories.png` | Stories screen, filter **Klaar** enabled, showing merged `[Audit]` stories with their `merged`/`done` badges. This is the money shot: it proves the loop closes. | `![The Stories screen: work the factory finished and merged itself](docs/images/dashboard-stories.png)` |
| `dashboard-audits.png` | Audits screen with both projects visible, each audit type with its last run time and the story it produced. | `![The Audits screen: a nightly audit per topic, each with the story it filed](docs/images/dashboard-audits.png)` |
| `dashboard-agents.png` | Agents screen while an agent run is actually in progress, so the live runtime is visible. Best taken while a story is running. | `![The Agents screen: what is running right now](docs/images/dashboard-agents.png)` |
| `telegram-question.png` | A Telegram thread where an agent asks a question and gets answered. Check for anything private before adding it — this repository is public. | `![An agent asking a question in Telegram](docs/images/telegram-question.png)` |

Note: the dashboard UI is in Dutch while the README is in English. If that bothers you, the
captions above carry the explanation, so a reader who does not speak Dutch can still follow what
they're looking at.
