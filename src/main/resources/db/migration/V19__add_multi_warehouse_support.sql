CREATE TABLE warehouses
(
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    is_default BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_warehouses_name_ci
    ON warehouses (LOWER(name));

CREATE UNIQUE INDEX uq_warehouses_single_default
    ON warehouses (is_default)
    WHERE is_default = TRUE;

INSERT INTO warehouses (id, name, is_default)
VALUES (1, 'Default Warehouse', TRUE);

SELECT setval(
    pg_get_serial_sequence('warehouses', 'id'),
    (SELECT MAX(id) FROM warehouses),
    TRUE
);

ALTER TABLE stock
    ADD COLUMN warehouse_id BIGINT DEFAULT 1;

UPDATE stock
SET warehouse_id = 1
WHERE warehouse_id IS NULL;

ALTER TABLE stock
    ALTER COLUMN warehouse_id DROP DEFAULT,
    ALTER COLUMN warehouse_id SET NOT NULL,
    ADD CONSTRAINT fk_stock_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id) ON DELETE RESTRICT;

ALTER TABLE stock
    DROP CONSTRAINT stock_item_id_key;

ALTER TABLE stock
    ADD CONSTRAINT uk_stock_item_warehouse UNIQUE (item_id, warehouse_id);

CREATE INDEX idx_stock_warehouse_id
    ON stock (warehouse_id);

ALTER TABLE stock_movements
    ADD COLUMN warehouse_id BIGINT DEFAULT 1,
    ADD COLUMN transfer_id UUID;

UPDATE stock_movements
SET warehouse_id = 1
WHERE warehouse_id IS NULL;

ALTER TABLE stock_movements
    ALTER COLUMN warehouse_id DROP DEFAULT,
    ALTER COLUMN warehouse_id SET NOT NULL,
    ADD CONSTRAINT fk_stock_movements_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id) ON DELETE RESTRICT;

ALTER TABLE stock_movements
    DROP CONSTRAINT IF EXISTS stock_movements_quantity_check,
    DROP CONSTRAINT IF EXISTS stock_movements_type_check;

ALTER TABLE stock_movements
    ADD CONSTRAINT stock_movements_quantity_check
        CHECK (
            (type IN ('RECEIVE', 'WRITE_OFF', 'TRANSFER_OUT', 'TRANSFER_IN') AND quantity > 0)
            OR (type = 'ADJUSTMENT' AND quantity <> 0)
        ),
    ADD CONSTRAINT stock_movements_type_check
        CHECK (type IN ('RECEIVE', 'WRITE_OFF', 'ADJUSTMENT', 'TRANSFER_OUT', 'TRANSFER_IN'));

CREATE INDEX idx_movements_item_warehouse_created
    ON stock_movements (item_id, warehouse_id, created_at DESC);

CREATE INDEX idx_stock_movements_transfer_id
    ON stock_movements (transfer_id)
    WHERE transfer_id IS NOT NULL;
