ALTER TABLE ${schema}.story_runs
  ADD COLUMN IF NOT EXISTS workspace_path TEXT;
