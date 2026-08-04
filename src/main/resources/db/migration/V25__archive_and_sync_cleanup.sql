-- V25: Move residual data to partitions and cleanup
DO $$
    DECLARE
        archive_date date;
    BEGIN
        archive_date := date_trunc('month', NOW() - INTERVAL '24 months')::date;

        PERFORM public.create_partition_time(
                p_parent_table => 'public.stock_movements',
                p_partition_times => ARRAY[archive_date::timestamp]
                );

        INSERT INTO stock_movements (
            id, item_id, user_id, type, quantity,
            created_at, warehouse_id, transfer_id
        )
        SELECT
            id, item_id, user_id, type, quantity,
            created_at, warehouse_id, transfer_id
        FROM stock_movements_archive
        WHERE created_at >= archive_date
        ON CONFLICT (id, created_at) DO NOTHING;
    END $$;

DROP FUNCTION IF EXISTS sync_insert_to_new();
DROP FUNCTION IF EXISTS sync_update_to_new();
DROP FUNCTION IF EXISTS sync_delete_to_new();
