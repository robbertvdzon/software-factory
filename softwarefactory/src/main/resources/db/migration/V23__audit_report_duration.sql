-- Duur van de audit-agent-run die dit rapport opleverde (ms), voor de "hoe lang deed hij erover"-
-- weergave in het dashboard. Denormaliseerd op audit_report zelf i.p.v. via audit_run_job.report_id
-- op te zoeken: eenvoudiger te lezen en de waarde is toch al bekend op het moment van persisteren
-- (AuditGatewayAdapter.auditOutcome() heeft 'm net uit agent-result.json gelezen).
ALTER TABLE ${schema}.audit_report
    ADD COLUMN IF NOT EXISTS duration_ms BIGINT;
