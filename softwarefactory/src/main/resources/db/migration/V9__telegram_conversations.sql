-- Fase B (assistent): gespreksgeheugen per Telegram-chat. We bewaren alleen de claude session-id;
-- de transcriptie zelf houdt claude lokaal bij (resume op session-id). Eén rij per chat.

CREATE TABLE IF NOT EXISTS ${schema}.telegram_conversations (
    chat_id    TEXT        PRIMARY KEY,
    session_id TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
