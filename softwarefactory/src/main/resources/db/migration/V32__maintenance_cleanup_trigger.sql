-- SF-1929: het Opruimen-scherm krijgt een "Nu draaien"-knop per opruimsoort. Een ronde die je zelf
-- start moet in de lijst te onderscheiden zijn van een ronde die vanzelf afging — anders is niet te
-- zien of de knop iets deed.
--
--   * `trigger` benoemt de aanleiding: 'scheduled' (cron/poller) of 'manual' (knop). Bewust een
--     vrije TEXT-kolom met de waarden als afspraak in code (`CleanupTriggers`), net als `kind` in
--     V31; een enum-tabel of CHECK zou elke nieuwe aanleiding een extra migratie kosten.
--   * Bestaande rijen komen per definitie van een geplande ronde (vóór deze migratie bestond de
--     handmatige route niet), dus de DEFAULT vult ze correct.
--
-- Retentie op deze tabel blijft ongewijzigd (`sf.maintenance.run-retention-days`) en dekt daarmee
-- automatisch ook de handmatige rondes.
ALTER TABLE ${schema}.maintenance_cleanup_runs
    ADD COLUMN IF NOT EXISTS trigger TEXT NOT NULL DEFAULT 'scheduled';
