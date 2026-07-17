-- Per-issue override van de test-chain-reset-cap, de spiegel van ai_max_developer_loopbacks.
-- Zonder dit veld was een bereikte test-cap een doodlopende straat (alleen paused/re-implement
-- of handwerk in de database); `resume` op de subtaak verhoogt nu deze limiet.
ALTER TABLE ${schema}.issues ADD COLUMN IF NOT EXISTS ai_max_test_chain_resets INTEGER;
