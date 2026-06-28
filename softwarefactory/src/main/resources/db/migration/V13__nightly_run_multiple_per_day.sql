-- Meerdere nightly-runs per dag toestaan + onderscheid scheduled/manual.
--
-- Voorheen was er max. één run per kalenderdag (UNIQUE op run_date). Nu kun je naast de
-- automatische dagelijkse run ook handmatig een run starten ("Run nu"-knop), en krijgt elke run
-- zijn eigen digest. De idempotentie van de digest zit al per run-rij (summary_sent_at).
--
--   * kind = 'scheduled'  -> de automatische dagelijkse run (digest op de summary-tijd).
--   * kind = 'manual'     -> handmatig gestart (digest zodra de run klaar is, niet vóór summary-tijd).

ALTER TABLE ${schema}.nightly_run
    DROP CONSTRAINT IF EXISTS nightly_run_unique_date;

ALTER TABLE ${schema}.nightly_run
    ADD COLUMN IF NOT EXISTS kind TEXT NOT NULL DEFAULT 'scheduled';

-- Snel kunnen vinden of er voor een dag al een scheduled run is (voorkomt dubbele auto-runs).
CREATE INDEX IF NOT EXISTS nightly_run_date_kind_idx
    ON ${schema}.nightly_run (run_date, kind);
