-- ВНИМАНИЕ: НЕ В db/migration/ - копировать после V33 (индекс должен уже существовать).

ALTER TABLE items ADD CONSTRAINT uk_items_barcode UNIQUE USING INDEX uk_items_barcode;
