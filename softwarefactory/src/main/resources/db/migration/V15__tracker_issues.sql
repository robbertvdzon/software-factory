-- Eigen tracker-tabellen (vervangen YouTrack als bron voor stories/subtaken).
-- Eén unified 'issues'-tabel i.p.v. aparte stories/subtasks: een subtaak is
-- gewoon een issue met een parent_key, net zo simpel als YouTrack's generieke
-- issue-links maar zonder de link-tabel-indirectie nodig te hebben.
CREATE TABLE IF NOT EXISTS ${schema}.issues (
  id                          BIGSERIAL PRIMARY KEY,
  issue_key                   TEXT NOT NULL UNIQUE,
  project_key                 TEXT NOT NULL,
  summary                     TEXT NOT NULL,
  description                 TEXT,
  parent_key                  TEXT REFERENCES ${schema}.issues(issue_key) ON DELETE SET NULL,
  status                      TEXT,
  repo                        TEXT,
  ai_supplier                 TEXT,
  auto_approve                BOOLEAN NOT NULL DEFAULT false,
  ai_phase                    TEXT,
  ai_level                    INTEGER,
  ai_max_developer_loopbacks  INTEGER,
  ai_token_budget             BIGINT,
  ai_tokens_used              BIGINT,
  agent_started_at            TIMESTAMPTZ,
  paused                      BOOLEAN NOT NULL DEFAULT false,
  silent                      BOOLEAN NOT NULL DEFAULT false,
  error                       TEXT,
  type                        TEXT,
  subtask_type                TEXT,
  ai_model                    TEXT,
  ai_reasoning_effort         TEXT,
  story_phase                 TEXT,
  subtask_phase               TEXT,
  created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_issues_parent_key ON ${schema}.issues(parent_key);
CREATE INDEX IF NOT EXISTS idx_issues_project_key ON ${schema}.issues(project_key);
CREATE INDEX IF NOT EXISTS idx_issues_ai_supplier ON ${schema}.issues(ai_supplier);
CREATE INDEX IF NOT EXISTS idx_issues_updated_at ON ${schema}.issues(updated_at);

CREATE TABLE IF NOT EXISTS ${schema}.issue_comments (
  id                  BIGSERIAL PRIMARY KEY,
  issue_key           TEXT NOT NULL REFERENCES ${schema}.issues(issue_key) ON DELETE CASCADE,
  -- Alleen gevuld door de migratietool (bewaart het originele YouTrack-comment-id voor audit);
  -- de app zelf gebruikt en leest dit veld nooit.
  external_comment_id TEXT,
  author_account_id   TEXT,
  author_display_name TEXT,
  body                TEXT NOT NULL,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_issue_comments_issue_key ON ${schema}.issue_comments(issue_key, id);

CREATE TABLE IF NOT EXISTS ${schema}.issue_attachments (
  id          BIGSERIAL PRIMARY KEY,
  issue_key   TEXT NOT NULL REFERENCES ${schema}.issues(issue_key) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  mime_type   TEXT,
  size_bytes  BIGINT,
  -- Absoluut pad op de laptop-schijf (buiten git/cloud) — geen bytes in de DB.
  local_path  TEXT NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_issue_attachments_issue_key ON ${schema}.issue_attachments(issue_key);

-- Per-project teller voor het genereren van nieuwe issue-keys ("SF-809", ...).
-- Atomisch bij te werken via UPDATE ... SET next_number = next_number + 1 RETURNING next_number,
-- zodat gelijktijdige aanmaak-calls nooit dezelfde key kunnen genereren.
CREATE TABLE IF NOT EXISTS ${schema}.project_key_sequences (
  project_key   TEXT PRIMARY KEY,
  next_number   INTEGER NOT NULL
);
