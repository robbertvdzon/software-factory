INSERT INTO ${schema}.agent_role_execution_config
  (role, project_key, vendor_id, model, mode, updated_by)
VALUES
  ('assistant', NULL, 'openai', 'gpt-5.6-sol', 'SUBSCRIPTION', 'migration')
ON CONFLICT DO NOTHING;
