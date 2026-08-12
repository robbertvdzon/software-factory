-- Roadmap-epics staan boven stories en hebben twee expliciete rangordes:
-- de klantvolgorde en het advies van het roadmap-proces. De effectieve
-- roadmap-rang wordt afgeleid, zodat dependencies altijd gerespecteerd worden.
CREATE TABLE IF NOT EXISTS ${schema}.roadmap_epics (
  id             BIGSERIAL PRIMARY KEY,
  title          TEXT NOT NULL,
  description    TEXT,
  status         TEXT NOT NULL DEFAULT 'planned',
  customer_rank  INTEGER NOT NULL CHECK (customer_rank > 0),
  process_rank   INTEGER NOT NULL CHECK (process_rank > 0),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT roadmap_epics_title_not_blank CHECK (length(trim(title)) > 0),
  CONSTRAINT roadmap_epics_status_valid CHECK (status IN ('planned', 'in_progress', 'done')),
  CONSTRAINT roadmap_customer_rank_unique UNIQUE (customer_rank) DEFERRABLE INITIALLY DEFERRED,
  CONSTRAINT roadmap_process_rank_unique UNIQUE (process_rank) DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX IF NOT EXISTS idx_roadmap_epics_customer_rank
  ON ${schema}.roadmap_epics(customer_rank, id);
CREATE INDEX IF NOT EXISTS idx_roadmap_epics_process_rank
  ON ${schema}.roadmap_epics(process_rank, id);

CREATE TABLE IF NOT EXISTS ${schema}.roadmap_epic_dependencies (
  epic_id        BIGINT NOT NULL REFERENCES ${schema}.roadmap_epics(id) ON DELETE CASCADE,
  dependency_id  BIGINT NOT NULL REFERENCES ${schema}.roadmap_epics(id) ON DELETE RESTRICT,
  PRIMARY KEY (epic_id, dependency_id),
  CONSTRAINT roadmap_dependency_not_self CHECK (epic_id <> dependency_id)
);

CREATE INDEX IF NOT EXISTS idx_roadmap_dependencies_dependency
  ON ${schema}.roadmap_epic_dependencies(dependency_id, epic_id);
