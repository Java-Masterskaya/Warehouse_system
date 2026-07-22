-- Создать партии для товаров без партий (для миграции старых данных)
-- Товары без партий получают "бессрочную" партию с сроком годности через 1 год

INSERT INTO batches (item_id, quantity, expiry_date, created_at)
SELECT 
    s.item_id,
    s.quantity,
    CURRENT_TIMESTAMP + INTERVAL '1 year',
    CURRENT_TIMESTAMP
FROM stock s
LEFT JOIN batches b ON b.item_id = s.item_id
WHERE b.id IS NULL;
