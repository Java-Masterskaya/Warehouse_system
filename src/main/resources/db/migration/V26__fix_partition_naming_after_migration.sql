-- =============================================
-- V26: Rename partitions to match new table name
-- =============================================

DO $$
    DECLARE
        r RECORD;
        new_name TEXT;
    BEGIN
        FOR r IN
            SELECT tablename
            FROM pg_tables
            WHERE tablename LIKE 'stock_movements_new_p%'
              AND schemaname = 'public'
            LOOP
                -- Меняем stock_movements_new_p2024_01 → stock_movements_p2024_01
                new_name := REPLACE(r.tablename, 'stock_movements_new', 'stock_movements');

                EXECUTE format('ALTER TABLE %I RENAME TO %I', r.tablename, new_name);
                RAISE NOTICE 'Renamed: % → %', r.tablename, new_name;
            END LOOP;
    END $$;

-- Проверяем, что партиции доступны
SELECT tablename FROM pg_tables
WHERE tablename LIKE 'stock_movements_p%'
ORDER BY tablename;