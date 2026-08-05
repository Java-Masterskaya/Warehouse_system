-- V39: Archive management after successful verification
DO $$
    DECLARE
        old_count bigint;
    BEGIN
        SELECT COUNT(*) INTO old_count FROM stock_movements_archive;

        IF old_count = 0 THEN
            DROP TABLE IF EXISTS stock_movements_archive CASCADE;
        ELSE
            ALTER TABLE stock_movements_archive RENAME TO stock_movements_archive_backup;
        END IF;
    END $$;

SELECT public.run_maintenance('public.stock_movements');
