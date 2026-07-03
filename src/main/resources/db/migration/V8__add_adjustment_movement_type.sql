-- Удаляем CHECK на quantity
ALTER TABLE stock_movements DROP CONSTRAINT IF EXISTS stock_movements_quantity_check;

-- Удаляем CHECK на type
ALTER TABLE stock_movements DROP CONSTRAINT IF EXISTS stock_movements_type_check;

-- Новый CHECK: три типа, ADJUSTMENT добавлен
ALTER TABLE stock_movements ADD CONSTRAINT stock_movements_type_check
    CHECK (type IN ('RECEIVE', 'WRITE_OFF', 'ADJUSTMENT'));
