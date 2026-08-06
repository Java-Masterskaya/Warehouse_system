-- V37: Setup pg_partman retention and maintenance, and pg_cron scheduling

UPDATE public.part_config
SET retention             = '24 months',
    retention_keep_table  = FALSE,
    retention_keep_index  = FALSE,
    automatic_maintenance = 'on',
    epoch                 = 'none'
WHERE parent_table = 'public.stock_movements';

CREATE OR REPLACE FUNCTION maintain_stock_movements_partitions()
    RETURNS VOID AS
$$
BEGIN
    PERFORM public.run_maintenance('public.stock_movements');

    PERFORM public.drop_partition_time(
            p_parent_table => 'public.stock_movements',
            p_retention => '24 months',
            p_keep_table => FALSE
            );
END;
$$ LANGUAGE plpgsql;

DO
$$
    BEGIN
        IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_cron') THEN
            PERFORM cron.unschedule('maintain-stock-movements');

            EXECUTE 'SELECT cron.schedule(
            ''maintain-stock-movements'',
            ''0 3 * * *'',  -- каждый день в 3:00
            ''SELECT maintain_stock_movements_partitions()''
        )';
            RAISE NOTICE 'Scheduled maintenance job created via pg_cron.';
        ELSE
            RAISE WARNING 'pg_cron extension is not installed or enabled. Automatic maintenance will not be scheduled.';
        END IF;
    END
$$;
