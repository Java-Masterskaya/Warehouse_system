CREATE TABLE suppliers (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE purchase_orders (
    id          BIGSERIAL PRIMARY KEY,
    supplier_id BIGINT      NOT NULL REFERENCES suppliers (id),
    status      VARCHAR(30) NOT NULL CHECK (status IN ('DRAFT', 'PLACED', 'PARTIALLY_RECEIVED', 'RECEIVED')),
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE TABLE purchase_order_items (
    id                BIGSERIAL PRIMARY KEY,
    purchase_order_id BIGINT  NOT NULL REFERENCES purchase_orders (id),
    item_id           BIGINT  NOT NULL REFERENCES items (id),
    ordered_qty       INTEGER NOT NULL CHECK (ordered_qty > 0),
    received_qty      INTEGER NOT NULL DEFAULT 0 CHECK (received_qty >= 0),
    CONSTRAINT chk_purchase_order_items_received_not_more_than_ordered
        CHECK (received_qty <= ordered_qty)
);

CREATE INDEX idx_purchase_orders_supplier_id ON purchase_orders (supplier_id);
CREATE INDEX idx_purchase_order_items_po_id ON purchase_order_items (purchase_order_id);
CREATE INDEX idx_purchase_order_items_item_id ON purchase_order_items (item_id);