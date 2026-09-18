-- Bewaar de inhoudelijke uitkomst apart van de technische runstatus, ook na payload-retentie.
ALTER TABLE ${schema}.agent_runs ADD COLUMN result_phase TEXT;
ALTER TABLE ${schema}.agent_runs ADD COLUMN checkout_commit_sha VARCHAR(40);
UPDATE ${schema}.agent_runs r SET result_phase = c.payload_json->>'phase'
FROM ${schema}.agent_run_completions c
WHERE c.agent_run_id = r.id AND c.payload_json IS NOT NULL
  AND COALESCE((c.payload_json->>'exitCode')::int, 0) = 0
  AND r.outcome NOT ILIKE '%error%' AND r.outcome NOT ILIKE '%failed%';
UPDATE ${schema}.agent_runs r SET checkout_commit_sha = j.checkout_commit_sha
FROM ${schema}.agent_runtime_jobs j WHERE j.agent_run_id = r.id;
