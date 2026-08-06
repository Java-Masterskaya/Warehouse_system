DROP INDEX IF EXISTS idx_items_active_name_id;
CREATE INDEX idx_items_active_name_id
    ON items (name, id)
    WHERE is_active = TRUE;

DROP INDEX IF EXISTS idx_items_active_sku_id;
CREATE INDEX idx_items_active_sku_id
    ON items (sku, id)
    WHERE is_active = TRUE;

DROP INDEX IF EXISTS idx_movements_item_created_id;
CREATE INDEX idx_movements_item_created_id
    ON stock_movements (item_id, created_at DESC, id DESC);

DROP INDEX IF EXISTS idx_movements_item_type_created_id;
CREATE INDEX idx_movements_item_type_created_id
    ON stock_movements (item_id, type, created_at DESC, id DESC);

DROP INDEX IF EXISTS idx_movements_item_id_created;
