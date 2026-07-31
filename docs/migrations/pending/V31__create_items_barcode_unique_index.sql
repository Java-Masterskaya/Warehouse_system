-- ВНИМАНИЕ: НЕ В db/migration/ - копировать вместе с V30, V32.
-- Требует spring.flyway.postgresql.transactional-lock: false (application.yml),
-- иначе Flyway зависнет на старте (advisory lock держит открытую транзакцию).

CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uk_items_barcode ON items (barcode);
