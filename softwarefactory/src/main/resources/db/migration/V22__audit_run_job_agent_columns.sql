-- Aanvulling op V21: audit-jobs draaien via een agent-container, net als subtaken. Deze drie
-- kolommen ontbraken in de eerst toegepaste versie van V21 op sommige omgevingen (het bestand
-- kreeg ze later toegevoegd, na een reeds toegepaste eerdere versie) — als aparte, idempotente
-- migratie i.p.v. de al toegepaste V21 achteraf te wijzigen.
ALTER TABLE ${schema}.audit_run_job
    ADD COLUMN IF NOT EXISTS container_name TEXT,
    ADD COLUMN IF NOT EXISTS workspace_path TEXT,
    ADD COLUMN IF NOT EXISTS story_run_id BIGINT;
