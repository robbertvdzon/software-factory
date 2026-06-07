ALTER TABLE ${schema}.agent_runs
  ADD COLUMN IF NOT EXISTS subtask_key TEXT;
