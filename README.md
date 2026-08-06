# Software Factory

**A software factory that builds software. You describe what you want; AI agents refine it, plan
it, build it, review it, test it, document it, merge it and deploy it — and they ask you whenever
they're unsure.**

Software Factory is a self-built platform that runs a full development pipeline on autopilot. It
is not a chatbot you copy-paste code out of: it is a factory floor. Work arrives as a story, moves
through a chain of specialised agents, and comes out the other end as a merged pull request on a
running system. A human stays in charge — but only where a human adds something.

It has been running since May 2026 and merges its work straight into real, live applications.

---

## What it does

### From an idea to merged code, without you opening a pull request

You write down what you want — in the dashboard, or just by telling the Telegram assistant. From
there the factory takes over:

1. A **refiner** sharpens the idea into a proper story: scope, acceptance criteria, assumptions.
2. A **planner** decides the approach and cuts the work into subtasks.
3. A **developer** writes the code *and* the tests, on its own branch.
4. A **reviewer** reads the whole change, hunting for bugs and missing coverage.
5. A **tester** verifies the behaviour — the factory itself re-runs the full test suite afterwards,
   because "the agent said it was green" is not evidence.
6. A **summarizer** and **documenter** write the story summary and update the documentation.
7. The factory merges the pull request and deploys it.

Per project, you decide how much of that you trust: fully automatic, or with an approval gate
right before the merge.

### When the AI isn't sure, it asks instead of guessing

This is the part that makes it usable. An agent that hits an ambiguity does not invent an answer
and quietly build the wrong thing. It stops, parks the story, and asks you a question in the
dashboard. When the story's `QUESTION` notification is enabled, the question also arrives in
Telegram. You answer in one sentence, and the agent picks up exactly where it left off.

### Every night it audits itself and creates its own follow-up work

Besides the work you ask for, a read-only **audit** runs each night per project: code quality,
security, ADR compliance, consistency, documentation, test coverage. An audit never changes
anything. It writes a report, and if it finds something structural, it proposes at most one small
follow-up story to fix it — which then goes through the normal pipeline above.

That loop is real, not theoretical. A documentation audit found that `docs/factory/secrets-local.md`
told developers to set `JWT_SECRET` while the code actually reads `APP_JWT_SECRET` — a mismatch
that silently invalidates everyone's login tokens on restart. It filed story SF-1394. The factory
refined it, fixed it, reviewed it, tested it and merged it. Nobody typed a line of that.

### Everything in one place

One dashboard for the whole factory: which agents are running right now and for how long, build
and deploy status per project and branch, all stories with their state, and every audit report
with its score history. No separate browser tab per repository.

### A factory assistant in your pocket

Telegram is a full channel, not just notifications. Ask for a story's status, create a new story,
steer a running one, answer an agent's question — a real assistant with memory, not a fixed
command list.

---

## What it looks like

Work reaches the factory in two ways: you ask for it, or the factory finds it.

### Work you ask for

You type what you want. It can be as rough as this — a title, one sentence, which project it
belongs to. "Direct starten" means it gets picked up immediately.

![The New story dialog: title, description, project, AI supplier and a few switches](docs/images/story-new.png)

It appears in the list and starts moving on its own. SF-1438 is already being refined.

![The Stories screen with the new story in the refining phase](docs/images/story-refining.png)

And then it asks — because "change the background color" isn't buildable yet. Which app? The whole
theme, or one screen? Which green exactly? These are the questions a good developer would have
asked you. Type the answer, the story continues, and if your answer raises a new question it asks
that one too.

![The story in the dashboard, phase refined-with-questions, with the refiner's question and an answer box](docs/images/story-question.png)

Once it knows enough, the planner cuts the work up and the chain starts running. Everything below
`Subtaken` was decided by the factory: write the code, test it, summarise, update the docs, ask for
approval, merge, deploy. The description underneath is the refined story it is now building — the
one sentence you typed has become a spec.

![The story in progress, showing the subtask chain from development through to deploy](docs/images/story-subtasks.png)

### Work the factory finds itself

Each project has its own set of audits — ADR compliance, consistency, documentation, integration
test coverage, code quality, security. Whichever ran longest ago goes next, so they all come round
in turn. The story link is what that audit filed.

![The Audits screen: audit types per project, each with its last run and the story it filed](docs/images/dashboard-audits.png)

An audit produces a score with a one-line justification, then a readable report: what was examined,
what was found, and what it proposes to do about it.

![An audit report: score, justification and the full report underneath](docs/images/audit-report.png)

Those proposals become ordinary stories and go through the same pipeline. These were all built,
reviewed, tested and merged without anyone writing the code.

![The Stories screen, showing merged stories that the factory proposed and built itself](docs/images/dashboard-stories.png)

### What's running right now

Either way, the Agents screen shows what is working at this moment and for how long.

![The Agents screen with an auditor agent running](docs/images/dashboard-agents.png)

### The factory in your pocket

You do not have to sit at the dashboard. The whole factory is reachable over Telegram, and it is a
two-way channel rather than a notification feed.

**It asks you things.** Enable the `QUESTION` notification for a story and the same questions that
appear in the dashboard also land in Telegram. Replying to the message is a valid answer, so you
can keep the story moving while away from your desk.

![The refiner asking questions about a story in Telegram](docs/images/telegram-question.png)

**It keeps you posted.** Notifications are configurable per story: the creation dialog offers
simple presets, and story detail lets you toggle questions, approvals, manual actions, quota
waits, errors, completed steps, completed workflows and deployed results independently. With the
**Na elke stap** preset, every finished subtask reports back with the running checklist. The tester
posts its verdict there too — what it checked, what passed, and a link to the preview environment
it checked it on.

![Telegram showing completed subtasks with a progress checklist and a test report](docs/images/telegram-progress.png)

**And you can just ask it things.** Not from a menu of commands — ask a question about the code and
it goes and reads it. Here: "how often are the RSS feeds actually fetched, and how does that work?"
The answer names the scheduler class, the cron expression, the lock that stops two pods doing the
same refresh, and the four steps of the pipeline.

![Asking the assistant how a feature works, and getting an answer drawn from the actual code](docs/images/telegram-ask.png)

---

## How it works

The everyday version — an idea goes in on the left, working software comes out on the right, and
the only time it stops is to ask you something:

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/flow-dark.png">
  <img alt="Your idea, refine, plan, build, your approval, merge and deploy — with any step able to stop and ask you a question" src="docs/images/flow-light.png">
</picture>

And the night shift, which generates work rather than consuming it:

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/night-dark.png">
  <img alt="Every night an audit reads the code, changes nothing, writes a scored report and proposes at most one small story, which enters the normal pipeline" src="docs/images/night-light.png">
</picture>

Every agent runs isolated in its own Docker container, with only the repository it is working on
mounted. Agents never commit or push themselves and never touch a pull request — the factory does
that, after the run, once the evidence checks out.

The full phase model behind this (`Story Phase` and `Subtask Phase`, with every state and
transition) is documented in [docs/technical/overview.md](docs/technical/overview.md).

---

## Want to run it yourself?

Everything you need — requirements, secrets, linking projects to repositories, Docker services,
building the agent images and starting the factory — is in
**[docs/installation.md](docs/installation.md)**.

## For developers

Read in this order, depending on what you're after:

1. [docs/installation.md](docs/installation.md) — install and run it yourself.
2. [runbook.md](runbook.md) — operations: what runs where, config/secrets, troubleshooting.
3. [docs/factory/functional-spec.md](docs/factory/functional-spec.md) — **what** the factory does.
4. [docs/factory/technical-spec.md](docs/factory/technical-spec.md) — **how** it's built.
5. [docs/onboarding-senior-developer.md](docs/onboarding-senior-developer.md) — the mental model,
   the main flow, the reasoning behind the architecture, test strategy, cookbooks and a review
   checklist.
6. [docs/kwaliteitsanalyse.md](docs/kwaliteitsanalyse.md) — the quality analysis and the July 2026
   refactor.

In addition: [docs/technical/](docs/technical/) (generated technical reference, including the full
phase model) and [specs/specs.md](specs/specs.md) (historical archive).
