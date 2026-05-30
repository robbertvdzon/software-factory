# SF-033 - Flyway V1 checksum herstellen

## Story

Als gebruiker wil ik de applicatie kunnen starten tegen een bestaand Neon-schema zonder Flyway checksum mismatch. De reeds toegepaste `V1`-migratie mag lokaal niet meer afwijken van de versie die al in de database staat.

## Stappenplan

[x]: Maak story-document aan met scope en plan.
[x]: Herstel `V1__initial_schema.sql` naar de oorspronkelijke vorm.
[x]: Controleer dat de latere migratie de ontbrekende kolom nog steeds toevoegt.
[ ]: Draai regressietests.

## Uitvoering

- De startup faalde omdat `V1` lokaal gewijzigd was nadat die al op Neon was toegepast. `workspace_path` hoort niet meer in `V1`, omdat `V5__story_run_workspace_path.sql` deze kolom idempotent toevoegt.
- `workspace_path` is uit `V1__initial_schema.sql` verwijderd. `V5__story_run_workspace_path.sql` blijft staan met `ADD COLUMN IF NOT EXISTS workspace_path TEXT`, dus nieuwe en bestaande schema's krijgen dezelfde eindstructuur.
