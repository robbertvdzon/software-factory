-- "Run now" mag een audit in de wachtrij zetten van een al lopende run (voorheen: geweigerd zolang
-- er een run actief was — en een scheduled run blijft de hele dag actief zolang een project met een
-- latere starttijd nog niet geseed is, dus in de praktijk lukte "Run now" bijna nooit).
--
-- Zo'n handmatig toegevoegde job mag níet doorgaan voor "dit project is al geseed": anders zou een
-- handmatige run van project X de geplande audits van X voor die dag stilletjes overslaan (zie
-- AuditScheduler.seedProject/pendingProjects). Daarom per job vastleggen hoe hij ontstond, met
-- dezelfde waarden als audit_run.kind (AuditRunKind).
ALTER TABLE ${schema}.audit_run_job
    ADD COLUMN IF NOT EXISTS kind TEXT NOT NULL DEFAULT 'scheduled';
