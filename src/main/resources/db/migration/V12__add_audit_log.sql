CREATE TABLE audit_log
(
    id          bigserial PRIMARY KEY,

    user_id     bigint,
    username    varchar(100),

    action      varchar(30)              NOT NULL,

    entity_type varchar(100)             NOT NULL,
    entity_id   bigint                   NOT NULL,

    old_value    JSONB,
    new_value    JSONB,

    created_at  timestamp with time zone NOT NULL DEFAULT NOW()
);