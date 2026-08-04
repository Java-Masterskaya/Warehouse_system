-- V26: Setup pg_partman retention and maintenance
UPDATE public.part_config
SET retention = '24 months',
    retention_keep_table = false,
    retention_keep_index = false,
    automatic_maintenance = 'on',
    epoch = 'none'
WHERE parent_table = 'public.stock_movements';

CREATE OR REPLACE FUNCTION maintain_stock_movements_partitions()
    RETURNS void AS $$
BEGIN
    PERFORM public.run_maintenance('public.stock_movements');

    PERFORM public.drop_partition_time(
            p_parent_table => 'public.stock_movements',
            p_retention => '24 months',
            p_keep_table => false
            );
END;
$$ LANGUAGE plpgsql;

DO $$
    BEGIN
        IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_cron') THEN
            EXECUTE 'SELECT cron.schedule(''maintain-stock-movements'', ''0 3 * * *'', ''SELECT maintain_stock_movements_partitions()'')';
        END IF;
    END $$;
