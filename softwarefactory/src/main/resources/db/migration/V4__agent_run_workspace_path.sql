ALTER TABLE ${schema}.agent_runs
  ADD COLUMN IF NOT EXISTS workspace_path TEXT;
