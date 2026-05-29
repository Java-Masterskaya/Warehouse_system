# Warehouse System

Backend-сервис учёта товарных запасов на складе.

## Стек

| Слой | Технология |
|------|-----------|
| Language | Java 21 |
| Framework | Spring Boot 3 (Web, Data JPA, Security) |
| DB | PostgreSQL |
| Cache | Redis |
| Messaging | Kafka |
| Build | Gradle |
| Infra | Docker, Docker Compose |

## Быстрый старт

```bash
# 1. Скопируй env-файл и заполни переменные
cp .env.example .env

# 2. Поднять инфраструктуру + приложение
docker compose up --build

# 3. API доступен на
http://localhost:8080
```

## Переменные окружения

Все переменные описаны в `.env.example`. Файл `.env` в git не коммитится.

## Роли

| Роль | Права |
|------|-------|
| `ADMIN` | CRUD товаров, поступления, списания |
| `USER` | Просмотр каталога, остатков, истории |

## Основные эндпоинты

| Метод | Путь | Описание |
|-------|------|----------|
| `GET` | `/api/items` | Список товаров (фильтр, поиск) |
| `POST` | `/api/items` | Создать товар |
| `PUT` | `/api/items/{id}` | Редактировать товар |
| `DELETE` | `/api/items/{id}` | Удалить товар |
| `GET` | `/api/items/{id}/stock` | Текущий остаток |
| `POST` | `/api/movements/receive` | Зарегистрировать поступление |
| `POST` | `/api/movements/write-off` | Списать товар |
| `GET` | `/api/movements/{itemId}/history` | История движения |

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
```

## Kafka события

- `LowStockAlertEvent` — публикуется при списании, если остаток падает ниже минимального порога

## Разработка

```bash
# Только инфраструктура (без приложения)
docker compose up postgres redis kafka -d

# Запустить приложение локально
./gradlew bootRun
```