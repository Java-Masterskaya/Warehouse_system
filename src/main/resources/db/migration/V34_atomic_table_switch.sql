-- V24: Atomic table rename for 100% Zero-Downtime switch

DO
$$
    DECLARE
        success              BOOLEAN := FALSE;
        current_lock_timeout TEXT;
    BEGIN
        current_lock_timeout := CURRENT_SETTING(
                'lock_timeout'
                                );

        SET lock_timeout = '100ms';

        WHILE NOT success
            LOOP
                BEGIN
                    DROP TRIGGER IF EXISTS sync_insert_to_new_trigger ON stock_movements;
                    DROP TRIGGER IF EXISTS sync_update_to_new_trigger ON stock_movements;
                    DROP TRIGGER IF EXISTS sync_delete_to_new_trigger ON stock_movements;

                    ALTER TABLE stock_movements
                        RENAME TO stock_movements_archive;
                    ALTER TABLE stock_movements_new
                        RENAME TO stock_movements;

                    IF EXISTS (SELECT 1
                               FROM pg_class
                               WHERE relname = 'stock_movements_id_seq') THEN
                        ALTER SEQUENCE stock_movements_id_seq RENAME TO stock_movements_archive_id_seq;
                    END IF;

                    IF EXISTS (SELECT 1
                               FROM pg_class
                               WHERE relname = 'stock_movements_new_id_seq') THEN
                        ALTER SEQUENCE stock_movements_new_id_seq RENAME TO stock_movements_id_seq;
                    END IF;

                    PERFORM SETVAL(
                            PG_GET_SERIAL_SEQUENCE('stock_movements', 'id'),
                            (SELECT COALESCE(MAX(id), 1)
                             FROM stock_movements),
                            TRUE
                            );

                    UPDATE
                        public.part_config
                    SET parent_table = 'public.stock_movements'
                    WHERE parent_table = 'public.stock_movements_new';

                    success := TRUE;
                EXCEPTION
                    WHEN lock_not_available THEN
                        PERFORM PG_SLEEP(1);
                    WHEN OTHERS THEN
                        RAISE EXCEPTION 'Atomic switch failed: %', sqlerrm;
                END;
            END LOOP;

        EXECUTE 'SET lock_timeout = ' || QUOTE_LITERAL(current_lock_timeout);
    END
$$;
