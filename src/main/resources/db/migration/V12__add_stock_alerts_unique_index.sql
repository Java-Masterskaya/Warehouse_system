-- Уникальный индекс для дедупликации stock_alerts по itemId и createdAt
-- Предотвращает дублирование алертов при повторной доставке из Kafka
CREATE UNIQUE INDEX IF NOT EXISTS uq_stock_alerts_item_created ON stock_alerts(item_id, created_at);
