CREATE TABLE stock_alerts (
    id            BIGSERIAL    PRIMARY KEY,
    item_id       BIGINT       NOT NULL REFERENCES items(id),
    current_stock INTEGER      NOT NULL,
    min_stock     INTEGER      NOT NULL,
    triggered_by  VARCHAR(100) NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);