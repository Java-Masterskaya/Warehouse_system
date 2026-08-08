-- V39: Setup pg_partman retention and maintenance, and pg_cron scheduling

DO
$$
BEGIN
        IF EXISTS (SELECT 1 FROM public.part_config WHERE parent_table = 'public.stock_movements') THEN
UPDATE public.part_config
SET retention             = '24 months',
    retention_keep_table  = TRUE,
    retention_keep_index  = TRUE,
    automatic_maintenance = 'on',
    epoch                 = 'none'
WHERE parent_table = 'public.stock_movements';
RAISE NOTICE 'Retention policy updated for stock_movements';
ELSE
            RAISE NOTICE 'Parent table not found in part_config, skipping retention configuration';
END IF;
END
$$;

CREATE OR REPLACE FUNCTION maintain_stock_movements_partitions()
    RETURNS VOID AS
$$
BEGIN
    PERFORM public.run_maintenance('public.stock_movements');

    PERFORM public.drop_partition_time(
            p_parent_table => 'public.stock_movements',
            p_retention => '24 months',
            p_keep_table => TRUE
            );
EXCEPTION
    WHEN OTHERS THEN
        RAISE NOTICE 'Error during maintenance: %', sqlerrm;
END;
$$ LANGUAGE plpgsql;