-- V36: Верификация результатов миграции stock_movements

DO
$$
    DECLARE
        source_count BIGINT;
        target_count BIGINT;
    BEGIN
        SELECT COUNT(*) INTO source_count FROM stock_movements_archive;
        SELECT COUNT(*) INTO target_count FROM stock_movements;

        IF source_count != target_count THEN
            RAISE EXCEPTION 'Количество записей не совпадает после миграции: source (archive)=%, target=%',
                source_count, target_count;
        END IF;

        RAISE NOTICE '✅ Миграция успешно завершена и проверена. Всего записей: %', target_count;
    END
$$;