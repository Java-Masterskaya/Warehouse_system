-- Таблица для архива "битых" событий outbox (Dead Letter Table)
-- Сюда попадают события, которые превысили maxRetries попыток отправки

CREATE TABLE IF NOT EXISTS outbox_dlt (
    id BIGSERIAL PRIMARY KEY,
    original_outbox_id BIGINT NOT NULL REFERENCES outbox(id) ON DELETE CASCADE,
    event_type VARCHAR(50) NOT NULL,
    payload TEXT NOT NULL,
    error_message TEXT,
    retry_count INTEGER NOT NULL,
    last_attempt_at TIMESTAMP WITH TIME ZONE,
    permanent_failure_reason VARCHAR(100) NOT NULL,
    dlt_created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Индекс для быстрого поиска по оригинальному ID
CREATE INDEX IF NOT EXISTS idx_outbox_dlt_original_id ON outbox_dlt(original_outbox_id);

-- Комментарии
COMMENT ON TABLE outbox_dlt IS 'Таблица для архива "битых" событий outbox (Dead Letter Table)';
COMMENT ON COLUMN outbox_dlt.id IS 'Уникальный идентификатор записи в DLT';
COMMENT ON COLUMN outbox_dlt.original_outbox_id IS 'ID оригинального события в outbox (для отслеживания)';
COMMENT ON COLUMN outbox_dlt.event_type IS 'Тип события (например, LowStockAlert)';
COMMENT ON COLUMN outbox_dlt.payload IS 'JSON-данные события';
COMMENT ON COLUMN outbox_dlt.error_message IS 'Последнее сообщение об ошибке';
COMMENT ON COLUMN outbox_dlt.retry_count IS 'Количество попыток отправки перед перемещением в DLT';
COMMENT ON COLUMN outbox_dlt.last_attempt_at IS 'Время последней попытки отправки';
COMMENT ON COLUMN outbox_dlt.permanent_failure_reason IS 'Причина перманентной ошибки (MAX_RETRIES_EXCEEDED, DESERIALIZATION_ERROR)';
COMMENT ON COLUMN outbox_dlt.dlt_created_at IS 'Время перемещения в DLT';
