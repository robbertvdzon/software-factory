-- Runtime-poging per job bijhouden. Een Agent Runtime-job kan na een WORKER_ERROR (bijv. een
-- verbroken verbinding tijdens een router-reload) automatisch een nieuwe poging krijgen die op nul
-- begint. De harde time-out van de factory telt sindsdien vanaf de start van de laatste poging in
-- plaats van vanaf de eerste dispatch, anders krijgt de retry alleen de resterende tijd.
ALTER TABLE ${schema}.agent_runtime_jobs
  ADD COLUMN IF NOT EXISTS attempt_count      INTEGER NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS attempt_started_at TIMESTAMPTZ;
