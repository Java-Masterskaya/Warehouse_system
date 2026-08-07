-- V36: Post-migration data integrity check
DO
$$
    DECLARE
        old_count  BIGINT;
        new_count  BIGINT;
        diff_count BIGINT;
    BEGIN
        SELECT COUNT(*) INTO old_count FROM stock_movements_archive;
        SELECT COUNT(*) INTO new_count FROM stock_movements;

        SELECT COUNT(*)
        INTO diff_count
        FROM stock_movements_archive a
        WHERE NOT EXISTS (SELECT 1
                          FROM stock_movements n
                          WHERE n.id = a.id
                            AND CAST(n.created_at AS TIMESTAMP) = CAST(a.created_at AS TIMESTAMP));

        IF diff_count > 0 THEN
            RAISE EXCEPTION 'Integrity failure: % rows missing in new table', diff_count;
        ELSE
            RAISE NOTICE 'Integrity check passed: All expected rows found.';
        END IF;

    END
$$;

ANALYZE stock_movements;
ANALYZE stock_movements_archive;
