-- Добавляет retry_count и last_attempt_at для outbox событий
-- Добавляет индекс для быстрого поиска FAILED событий

ALTER TABLE outbox ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMP WITH TIME ZONE;

-- Индекс для быстрого поиска FAILED событий для ретрая
CREATE INDEX IF NOT EXISTS idx_outbox_status_failed ON outbox(status) WHERE status = 'FAILED';

-- Комментарии
COMMENT ON COLUMN outbox.retry_count IS 'Количество попыток отправки (для ограничения ретраев)';
COMMENT ON COLUMN outbox.last_attempt_at IS 'Время последней попытки отправки (для экспоненциального бэкоффа)';
