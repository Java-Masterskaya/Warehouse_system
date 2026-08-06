-- V37: Setup pg_partman retention and maintenance, and pg_cron scheduling

DO
$$
    BEGIN
        IF EXISTS (SELECT 1 FROM public.part_config WHERE parent_table = 'public.stock_movements') THEN
            UPDATE public.part_config
            SET retention             = '24 months',
                retention_keep_table  = FALSE,
                retention_keep_index  = FALSE,
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
            p_keep_table => FALSE
            );
EXCEPTION
    WHEN OTHERS THEN
        RAISE NOTICE 'Error during maintenance: %', sqlerrm;
END;
$$ LANGUAGE plpgsql;

DO
$$
    DECLARE
        job_exists     BOOLEAN;
        cron_available BOOLEAN;
    BEGIN
        SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_cron') INTO cron_available;

        IF NOT cron_available THEN
            RAISE WARNING 'pg_cron extension is not installed or enabled. Automatic maintenance will not be scheduled.';
            RETURN;
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'cron') THEN
            RAISE WARNING 'cron schema does not exist. Automatic maintenance will not be scheduled.';
            RETURN;
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'cron' AND table_name = 'job') THEN
            RAISE WARNING 'cron.job table does not exist. Automatic maintenance will not be scheduled.';
            RETURN;
        END IF;

        SELECT EXISTS (SELECT 1 FROM cron.job WHERE jobname = 'maintain-stock-movements') INTO job_exists;

        IF job_exists THEN
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