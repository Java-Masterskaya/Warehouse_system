-- =============================================
-- V21.4: Finalize partitioning
-- =============================================

-- Restore foreign keys
ALTER TABLE stock_movements
    ADD CONSTRAINT stock_movements_item_id_fkey
        FOREIGN KEY (item_id) REFERENCES items (id);

ALTER TABLE stock_movements
    ADD CONSTRAINT stock_movements_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users (id);

-- Verify data integrity
DO
$$
    DECLARE
        old_count BIGINT;
        new_count BIGINT;
        old_sum   BIGINT;
        new_sum   BIGINT;
    BEGIN
        EXECUTE 'SELECT COUNT(*), COALESCE(SUM(quantity), 0) FROM stock_movements_old'
            INTO old_count, old_sum;

        EXECUTE 'SELECT COUNT(*), COALESCE(SUM(quantity), 0) FROM stock_movements'
            INTO new_count, new_sum;

        IF old_count = new_count AND old_sum = new_sum THEN
            RAISE NOTICE 'Migration successful: % rows, total quantity: %', new_count, new_sum;
        ELSE
            RAISE WARNING 'Data mismatch: old rows=%, new rows=%, old sum=%, new sum=%',
                old_count, new_count, old_sum, new_sum;
        END IF;
    END
$$;

-- Configure pg_partman
-- noinspection SqlResolve
SELECT public.create_parent(
               p_parent_table => 'public.stock_movements',
               p_control => 'created_at',
               p_type => 'range',
               p_interval => '1 month',
               p_premake => 3,
               p_start_partition => TO_CHAR(DATE_TRUNC('month', CURRENT_DATE + INTERVAL '1 month'), 'YYYY-MM-DD')::TEXT
       );

-- noinspection SqlResolve
UPDATE public.part_config
SET retention                = '24 months',
    retention_keep_table     = FALSE,
    infinite_time_partitions = FALSE
WHERE parent_table = 'public.stock_movements';

-- Fallback: manual partition creation
CREATE OR REPLACE FUNCTION create_stock_movement_partition(
    p_date DATE DEFAULT CURRENT_DATE
) RETURNS VOID AS
$$
DECLARE
    partition_name TEXT;
    start_date     DATE;
    end_date       DATE;
BEGIN
    start_date := DATE_TRUNC('month', p_date);
    end_date := start_date + INTERVAL '1 month';
    partition_name := 'stock_movements_y' || TO_CHAR(start_date, 'YYYY') || '_m' || TO_CHAR(start_date, 'MM');

    EXECUTE FORMAT(
            'CREATE TABLE IF NOT EXISTS %s PARTITION OF stock_movements
             FOR VALUES FROM (%L) TO (%L)',
            partition_name, start_date, end_date
            );

    EXECUTE FORMAT(
            'CREATE INDEX IF NOT EXISTS %s_item_id_idx ON %s (item_id)',
            partition_name, partition_name
            );

    EXECUTE FORMAT(
            'CREATE INDEX IF NOT EXISTS %s_created_at_idx ON %s (created_at)',
            partition_name, partition_name
            );

    RAISE NOTICE 'Created partition % for period % to %', partition_name, start_date, end_date;
END;
$$ LANGUAGE plpgsql;

-- Fallback: trigger-based partition creation
CREATE OR REPLACE FUNCTION auto_create_partition_trigger()
    RETURNS TRIGGER AS
$$
DECLARE
    partition_name TEXT;
    start_date     DATE;
    end_date       DATE;
BEGIN
    start_date := DATE_TRUNC('month', new.created_at);
    end_date := start_date + INTERVAL '1 month';
    partition_name := 'stock_movements_y' || TO_CHAR(start_date, 'YYYY') || '_m' || TO_CHAR(start_date, 'MM');

    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = partition_name) THEN
        EXECUTE FORMAT(
                'CREATE TABLE %s PARTITION OF stock_movements
                 FOR VALUES FROM (%L) TO (%L)',
                partition_name, start_date, end_date
                );

        EXECUTE FORMAT(
                'CREATE INDEX %s_item_id_idx ON %s (item_id)',
                partition_name, partition_name
                );

        EXECUTE FORMAT(
                'CREATE INDEX %s_created_at_idx ON %s (created_at)',
                partition_name, partition_name
                );
    END IF;

    RETURN new;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_auto_create_partition ON stock_movements;

CREATE TRIGGER trigger_auto_create_partition
    BEFORE INSERT
    ON stock_movements
    FOR EACH ROW
EXECUTE FUNCTION auto_create_partition_trigger();

-- Verify partition structure
SELECT parent.relname                             AS parent_table,
       child.relname                              AS partition_name,
       PG_GET_EXPR(child.relpartbound, child.oid) AS partition_range
FROM pg_inherits
         JOIN pg_class parent ON pg_inherits.inhparent = parent.oid
         JOIN pg_class child ON pg_inherits.inhrelid = child.oid
WHERE parent.relname = 'stock_movements'
ORDER BY partition_name;