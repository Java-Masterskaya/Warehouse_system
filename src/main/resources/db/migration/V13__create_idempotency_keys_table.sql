-- Таблица для хранения идемпотентных ключей и результатов операций
CREATE TABLE idempotency_keys (
    id BIGSERIAL PRIMARY KEY,
    key_hash VARCHAR(64) NOT NULL UNIQUE,  -- SHA-256 хеш ключа для эффективного поиска
    user_id BIGINT NOT NULL REFERENCES users(id),
    endpoint VARCHAR(255) NOT NULL,  -- /api/movements/receive или /api/movements/write-off
    movement_id BIGINT REFERENCES stock_movements(id),
    response_body TEXT NOT NULL,  -- JSON ответа
    status_code INTEGER NOT NULL,  -- HTTP статус
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL,  -- Время истечения ключа
    CONSTRAINT uk_idempotency_key_hash UNIQUE (key_hash)
);

-- Индекс для быстрого поиска по ключу и пользователю
CREATE INDEX idx_idempotency_keys_key_hash ON idempotency_keys(key_hash);
CREATE INDEX idx_idempotency_keys_user_id ON idempotency_keys(user_id);
CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys(expires_at);

-- Комментарии к таблице и колонкам
COMMENT ON TABLE idempotency_keys IS 'Хранит идемпотентные ключи для API запросов';
COMMENT ON COLUMN idempotency_keys.key_hash IS 'SHA-256 хеш оригинального ключа';
COMMENT ON COLUMN idempotency_keys.movement_id IS 'ID созданного движения, если операция успешна';
COMMENT ON COLUMN idempotency_keys.response_body IS 'JSON-сериализованный ответ, который вернулся клиенту';
COMMENT ON COLUMN idempotency_keys.expires_at IS 'Время, после которого ключ считается устаревшим и может быть удален';