#!/bin/sh
set -euo pipefail

: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set}"
: "${POSTGRES_USER:=warehouse_user}"
: "${POSTGRES_DB:=warehouse}"

# ------------------------------------------------------------------
# 1. Создаём ~/.pgpass для pg_dump/pg_restore (пароль НЕ в ps)
# ------------------------------------------------------------------
PGPASS_PATH="/root/.pgpass"
echo "postgres:5432:${POSTGRES_DB}:${POSTGRES_USER}:${POSTGRES_PASSWORD}" > "$PGPASS_PATH"
chmod 600 "$PGPASS_PATH"
export PGPASSFILE="$PGPASS_PATH"

# Удаляем пароль из окружения, чтобы он не был виден в /proc/<pid>/environ
unset POSTGRES_PASSWORD
unset PGPASSWORD 2>/dev/null || true

# ------------------------------------------------------------------
# 2. Лениво ставим зависимости (curl всегда, gnupg/aws — по фичам)
# ------------------------------------------------------------------
if ! command -v curl >/dev/null 2>&1; then
    echo "[$(date '+%F %T')] Installing curl..."
    apk add --no-cache curl
fi

if [ -n "${BACKUP_ENCRYPT_KEY:-}" ] && ! command -v gpg >/dev/null 2>&1; then
    echo "[$(date '+%F %T')] Installing gnupg for encryption..."
    apk add --no-cache gnupg
fi

if [ -n "${S3_BUCKET:-}" ] && ! command -v aws >/dev/null 2>&1; then
    echo "[$(date '+%F %T')] Installing aws-cli for S3 upload..."
    apk add --no-cache aws-cli
fi

# ------------------------------------------------------------------
# 3. Настройка cron
# ------------------------------------------------------------------
mkdir -p /var/log
touch /var/log/backup.log

CRON_SCHEDULE="${BACKUP_CRON:-0 2 * * *}"

# Переменные окружения в crontab — busybox crond передаёт их в cron job.
# Без этого backup.sh не увидит PGPASSFILE, BACKUP_ENCRYPT_KEY, S3_BUCKET и т.д.
{
    echo "SHELL=/bin/sh"
    echo "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    echo "HOME=/root"
    echo "PGPASSFILE=${PGPASSFILE}"
    echo "POSTGRES_USER=${POSTGRES_USER}"
    echo "POSTGRES_DB=${POSTGRES_DB}"
    echo "BACKUP_DIR=${BACKUP_DIR:-/backups}"
    echo "BACKUP_RETENTION_COUNT=${BACKUP_RETENTION_COUNT:-10}"
    echo "WEBHOOK_URL=${WEBHOOK_URL:-}"
    echo "HEALTHCHECK_URL=${HEALTHCHECK_URL:-}"
    echo "BACKUP_ENCRYPT_KEY=${BACKUP_ENCRYPT_KEY:-}"
    echo "S3_BUCKET=${S3_BUCKET:-}"
    echo "S3_ENDPOINT=${S3_ENDPOINT:-}"
    echo "TZ=${TZ:-UTC}"
    echo "${CRON_SCHEDULE} sh /backup.sh >> /var/log/backup.log 2>&1"
} | crontab -

echo "[$(date '+%F %T')] Backup entrypoint ready."
echo "[$(date '+%F %T')] Cron schedule: ${CRON_SCHEDULE}"
echo "[$(date '+%F %T')] PGPASSFILE=${PGPASSFILE}"
echo "[$(date '+%F %T')] BACKUP_DIR=${BACKUP_DIR:-/backups}"

# ------------------------------------------------------------------
# 4. Запуск cron на переднем плане (PID 1)
# ------------------------------------------------------------------
exec crond -f -l 2
