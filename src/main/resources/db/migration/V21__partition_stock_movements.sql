-- =============================================
-- V21: Create partitioned table with pg_partman
-- =============================================

-- 1. Убеждаемся, что расширение установлено
CREATE EXTENSION IF NOT EXISTS pg_partman;

-- 2. Создаем партиционированную таблицу
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

-- 3. Настраиваем pg_partman для управления партициями
-- pg_partman сам создаст партиции для будущих периодов
-- и будет управлять ими
SELECT public.create_parent(
               p_parent_table => 'public.stock_movements_new',
               p_control => 'created_at',
               p_type => 'range',
               p_interval => '1 month',
               p_premake => 12
       );

-- 4. Создаем партиции для исторических данных через pg_partman
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
                -- Используем pg_partman для создания партиций
                -- Он сам выберет правильное имя и не будет конфликтовать
                    PERFORM public.create_partition_time(
                            p_parent_table => 'public.stock_movements_new',
                            p_partition_times => ARRAY[d::timestamp]
                            );
                    d := d + interval '1 month';
                END LOOP;
        END IF;
    END $$;

-- 5. Sync trigger for new inserts during migration
CREATE OR REPLACE FUNCTION sync_to_new_movements()
    RETURNS TRIGGER AS
$$
BEGIN
    INSERT INTO stock_movements_new (id, item_id, user_id, type, quantity, created_at, warehouse_id, transfer_id)
    VALUES (NEW.id,
            NEW.item_id,
            NEW.user_id,
            NEW.type,
            NEW.quantity,
            NEW.created_at,
            NEW.warehouse_id,
            NEW.transfer_id)
    ON CONFLICT (id, created_at) DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER sync_to_new_movements_trigger
    AFTER INSERT ON stock_movements
    FOR EACH ROW
EXECUTE FUNCTION sync_to_new_movements();