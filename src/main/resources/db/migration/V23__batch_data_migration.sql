-- V23: Batch data migration to minimize I/O impact
DO $$
    DECLARE
        batch_size INT := 10000;
        last_id BIGINT := 0;
        row_count INT;
        max_id BIGINT;
        total_rows BIGINT := 0;
    BEGIN
        SELECT COALESCE(MAX(id), 0) INTO max_id FROM stock_movements;

        CREATE INDEX IF NOT EXISTS idx_stock_movements_new_id ON stock_movements_new(id);

        WHILE last_id < max_id LOOP
                INSERT INTO stock_movements_new (
                    id, item_id, user_id, type, quantity,
                    created_at, warehouse_id, transfer_id
                )
                SELECT
                    id, item_id, user_id, type, quantity,
                    created_at, warehouse_id, transfer_id
                FROM stock_movements
                WHERE id > last_id
                ORDER BY id
                LIMIT batch_size
                ON CONFLICT (id, created_at) DO NOTHING;

                GET DIAGNOSTICS row_count = ROW_COUNT;
                total_rows := total_rows + row_count;
                EXIT WHEN row_count = 0;

                SELECT MAX(id) INTO last_id FROM stock_movements_new WHERE id > last_id - batch_size;

                IF last_id IS NULL OR row_count = 0 THEN EXIT; END IF;

                PERFORM PG_SLEEP(0.05); -- Throttle to reduce load
            END LOOP;
    END $$;
