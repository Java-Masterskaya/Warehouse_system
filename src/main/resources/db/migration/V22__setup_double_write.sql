-- V22: Setup double-write triggers for near-zero lag synchronization
CREATE OR REPLACE FUNCTION sync_insert_to_new()
    RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO stock_movements_new (
        id, item_id, user_id, type, quantity,
        created_at, warehouse_id, transfer_id
    )
    VALUES (
               NEW.id, NEW.item_id, NEW.user_id, NEW.type,
               NEW.quantity, NEW.created_at, NEW.warehouse_id, NEW.transfer_id
           )
    ON CONFLICT (id, created_at) DO UPDATE SET
                                               item_id = EXCLUDED.item_id,
                                               user_id = EXCLUDED.user_id,
                                               type = EXCLUDED.type,
                                               quantity = EXCLUDED.quantity,
                                               warehouse_id = EXCLUDED.warehouse_id,
                                               transfer_id = EXCLUDED.transfer_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION sync_update_to_new()
    RETURNS TRIGGER AS $$
BEGIN
    UPDATE stock_movements_new
    SET item_id = NEW.item_id,
        user_id = NEW.user_id,
        type = NEW.type,
        quantity = NEW.quantity,
        warehouse_id = NEW.warehouse_id,
        transfer_id = NEW.transfer_id,
        created_at = NEW.created_at
    WHERE id = NEW.id AND created_at = NEW.created_at;

    IF NOT FOUND THEN
        INSERT INTO stock_movements_new (
            id, item_id, user_id, type, quantity,
            created_at, warehouse_id, transfer_id
        )
        VALUES (
                   NEW.id, NEW.item_id, NEW.user_id, NEW.type,
                   NEW.quantity, NEW.created_at, NEW.warehouse_id, NEW.transfer_id
               );
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION sync_delete_to_new()
    RETURNS TRIGGER AS $$
BEGIN
    DELETE FROM stock_movements_new
    WHERE id = OLD.id AND created_at = OLD.created_at;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER sync_insert_to_new_trigger
    AFTER INSERT ON stock_movements
    FOR EACH ROW EXECUTE FUNCTION sync_insert_to_new();

CREATE TRIGGER sync_update_to_new_trigger
    AFTER UPDATE ON stock_movements
    FOR EACH ROW EXECUTE FUNCTION sync_update_to_new();

CREATE TRIGGER sync_delete_to_new_trigger
    AFTER DELETE ON stock_movements
    FOR EACH ROW EXECUTE FUNCTION sync_delete_to_new();
