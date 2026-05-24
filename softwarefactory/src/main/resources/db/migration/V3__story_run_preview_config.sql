ALTER TABLE ${schema}.story_runs
  ADD COLUMN IF NOT EXISTS base_branch TEXT,
  ADD COLUMN IF NOT EXISTS branch_prefix TEXT,
  ADD COLUMN IF NOT EXISTS preview_url_template TEXT,
  ADD COLUMN IF NOT EXISTS preview_namespace_template TEXT,
  ADD COLUMN IF NOT EXISTS preview_db_secret_recipe TEXT;
