#!/bin/sh
set -euo pipefail

: "${POSTGRES_USER:=warehouse_user}"
: "${POSTGRES_DB:=warehouse}"
: "${BACKUP_DIR:=/backups}"
: "${BACKUP_ENCRYPT_KEY:=}"

if [ $# -lt 1 ]; then
    echo "Usage: $0 <dump-file-path-inside-container>"
    echo "Example: $0 /backups/wh-2025-08-07-0200.dump"
    echo ""
    echo "Available dumps:"
    ls -1t "${BACKUP_DIR}"/wh-*.dump* 2>/dev/null || echo "  (none found)"
    exit 1
fi

DUMPFILE="$1"

if [ ! -f "${DUMPFILE}" ]; then
    echo "ERROR: Dump file not found: ${DUMPFILE}"
    exit 1
fi

# ------------------------------------------------------------------
# Опциональное дешифрование
# ------------------------------------------------------------------
RESTORE_SRC="${DUMPFILE}"
TMP_DECRYPTED=""

cleanup() {
    [ -n "${TMP_DECRYPTED}" ] && [ -f "${TMP_DECRYPTED}" ] && rm -f "${TMP_DECRYPTED}"
}
trap cleanup EXIT

if [ "${DUMPFILE##*.}" = "gpg" ]; then
    if [ -z "$BACKUP_ENCRYPT_KEY" ]; then
        echo "ERROR: BACKUP_ENCRYPT_KEY is required to decrypt .gpg dump" >&2
        exit 1
    fi
    TMP_DECRYPTED="/tmp/restore-$(date +%s).dump"
    gpg --batch --yes --passphrase "$BACKUP_ENCRYPT_KEY" \
        --decrypt --output "${TMP_DECRYPTED}" "${DUMPFILE}"
    RESTORE_SRC="${TMP_DECRYPTED}"
    echo "[$(date '+%F %T')] Decrypted to ${TMP_DECRYPTED}"
fi

echo "[$(date '+%F %T')] Restoring from: ${DUMPFILE}"
echo "[$(date '+%F %T')] Target DB: ${POSTGRES_DB}@postgres"

# ------------------------------------------------------------------
# pg_restore может возвращать 1 при warnings (--clean --if-exists).
# Код 1 = warnings, но restore успешен. Код 2+ = реальная ошибка.
# Поэтому временно отключаем set -e и проверяем код вручную.
# ------------------------------------------------------------------
set +e
pg_restore \
    --clean \
    --if-exists \
    --verbose \
    --no-owner \
    --no-acl \
    -w \
    -h postgres \
    -U "${POSTGRES_USER}" \
    -d "${POSTGRES_DB}" \
    "${RESTORE_SRC}"
PG_CODE=$?
set -e

if [ "$PG_CODE" -gt 1 ]; then
    echo "[$(date '+%F %T')] ERROR: pg_restore failed with code $PG_CODE" >&2
    exit "$PG_CODE"
fi

echo "[$(date '+%F %T')] Restore completed (pg_restore exit code: $PG_CODE)."
echo ""
echo "Run verification:"
echo "  SELECT * FROM flyway_schema_history;"
echo "  SELECT COUNT(*) FROM users;"
echo "  SELECT COUNT(*) FROM items;"
