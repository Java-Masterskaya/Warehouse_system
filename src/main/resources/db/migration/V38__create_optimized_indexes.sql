-- V38: Create optimized indexes

DO
$$
    BEGIN
        IF EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_stock_movements_item_id') THEN
            ALTER INDEX idx_stock_movements_item_id RENAME TO idx_stock_movements_archive_item_id;
        END IF;
        IF EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_stock_movements_user_id') THEN
            ALTER INDEX idx_stock_movements_user_id RENAME TO idx_stock_movements_archive_user_id;
        END IF;
        IF EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_stock_movements_warehouse_id') THEN
            ALTER INDEX idx_stock_movements_warehouse_id RENAME TO idx_stock_movements_archive_warehouse_id;
        END IF;
        IF EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_stock_movements_created_at') THEN
            ALTER INDEX idx_stock_movements_created_at RENAME TO idx_stock_movements_archive_created_at;
        END IF;
        IF EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_stock_movements_item_date') THEN
            ALTER INDEX idx_stock_movements_item_date RENAME TO idx_stock_movements_archive_item_date;
        END IF;
        IF EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_stock_movements_warehouse_date') THEN
            ALTER INDEX idx_stock_movements_warehouse_date RENAME TO idx_stock_movements_archive_warehouse_date;
        END IF;
    END
$$;

CREATE INDEX IF NOT EXISTS idx_stock_movements_item_id
    ON stock_movements (item_id);
CREATE INDEX IF NOT EXISTS idx_stock_movements_user_id
    ON stock_movements (user_id);
CREATE INDEX IF NOT EXISTS idx_stock_movements_warehouse_id
    ON stock_movements (warehouse_id);
CREATE INDEX IF NOT EXISTS idx_stock_movements_created_at
    ON stock_movements (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_stock_movements_item_date
    ON stock_movements (item_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_stock_movements_warehouse_date
    ON stock_movements (warehouse_id, created_at DESC);
