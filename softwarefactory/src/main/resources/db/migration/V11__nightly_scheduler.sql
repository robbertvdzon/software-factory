-- SF-350/SF-351 (nightly scheduler): persistentie-fundering voor de nachtelijke scheduler.
--
-- Drie tabellen:
--   * nightly_settings  - één rij met de master-switch + start-/summary-tijd (lokale NL-tijd, HH:MM).
--   * nightly_run       - één run per kalenderdag (run_date in NL-tijd) met status + summary-vlag.
--   * nightly_run_job   - per run en per project de queue van jobs met hun voortgang.
--
-- De run-status leeft volledig in de DB zodat een rest-restart een lopende run weer oppikt (SF-352).

CREATE TABLE IF NOT EXISTS ${schema}.nightly_settings (
    id           SMALLINT     PRIMARY KEY DEFAULT 1,
    enabled      BOOLEAN      NOT NULL DEFAULT FALSE,
    start_time   TEXT         NOT NULL DEFAULT '02:00',
    summary_time TEXT         NOT NULL DEFAULT '07:00',
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT nightly_settings_single_row CHECK (id = 1)
);

-- Seed de enkele rij met de neutrale defaults (scheduler doet niets tot bewust aangezet).
INSERT INTO ${schema}.nightly_settings (id) VALUES (1)
    ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS ${schema}.nightly_run (
    id              BIGSERIAL    PRIMARY KEY,
    run_date        DATE         NOT NULL,
    started_at      TIMESTAMPTZ,
    ended_at        TIMESTAMPTZ,
    status          TEXT         NOT NULL DEFAULT 'pending',
    summary_sent_at TIMESTAMPTZ,
    CONSTRAINT nightly_run_unique_date UNIQUE (run_date)
);

CREATE TABLE IF NOT EXISTS ${schema}.nightly_run_job (
    id         BIGSERIAL    PRIMARY KEY,
    run_id     BIGINT       NOT NULL REFERENCES ${schema}.nightly_run(id) ON DELETE CASCADE,
    project    TEXT         NOT NULL,
    job_name   TEXT         NOT NULL,
    title      TEXT         NOT NULL,
    status     TEXT         NOT NULL DEFAULT 'pending',
    story_key  TEXT,
    started_at TIMESTAMPTZ,
    ended_at   TIMESTAMPTZ,
    error      TEXT
);

CREATE INDEX IF NOT EXISTS nightly_run_job_run_idx
    ON ${schema}.nightly_run_job (run_id);
