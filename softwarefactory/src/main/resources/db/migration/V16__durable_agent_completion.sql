-- REL-01: durable, restart-safe agent completion protocol.
CREATE TABLE IF NOT EXISTS ${schema}.agent_run_completions (
  id                 BIGSERIAL PRIMARY KEY,
  agent_run_id       BIGINT NOT NULL UNIQUE REFERENCES ${schema}.agent_runs(id) ON DELETE CASCADE,
  story_run_id       BIGINT NOT NULL REFERENCES ${schema}.story_runs(id) ON DELETE CASCADE,
  story_key          TEXT NOT NULL,
  container_name     TEXT NOT NULL,
  workspace_path     TEXT,
  payload_json       JSONB,
  payload_hash       VARCHAR(64) NOT NULL,
  payload_validated  BOOLEAN NOT NULL DEFAULT false,
  status             TEXT NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED_RETRYABLE', 'FAILED_PERMANENT')),
  last_error         VARCHAR(2000),
  completed_at       TIMESTAMPTZ,
  payload_purged_at  TIMESTAMPTZ,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_run_completions_recovery
  ON ${schema}.agent_run_completions(status, updated_at)
  WHERE status <> 'COMPLETED';

CREATE TABLE IF NOT EXISTS ${schema}.agent_run_completion_steps (
  completion_id  BIGINT NOT NULL REFERENCES ${schema}.agent_run_completions(id) ON DELETE CASCADE,
  step_key       TEXT NOT NULL,
  step_order     SMALLINT NOT NULL,
  status         TEXT NOT NULL DEFAULT 'PENDING'
                 CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED_RETRYABLE', 'FAILED_PERMANENT')),
  attempts       INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  lease_owner    TEXT,
  lease_until    TIMESTAMPTZ,
  last_error     VARCHAR(2000),
  started_at     TIMESTAMPTZ,
  completed_at   TIMESTAMPTZ,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (completion_id, step_key),
  UNIQUE (completion_id, step_order)
);

CREATE INDEX IF NOT EXISTS idx_agent_run_completion_steps_claim
  ON ${schema}.agent_run_completion_steps(status, next_attempt_at, lease_until, step_order);

CREATE TABLE IF NOT EXISTS ${schema}.agent_run_completion_requeues (
  id             BIGSERIAL PRIMARY KEY,
  completion_id  BIGINT NOT NULL REFERENCES ${schema}.agent_run_completions(id) ON DELETE CASCADE,
  step_key       TEXT NOT NULL,
  requested_by   TEXT NOT NULL,
  reason         TEXT NOT NULL,
  previous_status TEXT NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Database-owned effects get their own idempotency keys. This closes the
-- crash-after-effect/before-ack window for usage totals and agent events.
CREATE TABLE IF NOT EXISTS ${schema}.agent_run_usage_applications (
  agent_run_id BIGINT PRIMARY KEY REFERENCES ${schema}.agent_runs(id) ON DELETE CASCADE,
  applied_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE ${schema}.agent_events
  ADD COLUMN IF NOT EXISTS completion_effect_key TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_events_completion_effect
  ON ${schema}.agent_events(agent_run_id, completion_effect_key)
  WHERE completion_effect_key IS NOT NULL;
