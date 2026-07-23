-- Индекс для оптимизации запросов нахождения просроченных партий
-- Используется BatchRepository.findExpiredWithQuantityForUpdate()

CREATE INDEX IF NOT EXISTS idx_batches_expiry_date_quantity 
ON batches(expiry_date, quantity) 
WHERE quantity > 0;
