# KAN-005 - Target Repo Docs En Story Logs

Story:
Als factory wil ik target-repo documentatie en story-logs kunnen aanmaken en
lezen, zodat agents met repo-specifieke context werken en hun plan zichtbaar
bijhouden.

Subtaken:
[x]: `docs/factory/` voor deze factory-repo aanmaken
[x]: `docs/stories/` voor deze factory-repo aanmaken
[x]: `docs/factory/` skeleton-template maken
[x]: `factory init-repo` CLI command maken
[x]: `loadFactoryDocs(role)` helper bouwen
[x]: `deployment.md` YAML-frontmatter parser maken
[x]: Ontbrekende `docs/factory/` soft-bootstrap flow implementeren
[x]: Developer story-log onder `docs/stories/<jira-key>-description.md` implementeren

Stappen:
[x]: create factory-ready docs for this repository
[x]: create 10 epic-level story files with subtasks
[x]: add skeleton template with all required files
[x]: implement init command to copy skeleton into target repos
[x]: load docs index and role-specific agent instructions
[x]: parse deployment frontmatter into runtime config
[x]: add bootstrap notice when target repo docs are missing
[x]: make developer create/update the story-log during implementation

Done / rationale:
- Deze repo is nu zelf factory-ready via `docs/factory/`.
- De backlog is teruggebracht naar 10 epic-level story files met subtaken.
- Start KAN-005: de specs en bestaande agent-runtime zijn gelezen; de implementatie wordt beperkt tot target-repo docs, bootstrap en story-log gedrag.
- De docs-skeleton is toegevoegd als classpath-resource en wordt ook in de agent-base image gekopieerd, zodat developers dezelfde template lokaal en in containers kunnen gebruiken.
- `factory init-repo` kopieert de skeleton naar een target-repo zonder bestaande bestanden te overschrijven, zodat bootstrap veilig op bestaande repositories kan draaien.
- `loadFactoryDocs(role)` bouwt nu een index van fysiek aanwezige `docs/factory` bestanden en voegt de rol-specifieke agent-instructies toe aan de agent-context.
- `deployment.md` frontmatter wordt geparsed naar runtime-config, inclusief multiline `preview_db_secret_recipe`.
- Ontbrekende target-repo docs leveren een bootstrap-notice op in plaats van een fout, zodat de developer dit als onderdeel van de PR kan oplossen.
- De developer-run maakt of werkt `docs/stories/<jira-key>-description.md` bij zodra er een target-repo onder `/work/repo` beschikbaar is.
- `mvn test` draait groen met dekking voor skeleton-installatie, docs-loader, deployment parser, story-log writer en Docker-runtime env.
- `factory init-repo` is handmatig getest op een tijdelijke directory; alle verplichte `docs/factory` bestanden en `docs/stories/.gitkeep` werden aangemaakt.
- `./factory build-images` draait groen en `agent-base:local` bevat de skeleton onder `/usr/local/share/factory/docs-skeleton/`.
