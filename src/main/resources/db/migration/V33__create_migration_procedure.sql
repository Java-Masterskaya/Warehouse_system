-- V33: Создание процедуры для пакетной миграции
CREATE OR REPLACE PROCEDURE migrate_stock_movements_batch_controlled(
    p_batch_size INT DEFAULT 10000,
    p_sleep_ms INT DEFAULT 50
)
    LANGUAGE plpgsql
AS
$$
DECLARE
    v_last_id             BIGINT  := 0;
    v_total_rows_migrated BIGINT  := 0;
    v_rows_in_batch       INT;
    v_batch_counter       INT     := 0;
    v_start_time          TIMESTAMP;
    v_batch_start_time    TIMESTAMP;
    v_batch_end_time      TIMESTAMP;
    v_has_more_rows       BOOLEAN := TRUE;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = 'stock_movements' AND relkind = 'r') THEN
        RAISE NOTICE 'Таблица stock_movements не существует, пропускаем миграцию';
        RETURN;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM stock_movements LIMIT 1) THEN
        RAISE NOTICE 'Нет данных для миграции в stock_movements';
        RETURN;
    END IF;

    SELECT COALESCE(MAX(id), 0) INTO v_last_id FROM stock_movements_new;

    RAISE NOTICE 'Начинаем пакетную миграцию данных. Стартуем с ID: %', v_last_id;
    v_start_time := CLOCK_TIMESTAMP();

    WHILE v_has_more_rows
        LOOP
            v_batch_start_time := CLOCK_TIMESTAMP();
            v_batch_counter := v_batch_counter + 1;

            WITH batch_data AS (SELECT id,
                                       item_id,
                                       user_id,
                                       type,
                                       quantity,
                                       created_at,
                                       warehouse_id,
                                       transfer_id,
                                       batch_id
                                FROM stock_movements
                                WHERE id > v_last_id
                                ORDER BY id
                                LIMIT p_batch_size)
            INSERT
            INTO stock_movements_new (id, item_id, user_id, type, quantity,
                                      created_at, warehouse_id, transfer_id, batch_id)
            SELECT id,
                   item_id,
                   user_id,
                   type,
                   quantity,
                   created_at,
                   warehouse_id,
                   transfer_id,
                   batch_id
            FROM batch_data
            ON CONFLICT (id, created_at) DO NOTHING;

            GET DIAGNOSTICS v_rows_in_batch = ROW_COUNT;

            SELECT MAX(id)
            INTO v_last_id
            FROM (SELECT id
                  FROM stock_movements
                  WHERE id > v_last_id
                  ORDER BY id
                  LIMIT p_batch_size) t;

            v_total_rows_migrated := v_total_rows_migrated + v_rows_in_batch;

            v_batch_end_time := CLOCK_TIMESTAMP();
            RAISE NOTICE 'Батч %: перенесено % строк, всего: %, последний ID: %, время: % ms',
                v_batch_counter, v_rows_in_batch, v_total_rows_migrated,
                v_last_id, EXTRACT(EPOCH FROM (v_batch_end_time - v_batch_start_time)) * 1000;

            COMMIT;

            IF p_sleep_ms > 0 THEN
                PERFORM PG_SLEEP(p_sleep_ms::FLOAT / 1000);
            END IF;

            SELECT COUNT(*) > 0
            INTO v_has_more_rows
            FROM stock_movements
            WHERE id > v_last_id;
        END LOOP;

    RAISE NOTICE '✅ Миграция успешно завершена. Всего перенесено строк: %, время: % сек',
        v_total_rows_migrated,
        EXTRACT(EPOCH FROM (CLOCK_TIMESTAMP() - v_start_time));
END;
$$;