-- V33: Batch data migration to minimize I/O impact and avoid long transactions

CREATE OR REPLACE PROCEDURE migrate_stock_movements_batch_controlled(
    p_batch_size INT DEFAULT 10000
)
    LANGUAGE plpgsql
AS
$$
DECLARE
    last_id              BIGINT := 0;
    max_id               BIGINT;
    total_rows_migrated  BIGINT := 0;
    rows_in_batch        INT;
    new_last_id_in_batch BIGINT;
    start_time           TIMESTAMP;
    end_time             TIMESTAMP;
    batch_counter        INT    := 0;
BEGIN
    RAISE NOTICE 'Начинаем пакетную миграцию данных...';
    start_time := CLOCK_TIMESTAMP();

    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = 'stock_movements' AND relkind = 'r') THEN
        RAISE NOTICE 'Таблица stock_movements не существует, пропускаем миграцию';
        RETURN;
    END IF;

    SELECT COALESCE(MAX(id), 0) INTO max_id FROM stock_movements;

    IF max_id = 0 THEN
        RAISE NOTICE 'Нет данных для миграции в stock_movements';
        RETURN;
    END IF;

    CREATE INDEX IF NOT EXISTS idx_stock_movements_id_temp ON stock_movements (id);

    COMMIT;

    WHILE last_id < max_id
        LOOP
            BEGIN
                INSERT INTO stock_movements_new (id, item_id, user_id, type, quantity,
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
                FROM stock_movements
                WHERE id > last_id
                  AND id <= max_id
                ORDER BY id
                LIMIT p_batch_size
                ON CONFLICT (id, created_at) DO NOTHING;

                GET DIAGNOSTICS rows_in_batch = ROW_COUNT;

                SELECT COALESCE(MAX(id), last_id)
                INTO new_last_id_in_batch
                FROM (SELECT id
                      FROM stock_movements
                      WHERE id > last_id
                        AND id <= max_id
                      ORDER BY id
                      LIMIT p_batch_size) AS batch_range;

                total_rows_migrated := total_rows_migrated + rows_in_batch;
                last_id := new_last_id_in_batch;
                batch_counter := batch_counter + 1;

                RAISE NOTICE 'Батч %. Перенесено % строк. Всего: %. Последний ID: %',
                    batch_counter, rows_in_batch, total_rows_migrated, last_id;

                COMMIT;

                PERFORM PG_SLEEP(0.05);

            EXCEPTION
                WHEN OTHERS THEN
                    RAISE WARNING 'Ошибка в батче %: %. Откатываем...', batch_counter, sqlerrm;
                    ROLLBACK;
                    RAISE;
            END;
        END LOOP;

    DROP INDEX IF EXISTS idx_stock_movements_id_temp;
    COMMIT;

    end_time := CLOCK_TIMESTAMP();
    RAISE NOTICE 'Миграция завершена. Всего перенесено строк: %. Длительность: %',
        total_rows_migrated, (end_time - start_time);
END;
$$;

CALL migrate_stock_movements_batch_controlled(10000);

DROP PROCEDURE IF EXISTS migrate_stock_movements_batch_controlled(INT);