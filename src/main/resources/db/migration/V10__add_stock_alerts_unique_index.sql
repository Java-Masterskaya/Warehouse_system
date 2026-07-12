-- Уникальный индекс для предотвращения логических дубликатов алертов
-- Позволяет обрабатывать одно и то же событие повторно без создания дубликатов
CREATE UNIQUE INDEX IF NOT EXISTS idx_stock_alerts_unique ON stock_alerts(item_id, created_at);
