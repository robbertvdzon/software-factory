ALTER TABLE ${schema}.agent_runs
    ADD COLUMN IF NOT EXISTS rate_limit_status TEXT,
    ADD COLUMN IF NOT EXISTS rate_limit_resets_at BIGINT,
    ADD COLUMN IF NOT EXISTS rate_limit_overage_resets_at BIGINT;
