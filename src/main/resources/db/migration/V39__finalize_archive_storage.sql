-- V48: Archive management after successful verification

-- Примечание: Мы НЕ удаляем и не переименовываем таблицу stock_movements_archive здесь,
-- чтобы Java-тесты могли проверить целостность данных после миграции.
-- В реальной среде эту таблицу можно удалить вручную после подтверждения успеха.

-- Обновляем статистику для старой таблицы
ANALYZE stock_movements_archive;

-- Запуск обслуживания партиций через pg_partman
-- Это гарантирует, что pg_partman обновит свои метаданные и создаст необходимые партиции
SELECT public.run_maintenance(
               p_parent_table => 'public.stock_movements'
       );
