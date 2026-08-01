-- =============================================
-- V21.2: Switch tables
-- =============================================

ALTER TABLE stock_movements
    RENAME TO stock_movements_old;
ALTER TABLE stock_movements_new
    RENAME TO stock_movements;

DROP SEQUENCE IF EXISTS stock_movements_id_seq CASCADE;
DROP SEQUENCE IF EXISTS stock_movements_new_id_seq CASCADE;

CREATE SEQUENCE stock_movements_id_seq;
SELECT SETVAL('stock_movements_id_seq', COALESCE((SELECT MAX(id) FROM stock_movements), 0) + 1);

ALTER TABLE stock_movements
    ALTER COLUMN id SET DEFAULT NEXTVAL('stock_movements_id_seq');
ALTER SEQUENCE stock_movements_id_seq OWNED BY stock_movements.id;

DROP TRIGGER sync_to_new_movements_trigger ON stock_movements_old;