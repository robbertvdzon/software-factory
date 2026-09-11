CREATE TABLE IF NOT EXISTS ${schema}.agent_role_execution_config (
  id          BIGSERIAL PRIMARY KEY,
  role        TEXT NOT NULL,
  project_key TEXT,
  vendor_id   TEXT NOT NULL,
  model       TEXT NOT NULL,
  mode        TEXT NOT NULL,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by  TEXT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_role_execution_config_default
  ON ${schema}.agent_role_execution_config(role)
  WHERE project_key IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_role_execution_config_project
  ON ${schema}.agent_role_execution_config(role, project_key)
  WHERE project_key IS NOT NULL;

INSERT INTO ${schema}.agent_role_execution_config
  (role, project_key, vendor_id, model, mode, updated_by)
VALUES
  ('refiner', NULL, 'openai', 'gpt-5.6-sol', 'SUBSCRIPTION', 'migration'),
  ('planner', NULL, 'openai', 'gpt-5.6-sol', 'SUBSCRIPTION', 'migration'),
  ('developer', NULL, 'openai', 'gpt-5.6-sol', 'SUBSCRIPTION', 'migration'),
  ('reviewer', NULL, 'openai', 'gpt-5.6-sol', 'SUBSCRIPTION', 'migration'),
  ('tester', NULL, 'openai', 'gpt-5.6-sol', 'SUBSCRIPTION', 'migration'),
  ('summarizer', NULL, 'openai', 'gpt-5.6-sol', 'SUBSCRIPTION', 'migration'),
  ('documenter', NULL, 'openai', 'gpt-5.6-sol', 'SUBSCRIPTION', 'migration'),
  ('auditor', NULL, 'openai', 'gpt-5.6-sol', 'SUBSCRIPTION', 'migration')
ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS ${schema}.agent_runtime_jobs (
  agent_run_id             BIGINT PRIMARY KEY REFERENCES ${schema}.agent_runs(id) ON DELETE CASCADE,
  runtime_job_id           UUID NOT NULL UNIQUE,
  idempotency_key          TEXT NOT NULL UNIQUE,
  runtime_status           TEXT NOT NULL,
  runtime_phase            TEXT NOT NULL,
  last_event_sequence      BIGINT NOT NULL DEFAULT 0,
  checkout_commit_sha      VARCHAR(40),
  published_commit_sha     VARCHAR(40),
  repository_result_json   JSONB,
  verification_result_json JSONB,
  last_error_code          TEXT,
  last_error_message       TEXT,
  created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_runtime_jobs_open
  ON ${schema}.agent_runtime_jobs(runtime_status, updated_at)
  WHERE runtime_status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT');

