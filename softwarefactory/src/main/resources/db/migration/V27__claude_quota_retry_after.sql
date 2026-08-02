ALTER TABLE ${schema}.issues
    ADD COLUMN IF NOT EXISTS retry_after TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_issues_retry_after
    ON ${schema}.issues(retry_after)
    WHERE retry_after IS NOT NULL;
