ALTER TABLE stock_movements
ADD COLUMN batch_id BIGINT;

ALTER TABLE stock_movements
ADD CONSTRAINT fk_stock_movements_batch_scope
FOREIGN KEY (batch_id, item_id, warehouse_id)
REFERENCES batches(id, item_id, warehouse_id)
ON DELETE RESTRICT;
