-- SF-1913: de nachtelijke maintenance-cleanup stuurde z'n resultaat als Telegram-bericht en liet
-- verder geen spoor na. Die melding vervalt; in plaats daarvan krijgt elke opruimronde een rij hier,
-- zodat het dashboard kan laten zien dát de opruimer gedraaid heeft en wat hij weggehaald heeft.
--
-- Er komt bewust ook een rij bij 0 verwijderingen, bij een dry-run en bij een mislukte projectrun
-- (`error` gevuld): "niets opgeruimd" is precies de uitkomst die je zonder historie niet kunt
-- onderscheiden van "niet gedraaid". Retentie loopt via de scheduler zelf (90 dagen, zie
-- `sf.maintenance.run-retention-days`) — de tabel groeit dus niet onbeperkt.
--
-- `details` is JSON-tekst met twee lijsten (verwijderde release-tags, verwijderde package-versions):
-- puur presentatiemateriaal voor het detailscherm, nooit een queryable dimensie — daarom TEXT en
-- geen aparte regeltabel.
CREATE TABLE IF NOT EXISTS ${schema}.maintenance_cleanup_runs (
    id               BIGSERIAL   PRIMARY KEY,
    project          TEXT        NOT NULL,
    started_at       TIMESTAMPTZ NOT NULL,
    finished_at      TIMESTAMPTZ NOT NULL,
    releases_deleted INTEGER     NOT NULL DEFAULT 0,
    releases_kept    INTEGER     NOT NULL DEFAULT 0,
    packages_deleted INTEGER     NOT NULL DEFAULT 0,
    packages_kept    INTEGER     NOT NULL DEFAULT 0,
    dry_run          BOOLEAN     NOT NULL DEFAULT FALSE,
    error            TEXT,
    details          TEXT
);

-- De enige leesroute is "de laatste runs, nieuwste eerst" (optioneel op één project gefilterd).
CREATE INDEX IF NOT EXISTS maintenance_cleanup_runs_started_idx
    ON ${schema}.maintenance_cleanup_runs (started_at DESC);
