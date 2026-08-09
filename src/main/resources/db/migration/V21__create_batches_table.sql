CREATE TABLE batches (
    id           BIGSERIAL PRIMARY KEY,
    item_id      BIGINT    NOT NULL,
    warehouse_id BIGINT    NOT NULL,
    quantity     INTEGER   NOT NULL,
    expiry_date  TIMESTAMP NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version      BIGINT    NOT NULL DEFAULT 0,
    CONSTRAINT fk_batches_item
        FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE RESTRICT,
    CONSTRAINT fk_batches_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouses(id) ON DELETE RESTRICT,
    CONSTRAINT fk_batches_stock_scope
        FOREIGN KEY (item_id, warehouse_id)
        REFERENCES stock(item_id, warehouse_id) ON DELETE RESTRICT,
    CONSTRAINT chk_batches_quantity_non_negative CHECK (quantity >= 0),
    CONSTRAINT uq_batches_id_item_warehouse UNIQUE (id, item_id, warehouse_id)
);
