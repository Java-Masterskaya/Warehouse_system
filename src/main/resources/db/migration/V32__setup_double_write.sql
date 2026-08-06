-- V32: Setup double-write with conflict handling

CREATE OR REPLACE FUNCTION sync_insert_to_new()
    RETURNS TRIGGER AS
$$
BEGIN
    INSERT INTO stock_movements_new (id, item_id, user_id, type, quantity,
                                     created_at, warehouse_id, transfer_id)
    VALUES (new.id, new.item_id, new.user_id, new.type,
            new.quantity, new.created_at, new.warehouse_id, new.transfer_id)
    ON CONFLICT (id, created_at) DO UPDATE SET item_id      = excluded.item_id,
                                               user_id      = excluded.user_id,
                                               type         = excluded.type,
                                               quantity     = excluded.quantity,
                                               warehouse_id = excluded.warehouse_id,
                                               transfer_id  = excluded.transfer_id;

    RETURN new;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION sync_update_to_new()
    RETURNS TRIGGER AS
$$
BEGIN
    UPDATE stock_movements_new
    SET item_id      = new.item_id,
        user_id      = new.user_id,
        type         = new.type,
        quantity     = new.quantity,
        warehouse_id = new.warehouse_id,
        transfer_id  = new.transfer_id,
        created_at   = new.created_at
    WHERE id = new.id
      AND created_at = new.created_at;

    IF NOT found THEN
        INSERT INTO stock_movements_new (id, item_id, user_id, type, quantity,
                                         created_at, warehouse_id, transfer_id)
        VALUES (new.id, new.item_id, new.user_id, new.type,
                new.quantity, new.created_at, new.warehouse_id, new.transfer_id)
        ON CONFLICT (id, created_at) DO UPDATE SET item_id      = excluded.item_id,
                                                   user_id      = excluded.user_id,
                                                   type         = excluded.type,
                                                   quantity     = excluded.quantity,
                                                   warehouse_id = excluded.warehouse_id,
                                                   transfer_id  = excluded.transfer_id;
    END IF;

    RETURN new;
END;
$$ LANGUAGE plpgsql;


CREATE OR REPLACE FUNCTION sync_delete_to_new()
    RETURNS TRIGGER AS
$$
BEGIN
    DELETE
    FROM stock_movements_new
    WHERE id = old.id
      AND created_at = old.created_at;

    RETURN old;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER sync_insert_to_new_trigger
    AFTER INSERT
    ON stock_movements
    FOR EACH ROW
EXECUTE FUNCTION sync_insert_to_new();

CREATE TRIGGER sync_update_to_new_trigger
    AFTER UPDATE
    ON stock_movements
    FOR EACH ROW
EXECUTE FUNCTION sync_update_to_new();

CREATE TRIGGER sync_delete_to_new_trigger
    AFTER DELETE
    ON stock_movements
    FOR EACH ROW
EXECUTE FUNCTION sync_delete_to_new();
