-- Создать партии для существующих остатков на каждом складе.
-- У старых данных нет известного срока годности, поэтому используется
-- техническая максимально удаленная дата.

INSERT INTO batches (item_id, warehouse_id, quantity, expiry_date, created_at)
SELECT
    s.item_id,
    s.warehouse_id,
    s.quantity,
    TIMESTAMP '9999-12-31 23:59:59',
    CURRENT_TIMESTAMP
FROM stock s
WHERE s.quantity > 0;
