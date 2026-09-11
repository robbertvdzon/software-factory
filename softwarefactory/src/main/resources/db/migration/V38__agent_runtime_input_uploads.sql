CREATE TABLE IF NOT EXISTS ${schema}.agent_runtime_input_uploads (
  idempotency_key TEXT NOT NULL,
  logical_name    TEXT NOT NULL,
  upload_id       UUID NOT NULL UNIQUE,
  object_id       UUID NOT NULL UNIQUE,
  filename        TEXT NOT NULL,
  mime_type       TEXT NOT NULL,
  size_bytes      BIGINT NOT NULL,
  sha256          VARCHAR(64) NOT NULL,
  chunk_size      BIGINT NOT NULL,
  uploaded_offset BIGINT NOT NULL DEFAULT 0,
  state           TEXT NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (idempotency_key, logical_name)
);

CREATE INDEX IF NOT EXISTS idx_agent_runtime_input_uploads_updated
  ON ${schema}.agent_runtime_input_uploads(updated_at);
