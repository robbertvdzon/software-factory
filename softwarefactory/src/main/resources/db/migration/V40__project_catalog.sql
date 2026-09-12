-- Projectcatalogus (de inhoud van projects.yaml) in de database, beheerd via het dashboard.
-- Eén document i.p.v. een tabel per veld: de bestaande YAML-vorm en -parser blijven de bron van
-- waarheid, en het scherm toont en bewaart precies dat document.
CREATE TABLE IF NOT EXISTS ${schema}.project_catalog (
  id         INTEGER PRIMARY KEY CHECK (id = 1),
  yaml       TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by TEXT NOT NULL
);
