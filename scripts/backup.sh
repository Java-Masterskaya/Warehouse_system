#!/bin/sh
set -euo pipefail

# Backup configuration via env vars (overridable in docker-compose)
: "${POSTGRES_USER:=warehouse_user}"
: "${POSTGRES_DB:=warehouse}"
: "${BACKUP_DIR:=/backups}"
: "${BACKUP_RETENTION_COUNT:=10}"

TIMESTAMP=$(date +%F-%H%M%S)
DUMPFILE="${BACKUP_DIR}/wh-${TIMESTAMP}.dump"

echo "[$(date '+%F %T')] Starting backup of ${POSTGRES_DB}..."

# PGPASSWORD is passed via Docker environment
pg_dump -Fc -h postgres -U "${POSTGRES_USER}" "${POSTGRES_DB}" > "${DUMPFILE}"

echo "[$(date '+%F %T')] Backup saved: ${DUMPFILE}"

# Rotation: keep only BACKUP_RETENTION_COUNT latest dumps
ls -1t "${BACKUP_DIR}"/wh-*.dump 2>/dev/null \
  | tail -n +$((BACKUP_RETENTION_COUNT + 1)) \
  | xargs -r rm -f

echo "[$(date '+%F %T')] Retention cleanup done. Remaining dumps:"
ls -1t "${BACKUP_DIR}"/wh-*.dump 2>/dev/null || echo "  (none)"
