#!/bin/sh
set -eu

: "${POSTGRES_USER:=warehouse_user}"
: "${POSTGRES_DB:=warehouse}"
: "${BACKUP_DIR:=/backups}"

if [ $# -lt 1 ]; then
    echo "Usage: $0 <dump-file-path-inside-container>"
    echo "Example: $0 /backups/wh-2025-08-07-0200.dump"
    echo ""
    echo "Available dumps:"
    ls -1t "${BACKUP_DIR}"/wh-*.dump 2>/dev/null || echo "  (none found)"
    exit 1
fi

DUMPFILE="$1"

if [ ! -f "${DUMPFILE}" ]; then
    echo "ERROR: Dump file not found: ${DUMPFILE}"
    exit 1
fi

echo "[$(date '+%F %T')] Restoring from: ${DUMPFILE}"
echo "[$(date '+%F %T')] Target DB: ${POSTGRES_DB}@postgres"

pg_restore \
  --clean \
  --if-exists \
  --verbose \
  --no-owner \
  --no-acl \
  -h postgres \
  -U "${POSTGRES_USER}" \
  -d "${POSTGRES_DB}" \
  "${DUMPFILE}"

echo "[$(date '+%F %T')] Restore completed."
echo ""
echo "Run verification:"
echo "  SELECT * FROM flyway_schema_history;"
echo "  SELECT COUNT(*) FROM users;"
echo "  SELECT COUNT(*) FROM items;"
