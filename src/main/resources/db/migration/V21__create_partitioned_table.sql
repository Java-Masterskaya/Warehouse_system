-- V21: Create partitioned table with pg_partman
CREATE EXTENSION IF NOT EXISTS pg_partman;

CREATE TABLE stock_movements_new
(
    id           BIGSERIAL,
    item_id      BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL,
    type         VARCHAR(20) NOT NULL,
    quantity     INTEGER     NOT NULL,
    warehouse_id BIGINT      NOT NULL,
    transfer_id  UUID,
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id, created_at),
    CONSTRAINT fk_stock_movements_new_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_movements_new_item
        FOREIGN KEY (item_id) REFERENCES items (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_movements_new_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT stock_movements_new_quantity_check
        CHECK (
            (type IN ('RECEIVE', 'WRITE_OFF', 'TRANSFER_OUT', 'TRANSFER_IN') AND quantity > 0)
                OR (type = 'ADJUSTMENT' AND quantity <> 0)
            )
) PARTITION BY RANGE (created_at);

SELECT public.create_parent(
               p_parent_table => 'public.stock_movements_new',
               p_control => 'created_at',
               p_type => 'range',
               p_interval => '1 month',
               p_premake => 12
       );

DO $$
    DECLARE
        min_date date;
        max_date date;
        d date;
    BEGIN
        SELECT MIN(created_at)::date, MAX(created_at)::date
        INTO min_date, max_date
        FROM stock_movements;

        IF min_date IS NOT NULL THEN
            d := date_trunc('month', min_date)::date;
            WHILE d <= date_trunc('month', max_date)::date LOOP
                    PERFORM public.create_partition_time(
                            p_parent_table => 'public.stock_movements_new',
                            p_partition_times => ARRAY[d::timestamp]
                            );
                    d := d + interval '1 month';
                END LOOP;
        END IF;
    END $$;
