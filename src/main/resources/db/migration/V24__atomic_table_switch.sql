-- V24: Atomic table rename for 100% Zero-Downtime switch
DO $$
    DECLARE
        success boolean := false;
        current_lock_timeout text;
    BEGIN
        current_lock_timeout := current_setting('lock_timeout');
        SET lock_timeout = '100ms';

        WHILE NOT success LOOP
                BEGIN
                    DROP TRIGGER IF EXISTS sync_insert_to_new_trigger ON stock_movements;
                    DROP TRIGGER IF EXISTS sync_update_to_new_trigger ON stock_movements;
                    DROP TRIGGER IF EXISTS sync_delete_to_new_trigger ON stock_movements;

                    ALTER TABLE stock_movements RENAME TO stock_movements_archive;
                    ALTER TABLE stock_movements_new RENAME TO stock_movements;

                    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'stock_movements_id_seq') THEN
                        ALTER SEQUENCE stock_movements_id_seq RENAME TO stock_movements_archive_id_seq;
                    END IF;

                    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'stock_movements_new_id_seq') THEN
                        ALTER SEQUENCE stock_movements_new_id_seq RENAME TO stock_movements_id_seq;
                    END IF;

                    PERFORM setval(
                            pg_get_serial_sequence('stock_movements', 'id'),
                            (SELECT COALESCE(MAX(id), 1) FROM stock_movements),
                            true
                            );

                    UPDATE public.part_config
                    SET parent_table = 'public.stock_movements'
                    WHERE parent_table = 'public.stock_movements_new';

                    success := true;
                EXCEPTION
                    WHEN lock_not_available THEN
                        PERFORM pg_sleep(1);
                    WHEN OTHERS THEN
                        RAISE EXCEPTION 'Atomic switch failed: %', SQLERRM;
                END;
            END LOOP;

        EXECUTE 'SET lock_timeout = ' || quote_literal(current_lock_timeout);
    END $$;
