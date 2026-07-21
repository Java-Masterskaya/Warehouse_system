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
