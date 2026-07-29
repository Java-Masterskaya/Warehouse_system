# OPS-5: Пошаговый гайд деплоя barcode (Zero-Downtime)

## ⚡ Быстрый старт

```bash
# Этап 1 (этот PR)
git checkout main && git pull
# оценить размер таблицы: SELECT COUNT(*) FROM items;
#   < 100 000  → скопировать docs/migrations/pending/V27__backfill_items_barcode.sql
#                в src/main/resources/db/migration/, задеплоить V25 + V26 + V27 + Java-код
#   >= 100 000 → НЕ копировать V27. Задеплоить только V25 + V26 + Java-код,
#                backfill — джобой (см. "Что делать, если V27 опасен" ниже)
# проверка: SELECT COUNT(*) FROM items WHERE barcode IS NULL;  → 0

# Этап 2 (отдельный деплой, когда убедились)
# проверить дубли, скопировать V28+V29+V30 из docs/migrations/pending/
# в src/main/resources/db/migration/, задеплоить
```

---

## 📋 Подробная инструкция

### Этап 1: Expand + Backfill (V25 + V26 + V27)

**Что деплоится:**
- `V25__add_items_barcode_nullable.sql` — добавляет nullable колонку (уже в `db/migration/`)
- `V26__create_items_barcode_seq.sql` — независимый sequence для номера barcode,
  не привязанный к id товара (уже в `db/migration/`, см. "Почему отдельный sequence" ниже)
- `docs/migrations/pending/V27__backfill_items_barcode.sql` — UPDATE существующих строк.
  **В `db/migration/` его кладём только для таблиц < 100 000 строк** (см. ниже) —
  Flyway не видит файлы в `pending/`, так что по умолчанию он никуда не применяется сам по себе.
- Java-код (ItemServiceImpl генерирует barcode автоматически через `ItemBarcodeGenerator`)
- ItemBarcodeBackfillJob + `/admin/backfill/barcode` (на случай, если таблица большая)

**Проверка перед деплоем:**
- [ ] `SELECT COUNT(*) FROM items;` — определить, нужен ли V27 в этом деплое
- [ ] `./gradlew test` проходит (миграции применяются к реальному Postgres через
      Testcontainers как часть интеграционных тестов — отдельной задачи
      `flywayValidate` в проекте нет)

**После деплоя:**
- [ ] Старый код продолжает работать (не знает про barcode — OK)
- [ ] Новый код создаёт товары с barcode (одним INSERT — см. ниже)
- [ ] Если V27 не деплоился (таблица большая) — запустить backfill джобой:
      `POST /admin/backfill/barcode` (асинхронно, возвращает `202` сразу;
      прогресс — `GET /admin/backfill/barcode/status`)
- [ ] Проверить: `SELECT COUNT(*) FROM items WHERE barcode IS NULL;` → `0`

### Этап 2: Contract (V28 + V29 + V30) — ОТДЕЛЬНО!

**Когда можно деплоить:**
1. ВСЕ инстансы приложения обновлены (старый код, который не пишет barcode, больше не работает)
2. `SELECT COUNT(*) FROM items WHERE barcode IS NULL;` → `0`
3. **Дублей нет**: `SELECT barcode, COUNT(*) FROM items GROUP BY barcode HAVING COUNT(*) > 1;` → `0` строк
4. Backfill завершён (V27 применён либо `GET /admin/backfill/barcode/status` показывает `COMPLETE`)

**Что деплоится:**
- `V28` — `SET NOT NULL`
- `V29` — `CREATE UNIQUE INDEX CONCURRENTLY` (требует `spring.flyway.postgresql.transactional-lock: false`)
- `V30` — `ADD CONSTRAINT UNIQUE USING INDEX`

**Важно:**
- Если есть NULL-строки — V28 УПАДЁТ.
- Если есть дубли barcode — V30 УПАДЁТ. Проверяйте дубли заранее (шаг 3 выше).
- CHECK на "зарезервированный формат" больше нет — проверка живёт только в приложении.

---

## 🚨 Почему нельзя V28–V30 вместе с V25/V26/V27

Если применить сразу, а у вас rolling deploy:

```
[Старый инстанс] → INSERT без barcode → БД с NOT NULL → 💥 ERROR 500
```

Пользователь получит ошибку. Поэтому V28–V30 — только после полного обновления всех инстансов.

---

## 🔧 Что делать, если V27 (SQL-backfill) опасен для продакшена

Если в таблице `items` > 100 000 строк:

1. **Не копировать `V27__backfill_items_barcode.sql` в `db/migration/` вообще.**

2. **Запустить Java-job (асинхронно):**
   ```bash
   curl -X POST "http://app/admin/backfill/barcode?batchSize=500" \
     -H "Authorization: Bearer $ADMIN_TOKEN"
   ```

3. **Следить за прогрессом:**
   ```bash
   curl "http://app/admin/backfill/barcode/status" \
     -H "Authorization: Bearer $ADMIN_TOKEN"
   ```
   Джоба идемпотентна — можно перезапускать, если прервалась. Повторный запуск
   поверх уже выполняющегося вернёт `409 Conflict`, а не тихо собьёт прогресс.

4. **Убедиться, что всё заполнено и без дублей:**
   ```sql
   SELECT COUNT(*) FROM items WHERE barcode IS NULL;
   -- должно быть 0

   SELECT barcode, COUNT(*) FROM items GROUP BY barcode HAVING COUNT(*) > 1;
   -- должно быть 0 строк
   ```

5. **Деплоить V28–V30** (Этап 2 выше).

---

## ✅ Чеклист перед закрытием задачи

- [ ] V25 и V26 применены в dev/staging
- [ ] Решение по V27 принято осознанно (скопирован в `db/migration/` для маленькой
      таблицы, либо явно пропущен в пользу `ItemBarcodeBackfillJob` для большой)
- [ ] Все строки имеют barcode, дублей нет
- [ ] V28–V30 применены в dev/staging
- [ ] Документация прочитана командой
