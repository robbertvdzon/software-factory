-- SF-1134: per-story opt-in vlag om een aparte Telegram-melding te sturen zodra het
-- eindresultaat écht extern zichtbaar/live is (naast de bestaande subtaak-DONE-melding).
ALTER TABLE ${schema}.issues ADD COLUMN IF NOT EXISTS telegram_result_notify BOOLEAN NOT NULL DEFAULT false;
