-- V28: Post-migration data integrity check
DO $$
    DECLARE
        old_count bigint;
        new_count bigint;
        diff_count bigint;
    BEGIN
        SELECT COUNT(*) INTO old_count FROM stock_movements_archive;
        SELECT COUNT(*) INTO new_count FROM stock_movements;

        SELECT COUNT(*) INTO diff_count
        FROM stock_movements_archive a
        WHERE NOT EXISTS (
            SELECT 1 FROM stock_movements n
            WHERE n.id = a.id AND n.created_at::timestamp = a.created_at::timestamp
        );

        IF diff_count > 0 THEN
            RAISE WARNING 'Integrity failure: % rows missing in new table', diff_count;
        ELSE
            RAISE NOTICE 'Integrity check passed.';
        END IF;
    END $$;

ANALYZE stock_movements;
ANALYZE stock_movements_archive;
