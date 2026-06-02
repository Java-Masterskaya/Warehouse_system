CREATE TABLE stock_movements (
    id         BIGSERIAL   PRIMARY KEY,
    item_id    BIGINT      NOT NULL REFERENCES items(id),
    user_id    BIGINT      NOT NULL REFERENCES users(id),
    type       VARCHAR(20) NOT NULL CHECK (type IN ('RECEIVE', 'WRITE_OFF')),
    quantity   INTEGER     NOT NULL CHECK (quantity > 0),
    created_at TIMESTAMP   NOT NULL DEFAULT NOW()
);
