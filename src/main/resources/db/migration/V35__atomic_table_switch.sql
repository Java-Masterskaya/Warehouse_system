-- V35: Atomic table rename for 100% Zero-Downtime switch

DO
$$
    DECLARE
        success              BOOLEAN := FALSE;
        current_lock_timeout TEXT;
        trig_record          RECORD;
        fk_record            RECORD;
        archive_suffix       TEXT    := TO_CHAR(NOW(), 'YYYYMMDD_HH24MISS');
    BEGIN
        current_lock_timeout := CURRENT_SETTING('lock_timeout');
        SET lock_timeout = '100ms';

        WHILE NOT success
            LOOP
                BEGIN
                    DROP TRIGGER IF EXISTS sync_insert_to_new_trigger ON stock_movements;
                    DROP TRIGGER IF EXISTS sync_update_to_new_trigger ON stock_movements;
                    DROP TRIGGER IF EXISTS sync_delete_to_new_trigger ON stock_movements;

                    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'stock_movements_archive') THEN
                        EXECUTE FORMAT('ALTER TABLE stock_movements_archive RENAME TO %I',
                                       'stock_movements_archive_' || archive_suffix);
                    END IF;

                    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'stock_movements') THEN
                        ALTER TABLE stock_movements
                            RENAME TO stock_movements_archive;
                    END IF;

                    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'stock_movements_new') THEN
                        ALTER TABLE stock_movements_new
                            RENAME TO stock_movements;
                    END IF;

                    FOR trig_record IN
                        SELECT tgname, PG_GET_TRIGGERDEF(oid) AS def
                        FROM pg_trigger
                        WHERE tgrelid = 'stock_movements_archive'::REGCLASS
                          AND tgisinternal = FALSE
                          AND tgname IN
                              ('sync_insert_to_new_trigger', 'sync_update_to_new_trigger', 'sync_delete_to_new_trigger')
                        LOOP
                            EXECUTE FORMAT('DROP TRIGGER IF EXISTS %I ON stock_movements', trig_record.tgname);
                            EXECUTE REPLACE(trig_record.def, 'stock_movements_archive', 'stock_movements');
                        END LOOP;

                    FOR fk_record IN
                        SELECT conname,
                               conrelid::REGCLASS        AS table_name,
                               PG_GET_CONSTRAINTDEF(oid) AS def
                        FROM pg_constraint
                        WHERE confrelid = 'stock_movements_archive'::REGCLASS
                          AND contype = 'f'
                        LOOP
                            EXECUTE FORMAT('ALTER TABLE %I DROP CONSTRAINT IF EXISTS %I', fk_record.table_name,
                                           fk_record.conname);
                            EXECUTE FORMAT('ALTER TABLE %I ADD CONSTRAINT %I %s',
                                           fk_record.table_name,
                                           fk_record.conname,
                                           REPLACE(fk_record.def, 'stock_movements_archive', 'stock_movements')
                                    );
                        END LOOP;

                    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'stock_movements_archive_id_seq') THEN
                        EXECUTE FORMAT('ALTER SEQUENCE stock_movements_archive_id_seq RENAME TO %I',
                                       'stock_movements_archive_id_seq_' || archive_suffix);
                    END IF;

                    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'stock_movements_id_seq') THEN
                        ALTER SEQUENCE stock_movements_id_seq RENAME TO stock_movements_archive_id_seq;
                    END IF;

                    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'stock_movements_new_id_seq') THEN
                        ALTER SEQUENCE stock_movements_new_id_seq RENAME TO stock_movements_id_seq;
                    END IF;

                    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'stock_movements') THEN
                        PERFORM SETVAL(
                                PG_GET_SERIAL_SEQUENCE('stock_movements', 'id'),
                                (SELECT COALESCE(MAX(id), 1) FROM stock_movements),
                                TRUE
                                );
                    END IF;

                    IF EXISTS (SELECT 1 FROM pg_tables WHERE tablename = 'part_config' AND schemaname = 'public') THEN
                        UPDATE public.part_config
                        SET parent_table = 'public.stock_movements'
                        WHERE parent_table = 'public.stock_movements_new';
                    END IF;

                    success := TRUE;
                EXCEPTION
                    WHEN lock_not_available THEN
                        PERFORM PG_SLEEP(1);
                    WHEN OTHERS THEN
                        RAISE EXCEPTION 'Atomic switch and migration failed: %', sqlerrm;
                END;
            END LOOP;

        EXECUTE 'SET lock_timeout = ' || QUOTE_LITERAL(current_lock_timeout);
    END
$$;