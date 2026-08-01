CREATE EXTENSION IF NOT EXISTS pg_partman;

DO $$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_partman') THEN
            RAISE EXCEPTION 'pg_partman extension was not installed successfully';
        END IF;
        RAISE NOTICE '✅ pg_partman extension installed successfully';
    END $$;