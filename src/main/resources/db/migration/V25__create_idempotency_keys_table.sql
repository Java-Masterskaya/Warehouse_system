-- Таблица для хранения идемпотентных ключей и результатов операций
CREATE TABLE idempotency_keys (
    id BIGSERIAL PRIMARY KEY,
    key_hash VARCHAR(64) NOT NULL,  -- SHA-256 хеш ключа для эффективного поиска
    user_id BIGINT NOT NULL REFERENCES users(id),
    endpoint VARCHAR(255) NOT NULL,  -- /api/movements/receive или /api/movements/write-off
    request_body_hash VARCHAR(64) NOT NULL,  -- SHA-256 хеш тела запроса для проверки конфликтов
    response_body TEXT NOT NULL,  -- JSON ответа (полный, для возврата при повторах)
    status_code INTEGER NOT NULL,  -- HTTP статус
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL,  -- Время истечения ключа
    CONSTRAINT uk_idempotency_key_hash UNIQUE (key_hash, user_id, endpoint)
);

-- Индексы для быстрого поиска
CREATE INDEX idx_idempotency_keys_user_id ON idempotency_keys(user_id);
CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys(expires_at);

-- Комментарии к таблице и колонкам
COMMENT ON TABLE idempotency_keys IS 'Хранит идемпотентные ключи для API запросов';
COMMENT ON COLUMN idempotency_keys.key_hash IS 'SHA-256 хеш оригинального ключа';
COMMENT ON COLUMN idempotency_keys.user_id IS 'ID пользователя, выполнившего запрос';
COMMENT ON COLUMN idempotency_keys.endpoint IS 'Эндпоинт запроса';
COMMENT ON COLUMN idempotency_keys.request_body_hash IS 'SHA-256 хеш тела запроса для обнаружения конфликтов';
COMMENT ON COLUMN idempotency_keys.response_body IS 'JSON-сериализованный ответ для возврата при повторных запросах';
COMMENT ON COLUMN idempotency_keys.expires_at IS 'Время, после которого ключ считается устаревшим и может быть удален';