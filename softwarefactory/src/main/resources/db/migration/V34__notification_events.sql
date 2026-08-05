-- SF-1986: vervang de combinatie-enum notify_mode door uitsluitend concrete Telegram-events.
ALTER TABLE ${schema}.issues
    ADD COLUMN IF NOT EXISTS notification_events TEXT[] NOT NULL
    DEFAULT ARRAY['DEPLOYED', 'QUESTION', 'MANUAL_ACTION_REQUIRED', 'ERROR']::TEXT[];

DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = '${schema}'
          AND table_name = 'issues'
          AND column_name = 'notify_mode'
    ) THEN
        UPDATE ${schema}.issues
        SET notification_events = CASE notify_mode
            WHEN 'geen' THEN ARRAY['QUESTION']::TEXT[]
            WHEN 'na-elke-stap' THEN ARRAY[
                'QUESTION', 'APPROVAL_REQUIRED', 'MANUAL_ACTION_REQUIRED', 'QUOTA_WAIT',
                'ERROR', 'STEP_COMPLETED', 'WORKFLOW_COMPLETED'
            ]::TEXT[]
            WHEN 'als-klaar' THEN ARRAY['QUESTION', 'ERROR', 'WORKFLOW_COMPLETED']::TEXT[]
            WHEN 'als-klaar-en-gedeployed' THEN ARRAY['QUESTION', 'ERROR', 'DEPLOYED']::TEXT[]
            ELSE ARRAY['DEPLOYED', 'QUESTION', 'MANUAL_ACTION_REQUIRED', 'ERROR']::TEXT[]
        END;

        ALTER TABLE ${schema}.issues DROP COLUMN notify_mode;
    END IF;
END
$migration$;
