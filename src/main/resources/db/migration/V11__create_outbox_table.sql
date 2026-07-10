-- Таблица для надежной доставки событий (Outbox pattern)
-- Гарантирует, что событие не потеряется даже при краше после коммита БД

CREATE TABLE IF NOT EXISTS outbox (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(50) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    sent_at TIMESTAMP WITH TIME ZONE,
    error_message TEXT
);

-- Индекс для быстрого поиска неотправленных событий
CREATE INDEX IF NOT EXISTS idx_outbox_status ON outbox(status);

-- Индекс для сортировки по времени создания (порядок отправки)
CREATE INDEX IF NOT EXISTS idx_outbox_created_at ON outbox(created_at);

-- Комментарии
COMMENT ON TABLE outbox IS 'Таблица для надежной доставки событий (Outbox pattern)';
COMMENT ON COLUMN outbox.id IS 'Уникальный идентификатор события';
COMMENT ON COLUMN outbox.event_type IS 'Тип события (например, LowStockAlert)';
COMMENT ON COLUMN outbox.payload IS 'JSON-данные события';
COMMENT ON COLUMN outbox.status IS 'Статус: PENDING (ожидает отправки), SENT (успешно отправлено), FAILED (ошибка)';
COMMENT ON COLUMN outbox.created_at IS 'Время создания события в outbox';
COMMENT ON COLUMN outbox.sent_at IS 'Время успешной отправки в Kafka';
COMMENT ON COLUMN outbox.error_message IS 'Сообщение об ошибке при отправке (если есть)';
