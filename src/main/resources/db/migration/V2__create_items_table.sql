CREATE TABLE items (
    id         BIGSERIAL    PRIMARY KEY,
    sku        VARCHAR(100) NOT NULL UNIQUE,
    name       VARCHAR(255) NOT NULL,
    category   VARCHAR(100) NOT NULL,
    min_stock  INTEGER      NOT NULL DEFAULT 0,
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW()
);