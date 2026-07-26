-- Audits vervangen de nachtelijke jobs (die zelf code aanpasten) door read-only audit-runs:
-- één AI-agent-run per project per nacht die een rapport schrijft en hooguit 1 story voorstelt.
-- Zie .factory/nightly/README.md en het bijbehorende ontwerp.
--
-- Drie tabellen, analoog aan de bewezen nightly_settings/nightly_run/nightly_run_job-opzet
-- (V11__nightly_scheduler.sql):
--   * audit_settings  - master-switch + start-/summary-tijd (default 08:00, i.p.v. 02:00 's nachts).
--   * audit_run       - één run per kalenderdag, net als nightly_run.
--   * audit_run_job   - per run, per project: PRECIES 1 gekozen audit-type (niet alle enabled).
--   * audit_report    - het daadwerkelijke rapport (tekst + evt. score), met historie: MAX(generated_at)
--                        per (project, audit_type) is zowel "laatste-run-timestamp" (voor de
--                        oudste-eerst-selectie) als het startpunt voor score-trend in de FE.
--
-- Geen aparte memory-tabel: de auditor gebruikt het bestaande `knowledge`-domein
-- (KnowledgeApi.find/upsert, sleutel target_repo+role+category+key) — role="auditor",
-- category=audit_type — precies het "tips voor de volgende run"-mechanisme dat er al is voor
-- andere agent-rollen, geen nieuwe infrastructuur nodig.

CREATE TABLE IF NOT EXISTS ${schema}.audit_settings (
    id           SMALLINT     PRIMARY KEY DEFAULT 1,
    enabled      BOOLEAN      NOT NULL DEFAULT FALSE,
    start_time   TEXT         NOT NULL DEFAULT '08:00',
    summary_time TEXT         NOT NULL DEFAULT '08:30',
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT audit_settings_single_row CHECK (id = 1)
);

INSERT INTO ${schema}.audit_settings (id) VALUES (1)
    ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS ${schema}.audit_run (
    id              BIGSERIAL    PRIMARY KEY,
    run_date        DATE         NOT NULL,
    kind            TEXT         NOT NULL DEFAULT 'scheduled',
    started_at      TIMESTAMPTZ,
    ended_at        TIMESTAMPTZ,
    status          TEXT         NOT NULL DEFAULT 'pending',
    summary_sent_at TIMESTAMPTZ,
    summary_text    TEXT
);

CREATE INDEX IF NOT EXISTS audit_run_date_kind_idx
    ON ${schema}.audit_run (run_date, kind);

-- Het eigenlijke rapport: staat los van audit_run_job zodat de historie (voor trend + "laatste
-- run"-selectie) blijft bestaan ongeacht of/hoe lang run-rijen bewaard blijven.
CREATE TABLE IF NOT EXISTS ${schema}.audit_report (
    id                  BIGSERIAL    PRIMARY KEY,
    project             TEXT         NOT NULL,
    audit_type          TEXT         NOT NULL,
    generated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    content             TEXT         NOT NULL,
    score               NUMERIC,
    score_label         TEXT,
    proposed_story_key  TEXT,
    status              TEXT         NOT NULL DEFAULT 'done',
    error               TEXT
);

-- Bepaalt zowel "wanneer draaide deze audit voor het laatst" (oudste-eerst-selectie) als de
-- score-trend (vergelijk met de voorgaande rij voor dezelfde project+audit_type).
CREATE INDEX IF NOT EXISTS audit_report_project_type_generated_idx
    ON ${schema}.audit_report (project, audit_type, generated_at DESC);

CREATE TABLE IF NOT EXISTS ${schema}.audit_run_job (
    id              BIGSERIAL    PRIMARY KEY,
    run_id          BIGINT       NOT NULL REFERENCES ${schema}.audit_run(id) ON DELETE CASCADE,
    project         TEXT         NOT NULL,
    audit_type      TEXT         NOT NULL,
    title           TEXT         NOT NULL,
    status          TEXT         NOT NULL DEFAULT 'pending',
    report_id       BIGINT       REFERENCES ${schema}.audit_report(id),
    -- Identiteit van de gestarte agent-run (gezet zodra de job 'running' wordt), nodig om 'm op
    -- volgende ticks te kunnen pollen: geen tracker-story dus geen story-key om op terug te vallen.
    container_name  TEXT,
    workspace_path  TEXT,
    story_run_id    BIGINT,
    started_at      TIMESTAMPTZ,
    ended_at        TIMESTAMPTZ,
    error           TEXT
);

CREATE INDEX IF NOT EXISTS audit_run_job_run_idx
    ON ${schema}.audit_run_job (run_id);
