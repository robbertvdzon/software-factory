# KAN-005 - Target Repo Docs En Story Logs

Story:
Als factory wil ik target-repo documentatie en story-logs kunnen aanmaken en
lezen, zodat agents met repo-specifieke context werken en hun plan zichtbaar
bijhouden.

Subtaken:
[x]: `docs/factory/` voor deze factory-repo aanmaken
[x]: `docs/stories/` voor deze factory-repo aanmaken
[ ]: `docs/factory/` skeleton-template maken
[ ]: `factory init-repo` CLI command maken
[ ]: `loadFactoryDocs(role)` helper bouwen
[ ]: `deployment.md` YAML-frontmatter parser maken
[ ]: Ontbrekende `docs/factory/` soft-bootstrap flow implementeren
[ ]: Developer story-log onder `docs/stories/<jira-key>-description.md` implementeren

Stappen:
[x]: create factory-ready docs for this repository
[x]: create 10 epic-level story files with subtasks
[ ]: add skeleton template with all required files
[ ]: implement init command to copy skeleton into target repos
[ ]: load docs index and role-specific agent instructions
[ ]: parse deployment frontmatter into runtime config
[ ]: add bootstrap notice when target repo docs are missing
[ ]: make developer create/update the story-log during implementation

Done / rationale:
- Deze repo is nu zelf factory-ready via `docs/factory/`.
- De backlog is teruggebracht naar 10 epic-level story files met subtaken.
