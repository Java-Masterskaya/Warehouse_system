# Makefile для управления стеком приложения Warehouse System
.PHONY: up down app-up app-down infra-up infra-down consul-up consul-down health liveness readiness test build checkstyle clean help backup-now backup-list backup-restore backup-test backup-status

## --- Управление всем стеком ---
up: ## Запуск всего стека (инфраструктура, приложение)
	docker-compose up -d --build

down: ## Остановка всех контейнеров
	docker-compose down

## --- Управление инфраструктурой (БД, Kafka, Consul) ---
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
	@docker compose exec postgres-backup sh /backup.sh

backup-list: ## Показать список бэкапов
	@docker compose exec postgres-backup sh -c 'ls -lt /backups/*.dump* 2>/dev/null || echo "No backups found"'

backup-restore: ## Восстановить из последнего бэкапа (⚠️ уничтожит текущие данные!)
	@echo "⚠️  This will DESTROY current database and restore from the latest backup!"
	@read -p "Are you sure? [y/N] " confirm && [ "$$confirm" = "y" ] || exit 1
	@echo "[1/4] Stopping services..."
	@docker compose stop warehouse-app 2>/dev/null || true
	@VOLUME_NAME=$$(docker compose ps -q postgres 2>/dev/null | xargs -I {} docker inspect --format='{{ range .Mounts }}{{ if eq .Destination "/var/lib/postgresql/data" }}{{ .Name }}{{ end }}{{ end }}' {} 2>/dev/null | head -1); \
		if [ -z "$$VOLUME_NAME" ]; then echo "❌ FAIL: Could not determine postgres volume"; exit 1; fi; \
		echo "   Volume to destroy: $$VOLUME_NAME"; \
		echo "[2/4] Destroying postgres container and volume..."; \
		docker compose stop postgres 2>/dev/null || true; \
		docker compose rm -f postgres 2>/dev/null || true; \
		docker volume rm -f "$$VOLUME_NAME" 2>/dev/null || { echo "❌ FAIL: Could not remove volume $$VOLUME_NAME"; exit 1; }; \
		echo "[3/4] Starting fresh Postgres..."; \
		docker compose up -d postgres; \
		for i in $$(seq 1 30); do \
			STATUS=$$(docker compose ps postgres --format "{{.Health}}" 2>/dev/null || echo ""); \
			if [ "$$STATUS" = "healthy" ]; then echo "   ✅ Postgres is healthy"; break; fi; \
			if [ $$i -eq 30 ]; then echo "   ❌ FAIL: Postgres did not become healthy"; exit 1; fi; \
			sleep 1; \
		done; \
		docker compose up -d postgres-backup; \
		echo "[4/4] Restoring from latest backup..."; \
		LATEST=$$(docker compose exec -T postgres-backup sh -c 'ls -1t /backups/*.dump* 2>/dev/null | head -n1'); \
		if [ -z "$$LATEST" ]; then echo "❌ FAIL: No backup found!"; exit 1; fi; \
		echo "   📦 Restoring from: $$LATEST"; \
		MSYS_NO_PATHCONV=1 docker compose exec -T postgres-backup sh /restore.sh "$$LATEST"; \
		docker compose up -d warehouse-app; \
		echo "✅ Restore complete. Check: make readiness"

backup-test: ## E2E-тест: бэкап → destroy → restore → проверка (весь стек)
	@echo "=== OPS-4 Backup/Restore E2E Test ==="
	@echo "[0/5] Starting full stack..."
	@docker compose up -d
	@sleep 30
	@echo "[1/5] Creating backup..."
	@docker compose exec -T postgres-backup sh /backup.sh
	@echo "[2/5] Stopping services and destroying postgres..."
	@docker compose stop warehouse-app 2>/dev/null || true
	@VOLUME_NAME=$$(docker compose ps -q postgres 2>/dev/null | xargs -I {} docker inspect --format='{{ range .Mounts }}{{ if eq .Destination "/var/lib/postgresql/data" }}{{ .Name }}{{ end }}{{ end }}' {} 2>/dev/null | head -1); \
		if [ -z "$$VOLUME_NAME" ]; then echo "❌ FAIL: Could not determine postgres volume"; exit 1; fi; \
		echo "   Volume to destroy: $$VOLUME_NAME"; \
		docker compose stop postgres 2>/dev/null || true; \
		docker compose rm -f postgres 2>/dev/null || true; \
		docker volume rm -f "$$VOLUME_NAME" 2>/dev/null || { echo "❌ FAIL: Could not remove volume $$VOLUME_NAME"; exit 1; }; \
		echo "[3/5] Starting fresh Postgres..."; \
		docker compose up -d postgres; \
		for i in $$(seq 1 30); do \
			STATUS=$$(docker compose ps postgres --format "{{.Health}}" 2>/dev/null || echo ""); \
			if [ "$$STATUS" = "healthy" ]; then echo "   ✅ Postgres is healthy"; break; fi; \
			if [ $$i -eq 30 ]; then echo "   ❌ FAIL: Postgres did not become healthy"; exit 1; fi; \
			sleep 1; \
		done; \
		docker compose up -d postgres-backup; \
		echo "[4/5] Restoring from latest backup..."; \
		LATEST=$$(docker compose exec -T postgres-backup sh -c 'ls -1t /backups/*.dump* 2>/dev/null | head -n1'); \
		if [ -z "$$LATEST" ]; then echo "❌ FAIL: No backup found!"; exit 1; fi; \
		echo "   📦 Restoring from: $$LATEST"; \
		MSYS_NO_PATHCONV=1 docker compose exec -T postgres-backup sh /restore.sh "$$LATEST"; \
		echo "[5/5] Starting app and verifying..."; \
		docker compose up -d warehouse-app; \
		for i in $$(seq 1 30); do \
			STATUS=$$(docker compose ps warehouse-app --format "{{.Health}}" 2>/dev/null || echo ""); \
			if [ "$$STATUS" = "healthy" ]; then echo "   ✅ App is ready"; break; fi; \
			if [ $$i -eq 30 ]; then echo "   ❌ App healthcheck failed"; exit 1; fi; \
			sleep 1; \
		done; \
		docker compose exec -T postgres sh -c \
			'psql -U "$${POSTGRES_USER}" -d "$${POSTGRES_DB}" \
			-c "SELECT CASE WHEN EXISTS (SELECT 1 FROM flyway_schema_history WHERE success = false) THEN '\''FAILED'\'' ELSE '\''OK'\'' END AS result;"' \
			| grep -q OK \
			|| { echo "   ❌ Flyway validation failed"; exit 1; }; \
		echo "✅ OPS-4 E2E test PASSED"

backup-status: ## Проверить статус последнего бэкапа
	@docker compose exec postgres-backup sh -c ' \
		if [ -f /backups/.last_success ]; then \
			LAST=$$(cat /backups/.last_success); \
			AGE=$$(( $$(date +%s) - LAST )); \
			HOURS=$$(( AGE / 3600 )); \
			echo "Last successful backup: $$(date -d @"$$LAST" "+%F %T") ($${HOURS}h ago)"; \
			[ $$AGE -lt 90000 ] && echo "✅ Backup is fresh" || echo "❌ Backup is stale (>25h)"; \
		else \
			echo "❌ No success marker found"; \
		fi'
	@docker compose ps postgres-backup --format "Status: {{.Status}} | Health: {{.Health}}"
