-- V33: Batch data migration to minimize I/O impact and avoid long transactions

CREATE OR REPLACE FUNCTION migrate_stock_movements_batch(
    p_batch_size INT,
    p_last_id INOUT BIGINT,
    p_max_id BIGINT
) RETURNS TABLE(rows_migrated INT, new_last_id BIGINT) AS $$
DECLARE
    current_rows_migrated INT;
BEGIN
    INSERT INTO stock_movements_new (
        id, item_id, user_id, type, quantity,
        created_at, warehouse_id, transfer_id
    )
    SELECT
        id, item_id, user_id, type, quantity,
        created_at, warehouse_id, transfer_id
    FROM stock_movements
    WHERE id > p_last_id AND id <= p_max_id
    ORDER BY id
    LIMIT p_batch_size
    ON CONFLICT (id, created_at) DO NOTHING;

    GET DIAGNOSTICS current_rows_migrated = ROW_COUNT;
    p_last_id := (SELECT COALESCE(MAX(id), p_last_id) FROM stock_movements_new WHERE id > p_last_id);

    RETURN QUERY SELECT current_rows_migrated, p_last_id;
END;
$$ LANGUAGE plpgsql;

DO $$
    DECLARE
        batch_size INT := 10000;
        last_id BIGINT := 0;
        max_id BIGINT;
        total_rows_migrated BIGINT := 0;
        rows_in_batch INT;
        new_last_id_in_batch BIGINT;
        start_time TIMESTAMP;
        end_time TIMESTAMP;
    BEGIN
        RAISE NOTICE 'Starting batch data migration...';
        start_time := clock_timestamp();

        SELECT COALESCE(MAX(id), 0) INTO max_id FROM stock_movements;

        CREATE INDEX IF NOT EXISTS idx_stock_movements_id_temp ON stock_movements(id);

        WHILE last_id < max_id LOOP
                SELECT * INTO rows_in_batch, new_last_id_in_batch
                FROM migrate_stock_movements_batch(batch_size, last_id, max_id);

                total_rows_migrated := total_rows_migrated + rows_in_batch;

                IF rows_in_batch = 0 THEN
                    EXIT;
                END IF;

                last_id := new_last_id_in_batch;

                RAISE NOTICE 'Migrated % rows. Total: %. Last ID: %', rows_in_batch, total_rows_migrated, last_id;

                PERFORM pg_sleep(0.05);

            END LOOP;

        DROP INDEX IF EXISTS idx_stock_movements_id_temp;

        DROP FUNCTION migrate_stock_movements_batch(INT, BIGINT, BIGINT);

        end_time := clock_timestamp();
        RAISE NOTICE 'Batch data migration finished. Total rows migrated: %. Duration: %', total_rows_migrated, (end_time - start_time);
    END $$;
