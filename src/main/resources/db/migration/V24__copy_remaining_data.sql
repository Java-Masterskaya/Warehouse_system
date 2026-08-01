-- =============================================
-- V21.3: Copy remaining data
-- =============================================

DO
$$
    DECLARE
        batch_size INT    := 1000;
        last_id    BIGINT := 0;
        row_count  INT;
        max_id     BIGINT;
        default_warehouse_id BIGINT := 1;  -- ID дефолтного склада
    BEGIN
        SELECT COALESCE(MAX(id), 0) INTO max_id FROM stock_movements_old;

        WHILE last_id < max_id
            LOOP
                -- Вставляем данные с warehouse_id = 1 (дефолтный склад)
                INSERT INTO stock_movements (
                    id,
                    item_id,
                    user_id,
                    type,
                    quantity,
                    created_at,
                    warehouse_id,
                    transfer_id
                )
                SELECT
                    id,
                    item_id,
                    user_id,
                    type,
                    quantity,
                    created_at,
                    COALESCE(warehouse_id, default_warehouse_id) AS warehouse_id,  -- Если есть warehouse_id - берем, иначе 1
                    transfer_id
                FROM stock_movements_old
                WHERE id > last_id
                ORDER BY id
                LIMIT batch_size
                ON CONFLICT (id, created_at) DO NOTHING;

                GET DIAGNOSTICS row_count = ROW_COUNT;
                EXIT WHEN row_count = 0;

                SELECT MAX(id)
                INTO last_id
                FROM stock_movements
                WHERE id > last_id - batch_size;

                IF last_id IS NULL OR row_count = 0 THEN
                    EXIT;
                END IF;
            END LOOP;

        RAISE NOTICE 'Remaining data copy completed';
    END
$$;

-- Update sequence
SELECT SETVAL('stock_movements_id_seq', COALESCE((SELECT MAX(id) FROM stock_movements), 1));