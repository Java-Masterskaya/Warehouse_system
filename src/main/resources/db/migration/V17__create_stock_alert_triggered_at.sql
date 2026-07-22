-- 1. Добавляем triggered_at (nullable сначала)
ALTER TABLE stock_alerts
    ADD COLUMN triggered_at TIMESTAMP;

-- 2. Переносим данные: triggered_at = created_at (для существующих записей)
UPDATE stock_alerts SET triggered_at = created_at;

-- 3. Делаем NOT NULL
ALTER TABLE stock_alerts
    ALTER COLUMN triggered_at SET NOT NULL;

-- 4. Удаляем старый индекс
DROP INDEX IF EXISTS uq_stock_alerts_item_created;

-- 5. Создаём новый индекс
CREATE UNIQUE INDEX uq_stock_alerts_item_triggered
    ON stock_alerts(item_id, triggered_at);