-- V24: Atomic table rename for 100% Zero-Downtime switch

DO $$
    DECLARE
        success boolean := false;
        current_lock_timeout text;
        trig_record RECORD;
        fk_record RECORD;
        archive_suffix text := to_char(now(), 'YYYYMMDD_HH24MISS');
    BEGIN
        current_lock_timeout := current_setting('lock_timeout');
        SET lock_timeout = '100ms';

        WHILE NOT success LOOP
                BEGIN
                    DROP TRIGGER IF EXISTS sync_insert_to_new_trigger ON stock_movements;
                    DROP TRIGGER IF EXISTS sync_update_to_new_trigger ON stock_movements;
                    DROP TRIGGER IF EXISTS sync_delete_to_new_trigger ON stock_movements;

                    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'stock_movements_archive') THEN
                        EXECUTE format('ALTER TABLE stock_movements_archive RENAME TO %I', 'stock_movements_archive_' || archive_suffix);
                    END IF;

                    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'stock_movements') THEN
                        ALTER TABLE stock_movements RENAME TO stock_movements_archive;
                    END IF;

                    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'stock_movements_new') THEN
                        ALTER TABLE stock_movements_new RENAME TO stock_movements;
                    END IF;

                    FOR trig_record IN
                        SELECT tgname, pg_get_triggerdef(oid) as def
                        FROM pg_trigger
                        WHERE tgrelid = 'stock_movements_archive'::regclass
                          AND tgisinternal = false
                          AND tgname NOT IN ('sync_insert_to_new_trigger', 'sync_update_to_new_trigger', 'sync_delete_to_new_trigger', 'trg_check_stock_movements_id_unique')
                        LOOP
                            EXECUTE format('DROP TRIGGER IF EXISTS %I ON stock_movements', trig_record.tgname);
                            EXECUTE replace(trig_record.def, 'stock_movements_archive', 'stock_movements');
                        END LOOP;

                    FOR fk_record IN
                        SELECT
                            conname,
                            conrelid::regclass as table_name,
                            pg_get_constraintdef(oid) as def
                        FROM pg_constraint
                        WHERE confrelid = 'stock_movements_archive'::regclass
                          AND contype = 'f'
                        LOOP
                            EXECUTE format('ALTER TABLE %I DROP CONSTRAINT IF EXISTS %I', fk_record.table_name, fk_record.conname);
                            EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I %s',
                                           fk_record.table_name,
                                           fk_record.conname,
                                           replace(fk_record.def, 'stock_movements_archive', 'stock_movements')
                                    );
                        END LOOP;

                    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'stock_movements_archive_id_seq') THEN
                        EXECUTE format('ALTER SEQUENCE stock_movements_archive_id_seq RENAME TO %I', 'stock_movements_archive_id_seq_' || archive_suffix);
                    END IF;

                    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'stock_movements_id_seq') THEN
                        ALTER SEQUENCE stock_movements_id_seq RENAME TO stock_movements_archive_id_seq;
                    END IF;

                    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'stock_movements_new_id_seq') THEN
                        ALTER SEQUENCE stock_movements_new_id_seq RENAME TO stock_movements_id_seq;
                    END IF;

                    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'stock_movements') THEN
                        PERFORM setval(
                                pg_get_serial_sequence('stock_movements', 'id'),
                                (SELECT COALESCE(MAX(id), 1) FROM stock_movements),
                                true
                                );
                    END IF;

                    IF EXISTS (SELECT 1 FROM pg_tables WHERE tablename = 'part_config' AND schemaname = 'public') THEN
                        UPDATE public.part_config
                        SET parent_table = 'public.stock_movements'
                        WHERE parent_table = 'public.stock_movements_new';
                    END IF;

                    success := true;
                EXCEPTION
                    WHEN lock_not_available THEN
                        PERFORM pg_sleep(1);
                    WHEN OTHERS THEN
                        RAISE EXCEPTION 'Atomic switch and migration failed: %', SQLERRM;
                END;
            END LOOP;

        EXECUTE 'SET lock_timeout = ' || quote_literal(current_lock_timeout);
    END $$;