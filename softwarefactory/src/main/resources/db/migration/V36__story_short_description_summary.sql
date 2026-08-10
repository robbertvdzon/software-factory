-- SF: korte "for dummies"-samenvatting (max. 3 zinnen), door de summarizer geschreven ná
-- oplevering. Voedt de Telegram-deploy-melding en de publieke changelog-endpoint.
ALTER TABLE ${schema}.issues ADD COLUMN IF NOT EXISTS short_description_summary TEXT;
