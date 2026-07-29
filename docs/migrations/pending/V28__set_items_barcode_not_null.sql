-- ⚠️ НЕ В db/migration/ — Flyway не видит. Копировать вместе с V29, V30
-- только когда: все инстансы пишут barcode, NULL-строк нет, дублей нет.
-- Подробнее: docs/OPS-5-deployment-guide.md

ALTER TABLE items ALTER COLUMN barcode SET NOT NULL;
