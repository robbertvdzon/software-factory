-- Story 5 (multi-deployment-rollout-plan): nullable timestamp, apart van het story-afrondingsproces
-- gezet — alleen door StoryDeployReconciler, zodra alle door de story geraakte deploy-doelen
-- daadwerkelijk live blijken (ancestor-check resp. APK-release ná de merge). NOOIT gezet door de
-- normale story-afronding zelf (final_status = 'merged'/'done').
ALTER TABLE ${schema}.story_runs ADD COLUMN IF NOT EXISTS deployed_at TIMESTAMPTZ;
