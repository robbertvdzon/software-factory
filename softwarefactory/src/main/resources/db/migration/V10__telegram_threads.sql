-- Fase 5 (threading): elke reply-keten = een eigen gesprek (claude-sessie). We koppelen elk
-- bericht (zowel de vraag van de gebruiker als ons antwoord) aan de sessie van die thread, zodat een
-- reply op een willekeurig bericht in de keten de juiste sessie hervat. Vervangt het één-sessie-per-chat
-- model (telegram_conversations blijft bestaan maar wordt niet meer gebruikt).

CREATE TABLE IF NOT EXISTS ${schema}.telegram_threads (
    chat_id    TEXT        NOT NULL,
    message_id BIGINT      NOT NULL,
    session_id TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (chat_id, message_id)
);
