ALTER TABLE stock_movements
ADD COLUMN batch_id BIGINT REFERENCES batches(id);
