-- V30: Operational monitoring views for partitioned data
CREATE OR REPLACE FUNCTION monitor_stock_movements_partitions()
    RETURNS TABLE(
                     partition_name text,
                     partition_size text,
                     row_count bigint,
                     partition_date date
                 ) AS $$
BEGIN
    RETURN QUERY
        SELECT
            inhrelid::regclass::text,
            pg_size_pretty(pg_total_relation_size(inhrelid)),
            (SELECT count(*) FROM pg_stat_all_tables WHERE relid = inhrelid),
            (regexp_match(inhrelid::regclass::text, 'stock_movements_p(\d{4}_\d{2})'))[1]::date
        FROM pg_inherits
        WHERE inhparent = 'stock_movements'::regclass
        ORDER BY partition_date DESC;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE VIEW stock_movements_partition_stats AS
SELECT * FROM monitor_stock_movements_partitions();
