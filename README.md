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

## Быстрый старт

```bash
# 1. Скопируй env-файл и заполни переменные
# (создай .env на основе .env.example с реальными значениями)
cp .env.example .env

# 2. Поднять инфраструктуру + приложение
docker compose up --build
```

```bash
# Или через Makefile
make up

# 3. API доступен на
http://localhost:8080
```

## Переменные окружения

Все необходимые переменные описаны в `.env.example`. Файл `.env`:

- **Не коммитится** в git (указан в `.gitignore`)
- **Содержит реальные значения** (пароли, хосты, секреты)
- **Копируется из `.env.example`** и заполняется конкретными значениями перед запуском

## Инфраструктура

| Сервис           | Образ              | Порт |
|------------------|--------------------|------|
| PostgreSQL       | postgres:16        | 5432 |
| Redis            | redis:7-alpine     | 6379 |
| Redpanda (Kafka) | redpanda:v23.2.11  | 9092 |
| Schema Registry  | встроен в Redpanda | 8081 |

## Роли

| Роль    | Права                                |
|---------|--------------------------------------|
| `ADMIN` | CRUD товаров, поступления, списания  |
| `USER`  | Просмотр каталога, остатков, истории |

## Основные эндпоинты

| Метод    | Путь                              | Описание                       |
|----------|-----------------------------------|--------------------------------|
| `GET`    | `/api/items`                      | Список товаров (фильтр, поиск) |
| `POST`   | `/api/items`                      | Создать товар                  |
| `PUT`    | `/api/items/{id}`                 | Редактировать товар            |
| `DELETE` | `/api/items/{id}`                 | Удалить товар                  |
| `GET`    | `/api/items/{id}/stock`           | Текущий остаток                |
| `POST`   | `/api/movements/receive`          | Зарегистрировать поступление   |
| `POST`   | `/api/movements/write-off`        | Списать товар                  |
| `GET`    | `/api/movements/{itemId}/history` | История движения               |

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
```

## Kafka события

- `LowStockAlertEvent` — публикуется при списании, если остаток падает ниже минимального порога

## Разработка

```bash
# Только инфраструктура (без приложения)
docker compose up postgres redis kafka -d
```

```bash
# Или через Makefile
make infra-up
```

```bash
# Запустить приложение локально
./gradlew bootRun
```

```bash
# Или через Makefile
make app-up
```

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

### Переменные окружения

Создайте в корне проекта файл `.env` на основе `.env.example` и укажите значения:

```ini
SPRING_PROFILES_ACTIVE=dev          # или stg для стенда
JWT_SECRET=ваш_очень_длинный_ключ  # минимум 256 бит
JWT_EXPIRATION_MS=86400000          # время жизни токена в мс (по умолч. 24 ч)
```

### Запуск для локальной разработки

1. Поднимите инфраструктуру: `docker-compose up -d postgres redis kafka`
2. Запустите приложение: `./gradlew bootRun`
3. Приложение стартует с профилем `dev`, миграции Flyway создадут администратора по умолчанию.

### Учётные данные по умолчанию

Напишу в телеграме.

Эти данные действуют **только для локальной разработки**. На стенде и в продакшене используйте собственные учётные
записи.

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