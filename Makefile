# Makefile для управления стеком приложения Warehouse System
.PHONY: up down app-up app-down infra-up infra-down consul-up consul-down health test build checkstyle help

## --- Управление всем стеком ---
up: ## Запуск всего стека (инфраструктура, приложение)
	docker-compose up -d --build

down: ## Остановка всех контейнеров
	docker-compose down

## --- Управление инфраструктурой (БД, Kafka) ---
infra-up: ## Запуск только инфраструктуры (PostgreSQL, Redis, Kafka, Consul + Seed)
	docker-compose up -d postgres redis kafka consul consul-seed

infra-down: ## Остановка инфраструктуры
	docker-compose down postgres redis kafka consul consul-seed --remove-orphans

## --- Управление Consul ---
consul-up: ## Запуск только Consul и Seed
	docker-compose up -d consul consul-seed

consul-down: ## Остановка Consul
	docker-compose down consul consul-seed

## --- Управление приложением ---
app-up: ## Запуск приложения в терминале (инфраструктура должна быть запущена через docker-compose)
	./gradlew bootRun

app-down: ## Остановка приложения (SIGTERM)
	@echo "Приложение запущено в терминале, остановите его вручную через Ctrl+C"

## --- Проверка работоспособности ---
health: ## Проверка работоспособности через Actuator
	@echo "Проверка статуса приложения..."
	@curl -s -H "Accept: application/json" http://localhost:8080/actuator/health

liveness: ## Проверка liveness-пробы
	@echo "Проверка liveness..."
	@curl -s -H "Accept: application/json" http://localhost:8080/actuator/health/liveness

readiness: ## Проверка readiness-пробы
	@echo "Проверка readiness..."
	@curl -s -H "Accept: application/json" http://localhost:8080/actuator/health/readiness

## --- Тестирование ---
test: ## Запуск тестов
	./gradlew clean test

## --- Сборка проекта ---
build: ## Сборка проекта с тестами и проверкой стиля
	./gradlew clean build

## --- Проверка стиля ---
checkstyle: ## Проверка стиля кода
	./gradlew checkstyleMain checkstyleTest

## --- Вспомогательные команды ---
clean: ## Остановка и удаление контейнеров и томов
	docker-compose down -v --remove-orphans

help: ## Показать эту справку
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z_-]+:.*?## / {printf "\033[36m%-15s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)