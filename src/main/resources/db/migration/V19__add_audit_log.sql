CREATE TABLE audit_log
(
    id           bigserial PRIMARY KEY,

    user_id      bigint,
    username     varchar(100),

    audit_action varchar(30)              NOT NULL,

    entity_type  varchar(100)             NOT NULL,
    entity_id    bigint                   NOT NULL,

    old_value    JSONB,
    new_value    JSONB,

    created_at   timestamp with time zone NOT NULL DEFAULT NOW()
);

-- Индексы для быстрого поиска и очистки
CREATE INDEX idx_audit_entity ON audit_log (entity_type, entity_id);

CREATE INDEX idx_audit_user ON audit_log (user_id);

CREATE INDEX idx_audit_created_at ON audit_log (created_at);

-- 2. ТРИГГЕРНАЯ ФУНКЦИЯ: Проверяет "пропуск"
CREATE OR REPLACE FUNCTION prevent_audit_changes() RETURNS trigger AS $$ BEGIN
    -- ПРОВЕРКА ПРОПУСКА:
    -- Проверяем сессионную переменную 'app.allow_audit_delete'.
    -- Второй аргумент 'true' означает "не выдавать ошибку, если переменная еще не создана".
    IF CURRENT_SETTING('app.allow_audit_delete', TRUE) = 'true' THEN
        -- Если пропуск предъявлен и это операция DELETE — разрешаем!
        IF tg_op = 'DELETE' THEN RETURN old;
        END IF;
    END IF;

    -- Если пропуска нет (или это UPDATE) — блокируем операцию!
    RAISE EXCEPTION 'Audit log is immutable';
END; $$ LANGUAGE plpgsql;

-- Привязываем триггер к таблице
CREATE TRIGGER audit_log_no_update_delete
    BEFORE UPDATE OR DELETE
    ON audit_log
    FOR EACH ROW
EXECUTE FUNCTION prevent_audit_changes();

-- 3. ФУНКЦИЯ ОЧИСТКИ (Устанавливает "пропуск" и удаляет 1 батч)
CREATE OR REPLACE FUNCTION purge_old_audit_logs_batch(
    retention_days int, batch_size int
) RETURNS int AS $$ DECLARE
    deleted_count int;
BEGIN
    PERFORM SET_CONFIG('app.allow_audit_delete', 'true', TRUE);

    WITH deleted AS (SELECT id
                     FROM audit_log
                     WHERE created_at < NOW() - (retention_days || ' days')::interval
                     LIMIT batch_size FOR UPDATE SKIP LOCKED)
    DELETE
    FROM audit_log
    WHERE id IN (SELECT id FROM deleted);

    -- Возвращаем количество реально удаленных строк
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END; $$ LANGUAGE plpgsql;
