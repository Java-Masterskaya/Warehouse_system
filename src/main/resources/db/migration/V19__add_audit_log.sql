CREATE TABLE audit_log
(
    id          bigserial PRIMARY KEY,

    user_id     bigint,
    username    varchar(100),

    audit_action      varchar(30)              NOT NULL,

    entity_type varchar(100)             NOT NULL,
    entity_id   bigint                   NOT NULL,

    old_value    JSONB,
    new_value    JSONB,

    created_at  timestamp with time zone NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_entity
    ON audit_log(entity_type, entity_id);

CREATE INDEX idx_audit_user
    ON audit_log(user_id);

CREATE INDEX idx_audit_created_at
    ON audit_log(created_at);

CREATE FUNCTION prevent_audit_changes()
    RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Audit log is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_no_update_delete
    BEFORE UPDATE OR DELETE
    ON audit_log
    FOR EACH ROW
EXECUTE FUNCTION prevent_audit_changes();

-- Функция для очистки записей старше retention_days дней
CREATE OR REPLACE FUNCTION purge_old_audit_logs(retention_days INT, batch_size INT)
    RETURNS INT AS $$
DECLARE
    deleted_count INT := 0;
    rows_in_batch INT;
BEGIN
    LOOP
        DELETE FROM audit_log
        WHERE id IN (
            SELECT id FROM audit_log
            WHERE created_at < NOW() - (retention_days || ' days')::INTERVAL
            LIMIT batch_size
        );

        GET DIAGNOSTICS rows_in_batch = ROW_COUNT;
        deleted_count := deleted_count + rows_in_batch;

        -- Если удалили меньше размера батча, значит, больше старых данных нет
        EXIT WHEN rows_in_batch < batch_size;
    END LOOP;

    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;
