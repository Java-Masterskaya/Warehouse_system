-- noinspection SqlResolveForFile

-- =============================================
-- V21: Migrate stock_movements to partitioned table
-- =============================================

-- Enable pg_partman extension for automated partition management
-- noinspection SqlResolve
CREATE EXTENSION IF NOT EXISTS pg_partman;

-- =============================================
-- Step 1: Create new partitioned table
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
-- Step 2: Create partitions for existing data
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
-- Step 3: Create indexes on partitions
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
-- Step 4: Batch data migration
-- =============================================
DO $$
    DECLARE
        batch_size INT := 10000;
        last_id BIGINT := 0;
        row_count INT;
        total_count BIGINT := 0;
    BEGIN
        LOOP
            WITH batch AS (
                SELECT * FROM stock_movements
                WHERE id > last_id
                ORDER BY id
                LIMIT batch_size
            )
            INSERT INTO stock_movements_new
            SELECT * FROM batch
            RETURNING id INTO last_id;

            GET DIAGNOSTICS row_count = ROW_COUNT;
            EXIT WHEN row_count = 0;

            total_count := total_count + row_count;
            COMMIT;
        END LOOP;
    END $$;

-- =============================================
-- Step 5: Switch tables with sequence rename
-- =============================================

LOCK TABLE stock_movements IN ACCESS EXCLUSIVE MODE;

ALTER TABLE stock_movements RENAME TO stock_movements_old;
ALTER TABLE stock_movements_new RENAME TO stock_movements;

-- CRITICAL: Rename the sequence too!
ALTER SEQUENCE stock_movements_new_id_seq RENAME TO stock_movements_id_seq;

INSERT INTO stock_movements
SELECT * FROM stock_movements_old
ON CONFLICT (id, created_at) DO NOTHING;

SELECT setval('stock_movements_id_seq', (SELECT MAX(id) FROM stock_movements));

ALTER TABLE stock_movements
    ADD CONSTRAINT stock_movements_item_id_fkey
        FOREIGN KEY (item_id) REFERENCES items(id);

ALTER TABLE stock_movements
    ADD CONSTRAINT stock_movements_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id);

-- =============================================
-- Step 6: Verify data integrity
-- =============================================
DO $$
    DECLARE
        old_count BIGINT;
        new_count BIGINT;
    BEGIN
        EXECUTE 'SELECT COUNT(*) FROM stock_movements_old' INTO old_count;
        EXECUTE 'SELECT COUNT(*) FROM stock_movements' INTO new_count;

        IF old_count = new_count THEN
            RAISE NOTICE 'Migration successful: % rows migrated', new_count;
        ELSE
            RAISE WARNING 'Data mismatch: old=%, new=%', old_count, new_count;
        END IF;
    END $$;

-- =============================================
-- Step 7: Configure pg_partman
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
-- Step 8: Manual partition creation function (fallback)
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
-- Step 9: Trigger-based partition creation (fallback)
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
-- Verification: Partition Pruning
-- =============================================

-- Verify partition structure
SELECT
    parent.relname AS parent_table,
    child.relname AS partition_name,
    pg_get_expr(child.relpartbound, child.oid) AS partition_range
FROM pg_inherits
         JOIN pg_class parent ON pg_inherits.inhparent = parent.oid
         JOIN pg_class child ON pg_inherits.inhrelid = child.oid
WHERE parent.relname = 'stock_movements'
ORDER BY partition_name;

-- Verify partition pruning for history query (#8)
EXPLAIN (ANALYZE, BUFFERS, COSTS)
SELECT * FROM stock_movements
WHERE item_id = 12345
  AND created_at BETWEEN '2026-01-01' AND '2026-03-31';

-- Verify partition pruning for low-stock report
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