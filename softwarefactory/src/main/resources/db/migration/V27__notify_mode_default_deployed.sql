-- SF-1776: de aanmaak-default van de meldingen-as (as 3) verschuift van 'als-klaar' naar
-- 'als-klaar-en-gedeployed', zodat een nieuwe story pas een Telegram-bericht oplevert als het
-- resultaat ook echt live staat. Deze kolomdefault is leidend voor de API-/Telegram-route:
-- PostgresTrackerClient.createStory neemt notify_mode niet op in de INSERT.
-- Bewust GEEN UPDATE/backfill: bestaande story's houden exact hun huidige notify_mode.
ALTER TABLE ${schema}.issues ALTER COLUMN notify_mode SET DEFAULT 'als-klaar-en-gedeployed';
