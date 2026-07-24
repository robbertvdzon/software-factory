-- SF-1261: herstructureer de drie overlappende story-opties (auto_approve/silent/
-- telegram_result_notify) naar drie onafhankelijke assen: vragen toestaan (boolean),
-- goedkeuring (enum-achtige tekst) en meldingen (enum-achtige tekst).
ALTER TABLE ${schema}.issues ADD COLUMN IF NOT EXISTS questions_allowed BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE ${schema}.issues ADD COLUMN IF NOT EXISTS approval_mode TEXT NOT NULL DEFAULT 'automatisch';
ALTER TABLE ${schema}.issues ADD COLUMN IF NOT EXISTS notify_mode TEXT NOT NULL DEFAULT 'als-klaar';

-- Backfill bestaande stories 1-op-1 gedragsequivalent aan hun oude staat (zie de migratietabel in
-- de SF-1261-scope). Alleen ECHT nieuwe stories (aangemaakt ná deze migratie) krijgen de nieuwe
-- default notify_mode='als-klaar'; bestaande niet-silent stories migreren expliciet naar
-- 'na-elke-stap' om het huidige gedrag exact te behouden.
UPDATE ${schema}.issues
SET questions_allowed = false,
    approval_mode = 'automatisch',
    notify_mode = 'geen'
WHERE silent = true;

UPDATE ${schema}.issues
SET questions_allowed = true,
    approval_mode = CASE WHEN auto_approve THEN 'automatisch' ELSE 'elke-stap' END,
    notify_mode = CASE WHEN telegram_result_notify THEN 'als-klaar' ELSE 'na-elke-stap' END
WHERE silent = false;

ALTER TABLE ${schema}.issues DROP COLUMN IF EXISTS auto_approve;
ALTER TABLE ${schema}.issues DROP COLUMN IF EXISTS silent;
ALTER TABLE ${schema}.issues DROP COLUMN IF EXISTS telegram_result_notify;
