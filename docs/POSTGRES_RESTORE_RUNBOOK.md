# Рунбук: Восстановление PostgreSQL из бэкапа

## Цель
Пошаговая инструкция по восстановлению базы данных Warehouse из логического дампа (`pg_dump -Fc`) на чистый Docker volume.

## Предусловия
- Установлены Docker и Docker Compose
- В корне проекта есть файл `.env` с актуальными credentials

## Формат бэкапа
- Custom format (`-Fc`)
- Создаётся ежедневно в 02:00 по расписанию через `postgres-backup` контейнер
- Хранится в named volume `postgres_backups`
- Ротация: не более `BACKUP_RETENTION_COUNT` последних копий

---

## Вариант 1: Восстановление через Makefile

```bash
make backup-restore
```

> Если `make` недоступен — используй Вариант 2.

Интерактивно запросит подтверждение, затем автоматически:
1. Остановит приложение
2. Удалит старый том БД
3. Создаст чистый Postgres
4. Восстановит данные из последнего дампа
5. Запустит приложение

---

## Вариант 2: Пошаговое восстановление вручную

### Шаг 1. Остановить приложение
```bash
docker compose stop warehouse-app
```

### Шаг 2. Удалить старый том БД (⚠️ необратимо!)
```bash
docker compose stop postgres
docker compose rm -f postgres
# Узнать имя volume и удалить
docker volume rm $(docker compose ps -q postgres | xargs -I {} docker inspect --format='{{ range .Mounts }}{{ if eq .Destination "/var/lib/postgresql/data" }}{{ .Name }}{{ end }}{{ end }}' {})
```

> Если `xargs` недоступен — посмотри список volumes: `docker volume ls` и удали нужный вручную.

### Шаг 3. Запустить чистый Postgres
```bash
docker compose up -d postgres
sleep 10
```

Контейнер автоматически создаст БД из `POSTGRES_DB` в `.env`.

### Шаг 4. Определить последний дамп
```bash
docker compose exec postgres-backup sh -c 'ls -lt /backups/'
```

### Шаг 5. Восстановить данные

**Способ А — автоматически из последнего дампа (рекомендуется):**
```bash
docker compose exec postgres-backup sh -c 'LATEST=$(ls -1t /backups/*.dump | head -n1); echo "Restoring from: $LATEST"; pg_restore \
  --clean --if-exists --no-owner --no-acl \
  -h postgres -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  "$LATEST"'
```

**Способ Б — указать имя файла вручную:**
```bash
docker compose exec postgres-backup sh -c 'pg_restore \
  --clean --if-exists --no-owner --no-acl \
  -h postgres -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  "/backups/wh-YYYY-MM-DD-HHMMSS.dump"'
```

> **Важно:** замени `wh-YYYY-MM-DD-HHMMSS.dump` на реальное имя файла из шага 4. Переменные `$POSTGRES_USER` и `$POSTGRES_DB` подставляются автоматически из окружения контейнера.

**Почему эти флаги:**
| Флаг | Назначение |
|---|---|
| `--clean` | Удаляет объекты перед созданием |
| `--if-exists` | Не падает, если удалять нечего |
| `--no-owner` | Сбрасывает OWNER из дампа |
| `--no-acl` | Сбрасывает GRANT/REVOKE из дампа |

### Шаг 6. Проверить flyway_schema_history
```bash
docker compose exec postgres psql -U warehouse_user -d warehouse \
  -c "SELECT version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank;"
```

> Если в `.env` изменены `POSTGRES_USER` / `POSTGRES_DB` — подставь свои значения.

**Ожидаемый результат:** 9 строк (V1–V9), все `success = true`.

### Шаг 7. Запустить приложение
```bash
docker compose up -d warehouse-app
```

### Шаг 8. Проверить работоспособность
```bash
# Health check
curl -s http://localhost:8080/actuator/health/readiness | jq

# Данные на месте
docker compose exec postgres psql -U warehouse_user -d warehouse \
  -c "SELECT COUNT(*) AS users FROM users;" \
  -c "SELECT COUNT(*) AS items FROM items;" \
  -c "SELECT COUNT(*) AS movements FROM stock_movements;"
```

> Если в `.env` изменены `POSTGRES_USER` / `POSTGRES_DB` — подставь свои значения.

### Шаг 9. Проверить Flyway validate
При старте приложение выполнит `flyway.validate()`. Если в логах:
```
Successfully validated 9 migrations
```
— значит всё в порядке.

---

## Альтернатива: восстановление поверх существующей БД
Если том не потерян, а нужно просто откатить данные на момент бэкапа:

```bash
docker compose stop warehouse-app
docker compose exec postgres-backup sh -c 'LATEST=$(ls -1t /backups/*.dump | head -n1); pg_restore \
  --clean --if-exists --no-owner --no-acl \
  -h postgres -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  "$LATEST"'
docker compose start warehouse-app
```

---

## Troubleshooting

### pg_restore: ошибка "database does not exist"
Убедись, что Postgres успел инициализироваться. Контейнер `postgres:16` при первом старте создаёт БД из `POSTGRES_DB`. Подожди 5–10 секунд.

### pg_restore: ошибка прав доступа
Флаги `--no-owner --no-acl` решают 99% проблем. Если остались — проверь, что `POSTGRES_USER` в `.env` совпадает с тем, что был при создании дампа.

### Flyway validate failed после restore
- Проверь `SELECT * FROM flyway_schema_history` — должны быть все 9 миграций.
- Если таблицы нет — значит дамп делался с `--data-only` (не тот случай, но проверь).
- Если миграции есть, но validate падает — возможно, checksum mismatch. Это означает, что файлы миграций в `src/main/resources/db/migration/` изменились после создания дампа. **Никогда не редактируй уже применённые миграции.**


### Нет свободного места в volume
```bash
docker system df -v
docker volume inspect postgres_backups
```

### Нет дампов в /backups/
```bash
# Проверить, что volume подключён
docker inspect postgres-backup --format='{{ range .Mounts }}{{ .Type }}: {{ .Destination }}{{ println }}{{ end }}'

# Создать дамп вручную
docker compose exec postgres-backup sh -c '/backup.sh'
```
