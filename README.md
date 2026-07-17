# Warehouse System

Backend-сервис учёта товарных запасов на складе.

## Стек

| Слой         | Технология                              |
|--------------|-----------------------------------------|
| Language     | Java 21                                 |
| Framework    | Spring Boot 3 (Web, Data JPA, Security) |
| DB           | PostgreSQL                              |
| Cache        | Redis                                   |
| Messaging    | Kafka                                   |
| Build        | Gradle                                  |
| Code Quality | Checkstyle                              |
| Infra        | Docker, Docker Compose                  |
| Config       | Consul (Centralized Configuration)      |

## Быстрый старт

```bash
# 1. Скопируй env-файл и заполни переменные
# (создай .env на основе .env.example с реальными значениями)
cp .env.example .env
```

```bash
# 2. Поднять инфраструктуру + приложение
docker compose up --build
```

```bash
# Или через Makefile
make up
```

## Переменные окружения

Все необходимые переменные описаны в `.env.example`. Файлы `.env` и `.env.local`:

- **Не коммитятся** в git (указаны в `.gitignore`)
- **Содержат реальные значения** (пароли, хосты, секреты)
- **Копируются из `.env.example`** и заполняются конкретными значениями перед запуском

### Какой файл использовать?

| Сценарий | Файл | Назначение |
|----------|------|------------|
| Запуск всего стека через `docker-compose` | `.env` | Переменные для Docker-контейнеров (базы, Kafka, приложение в контейнере) |
| Локальный запуск приложения | `.env.local` | Переменные для локальной разработки (перезаписывают `.env`) |

**Важно:** При локальном запуске через `make app-up` или `./gradlew bootRun` приоритет у `.env.local` — он загружается вторым и перезаписывает переменные из `.env`.

## Инфраструктура

| Сервис           | Образ              | Порт |
|------------------|--------------------|------|
| PostgreSQL       | postgres:16        | 5432 |
| Redis            | redis:7-alpine     | 6379 |
| Redpanda (Kafka) | redpanda:v23.2.11  | 9092 (внутри Docker: 29092) |
| Schema Registry  | встроен в Redpanda | 8081 |
| Consul           | hashicorp/consul:1.16 | 8500 (UI) |

## Бэкап и восстановление БД

| Команда | Описание |
|---------|----------|
| `make backup-now` | Создать дамп немедленно |
| `make backup-list` | Показать список дампов |
| `make backup-status` | Проверить статус и свежесть последнего бэкапа |
| `make backup-restore` | Восстановить из последнего дампа (⚠️ разрушительно) |
| `make backup-test` | E2E-тест цикла backup → restore |

Расписание: ежедневно в 02:00 (настраивается через TZ в .env, по умолчанию Europe/Moscow)
через `postgres-backup` контейнер.

**Шифрование:** если задан `BACKUP_ENCRYPT_KEY` в `.env`, дампы шифруются GPG (AES-256).  
**Offsite:** если задан `S3_BUCKET`, успешные дампы дублируются в S3.  
**Безопасность:** пароль передаётся через `~/.pgpass` (`PGPASSFILE`), не виден в `ps` или `/proc`.

Подробный рунбук: [`docs/POSTGRES_RESTORE_RUNBOOK.md`](docs/POSTGRES_RESTORE_RUNBOOK.md)

## Роли

| Роль    | Права                                |
|---------|--------------------------------------|
| `ADMIN` | CRUD товаров, поступления, списания  |
| `USER`  | Просмотр каталога, остатков, истории |

## Основные эндпоинты

| Метод    | Путь                                 | Описание                      |
|----------|--------------------------------------|-------------------------------|
| `GET`    | `/api/items`                         | Список товаров (фильтр, поиск) |
| `POST`   | `/api/items`                         | Создать товар                 |
| `PUT`    | `/api/items/{id}`                    | Редактировать товар           |
| `DELETE` | `/api/items/{id}`                    | Удалить товар                 |
| `GET`    | `/api/items/{id}/stock`              | Текущий остаток               |
| `POST`   | `/api/movements/receive`             | Зарегистрировать поступление  |
| `POST`   | `/api/movements/write-off`           | Списать товар                 |
| `GET`    | `/api/movements/{itemId}/history`    | История движения              |
| `POST`   | `/api/inventory/stocktake`           | Инвентаризация                |
| `POST`   | `/api/admin/dlq/low-stock/reprocess` | Реобработка DLT               |
| `POST`   | `/api/stock/{itemId}/reserve`        | Резервирование остатков       |
| `POST`   | `/api/stock/{itemId}/release`        | Отмена резервирования         |
| `POST`   | `/api/stock/{itemId}/write-off`      | Выкуп резерва                 |

Полная спецификация: `docs/warehouse_openapi.yaml`

## Архитектура

```
HTTP Request
    │
    ▼
Spring Security (JWT + роли)
    │
    ▼
Controller → Service → Repository (JPA)
                │              │
                │         PostgreSQL
                │
         ┌──────┴──────┐
         │             │
       Redis          Kafka
    (кэш карточек)  (LowStockAlertEvent)
         │
         ▼
       Consul (Centralized Config)
```

## Kafka события

- `LowStockAlertEvent` — публикуется при списании, если остаток падает ниже минимального порога

## Централизованная конфигурация (Consul KV)

Приложение использует Consul KV как централизованное хранилище конфигурации. Бизнес-параметры хранятся в Consul и могут быть изменены без перезапуска приложения.

| Параметр              | Описание                       | Значение по умолчанию |
|-----------------------|--------------------------------|-----------------------|
| app.jwt.expiration-ms | Время жизни JWT токена (мс)    | 86400000 (24 часа)    |
| redis.cache.categories-ttl-minutes | TTL кэша категорий (минуты) | 10          |
| redis.cache.item-ttl-minutes | TTL кэша товаров (минуты)| 5                    |

Запуск Consul
Consul автоматически поднимается через docker-compose:
```bash
docker compose up -d consul consul-seed
```
При первом запуске Consul Seed автоматически загружает начальную конфигурацию из `monitoring/consul/warehouse-config.yaml`.

### Как обновить настройки
Способ 1. Через загрузку YAML-файла (рекомендуется)
Отредактируйте файл monitoring/consul/warehouse-config.yaml:
```yaml
app:
  jwt:
    expiration-ms: 86400000
redis:
  cache:
    categories-ttl-minutes: 45
    item-ttl-minutes: 5
```
Загрузите его в Consul:
```bash
curl -X PUT --data-binary @monitoring/consul/warehouse-config.yaml \
http://localhost:8500/v1/kv/config/warehouse-system/data
```
* Если находитесь в папке с файлом, то вместо "@monitoring/consul/warehouse-config.yaml" сразу пишем 
"@warehouse-config.yaml"
* Приложение автоматически применит изменения (в течение 1 секунды, благодаря ConfigWatch), либо:
```bash
  curl -X POST -H "Authorization: Bearer <токен>" http://localhost:8080/actuator/refresh
```
Способ 2. Через Consul UI
Откройте http://localhost:8500
Перейдите в Key/Value → config/warehouse-system/data  
Нажмите Edit и измените значения  
Нажмите Save  


## Разработка

### Запуск всего стека (инфраструктура + приложение)

```bash
# Сначала создайте .env из .env.example
cp .env.example .env
```
```bash
# Запустить через docker-compose
docker compose up --build
```
```bash
# Или через Makefile
make up
```

### Локальный запуск приложения (без Docker)

```bash
# 1. Скопируйте файлы конфигурации
cp .env.example .env.local

```
```bash
# 2. В .env.local укажите локальные значения переменных:
# - SPRING_PROFILES_ACTIVE=dev (или другой профиль)
# - Хосты баз данных и kafka: localhost вместо имен контейнеров
# - Пароли и секреты
# - СМ. Детали ниже - порт кафки при локальном запуске меняется на 9092
```
```bash

# 3. Поднимите только инфраструктуру
docker compose up postgres redis kafka consul consul-seed -d
```
```bash
# Или через Makefile
make infra-up
```
```bash
# 4. Запустите приложение локально
./gradlew bootRun
```
```bash
# Или через Makefile
make app-up
```

**Важно:** При локальном запуске через `./gradlew bootRun` или `make app-up` приоритет у `.env.local` — он загружается вторым и перезаписывает переменные из `.env`.

## Контроль качества кода

В проекте используется Checkstyle для проверки стиля кода.

**Проверка стиля:**

```bash
# Через Gradle
./gradlew checkstyleMain checkstyleTest
```

```bash
# Через Makefile
make checkstyle
```

Проверка запускается автоматически при:

- `./gradlew build`
- `./gradlew check`
- CI/CD (GitHub Actions)

## Аутентификация и авторизация (JWT)

### Переменные окружения для локальной разработки

Создайте в корне проекта файл `.env.local` на основе `.env.example` и укажите локальные значения:

```ini
SPRING_PROFILES_ACTIVE=dev
JWT_SECRET=ваш_очень_длинный_ключ
JWT_EXPIRATION_MS=86400000

# Локальные хосты для разработки
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
REDIS_HOST=localhost
REDIS_PORT=6379
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
```

**Важно:** При локальном запуске приложения (через `./gradlew bootRun` или `make app-up`) переменные из `.env.local` перезаписывают значения из `.env`.

### Запуск для локальной разработки

1. Поднимите инфраструктуру: `docker-compose up -d postgres redis kafka consul consul-seed`
2. Запустите приложение: `./gradlew bootRun`
3. Приложение стартует с профилем `dev`, миграции Flyway создадут администратора по умолчанию.

### Учётные данные по умолчанию

Для локальной разработки создан admin пользователь:

| Поле | Значение |
|------|----------|
| **Username** | `admin`  |
| **Password** | `secret` |

Эти данные создаются автоматически при первом запуске приложения через Flyway миграцию.

**Важно:** Эти учётные данные действуют **только для локальной разработки**!

### Получение токена

`POST /api/auth/login`  
Тело:

```json
{
  "username": "username из телеграм",
  "password": "password из телеграм"
}
```

Ответ (200):

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "expiresIn": 86400000
}
```

### Использование токена

Добавляйте заголовок к каждому защищённому запросу:

```
Authorization: Bearer ваш_токен
```

### Роли и доступ

- `ROLE_ADMIN` – полный доступ, включая создание/редактирование пользователей и товаров.
- `ROLE_USER` – только чтение (просмотр каталога, истории, остатков).

Эндпоинты, требующие определённой роли, помечены аннотацией `@PreAuthorize`, например:

```java
@PreAuthorize("hasRole('ADMIN')")
```

### Ошибки аутентификации

- 401 `UNAUTHORIZED` – токен отсутствует, невалиден или просрочен.
- 403 `ACCESS_DENIED` – недостаточно прав (роль не соответствует требуемой).