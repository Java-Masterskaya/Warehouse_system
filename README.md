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
| Monitoring   | Prometheus, Alertmanager, Grafana       |
| Webhook      | Custom webhook-server for alerts        |

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
| Schema Registry  | встроен в Redpanda | 18081 (внутри Docker: 8081) |
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

| Метод    | Путь                                             | Описание                            |
|----------|--------------------------------------------------|-------------------------------------|
| `GET`    | `/api/items`                                     | Список товаров (фильтр, поиск)      |
| `POST`   | `/api/items`                                     | Создать товар                       |
| `PUT`    | `/api/items/{id}`                                | Редактировать товар                 |
| `DELETE` | `/api/items/{id}`                                | Удалить товар                       |
| `GET`    | `/api/items/{itemId}`                            | Карточка и остатки по складам       |
| `GET`    | `/api/items/categories`                          | Список категорий                    |
| `POST`   | `/api/categories`                                | Создать категорию                   |
| `GET`    | `/api/categories/{categoryId}`                   | Получить категорию по ID            |
| `PUT`    | `/api/categories/{categoryId}`                   | Обновить категорию                  |
| `DELETE` | `/api/categories/{categoryId}`                   | Удалить категорию                   |
| `GET`    | `/api/warehouses`                                | Список складов                      |
| `POST`   | `/api/warehouses`                                | Создать склад                       |
| `POST`   | `/api/movements/receive`                         | Зарегистрировать поступление        |
| `POST`   | `/api/movements/write-off`                       | Списать товар                       |
| `POST`   | `/api/movements/transfer`                        | Перевести товар между складами      |
| `GET`    | `/api/movements/{itemId}/history`                | История движения                    |
| `POST`   | `/api/inventory/stocktake`                       | Инвентаризация                      |
| `POST`   | `/api/purchase-orders`                           | Создать заказ поставщику            |
| `GET`    | `/api/purchase-orders`                           | Получить список заказов поставщикам |
| `GET`    | `/api/purchase-orders/{purchaseOrderId}`         | Получить заказ поставщику по ID     |
| `POST`   | `/api/purchase-orders/{purchaseOrderId}/place`   | Разместить заказ у поставщика       |
| `POST`   | `/api/purchase-orders/{purchaseOrderId}/receive` | Принять товар по заказу поставки    |
| `POST`   | `/api/admin/dlq/low-stock/reprocess`             | Реобработка DLT                     |
| `POST`   | `/api/stock/{itemId}/reserve`                    | Резервирование остатков             |
| `POST`   | `/api/stock/{itemId}/release`                    | Отмена резервирования               |
| `POST`   | `/api/stock/{itemId}/write-off`                  | Выкуп резерва                       |
| `GET`    | `/api/reports/low-stock`                         | Товары ниже общего минимума         |
| `GET`    | `/api/reports/stock-valuation`                   | Общая стоимость остатков            |
| `GET`    | `/api/reports/expiring?days=N`                   | Партии с истекающим сроком          |

## Партии и сроки годности

DOM-5 хранит физический остаток как набор партий конкретного товара на конкретном складе.
Для каждой пары `item + warehouse` количество в `stock` поддерживается равным сумме количеств ее партий.

- Поступление создает новую партию с обязательным будущим `expiryDate`.
- Списание и выкуп резерва используют FEFO: сначала расходуется партия с ближайшим сроком.
- Просроченные партии не участвуют в доступном остатке, резервировании, списании и переводе.
- Перевод между складами сохраняет срок годности каждой перенесенной части партии.
- Положительная разница инвентаризации требует `surplusExpiryDate` и создает отдельную партию.
- Ежедневная задача обнуляет просроченные партии и уменьшает остаток именно их склада.
- Отчет `/api/reports/expiring?days=N` показывает истекающие партии с товаром и складом.

## Несколько складов

Миграция V19 создает склад `Default Warehouse` и связывает с ним все старые остатки и движения.
Идентификаторы строк, количества, история и ссылки резервов при этом сохраняются.

- Остаток хранится отдельно для каждой пары `item + warehouse`.
- Старые операции receive, write-off, stocktake, reserve и приемка заказа работают со складом по умолчанию.
- Карточка товара возвращает общий остаток и массив `warehouseStocks` с разбивкой по складам.
- Low-stock и valuation отчеты считают сумму по всем складам.
- История движения содержит склад и `transferId` для двух частей перевода.

Пример перевода, доступного только роли `ADMIN`:

```http
POST /api/movements/transfer
Authorization: Bearer <access-token>
Content-Type: application/json
```

```json
{
  "itemId": 42,
  "fromWarehouseId": 1,
  "toWarehouseId": 2,
  "quantity": 7
}
```

Операция блокирует оба остатка в стабильном порядке, проверяет доступное количество на складе-источнике,
уменьшает источник, увеличивает приемник и сохраняет две записи движения с общим `transferId`.
При нехватке возвращается `422 INSUFFICIENT_STOCK`, а вся транзакция откатывается.

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
  curl -X POST -H "Authorization: Bearer <токен>" http://localhost:8081/actuator/refresh
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

## Алертинг и мониторинг

Проект использует стек Prometheus + Alertmanager + Grafana для мониторинга и алертинга.

### Prometheus

**Что делает:** сбор метрик приложения и оценка правил алертинга.

**Конфигурация:**
- `scrape_interval: 10s` - сбор метрик каждые 10 секунд
- `evaluation_interval: 10s` - проверка правил алертинга каждые 10 секунд
- Подключается к Alertmanager на `alertmanager:9093` для отправки алертов

**Важно:** Для запуска через `docker-compose up` или `make up` **обязательно** должен быть запущен контейнер `warehouse-app`, так как Prometheus собирает метрики с приложения через `/actuator/prometheus`. Если приложение не запущено - алерты не будут работать.

**Настройка цели (target):**
- В Docker-сети: `['warehouse-app:8081']`
- При локальном запуске: `['host.docker.internal:8081']`

### Alertmanager

**Что делает:** получает алерты от Prometheus и отправляет их на webhook-сервер.

**Настроенные алерты:**

| Алерт | Уровень | Описание |
|-------|---------|----------|
| `Brute-force login` | warning | Высокая частота неудачных попыток входа (>5/мин) |
| `Rejected write-off rate high` | warning | Высокая частота отклонённых списаний (>2 за 5мин) |
| `Low-stock alert spike` | info | Пики алертов о низких остатках (>3 за 5мин) |
| `App down` | critical | Приложение недоступно (>1 минута) |
| `JVM heap > 90%` | warning | Использование heap памяти >90% |

### Webhook Server

**Что делает:** простой сервер для приёма алертов в dev-среде. При получении алерта выводит его в консоль.

**Зачем нужен:** позволяет тестировать алертинг без настройки внешних интеграций (Telegram, Slack и т.д.).

**Пример вывода:**
```
============================================================
WEBHOOK ALERT RECEIVED
============================================================
Headers: {...}
Body: {...}
============================================================
```

### Grafana

**Что делает:** визуализация метрик приложения.

**Предустановленные дашборды:** метрики приложения, JVM, Kafka и т.д.

**Доступ:**
- URL: `http://localhost:3000`
- Username: `admin`
- Password: `admin`

**Порты мониторинга:**

| Сервис | Порт |
|--------|------|
| Prometheus | 9090 |
| Alertmanager | 9093 |
| Grafana | 3000 |
| Webhook Server | 8082 |

**Настройка мониторинга:**

```bash
# Запуск мониторинга
make monitor-up
```

```bash
# Остановка мониторинга
make monitor-down
```

**Конфигурационные файлы:**
- `monitoring/prometheus.yml` — настройки Prometheus
- `monitoring/alertmanager/alertmanager.yml` — настройки Alertmanager
- `monitoring/grafana/` — дашборды и дата-источники

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
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
  "expiresIn": 86400000
}
```

| Поле         | Описание                                                                                                    |
|--------------|-------------------------------------------------------------------------------------------------------------|
| accessToken  | JWT токен для доступа к API. Время жизни по умолчанию — 1 сутки (настраивается через консул).               |
| refreshToken | Refresh токен для обновления access токена. Время жизни по умолчанию — 7 дней (настраивается через консул). |
| expiresIn | Время жизни access токена в миллисекундах.                                                                  |

### Обновление access токена
`POST /api/auth/refresh`  
Тело:
```json
{
"refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```
Ответ (200):
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
  "expiresIn": 86400000
}
```
Примечание: При каждом обновлении создаётся новая пара токенов. Старый refresh токен становится недействительным (ротация). Все старые access токены пользователя автоматически добавляются в blacklist.

### Выход из системы
`POST /api/auth/logout`  
Тело:
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```
Ответ (200) OK.  
Примечание: После выхода access токен добавляется в blacklist, refresh токен удаляется из Redis.

### Использование токена

Добавляйте заголовок к каждому защищённому запросу:

```
Authorization: Bearer ваш_access_token
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

### Особенности работы с токенами
- Access токен — короткоживущий (по умолчанию 1 сутки). Используется для доступа к API.

- Refresh токен — долгоживущий (по умолчанию 7 дней). Используется только для получения нового access токена.

- Ротация refresh токена — при каждом обновлении старый refresh токен становится недействительным.

- Защита от повторного использования — если кто-то попытается использовать уже ротированный refresh токен, все токены пользователя будут мгновенно отозваны.

- Мгновенный отзыв доступа — при деактивации пользователя или выходе из системы access токен добавляется в blacklist и перестаёт работать немедленно (не дожидаясь истечения TTL).

- Хранение в Redis — все токены хранятся в Redis для быстрой проверки и отзыва.

- Автоматическое обновление - клиент должен автоматически обновлять access токен при получении 401 Unauthorized, используя refresh токен.
