#!/usr/bin/env bash
# Encrypted PostgreSQL backup of the AstraWMS VPS (ADR-0029, review H-02). Run nightly from cron (remote-deploy.sh).
#   - pg_dumpall of every database and role (AstraWMS services and Keycloak), gzip, AES-256 (openssl, PBKDF2) with
#     BACKUP_PASSPHRASE from /opt/astrawms/.env. Keep that passphrase OFF the server too: without it no backup can be
#     restored, and with only the server's copy a lost server loses it.
#   - kept BACKUP_KEEP_DAYS days (default 14) in BACKUP_DIR (default /opt/astrawms/backups);
#   - copied off the host when BACKUP_REMOTE is set: an rsync/scp target such as backup@host:/srv/astrawms (SSH key of
#     root), or an rclone remote such as s3:bucket/astrawms when it starts with rclone: (rclone:s3:bucket/path).
# Writes a status line to BACKUP_DIR/backups.log; exits non-zero on any failure (cron mails root).
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/astrawms}"
[[ -f "$APP_DIR/.env" ]] && { set -a; source "$APP_DIR/.env"; set +a; }
: "${BACKUP_PASSPHRASE:?BACKUP_PASSPHRASE is not set (generated into $APP_DIR/.env by remote-deploy.sh)}"
BACKUP_DIR="${BACKUP_DIR:-$APP_DIR/backups}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
PG_CONTAINER="${PG_CONTAINER:-$(cd "$APP_DIR" && docker compose --env-file .env ps -q postgres)}"
[[ -n "$PG_CONTAINER" ]] || { echo "postgres container is not running" >&2; exit 1; }

umask 077
mkdir -p "$BACKUP_DIR"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
file="$BACKUP_DIR/astrawms-$stamp.sql.gz.enc"
start=$(date +%s)

docker exec "$PG_CONTAINER" pg_dumpall -U postgres --clean --if-exists \
  | gzip -6 \
  | BACKUP_PASSPHRASE="$BACKUP_PASSPHRASE" openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass env:BACKUP_PASSPHRASE \
  > "$file.partial"
mv "$file.partial" "$file"
sha256sum "$file" | awk '{print $1}' > "$file.sha256"
size=$(stat -c %s "$file")

remote="none"
if [[ -n "${BACKUP_REMOTE:-}" ]]; then
  if [[ "$BACKUP_REMOTE" == rclone:* ]]; then
    rclone copy "$file" "${BACKUP_REMOTE#rclone:}" && rclone copy "$file.sha256" "${BACKUP_REMOTE#rclone:}"
  else
    rsync -a -e "ssh -o BatchMode=yes" "$file" "$file.sha256" "$BACKUP_REMOTE/"
  fi
  remote="$BACKUP_REMOTE"
fi

find "$BACKUP_DIR" -maxdepth 1 -name 'astrawms-*.sql.gz.enc*' -mtime +"$KEEP_DAYS" -delete
echo "$(date -u +%FT%TZ) backup ok file=$(basename "$file") bytes=$size seconds=$(( $(date +%s) - start )) remote=$remote" \
  | tee -a "$BACKUP_DIR/backups.log"
