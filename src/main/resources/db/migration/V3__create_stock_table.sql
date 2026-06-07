CREATE TABLE stock (
    id         BIGSERIAL PRIMARY KEY,
    item_id    BIGINT    NOT NULL UNIQUE REFERENCES items(id),
    quantity   INTEGER   NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);