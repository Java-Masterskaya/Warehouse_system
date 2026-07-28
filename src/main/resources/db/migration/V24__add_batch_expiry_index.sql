CREATE INDEX idx_batches_item_warehouse_fefo
    ON batches(item_id, warehouse_id, expiry_date, id)
    WHERE quantity > 0;

CREATE INDEX idx_batches_expiry_positive
    ON batches(expiry_date, item_id, warehouse_id)
    WHERE quantity > 0;

CREATE INDEX idx_stock_movements_batch_id
    ON stock_movements(batch_id)
    WHERE batch_id IS NOT NULL;
