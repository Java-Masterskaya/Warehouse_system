# Makefile для управления стеком приложения Warehouse System
.PHONY: up down app-up app-down infra-up infra-down consul-up consul-down health liveness readiness test build checkstyle clean help backup-now backup-list backup-restore backup-test

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

## --- Backup & Restore ---

backup-now: ## Создать бэкап немедленно
	docker compose exec postgres-backup /backup.sh

backup-list: ## Показать список бэкапов
	@docker compose exec postgres-backup sh -c 'ls -lt /backups/*.dump 2>/dev/null || echo "No backups found"'

backup-restore: ## Восстановить из последнего бэкапа (⚠️ уничтожит текущие данные!)
	@echo "⚠️  This will DESTROY current database and restore from the latest backup!"
	@read -p "Are you sure? [y/N] " confirm && [ "$$confirm" = "y" ] || exit 1
	docker compose stop warehouse-app
	docker compose rm -f postgres
	docker volume rm $$(docker compose ps -q postgres | xargs -I {} docker inspect --format='{{.Mounts}}' {} | grep -oP 'postgres_data[^ ]*' | head -1) 2>/dev/null || true
	docker compose up -d postgres
	@sleep 5
	LATEST=$$(docker compose exec postgres-backup sh -c 'ls -1t /backups/*.dump 2>/dev/null | head -n1'); \
	if [ -z "$$LATEST" ]; then echo "No backup found!"; exit 1; fi; \
	echo "Restoring from: $$LATEST"; \
	docker compose exec -e PGPASSWORD=$$(grep POSTGRES_PASSWORD .env | cut -d= -f2) postgres-backup \
		pg_restore --clean --if-exists --no-owner --no-acl -h postgres -U $$(grep POSTGRES_USER .env | cut -d= -f2) -d $$(grep POSTGRES_DB .env | cut -d= -f2) "$$LATEST"
	docker compose up -d warehouse-app
	@echo "Restore complete. Check readiness: make readiness"

backup-test: ## E2E-тест: бэкап → destroy → restore → проверка
	@echo "=== OPS-4 Backup/Restore E2E Test ==="
	docker compose up -d
	@sleep 30
	@echo "[1/5] Creating backup..."
	docker compose exec postgres-backup /backup.sh
	@echo "[2/5] Stopping app and destroying DB volume..."
	docker compose stop warehouse-app
	docker compose rm -f postgres
	-docker volume rm $$(docker compose ps -q postgres 2>/dev/null | xargs -I {} docker inspect --format='{{.Mounts}}' {} 2>/dev/null | grep -oP 'postgres_data[^ ]*' | head -1) 2>/dev/null || true
	@echo "[3/5] Starting fresh Postgres..."
	docker compose up -d postgres
	@sleep 10
	@echo "[4/5] Restoring from latest backup..."
	LATEST=$$(docker compose exec postgres-backup sh -c 'ls -1t /backups/*.dump 2>/dev/null | head -n1'); \
	if [ -z "$$LATEST" ]; then echo "FAIL: No backup found!"; exit 1; fi; \
	docker compose exec -e PGPASSWORD=$$(grep POSTGRES_PASSWORD .env | cut -d= -f2) postgres-backup \
		pg_restore --clean --if-exists --no-owner --no-acl -h postgres -U $$(grep POSTGRES_USER .env | cut -d= -f2) -d $$(grep POSTGRES_DB .env | cut -d= -f2) "$$LATEST"
	@echo "[5/5] Starting app and verifying..."
	docker compose up -d warehouse-app
	@sleep 15
	curl -sf http://localhost:8080/actuator/health/readiness && echo "✅ App is ready" || (echo "❌ App healthcheck failed"; exit 1)
	docker compose exec -e PGPASSWORD=$$(grep POSTGRES_PASSWORD .env | cut -d= -f2) postgres \
		psql -U $$(grep POSTGRES_USER .env | cut -d= -f2) -d $$(grep POSTGRES_DB .env | cut -d= -f2) \
		-c "SELECT COUNT(*) AS flyway_migrations FROM flyway_schema_history;" \
		|| (echo "❌ Flyway validation failed"; exit 1)
	@echo "✅ OPS-4 E2E test PASSED"
