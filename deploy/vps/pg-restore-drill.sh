#!/usr/bin/env bash
# Restore drill (ADR-0029, review H-02): restores the newest backup into a throwaway PostgreSQL container, checks that
# every AstraWMS database came back with its schema history and data, and records the recovery point (age of the
# backup) and recovery time (decrypt + restore + checks). Nothing touches the running stack. Run weekly from cron, and
# by hand before any risky change:
#   /opt/astrawms/pg-restore-drill.sh [backup-file]
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/astrawms}"
[[ -f "$APP_DIR/.env" ]] && { set -a; source "$APP_DIR/.env"; set +a; }
: "${BACKUP_PASSPHRASE:?}"
BACKUP_DIR="${BACKUP_DIR:-$APP_DIR/backups}"
IMAGE="${DRILL_IMAGE:-postgres:16-alpine}"
DATABASES="${DRILL_DATABASES:-masterdata inventory inbound sapadapter tasks outbound keycloak}"
file="${1:-$(ls -1t "$BACKUP_DIR"/astrawms-*.sql.gz.enc 2>/dev/null | head -1)}"
[[ -n "$file" && -f "$file" ]] || { echo "No backup found in $BACKUP_DIR" >&2; exit 1; }

if [[ -f "$file.sha256" ]] && [[ "$(sha256sum "$file" | awk '{print $1}')" != "$(cat "$file.sha256")" ]]; then
  echo "Checksum mismatch: $file" >&2; exit 1
fi

name="astrawms-restore-drill-$$"
start=$(date +%s)
backup_time=$(stat -c %Y "$file")
docker run -d --rm --name "$name" -e POSTGRES_PASSWORD=drill "$IMAGE" >/dev/null
trap 'docker rm -f "$name" >/dev/null 2>&1 || true' EXIT
for _ in $(seq 1 60); do docker exec "$name" pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1; done

BACKUP_PASSPHRASE="$BACKUP_PASSPHRASE" openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:BACKUP_PASSPHRASE -in "$file" \
  | gunzip \
  | docker exec -i "$name" psql -q -v ON_ERROR_STOP=0 -U postgres -d postgres > /dev/null 2> "$BACKUP_DIR/drill-last.err" || true

failures=0
report=""
for db in $DATABASES; do
  if ! docker exec "$name" psql -U postgres -lqt | cut -d'|' -f1 | grep -qw "$db"; then
    report+=" $db=MISSING"; failures=$((failures + 1)); continue
  fi
  tables=$(docker exec "$name" psql -U postgres -d "$db" -tAc "select count(*) from information_schema.tables where table_schema = 'public'")
  if [[ "$db" == keycloak ]]; then
    check=$(docker exec "$name" psql -U postgres -d "$db" -tAc "select count(*) from realm" 2>/dev/null || echo ERR)
  else
    check=$(docker exec "$name" psql -U postgres -d "$db" -tAc "select coalesce((select version from flyway_schema_history where success and version is not null order by installed_rank desc limit 1), 'none')" 2>/dev/null || echo ERR)
  fi
  [[ "$tables" -gt 0 && "$check" != ERR && "$check" != none ]] || failures=$((failures + 1))
  report+=" $db=tables:$tables,check:$check"
done

rto=$(( $(date +%s) - start ))
rpo_min=$(( (start - backup_time) / 60 ))
result=$([[ $failures -eq 0 ]] && echo PASSED || echo FAILED)
echo "$(date -u +%FT%TZ) restore drill $result backup=$(basename "$file") rpo_minutes=$rpo_min rto_seconds=$rto$report" \
  | tee -a "$BACKUP_DIR/drills.log"
[[ $failures -eq 0 ]]
