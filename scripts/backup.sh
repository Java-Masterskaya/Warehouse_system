#!/bin/sh
set -euo pipefail

: "${POSTGRES_USER:=warehouse_user}"
: "${POSTGRES_DB:=warehouse}"
: "${BACKUP_DIR:=/backups}"
: "${BACKUP_RETENTION_COUNT:=10}"
: "${WEBHOOK_URL:=}"
: "${BACKUP_ENCRYPT_KEY:=}"
: "${S3_BUCKET:=}"
: "${S3_ENDPOINT:=}"

TIMESTAMP=$(date +%F-%H%M%S)
TMPFILE="${BACKUP_DIR}/.wh-${TIMESTAMP}.dump.tmp"
DUMPFILE="${BACKUP_DIR}/wh-${TIMESTAMP}.dump"
SUCCESS_MARKER="${BACKUP_DIR}/.last_success"

# ------------------------------------------------------------------
# alert — отправка уведомления о падении с retry
# ------------------------------------------------------------------
alert() {
    local message="$1"
    local attempt=0
    echo "[$(date '+%F %T')] ALERT: $message" >&2

    if [ -n "$WEBHOOK_URL" ] && ! command -v curl >/dev/null 2>&1; then
        echo "[$(date '+%F %T')] WARN: curl not found, webhook disabled" >&2
        return
    fi

    while [ -n "$WEBHOOK_URL" ] && [ "$attempt" -lt 3 ]; do
        if curl -fsS --max-time 15 -X POST \
            -H 'Content-type: application/json' \
            --data "{\"text\":\"🚨 Warehouse backup failed: $message\"}" \
            "$WEBHOOK_URL" >/dev/null 2>&1; then
            break
        fi
        attempt=$((attempt + 1))
        [ "$attempt" -lt 3 ] && sleep 5
    done
}

# ------------------------------------------------------------------
# on_exit — cleanup + alert только при ненулевом exit code
# ------------------------------------------------------------------
on_exit() {
    local code=$?
    [ -f "${TMPFILE}" ] && rm -f "${TMPFILE}"
    if [ "$code" -ne 0 ]; then
        alert "backup script failed (exit $code) on $(hostname)"
    fi
    exit $code
}
trap on_exit EXIT

# ------------------------------------------------------------------
# 1. Проверка свободного места (минимум 500 MB)
# ------------------------------------------------------------------
AVAILABLE_KB=$(df -k "${BACKUP_DIR}" | awk 'NR==2 {print $4}')
if [ "${AVAILABLE_KB:-0}" -lt 512000 ]; then
    alert "disk space critical: ${AVAILABLE_KB} KB available"
    exit 1
fi

echo "[$(date '+%F %T')] Starting backup of ${POSTGRES_DB}..."

# ------------------------------------------------------------------
# 2. pg_dump (пароль берётся из ~/.pgpass через PGPASSFILE,
#    поэтому -w / --no-password и пароль НЕ виден в ps)
# ------------------------------------------------------------------
pg_dump -Fc -w -h postgres -U "${POSTGRES_USER}" "${POSTGRES_DB}" > "${TMPFILE}"

# ------------------------------------------------------------------
# 3. Проверка, что дамп не пустой
# ------------------------------------------------------------------
if [ ! -s "${TMPFILE}" ]; then
    alert "dump file is empty"
    exit 1
fi

# ------------------------------------------------------------------
# 4. Проверка целостности дампа (pg_restore --list)
# ------------------------------------------------------------------
if ! pg_restore --list "${TMPFILE}" >/dev/null 2>&1; then
    alert "dump file is corrupt (pg_restore --list failed)"
    exit 1
fi

# ------------------------------------------------------------------
# 5. Опциональное шифрование (AES-256-GCM через gpg)
# ------------------------------------------------------------------
if [ -n "$BACKUP_ENCRYPT_KEY" ]; then
    ENCRYPTED="${TMPFILE}.gpg"
    gpg --batch --yes --passphrase "$BACKUP_ENCRYPT_KEY" \
        --symmetric --cipher-algo AES256 --compress-algo 0 \
        --output "${ENCRYPTED}" "${TMPFILE}"
    rm -f "${TMPFILE}"
    TMPFILE="${ENCRYPTED}"
    DUMPFILE="${DUMPFILE}.gpg"
    echo "[$(date '+%F %T')] Dump encrypted: ${DUMPFILE}"
fi

mv "${TMPFILE}" "${DUMPFILE}"

# ------------------------------------------------------------------
# 6. Опциональный offsite upload (S3)
# ------------------------------------------------------------------
if [ -n "$S3_BUCKET" ] && command -v aws >/dev/null 2>&1; then
    S3_KEY="warehouse-backups/$(basename "$DUMPFILE")"
    EXTRA_ARGS=""
    [ -n "$S3_ENDPOINT" ] && EXTRA_ARGS="--endpoint-url=${S3_ENDPOINT}"

    # Создаём bucket если не существует (тихо, если AlreadyExists)
    # shellcheck disable=SC2086
    aws s3 mb ${EXTRA_ARGS} "s3://${S3_BUCKET}" 2>/dev/null || true

    # Заливаем с таймаутами (30 сек чтение, 10 сек соединение)
    # shellcheck disable=SC2086
    if aws s3 cp --cli-read-timeout 30 --cli-connect-timeout 10 ${EXTRA_ARGS} "${DUMPFILE}" "s3://${S3_BUCKET}/${S3_KEY}"; then
        echo "[$(date '+%F %T')] Uploaded to s3://${S3_BUCKET}/${S3_KEY}"
    else
        alert "S3 upload failed for ${DUMPFILE}"
        exit 1
    fi
fi

# ------------------------------------------------------------------
# 7. Ротация локальных дампов (|| true — чтобы pipefail не упал)
# ------------------------------------------------------------------
ls -1t "${BACKUP_DIR}"/wh-*.dump* 2>/dev/null \
    | tail -n +$((BACKUP_RETENTION_COUNT + 1)) \
    | xargs -r rm -f || true

echo "[$(date '+%F %T')] Backup saved: ${DUMPFILE}"
echo "[$(date '+%F %T')] Retention cleanup done. Remaining dumps:"
ls -1t "${BACKUP_DIR}"/wh-*.dump* 2>/dev/null || echo "  (none)"

# ------------------------------------------------------------------
# 8. Пишем метку успешного бэкапа для внешнего healthcheck
# ------------------------------------------------------------------
date '+%s' > "${SUCCESS_MARKER}"

# ------------------------------------------------------------------
# 9. Dead man's switch — пингуем внешний сервис, что бэкап успешен
# ------------------------------------------------------------------
if [ -n "${HEALTHCHECK_URL:-}" ]; then
    curl -fsS --max-time 10 "${HEALTHCHECK_URL}" >/dev/null 2>&1 || true
fi

echo "[$(date '+%F %T')] Backup completed successfully."
