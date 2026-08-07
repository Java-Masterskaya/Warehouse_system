-- V34: Выполнение пакетной миграции stock_movements

DO
$$
    BEGIN
        CALL migrate_stock_movements_batch_controlled(10000, 50);
    EXCEPTION
        WHEN OTHERS THEN
            RAISE NOTICE 'Ошибка при выполнении миграции: %', sqlerrm;
    END
$$;

DROP PROCEDURE IF EXISTS migrate_stock_movements_batch_controlled(INT, INT);

SELECT SETVAL('stock_movements_new_id_seq',
              (SELECT COALESCE(MAX(id), 1) FROM stock_movements_new), TRUE);

DO
$$
    DECLARE
        source_count BIGINT;
        target_count BIGINT;
    BEGIN
        SELECT COUNT(*) INTO source_count FROM stock_movements;
        SELECT COUNT(*) INTO target_count FROM stock_movements_new;

        IF source_count != target_count THEN
            RAISE WARNING '⚠️ Количество записей не совпадает: source=%, target=%', source_count, target_count;
        ELSE
            RAISE NOTICE '✅ Миграция успешно завершена. Всего записей: %', target_count;
        END IF;
    END
$$;