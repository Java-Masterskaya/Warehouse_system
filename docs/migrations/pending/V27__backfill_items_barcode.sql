-- ============================================================================
-- OPS-5 Шаг 2: Заполнение (Backfill) — ОЖИДАЕТ РЕШЕНИЯ ПЕРЕД ДЕПЛОЕМ
-- ============================================================================
-- ⚠️  ЭТОТ ФАЙЛ НЕ В db/migration/ — Flyway его НЕ ВИДИТ ⚠️
-- Скопировать в src/main/resources/db/migration/ (и задеплоить) ТОЛЬКО если:
--   SELECT COUNT(*) FROM items; -- < 100 000
--
-- Если строк >= 100 000 — НЕ копировать этот файл никогда. Вместо этого:
--   1. Задеплоить только V25 + V26 (nullable-колонка + sequence) + Java-код.
--   2. Запустить ItemBarcodeBackfillJob через POST /admin/backfill/barcode
--      (батчами, короткими транзакциями — см. docs/database-migrations.md).
--   3. Дождаться SELECT COUNT(*) FROM items WHERE barcode IS NULL; → 0.
--   4. Только затем — V28 (contract).
--
-- Подробнее: docs/OPS-5-deployment-guide.md
-- ============================================================================

UPDATE items
SET barcode = 'ITEM-' || lpad(nextval('items_barcode_seq')::text, 10, '0')
WHERE barcode IS NULL;
