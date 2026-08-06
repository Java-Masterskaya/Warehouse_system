-- V31: Create partitioned table with pg_partman and DEFAULT partition

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
    batch_id     BIGINT,
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

ALTER TABLE stock_movements_new
    ADD CONSTRAINT fk_stock_movements_new_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id) ON DELETE RESTRICT;
ALTER TABLE stock_movements_new
    ADD CONSTRAINT fk_stock_movements_new_item
        FOREIGN KEY (item_id) REFERENCES items (id) ON DELETE RESTRICT;
ALTER TABLE stock_movements_new
    ADD CONSTRAINT fk_stock_movements_new_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE stock_movements
    ADD CONSTRAINT fk_stock_movements_batch_scope
        FOREIGN KEY (batch_id, item_id, warehouse_id)
            REFERENCES batches (id, item_id, warehouse_id)
            ON DELETE RESTRICT;


ALTER TABLE stock_movements_new
    ADD CONSTRAINT stock_movements_new_quantity_check
        CHECK (
            (type IN (
                      'RECEIVE', 'WRITE_OFF', 'TRANSFER_OUT', 'TRANSFER_IN'
                ) AND quantity > 0) OR (type = 'ADJUSTMENT' AND quantity <> 0)
            );

CREATE OR REPLACE FUNCTION check_stock_movements_id_unique()
    RETURNS TRIGGER AS
$$
BEGIN
    EXECUTE FORMAT('SELECT 1 FROM %I WHERE id = $1 LIMIT 1', tg_table_name)
        USING new.id;

    IF found THEN
        RAISE EXCEPTION 'duplicate key value violates unique constraint on id: %', new.id;
    END IF;
    RETURN new;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_check_stock_movements_id_unique
    BEFORE INSERT
    ON stock_movements_new
    FOR EACH ROW
EXECUTE FUNCTION check_stock_movements_id_unique();

SELECT public.create_parent(
               p_parent_table => 'public.stock_movements_new',
               p_control => 'created_at',
               p_type => 'range',
               p_interval => '1 month',
               p_premake => 12,
               p_start_partition => TO_CHAR(DATE_TRUNC('month', NOW() - INTERVAL '1 year'), 'YYYY-MM-DD')
       );

CREATE TABLE IF NOT EXISTS stock_movements_new_default PARTITION OF stock_movements_new DEFAULT;

DO
$$
    DECLARE
        min_date DATE;
        max_date DATE;
        d        DATE;
    BEGIN
        SELECT MIN(created_at)::DATE, MAX(created_at)::DATE
        INTO min_date, max_date
        FROM stock_movements;

        IF min_date IS NOT NULL THEN
            d := DATE_TRUNC('month', min_date)::DATE;
            WHILE d <= DATE_TRUNC('month', max_date)::DATE
                LOOP
                    PERFORM public.create_partition_time(
                            p_parent_table => 'public.stock_movements_new',
                            p_partition_times => ARRAY [d::TIMESTAMP]
                            );
                    d := d + INTERVAL '1 month';
                END LOOP;
        END IF;
    END
$$;