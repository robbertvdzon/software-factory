-- SF-1795: alleen nieuwe stories krijgen voortaan de live/deploy-bevestigde meldingenstand.
-- Bestaande rijen blijven bewust onaangeraakt; deze migratie wijzigt uitsluitend de kolomdefault.
ALTER TABLE ${schema}.issues
    ALTER COLUMN notify_mode SET DEFAULT 'als-klaar-en-gedeployed';
