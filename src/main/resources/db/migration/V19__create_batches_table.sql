CREATE TABLE batches (
    id          BIGSERIAL   PRIMARY KEY,
    item_id     BIGINT      NOT NULL REFERENCES items(id),
    quantity    INTEGER     NOT NULL CHECK (quantity > 0),
    expiry_date TIMESTAMP   NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_batches_item_expiry ON batches (item_id, expiry_date);
CREATE INDEX idx_batches_expiry ON batches (expiry_date);
