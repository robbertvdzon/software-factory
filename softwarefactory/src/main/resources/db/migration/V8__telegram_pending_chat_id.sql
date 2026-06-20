-- Per-project kanalen: een Telegram message_id is alleen uniek BINNEN een chat. Nu er meerdere
-- kanalen zijn, moet de reply-koppeling op (chat_id, message_id) i.p.v. message_id alleen.

ALTER TABLE ${schema}.telegram_pending_questions ADD COLUMN IF NOT EXISTS chat_id TEXT;

-- Bestaande rijen (van vóór per-project kanalen) hoorden bij het globale kanaal; vul leeg in.
UPDATE ${schema}.telegram_pending_questions SET chat_id = '' WHERE chat_id IS NULL;

ALTER TABLE ${schema}.telegram_pending_questions ALTER COLUMN chat_id SET NOT NULL;

ALTER TABLE ${schema}.telegram_pending_questions DROP CONSTRAINT IF EXISTS telegram_pending_questions_pkey;
ALTER TABLE ${schema}.telegram_pending_questions ADD PRIMARY KEY (chat_id, message_id);
