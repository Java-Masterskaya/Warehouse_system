-- Добавляем версионирование для optimistic locking на таблице stock
ALTER TABLE stock ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
