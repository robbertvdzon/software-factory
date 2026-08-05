-- SF-1959: vierde story-as `Hotfix` naast questions_allowed/approval_mode/notify_mode.
-- Staat de vlag aan, dan slaat de factory refine/plan/review/test/documentatie over.
-- Bestaande rijen worden bewust NIET aangeraakt: een bestaande story is nooit een hotfix.
ALTER TABLE ${schema}.issues ADD COLUMN IF NOT EXISTS hotfix BOOLEAN NOT NULL DEFAULT false;
