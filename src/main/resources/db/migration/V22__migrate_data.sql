-- =============================================
-- V22: Migrate existing data to partitioned table
-- =============================================

DO
$$
    DECLARE
        batch_size INT    := 10000;
        last_id    BIGINT := 0;
        row_count  INT;
        max_id     BIGINT;
    BEGIN
        SELECT COALESCE(MAX(id), 0) INTO max_id FROM stock_movements;

        RAISE NOTICE 'Starting migration of up to % rows', max_id;

        WHILE last_id < max_id
            LOOP
                INSERT INTO stock_movements_new (id, item_id, user_id, type, quantity, created_at, warehouse_id, transfer_id)
                SELECT id, item_id, user_id, type, quantity, created_at, warehouse_id, transfer_id
                FROM stock_movements
                WHERE id > last_id
                ORDER BY id
                LIMIT batch_size;

                GET DIAGNOSTICS row_count = ROW_COUNT;
                EXIT WHEN row_count = 0;

                SELECT MAX(id)
                INTO last_id
                FROM stock_movements_new
                WHERE id > last_id - batch_size;

                IF last_id IS NULL OR row_count = 0 THEN
                    EXIT;
                END IF;

                PERFORM PG_SLEEP(0.1);
                RAISE NOTICE 'Migrated up to ID: %, rows: %', last_id, row_count;
            END LOOP;

        RAISE NOTICE 'Migration completed, last_id: %', last_id;
    END
$$;