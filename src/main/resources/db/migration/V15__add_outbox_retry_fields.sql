-- Добавляет retry_count и last_attempt_at для outbox событий
-- Добавляет индекс для быстрого поиска FAILED событий

ALTER TABLE outbox ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMP WITH TIME ZONE;

-- Составной индекс для быстрого поиска FAILED событий для ретрая
-- Позволяет эффективно фильтровать по status, retry_count и сортировать по created_at
CREATE INDEX IF NOT EXISTS idx_outbox_status_failed_retry ON outbox(status, retry_count, created_at);

-- Комментарии
COMMENT ON COLUMN outbox.retry_count IS 'Количество попыток отправки (для ограничения ретраев)';
COMMENT ON COLUMN outbox.last_attempt_at IS 'Время последней попытки отправки (для экспоненциального бэкоффа)';
