-- SF-1921: `maintenance_cleanup_runs` was in V30 alleen bedoeld voor de nachtelijke
-- GitHub-release/package-opruiming. De factory ruimt echter op vijf plekken op (GitHub-releases,
-- agent-events, agent-runs, completion-payloads en work/-mappen) en van vier daarvan was in het
-- dashboard niets terug te zien. Deze migratie generaliseert de tabel tot één opruim-log:
--
--   * `kind` benoemt het mechanisme ('github-releases', 'agent-events', 'agent-runs',
--     'completion-payloads', 'workspaces'). Bewust een vrije TEXT-kolom met de waarden als afspraak
--     in code — net als `outcome`/`role` in `agent_runs`; een enum-tabel of CHECK zou elke nieuwe
--     opruimer een extra migratie kosten.
--   * `project` wordt nullable: alleen de GitHub-cleanup draait per project, de vier andere
--     mechanismen zijn factory-breed (NULL).
--   * `releases_*`/`packages_*` maken plaats voor generieke `items_deleted`/`items_kept`. De
--     release/package-uitsplitsing van bestaande rijen gaat mee naar `details`, zodat het
--     detailscherm van een oude ronde niets verliest.
--
-- Retentie op deze tabel zelf blijft ongewijzigd (`sf.maintenance.run-retention-days`) en dekt
-- daarmee automatisch ook de nieuwe soorten.
ALTER TABLE ${schema}.maintenance_cleanup_runs
    ADD COLUMN IF NOT EXISTS kind          TEXT,
    ADD COLUMN IF NOT EXISTS items_deleted INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS items_kept    INTEGER NOT NULL DEFAULT 0;

-- Alle bestaande rijen komen per definitie van de GitHub-cleanup (V30 kende geen andere schrijver).
UPDATE ${schema}.maintenance_cleanup_runs
SET kind          = 'github-releases',
    items_deleted = releases_deleted + packages_deleted,
    items_kept    = releases_kept + packages_kept,
    details       = (
        COALESCE(NULLIF(details, '')::jsonb, '{}'::jsonb) || jsonb_build_object(
            'releasesDeleted', releases_deleted,
            'releasesKept', releases_kept,
            'packagesDeleted', packages_deleted,
            'packagesKept', packages_kept
        )
    )::text
WHERE kind IS NULL;

ALTER TABLE ${schema}.maintenance_cleanup_runs
    ALTER COLUMN kind SET NOT NULL,
    ALTER COLUMN project DROP NOT NULL,
    DROP COLUMN IF EXISTS releases_deleted,
    DROP COLUMN IF EXISTS releases_kept,
    DROP COLUMN IF EXISTS packages_deleted,
    DROP COLUMN IF EXISTS packages_kept;

-- Het scherm filtert op soort; zonder deze index zou dat een seq scan over de hele historie zijn.
CREATE INDEX IF NOT EXISTS maintenance_cleanup_runs_kind_started_idx
    ON ${schema}.maintenance_cleanup_runs (kind, started_at DESC);
