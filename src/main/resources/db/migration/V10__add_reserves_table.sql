CREATE TABLE reserves
(
    id          BIGSERIAL PRIMARY KEY,
    stock_id    BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    quantity    INT         NOT NULL,
    status      VARCHAR(20) NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    expired_at  TIMESTAMP   NOT NULL,
    CONSTRAINT fk_reserves_stock
        FOREIGN KEY (stock_id) REFERENCES stock(id),
    CONSTRAINT fk_reserves_user
        FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Used by reservation availability calculation.
CREATE INDEX idx_reserves_stock_status
    ON reserves (stock_id, status);

-- Used by reservation expiration scheduler.
CREATE INDEX idx_reserves_status_expired_at
    ON reserves (status, expired_at);