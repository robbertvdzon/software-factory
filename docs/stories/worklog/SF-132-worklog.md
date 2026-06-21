# SF-132 / SF-133 - Worklog

Story-context:
Voeg spec-update verplichting toe aan developer- en reviewer-instructies (SF-133, subtaak van SF-132).

Doel: developers verplichten de docs/factory/-specs bij te werken als onderdeel van elke story-implementatie, en reviewers laten controleren of specs consistent zijn met de PR-diff.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes in developer.md and reviewer.md
[ ]: run relevant tests (geen code-tests; wijzigingen zijn pure Markdown)
[x]: update story-log with results

Done / rationale:

- `docs/factory/agents/developer.md`: onder 'Verplicht per story' een regel toegevoegd die de developer verplicht de gerakte specs in `docs/factory/` (functional-spec.md, technical-spec.md, UX-docs) bij te werken en dit te vermelden in het worklog.
- `docs/factory/agents/reviewer.md`: aan de focus-lijst een punt toegevoegd dat spec-consistentie met de PR-diff controleert en specs-inconsistenties als blocker voor merge markeert.

Specs aangepast: geen inhoudelijke wijzigingen in functional-spec.md of technical-spec.md nodig — de wijzigingen betreffen alleen de agent-instructies zelf.
