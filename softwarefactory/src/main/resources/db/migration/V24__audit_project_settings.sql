-- Per-project audit-instellingen: starttijd en aantal audits per nacht (voorheen één globale
-- starttijd + altijd precies 1 audit per project, zie V21/audit_settings). Een ontbrekende rij
-- betekent "nog niet aangepast" — de scheduler valt dan terug op audit_settings.start_time
-- (globaal) en audit_count = 1 (het oude, altijd-1-per-project gedrag).
CREATE TABLE IF NOT EXISTS ${schema}.audit_project_settings (
    project      TEXT         PRIMARY KEY,
    start_time   TEXT,
    audit_count  SMALLINT     NOT NULL DEFAULT 1,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT audit_project_settings_count_non_negative CHECK (audit_count >= 0)
);
