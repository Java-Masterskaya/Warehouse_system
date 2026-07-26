-- noinspection SqlResolveForFile

-- =============================================
-- V21: Migrate stock_movements to partitioned table
-- =============================================
-- Description:
--   Transforms stock_movements into a range-partitioned table by created_at (monthly).
--   Implements zero-downtime migration using batch processing with sync trigger.
--
-- Strategy:
--   1. Create new partitioned table
--   2. Create partitions for existing data (2024-2027)
--   3. Create sync trigger to replicate new inserts during migration
--   4. Migrate existing data in batches (10,000 rows)
--   5. Switch tables with minimal locking (milliseconds)
--   6. Copy remaining data in small batches (1,000 rows)
--   7. Configure pg_partman for automatic partition management
-- =============================================

-- Enable pg_partman extension
-- noinspection SqlResolve
CREATE EXTENSION IF NOT EXISTS pg_partman;

-- =============================================
-- 1. Create partitioned table
-- =============================================
CREATE TABLE stock_movements_new (
                                     id         BIGSERIAL,
                                     item_id    BIGINT      NOT NULL,
                                     user_id    BIGINT      NOT NULL,
                                     type       VARCHAR(20) NOT NULL CHECK (type IN ('RECEIVE', 'WRITE_OFF')),
                                     quantity   INTEGER     NOT NULL CHECK (quantity > 0),
                                     created_at TIMESTAMP   NOT NULL DEFAULT NOW(),
                                     PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- =============================================
-- 2. Create partitions for existing data
-- =============================================
DO $$
    DECLARE
        min_date DATE;
        max_date DATE;
        start_date DATE;
        end_date DATE := '2027-01-01';
        current_date DATE;
    BEGIN
        SELECT MIN(DATE(created_at)), MAX(DATE(created_at))
        INTO min_date, max_date
        FROM stock_movements;

        IF min_date IS NULL THEN
            min_date := DATE_TRUNC('month', CURRENT_DATE);
        ELSE
            min_date := DATE_TRUNC('month', min_date);
        END IF;

                current_date := min_date;
        WHILE current_date < end_date LOOP
                EXECUTE format(
                        'CREATE TABLE IF NOT EXISTS stock_movements_y%s_m%s
                         PARTITION OF stock_movements_new
                         FOR VALUES FROM (%L) TO (%L)',
                        TO_CHAR(current_date, 'YYYY'),
                        TO_CHAR(current_date, 'MM'),
                        current_date,
                        current_date + INTERVAL '1 month'
                        );
                        current_date := current_date + INTERVAL '1 month';
            END LOOP;
    END $$;

-- =============================================
-- 3. Create indexes on partitions
-- =============================================
DO $$
    DECLARE
        partition_name TEXT;
    BEGIN
        FOR partition_name IN
            SELECT tablename
            FROM pg_tables
            WHERE schemaname = 'public'
              AND tablename LIKE 'stock_movements_y%'
            LOOP
                EXECUTE format(
                        'CREATE INDEX %s_item_id_idx ON %s (item_id)',
                        partition_name,
                        partition_name
                        );

                EXECUTE format(
                        'CREATE INDEX %s_created_at_idx ON %s (created_at)',
                        partition_name,
                        partition_name
                        );
            END LOOP;
    END $$;

-- =============================================
-- 4. Setup sync trigger for new inserts during migration
-- =============================================
-- This ensures no data is lost during batch migration
CREATE OR REPLACE FUNCTION sync_to_new_movements()
    RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO stock_movements_new (id, item_id, user_id, type, quantity, created_at)
    VALUES (NEW.id, NEW.item_id, NEW.user_id, NEW.type, NEW.quantity, NEW.created_at)
    ON CONFLICT (id, created_at) DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER sync_to_new_movements_trigger
    AFTER INSERT ON stock_movements
    FOR EACH ROW EXECUTE FUNCTION sync_to_new_movements();

-- =============================================
-- 5. Batch migration (zero downtime)
-- =============================================
-- Migrates existing data in batches of 10,000 rows
DO $$
    DECLARE
        batch_size INT := 10000;
        last_id BIGINT := 0;
        row_count INT;
    BEGIN
        LOOP
            INSERT INTO stock_movements_new (id, item_id, user_id, type, quantity, created_at)
            SELECT id, item_id, user_id, type, quantity, created_at
            FROM stock_movements
            WHERE id > last_id
            ORDER BY id
            LIMIT batch_size;

            GET DIAGNOSTICS row_count = ROW_COUNT;
            EXIT WHEN row_count = 0;

            last_id := last_id + batch_size;
            COMMIT;

            -- Small delay to prevent resource contention
            PERFORM pg_sleep(0.1);
        END LOOP;
    END $$;

-- =============================================
-- 6. Switch tables (sub-second lock)
-- =============================================
-- Rename tables and sequence, remove trigger
ALTER TABLE stock_movements RENAME TO stock_movements_old;
ALTER TABLE stock_movements_new RENAME TO stock_movements;
ALTER SEQUENCE stock_movements_new_id_seq RENAME TO stock_movements_id_seq;

DROP TRIGGER sync_to_new_movements_trigger ON stock_movements_old;

-- =============================================
-- 7. Copy remaining data (small batches)
-- =============================================
-- Only a few rows should be here (inserted during migration)
DO $$
    DECLARE
        batch_size INT := 1000;
        last_id BIGINT := 0;
        row_count INT;
    BEGIN
        LOOP
            INSERT INTO stock_movements (id, item_id, user_id, type, quantity, created_at)
            SELECT id, item_id, user_id, type, quantity, created_at
            FROM stock_movements_old
            WHERE id > last_id
            ORDER BY id
            LIMIT batch_size
            ON CONFLICT (id, created_at) DO NOTHING;

            GET DIAGNOSTICS row_count = ROW_COUNT;
            EXIT WHEN row_count = 0;

            last_id := last_id + batch_size;
            COMMIT;
        END LOOP;
    END $$;

-- Update sequence
SELECT setval('stock_movements_id_seq', (SELECT MAX(id) FROM stock_movements));

-- =============================================
-- 8. Restore foreign key constraints
-- =============================================
ALTER TABLE stock_movements
    ADD CONSTRAINT stock_movements_item_id_fkey
        FOREIGN KEY (item_id) REFERENCES items(id);

ALTER TABLE stock_movements
    ADD CONSTRAINT stock_movements_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id);

-- =============================================
-- 9. Verify data integrity
-- =============================================
DO $$
    DECLARE
        old_count BIGINT;
        new_count BIGINT;
        old_sum BIGINT;
        new_sum BIGINT;
    BEGIN
        EXECUTE 'SELECT COUNT(*), COALESCE(SUM(quantity), 0) FROM stock_movements_old'
            INTO old_count, old_sum;

        EXECUTE 'SELECT COUNT(*), COALESCE(SUM(quantity), 0) FROM stock_movements'
            INTO new_count, new_sum;

        IF old_count = new_count AND old_sum = new_sum THEN
            RAISE NOTICE 'Migration successful: % rows migrated, total quantity: %', new_count, new_sum;
        ELSE
            RAISE WARNING 'Data mismatch: old rows=%, new rows=%, old sum=%, new sum=%',
                old_count, new_count, old_sum, new_sum;
        END IF;
    END $$;

-- =============================================
-- 10. Configure pg_partman
-- =============================================
-- noinspection SqlResolve
SELECT partman.create_parent(
               p_parent_table => 'public.stock_movements',
               p_control => 'created_at',
               p_type => 'range',
               p_interval => '1 month',
               p_premake => 3,
               p_start_partition => TO_CHAR(DATE_TRUNC('month', CURRENT_DATE + INTERVAL '1 month'), 'YYYY-MM-DD')::text
       );

-- noinspection SqlResolve
UPDATE partman.part_config
SET
    retention = '24 months',
    retention_keep_table = false,
    infinite_time_partitions = false
WHERE parent_table = 'public.stock_movements';

-- =============================================
-- 11. Fallback: manual partition creation
-- =============================================
CREATE OR REPLACE FUNCTION create_stock_movement_partition(
    p_date DATE DEFAULT CURRENT_DATE
) RETURNS VOID AS $$
DECLARE
    partition_name TEXT;
    start_date DATE;
    end_date DATE;
BEGIN
    start_date := DATE_TRUNC('month', p_date);
    end_date := start_date + INTERVAL '1 month';
    partition_name := 'stock_movements_y' || TO_CHAR(start_date, 'YYYY') || '_m' || TO_CHAR(start_date, 'MM');

    EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %s PARTITION OF stock_movements
             FOR VALUES FROM (%L) TO (%L)',
            partition_name,
            start_date,
            end_date
            );

    EXECUTE format(
            'CREATE INDEX IF NOT EXISTS %s_item_id_idx ON %s (item_id)',
            partition_name, partition_name
            );

    EXECUTE format(
            'CREATE INDEX IF NOT EXISTS %s_created_at_idx ON %s (created_at)',
            partition_name, partition_name
            );

    RAISE NOTICE 'Created partition % for period % to %', partition_name, start_date, end_date;
END;
$$ LANGUAGE plpgsql;

-- =============================================
-- 12. Fallback: trigger-based partition creation
-- =============================================
CREATE OR REPLACE FUNCTION auto_create_partition_trigger()
    RETURNS TRIGGER AS $$
DECLARE
    partition_name TEXT;
    start_date DATE;
    end_date DATE;
BEGIN
    start_date := DATE_TRUNC('month', NEW.created_at);
    end_date := start_date + INTERVAL '1 month';
    partition_name := 'stock_movements_y' || TO_CHAR(start_date, 'YYYY') || '_m' || TO_CHAR(start_date, 'MM');

    IF NOT EXISTS (
        SELECT 1
        FROM pg_tables
        WHERE schemaname = 'public'
          AND tablename = partition_name
    ) THEN
        EXECUTE format(
                'CREATE TABLE %s PARTITION OF stock_movements
                 FOR VALUES FROM (%L) TO (%L)',
                partition_name,
                start_date,
                end_date
                );

        EXECUTE format(
                'CREATE INDEX %s_item_id_idx ON %s (item_id)',
                partition_name, partition_name
                );

        EXECUTE format(
                'CREATE INDEX %s_created_at_idx ON %s (created_at)',
                partition_name, partition_name
                );
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_auto_create_partition ON stock_movements;
CREATE TRIGGER trigger_auto_create_partition
    BEFORE INSERT ON stock_movements
    FOR EACH ROW
EXECUTE FUNCTION auto_create_partition_trigger();

-- =============================================
-- 13. Verify partition pruning
-- =============================================

-- Show partition structure
SELECT
    parent.relname AS parent_table,
    child.relname AS partition_name,
    pg_get_expr(child.relpartbound, child.oid) AS partition_range
FROM pg_inherits
         JOIN pg_class parent ON pg_inherits.inhparent = parent.oid
         JOIN pg_class child ON pg_inherits.inhrelid = child.oid
WHERE parent.relname = 'stock_movements'
ORDER BY partition_name;

-- Verify pruning: history query (issue #8)
EXPLAIN (ANALYZE, BUFFERS, COSTS)
SELECT * FROM stock_movements
WHERE item_id = 12345
  AND created_at BETWEEN '2026-01-01' AND '2026-03-31';

-- Verify pruning: low-stock report
EXPLAIN (ANALYZE, BUFFERS, COSTS)
SELECT
    item_id,
    SUM(quantity) as total_quantity,
    COUNT(*) as movement_count
FROM stock_movements
WHERE created_at >= CURRENT_DATE - INTERVAL '30 days'
GROUP BY item_id
HAVING SUM(quantity) < 10
ORDER BY total_quantity ASC;

-- =============================================
-- 14. Verify indexes on all partitions
-- =============================================
SELECT
    partition_name,
    CASE WHEN has_item_index THEN '✅' ELSE '❌' END AS item_index,
    CASE WHEN has_created_index THEN '✅' ELSE '❌' END AS created_index
FROM (
         SELECT
             child.relname AS partition_name,
             EXISTS (
                 SELECT 1 FROM pg_indexes
                 WHERE schemaname = 'public'
                   AND tablename = child.relname
                   AND indexname LIKE '%item_id_idx'
             ) AS has_item_index,
             EXISTS (
                 SELECT 1 FROM pg_indexes
                 WHERE schemaname = 'public'
                   AND tablename = child.relname
                   AND indexname LIKE '%created_at_idx'
             ) AS has_created_index
         FROM pg_inherits
                  JOIN pg_class parent ON pg_inherits.inhparent = parent.oid
                  JOIN pg_class child ON pg_inherits.inhrelid = child.oid
         WHERE parent.relname = 'stock_movements'
     ) partitions
ORDER BY partition_name;

-- =============================================
-- Cleanup (optional, after verification)
-- =============================================
-- DROP TABLE stock_movements_old;