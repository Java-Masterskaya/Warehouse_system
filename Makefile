# Makefile для управления стеком приложения Warehouse System
.PHONY: up down app-up app-down infra-up infra-down health test

## --- Управление всем стеком ---
up: ## Запуск всего стека (инфраструктура, приложение)
	docker-compose up -d --build

down: ## Остановка всех контейнеров
	docker-compose down

## --- Управление инфраструктурой (БД, Kafka) ---
infra-up: ## Запуск только инфраструктуры (PostgreSQL, Redis, Kafka)
	docker-compose up -d postgres kafka redis

infra-down: ## Остановка инфраструктуры
	docker-compose down postgres kafka redis --remove-orphans

## --- Управление приложением ---
app-up: ## Запуск приложения в терминале (инфраструктура должна быть запущена через docker-compose)
	./gradlew bootRun --args='--spring.profiles.active=dev'

app-down: ## Остановка приложения (SIGTERM)
	@echo "Приложение запущено в терминале, остановите его вручную через Ctrl+C"

## --- Проверка работоспособности ---
health: ## Проверка работоспособности через Actuator
	@echo "Проверка статуса приложения..."
	@curl -s -H "Accept: application/json" http://localhost:8080/actuator/health

## --- Тестирование ---
test: ## Запуск тестов
	./gradlew clean test

## --- Проверка стиля ---
checkstyle: ## Проверка стиля кода
	./gradlew checkstyleMain checkstyleTest

## --- Вспомогательные команды ---
clean: ## Остановка и удаление контейнеров и томов
	docker-compose down -v --remove-orphans

help: ## Показать эту справку
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z_-]+:.*?## / {printf "\033[36m%-15s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)
