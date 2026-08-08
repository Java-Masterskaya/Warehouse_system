-- V34: Выполнение пакетной миграции stock_movements

CALL migrate_stock_movements_batch_controlled(10000, 50);

DROP PROCEDURE IF EXISTS migrate_stock_movements_batch_controlled(INT, INT);

SELECT SETVAL('stock_movements_new_id_seq',
              (SELECT COALESCE(MAX(id), 1) FROM stock_movements_new), TRUE);