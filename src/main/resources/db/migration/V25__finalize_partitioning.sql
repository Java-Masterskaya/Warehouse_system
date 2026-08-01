-- =============================================
-- V25: Finalize partitioning with pg_partman
-- =============================================

-- 1. Обновляем pg_partman: переносим управление на переименованную таблицу
-- pg_partman хранит конфигурацию в таблице partman.part_config
-- Нужно обновить parent_table с stock_movements_new на stock_movements

UPDATE public.part_config
SET parent_table = 'public.stock_movements'
WHERE parent_table = 'public.stock_movements_new';

-- 2. Настраиваем политику хранения (опционально)
UPDATE public.part_config
SET retention = '24 months',
    retention_keep_table = false
WHERE parent_table = 'public.stock_movements';

-- 3. Запускаем обслуживание для создания недостающих партиций
SELECT public.run_maintenance('public.stock_movements');

DO $$
    BEGIN
        RAISE NOTICE 'Stock movements partitioning finalized with pg_partman';
    END $$;