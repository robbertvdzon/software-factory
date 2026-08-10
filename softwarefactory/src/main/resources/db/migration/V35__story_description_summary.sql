-- SF: korte, niet-technische samenvatting van de refiner naast de volledige description.
ALTER TABLE ${schema}.issues ADD COLUMN IF NOT EXISTS description_summary TEXT;
