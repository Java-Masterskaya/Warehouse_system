# OPS-5: Пошаговый гайд деплоя barcode (Zero-Downtime)

## ⚡ Быстрый старт

```bash
# Этап 1 (этот PR)
git checkout main && git pull
# деплой V20 + V21 + Java-код
# запуск backfill: curl -X POST /admin/backfill/barcode
# проверка: SELECT COUNT(*) FROM items WHERE barcode IS NULL;  → 0

# Этап 2 (отдельный деплой, когда убедились)
# раскомментировать/применить V22 вручную
```

---

## 📋 Подробная инструкция

### Этап 1: Expand + Backfill (V20 + V21)

**Что деплоится:**
- `V20__add_items_barcode_nullable.sql` — добавляет nullable колонку
- `V21__backfill_items_barcode.sql` — заполняет существующие строки
- Java-код (ItemServiceImpl генерирует barcode автоматически)
- ItemBarcodeBackfillJob (на случай, если таблица большая)

**Проверка перед деплоем:**
- [ ] `./gradlew flywayValidate` проходит
- [ ] `./gradlew test` проходит

**После деплоя:**
- [ ] Старый код продолжает работать (не знает про barcode — OK)
- [ ] Новый код создаёт товары с barcode
- [ ] Запустить backfill для подстраховки: `POST /admin/backfill/barcode`
- [ ] Проверить: `SELECT COUNT(*) FROM items WHERE barcode IS NULL;` → `0`

### Этап 2: Contract (V22) — ОТДЕЛЬНО!

**Когда можно деплоить:**
1. ВСЕ инстансы приложения обновлены (старый код не работает)
2. `SELECT COUNT(*) FROM items WHERE barcode IS NULL;` → `0`
3. Backfill завершён

**Что деплоится:**
- `V22__set_items_barcode_not_null.sql` — навешивает NOT NULL

**Важно:**
- Это короткая блокировка (ACCESS EXCLUSIVE)
- Для больших таблиц — деплоить в окно низкой нагрузки
- Если есть NULL-строки — миграция УПАДЁТ

---

## 🚨 Почему нельзя V22 вместе с V20/V21

Если применить V22 сразу, а у вас rolling deploy:

```
[Старый инстанс] → INSERT без barcode → БД с NOT NULL → 💥 ERROR 500
```

Пользователь получит ошибку. Поэтому V22 — только после полного обновления всех инстансов.

---

## 🔧 Что делать, если V21 (SQL-backfill) опасен для продакшена

Если в таблице `items` > 100 000 строк:

1. **Сделать V21 no-op:**
   ```sql
   -- V21__backfill_items_barcode.sql
   SELECT 1; -- backfill выполняется через Java job
   ```

2. **Запустить Java-job:**
   ```bash
   curl -X POST http://app/admin/backfill/barcode?batchSize=500
   ```

3. **Убедиться, что всё заполнено:**
   ```sql
   SELECT COUNT(*) FROM items WHERE barcode IS NULL;
   -- должно быть 0
   ```

4. **Деплоить V22**

---

## ✅ Чеклист перед закрытием задачи

- [ ] V20 применена в dev/staging
- [ ] V21 применена (или backfill job завершён)
- [ ] Все строки имеют barcode
- [ ] V22 применена в dev/staging
- [ ] Документация прочитана командой
