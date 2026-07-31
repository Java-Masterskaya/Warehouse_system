# Правила миграций БД (Zero-Downtime)

## Цель

Все изменения схемы базы данных должны выполняться без простоя приложения. Старый и новый код должны уметь работать параллельно во время rolling-deploy.

---

## Чеклист «что нельзя делать в одной миграции»

| Запрещено | Почему опасно | Правильный подход |
|-----------|---------------|-------------------|
| `ADD COLUMN ... NOT NULL DEFAULT 'x'` | Переписывает **всю таблицу** и держит `ACCESS EXCLUSIVE` блокировку минутами/часами на больших таблицах | 3 шага: nullable → backfill → `SET NOT NULL` |
| `ADD COLUMN ... NOT NULL` (без DEFAULT) | Сломается на существующих строках | Сначала nullable, потом backfill, потом NOT NULL |
| `ALTER TABLE ... RENAME COLUMN` | Старый код мгновенно ломается (не находит колонку) | Добавить новую → мигрировать данные → удалить старую в следующем релизе |
| `ALTER TABLE ... DROP COLUMN` | Старый код, который ещё читает колонку, упадёт | Сначала убрать использование из кода, потом DROP в следующем релизе |
| `ALTER TABLE ... ALTER COLUMN ... TYPE` с сужением | Долгая блокировка + риск потери данных | Новая колонка с новым типом → миграция → переключение кода |
| `UPDATE без WHERE` (или `UPDATE` огромной таблицы) | Блокирует обновляемые строки на всё время выполнения | Батчевый backfill через приложение |
| `CREATE INDEX` без `CONCURRENTLY` (PostgreSQL) | Блокирует таблицу на запись до окончания построения | `CREATE INDEX CONCURRENTLY` |
| DDL + DML в одной транзакции | Долгая транзакция = долгие блокировки | Разделить на отдельные миграции или шаги |

---

## Паттерн Expand/Contract

Стандартный способ внедрить изменение схемы без даунтайма.

### 1. Expand (расширить)

Добавляем новое безопасно:

- Новая колонка — только `NULL`, без `DEFAULT`.
- Новая таблица — создаём, старый код её не трогает.
- Новый индекс — `CONCURRENTLY`, чтобы не блокировать.

**Старый код** продолжает работать: он просто не знает о новой колонке и игнорирует её.

### 2. Backfill (заполнить)

Заполняем существующие данные:

- Маленькая таблица (< 100 000 строк) — можно `UPDATE` в отдельной миграции.
- Большая таблица — **только** через батчевую джобу в приложении (`ItemBarcodeBackfillJob`).

**Важно:** сама UPDATE-миграция для маленьких таблиц тоже держится в
`docs/migrations/pending/`, а не в `db/migration/`, до момента, пока кто-то не
проверит размер таблицы и не скопирует файл осознанно. Если положить её сразу
в `db/migration/`, Flyway применит её на первом же деплое ещё до того, как
кто-либо успеет решить, безопасна ли она для текущего размера таблицы — а
исправить это позже правкой уже применённого файла нельзя (см. ниже).

### 3. Contract (сжать)

Навешиваем ограничения, но **только когда**:

1. Все инстансы приложения обновлены и пишут новые данные корректно.
2. `SELECT COUNT(*) FROM items WHERE barcode IS NULL` → `0`.
3. Backfill завершён.

Теперь можно:
- `SET NOT NULL`
- Добавить `UNIQUE` / `FK`
- Удалить старую колонку (в следующем релизе!)

---

## Пример: добавление колонки `items.barcode`

### Шаг 1 — миграция `V27__add_items_barcode_nullable.sql`

```sql
ALTER TABLE items ADD COLUMN barcode VARCHAR(255);
```

- O(1) операция в PostgreSQL.
- Старый код вставляет без `barcode` — не падает.
- Новый код уже пишет `barcode`.

### Шаг 1.5 — миграция `V28__create_items_barcode_seq.sql`

```sql
CREATE SEQUENCE IF NOT EXISTS items_barcode_seq START WITH 1 INCREMENT BY 1;

SELECT setval('items_barcode_seq', COALESCE((SELECT MAX(id) FROM items), 1));
```

Номер для `barcode` берётся из этого sequence, а **не** из `id` товара —
осознанно расцеплено (см. `ItemBarcodeGenerator`). Так номер можно получить
ДО `INSERT` строки, одним `save()` вместо `INSERT` + `UPDATE`: `id` для новых
строк генерируется стратегией `GenerationType.IDENTITY`, а значит физически
не известен, пока не выполнен реальный `INSERT`. `CREATE SEQUENCE` — мгновенная
операция, деплоится вместе с V27, т.к. новый код сразу начинает вызывать
`nextval()`.

### Шаг 2 — backfill

Оба варианта ниже лежат в `docs/migrations/pending/`, а не в `db/migration/` —
Flyway их не видит, пока кто-то не скопирует нужный файл осознанно (см. выше).

**Для таблиц < 100K** — копируем `V29__backfill_items_barcode.sql` в
`db/migration/` и деплоим вместе с V27/V28:

```sql
UPDATE items
SET barcode = 'ITEM-' || lpad(nextval('items_barcode_seq')::text, 10, '0')
WHERE barcode IS NULL;
```

**Для таблиц ≥ 100K** — `V29` не трогаем вообще. Запускаем
`ItemBarcodeBackfillJob` через админ-эндпоинт. Эндпоинт асинхронный: сразу
отвечает `202 Accepted` и не держит HTTP-соединение на время всего backfill.

```bash
curl -X POST "http://app/admin/backfill/barcode?batchSize=500" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# прогресс/результат:
curl "http://app/admin/backfill/barcode/status" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

Джоба идемпотентна — можно перезапустить. Повторный запуск, пока предыдущий
ещё выполняется, вернёт `409 Conflict` (не сбрасывает прогресс первого).

### Шаг 3 — миграции V30, V31, V32

Перед деплоем проверяем не только NULL, но и дубли (иначе V32 упадёт):

```sql
SELECT COUNT(*) FROM items WHERE barcode IS NULL;
-- должно быть 0

SELECT barcode, COUNT(*) FROM items GROUP BY barcode HAVING COUNT(*) > 1;
-- должно быть 0 строк
```

```sql
-- V30
ALTER TABLE items ALTER COLUMN barcode SET NOT NULL;

-- V31 (требует spring.flyway.postgresql.transactional-lock: false)
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uk_items_barcode ON items (barcode);

-- V32
ALTER TABLE items ADD CONSTRAINT uk_items_barcode UNIQUE USING INDEX uk_items_barcode;
```

---

## Rolling-deploy и совместимость

```
Время →

[Старый код] ------+------+------+------+------+ [Новый код]
                    \     |      |      /
                     \    |      |     /
[V27+V28] - - - - - +----+------+----+ - - - -  (колонка nullable + sequence)
                      \   |      |   /
                       \  |      |  /
[Backfill] - - - - - - - +------+- - - - - - - -  (заполняем NULL)
                         \      /
                          \    /
[V30-V32] - - - - - - - - + - - - - - - - - - -  (NOT NULL + UNIQUE)
```

- В промежутке между V27/V28 и V30–V32 старый и новый код работают одновременно.
- V27/V28 безопасны, потому что не ломают старый INSERT.
- V30–V32 деплоятся только когда все инстансы уже новые (это отдельный деплой).

---

## Flyway-конвенции

- `V{номер}__{описание}.sql` — версионированные (применяются ровно один раз).
- Нумерация строго последовательная, без пропусков.
- Никаких `UPDATE` миграций — если файл **уже применялся** (в любом окружении,
  включая dev/staging), он не изменяется: правка меняет checksum, и Flyway
  откажется валидировать/применять остальные миграции.
- Файлы в `docs/migrations/pending/` — исключение из предыдущего пункта:
  раз Flyway их не видит и не применял, их можно свободно редактировать до
  момента, пока их не скопируют в `db/migration/`. После копирования —
  действует обычное правило "не редактировать".
- Перед merge: `./gradlew test`.

---

## Где найти код

- Применённые миграции: `src/main/resources/db/migration/`
- Миграции, ожидающие ручного решения (contract-шаг, опасный backfill):
  `docs/migrations/pending/`
- Генератор barcode (номер из независимого `items_barcode_seq`, с защитой от
  коллизии автоген/ручной ввод):
  `src/main/java/com/warehouse/service/item/ItemBarcodeGenerator.java`
- Backfill job: `src/main/java/com/warehouse/batch/ItemBarcodeBackfillJob.java`
- Админ-эндпоинт: `src/main/java/com/warehouse/controller/BackfillAdminController.java`
- Тесты: `src/test/java/com/warehouse/batch/MigrationCompatibilityTest.java`,
  `src/test/java/com/warehouse/controller/integration/BackfillAdminControllerTest.java`
