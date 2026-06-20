-- Telegram-integratie: idempotente uitgaande meldingen, reply-koppeling en getUpdates-offset.

-- Eén rij per al-verstuurde melding. De signature codeert de fase/toestand, zodat elke nieuwe
-- transitie precies één keer een bericht oplevert (en herstarts niet opnieuw spammen).
CREATE TABLE IF NOT EXISTS ${schema}.telegram_notifications (
    issue_key   TEXT        NOT NULL,
    signature   TEXT        NOT NULL,
    notified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (issue_key, signature)
);

-- Koppeling tussen een verstuurd vraag-bericht (Telegram message_id) en het issue, zodat een
-- reply op dat bericht naar de juiste story/subtask terugvloeit. Overleeft een herstart.
CREATE TABLE IF NOT EXISTS ${schema}.telegram_pending_questions (
    message_id   BIGINT      PRIMARY KEY,
    issue_key    TEXT        NOT NULL,
    issue_level  TEXT        NOT NULL,
    source_phase TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Kleine key/value-tabel voor de getUpdates-offset (hoogste verwerkte update_id + 1).
CREATE TABLE IF NOT EXISTS ${schema}.telegram_state (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
