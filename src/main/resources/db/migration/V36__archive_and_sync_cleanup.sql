-- V36: Move residual data to partitions and cleanup

DO
$$
    DECLARE
        archive_date DATE;
    BEGIN
        archive_date := DATE_TRUNC(
                'month',
                NOW() - INTERVAL '24 months'
                        )::DATE;

        PERFORM public.create_partition_time(
                p_parent_table => 'public.stock_movements',
                p_partition_times => ARRAY [archive_date::TIMESTAMP]
                );

        RAISE NOTICE 'Archiving data older than %', archive_date;

        INSERT INTO stock_movements (id, item_id, user_id, type, quantity,
                                     created_at, warehouse_id, transfer_id, batch_id)
        SELECT id,
               item_id,
               user_id,
               type,
               quantity,
               created_at,
               warehouse_id,
               transfer_id,
               batch_id
        FROM stock_movements_archive
        WHERE created_at >= archive_date
        ON CONFLICT (
            id, created_at
            ) DO NOTHING;

        RAISE NOTICE 'Archived data moved to partitioned table';
    END
$$;

DROP FUNCTION IF EXISTS sync_insert_to_new() CASCADE;
DROP FUNCTION IF EXISTS sync_update_to_new() CASCADE;
DROP FUNCTION IF EXISTS sync_delete_to_new() CASCADE;
