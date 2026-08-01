-- noinspection SqlResolveForFile

-- =============================================
-- V21: Create partitioned table for stock_movements
-- =============================================

-- 1. Create partitioned table
CREATE TABLE stock_movements_new
(
    id           BIGSERIAL,
    item_id      BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL,
    type         VARCHAR(20) NOT NULL,
    quantity     INTEGER     NOT NULL,
    warehouse_id BIGINT      NOT NULL,
    transfer_id  UUID,
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id, created_at),
    CONSTRAINT fk_stock_movements_new_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_movements_new_item
        FOREIGN KEY (item_id) REFERENCES items (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_movements_new_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT stock_movements_new_quantity_check
        CHECK (
            (type IN ('RECEIVE', 'WRITE_OFF', 'TRANSFER_OUT', 'TRANSFER_IN') AND quantity > 0)
                OR (type = 'ADJUSTMENT' AND quantity <> 0)
            )
) PARTITION BY RANGE (created_at);

-- 2. Sync trigger for new inserts during migration
CREATE OR REPLACE FUNCTION sync_to_new_movements()
    RETURNS TRIGGER AS
$$
BEGIN
    INSERT INTO stock_movements_new (id, item_id, user_id, type, quantity, created_at, warehouse_id, transfer_id)
    VALUES (new.id,
            new.item_id,
            new.user_id,
            new.type,
            new.quantity,
            new.created_at,
            new.warehouse_id,
            new.transfer_id)
    ON CONFLICT (id, created_at) DO NOTHING;
    RETURN new;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER sync_to_new_movements_trigger
    AFTER INSERT
    ON stock_movements
    FOR EACH ROW
EXECUTE FUNCTION sync_to_new_movements();