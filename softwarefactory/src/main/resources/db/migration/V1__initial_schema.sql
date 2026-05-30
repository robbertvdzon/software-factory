CREATE SCHEMA IF NOT EXISTS ${schema};

CREATE TABLE IF NOT EXISTS ${schema}.story_runs (
  id                          BIGSERIAL PRIMARY KEY,
  story_key                   TEXT NOT NULL,
  target_repo                 TEXT NOT NULL,
  started_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
  ended_at                    TIMESTAMPTZ,
  final_status                TEXT,
  total_input_tokens          INTEGER NOT NULL DEFAULT 0,
  total_output_tokens         INTEGER NOT NULL DEFAULT 0,
  total_cache_read_tokens     INTEGER NOT NULL DEFAULT 0,
  total_cache_creation_tokens INTEGER NOT NULL DEFAULT 0,
  total_cost_usd_est          NUMERIC(10,4) NOT NULL DEFAULT 0.0
);

CREATE TABLE IF NOT EXISTS ${schema}.agent_runs (
  id                          BIGSERIAL PRIMARY KEY,
  story_run_id                BIGINT NOT NULL REFERENCES ${schema}.story_runs(id) ON DELETE CASCADE,
  role                        TEXT NOT NULL,
  container_name              TEXT NOT NULL,
  model                       TEXT,
  effort                      TEXT,
  level                       SMALLINT,
  started_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
  ended_at                    TIMESTAMPTZ,
  outcome                     TEXT,
  input_tokens                INTEGER NOT NULL DEFAULT 0,
  output_tokens               INTEGER NOT NULL DEFAULT 0,
  cache_read_input_tokens     INTEGER NOT NULL DEFAULT 0,
  cache_creation_input_tokens INTEGER NOT NULL DEFAULT 0,
  num_turns                   INTEGER NOT NULL DEFAULT 0,
  duration_ms                 INTEGER NOT NULL DEFAULT 0,
  cost_usd_est                NUMERIC(10,4) NOT NULL DEFAULT 0.0,
  summary_text                TEXT
);

CREATE TABLE IF NOT EXISTS ${schema}.agent_events (
  id              BIGSERIAL PRIMARY KEY,
  agent_run_id    BIGINT NOT NULL REFERENCES ${schema}.agent_runs(id) ON DELETE CASCADE,
  ts              TIMESTAMPTZ NOT NULL DEFAULT now(),
  kind            TEXT NOT NULL,
  payload         JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS ${schema}.agent_knowledge (
  id                BIGSERIAL PRIMARY KEY,
  target_repo       TEXT NOT NULL,
  role              TEXT NOT NULL,
  category          TEXT NOT NULL,
  key               TEXT NOT NULL,
  content           TEXT NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by_story  TEXT,
  UNIQUE (target_repo, role, category, key)
);

CREATE TABLE IF NOT EXISTS ${schema}.processed_comments (
  id            BIGSERIAL PRIMARY KEY,
  story_key     TEXT NOT NULL,
  comment_id    TEXT NOT NULL,
  role          TEXT NOT NULL,
  processed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (story_key, comment_id, role)
);

CREATE TABLE IF NOT EXISTS ${schema}.system_state (
  id                     SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  credits_paused_until   TIMESTAMPTZ,
  credits_paused_reason  TEXT
);

INSERT INTO ${schema}.system_state (id)
VALUES (1)
ON CONFLICT (id) DO NOTHING;
