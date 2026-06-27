-- SF-352 (nightly scheduler): bewaar de verstuurde digest-tekst zodat die in de UI zichtbaar blijft.
--
-- De reconciliation-scheduler stuurt na de summary-tijd één digest naar Telegram en zet tegelijk
-- summary_sent_at (idempotentie). We bewaren de bijbehorende tekst zodat /nightly de laatste digest
-- kan tonen, ook nadat het Telegram-bericht weg is.

ALTER TABLE ${schema}.nightly_run
    ADD COLUMN IF NOT EXISTS summary_text TEXT;
