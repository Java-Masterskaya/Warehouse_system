-- V41: Operational monitoring views for partitioned data

CREATE OR REPLACE FUNCTION monitor_stock_movements_partitions()
    RETURNS TABLE
            (
                PARTITION_NAME TEXT,
                PARTITION_SIZE TEXT,
                ROW_COUNT      BIGINT,
                PARTITION_DATE DATE
            )
AS
$$
BEGIN
    RETURN QUERY
        SELECT inhrelid::REGCLASS::TEXT,
               PG_SIZE_PRETTY(PG_TOTAL_RELATION_SIZE(inhrelid)),
               COALESCE((SELECT n_live_tup FROM pg_stat_all_tables WHERE relid = inhrelid), 0),
               CASE
                   WHEN inhrelid::REGCLASS::TEXT ~ E'stock_movements(_new)?_p(\\d{4})_(\\d{2})'
                       THEN TO_DATE(
                           (REGEXP_MATCH(inhrelid::REGCLASS::TEXT, E'stock_movements(_new)?_p(\\d{4})_(\\d{2})'))[2] ||
                           '_' ||
                           (REGEXP_MATCH(inhrelid::REGCLASS::TEXT, E'stock_movements(_new)?_p(\\d{4})_(\\d{2})'))[3],
                           'YYYY_MM')
                   ELSE NULL
                   END AS partition_date
        FROM pg_inherits
        WHERE inhparent = 'stock_movements'::REGCLASS
        ORDER BY partition_date DESC NULLS LAST;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE VIEW stock_movements_partition_stats AS
SELECT *
FROM monitor_stock_movements_partitions();

DO
$$
    BEGIN
        RAISE NOTICE '========================================';
        RAISE NOTICE 'Migration V31-V40 completed successfully!';
        RAISE NOTICE '========================================';
    END
$$;