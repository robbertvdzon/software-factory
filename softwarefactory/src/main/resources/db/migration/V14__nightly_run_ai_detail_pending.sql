-- Uitgestelde AI-verrijking van de nightly-digest.
--
-- De digest gaat direct de deur uit (links + feiten). Lukt de AI-samenvatting op dat moment niet
-- (bv. de Claude 5-uurs-limiet is op direct na een zware run, en overage staat org-breed uit), dan
-- markeren we de run hiermee: een latere, rustigere tick probeert de samenvatting opnieuw en stuurt
-- de details na zodra het budget hersteld is.

ALTER TABLE ${schema}.nightly_run
    ADD COLUMN IF NOT EXISTS ai_detail_pending BOOLEAN NOT NULL DEFAULT FALSE;
