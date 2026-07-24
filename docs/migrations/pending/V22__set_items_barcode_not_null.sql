-- ============================================================================
-- OPS-5 Шаг 3: Сжатие (Contract) — ОЖИДАЕТ ДЕПЛОЯ
-- ============================================================================
-- ⚠️  ЭТОТ ФАЙЛ НЕ В db/migration/ — Flyway его НЕ ВИДИТ ⚠️
--
-- Переместить в src/main/resources/db/migration/ ТОЛЬКО когда:
--   1. Весь код (все инстансы) уже пишет barcode.
--   2. SELECT COUNT(*) FROM items WHERE barcode IS NULL; → 0
--   3. Backfill (V21 или джоба) завершён.
--
-- Подробнее: docs/OPS-5-deployment-guide.md
-- ============================================================================

ALTER TABLE items ALTER COLUMN barcode SET NOT NULL;

-- Уникальность штрихкода
ALTER TABLE items ADD CONSTRAINT uk_items_barcode UNIQUE (barcode);
