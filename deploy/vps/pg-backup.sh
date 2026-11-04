#!/usr/bin/env bash
# Encrypted PostgreSQL backup of the AstraWMS VPS (ADR-0029, review H-02). Run nightly from cron (remote-deploy.sh).
#   - pg_dumpall of every database and role (AstraWMS services and Keycloak), gzip, AES-256 (openssl, PBKDF2) with
#     BACKUP_PASSPHRASE from /opt/astrawms/.env. Keep that passphrase OFF the server too: without it no backup can be
#     restored, and with only the server's copy a lost server loses it.
#   - kept BACKUP_KEEP_DAYS days (default 14) in BACKUP_DIR (default /opt/astrawms/backups);
#   - copied off the host when BACKUP_REMOTE is set: an rsync/scp target such as backup@host:/srv/astrawms (SSH key of
#     root), or an rclone remote such as s3:bucket/astrawms when it starts with rclone: (rclone:s3:bucket/path, or
#     rclone:gdrive:astrawms-backups for Google Drive);
#   - on an rclone remote, backups older than BACKUP_REMOTE_KEEP_DAYS days (default 30; 0 keeps them all) are deleted
#     after the night's upload succeeded. Only this script's files (astrawms-*.sql.gz.enc and .sha256) at the top of
#     the remote path are touched; Google Drive moves them to its trash. An rsync target keeps its own retention.
# Writes a status line to BACKUP_DIR/backups.log; exits non-zero on any failure (cron mails root).
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/astrawms}"
[[ -f "$APP_DIR/.env" ]] && { set -a; source "$APP_DIR/.env"; set +a; }
: "${BACKUP_PASSPHRASE:?BACKUP_PASSPHRASE is not set (generated into $APP_DIR/.env by remote-deploy.sh)}"
BACKUP_DIR="${BACKUP_DIR:-$APP_DIR/backups}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
REMOTE_KEEP_DAYS="${BACKUP_REMOTE_KEEP_DAYS:-30}"
[[ "$REMOTE_KEEP_DAYS" =~ ^[0-9]+$ ]] || { echo "BACKUP_REMOTE_KEEP_DAYS must be a whole number of days" >&2; exit 1; }
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

remote="none"; pruned=""
if [[ -n "${BACKUP_REMOTE:-}" ]]; then
  if [[ "$BACKUP_REMOTE" == rclone:* ]]; then
    dest="${BACKUP_REMOTE#rclone:}"
    rclone copy "$file" "$dest" && rclone copy "$file.sha256" "$dest"
    if (( REMOTE_KEEP_DAYS > 0 )); then
      # top level only, this script's files only, older than the retention
      old_filter=(--max-depth 1 --include 'astrawms-*.sql.gz.enc' --include 'astrawms-*.sql.gz.enc.sha256'
                  --min-age "${REMOTE_KEEP_DAYS}d")
      old="$(rclone lsf --files-only "${old_filter[@]}" "$dest")"
      if grep -qxF "$(basename "$file")" <<<"$old"; then
        echo "refusing to prune $dest: tonight's backup reads as older than $REMOTE_KEEP_DAYS days (clock?)" >&2; exit 1
      fi
      [[ -z "$old" ]] || rclone delete "${old_filter[@]}" "$dest"
      pruned=" remote_pruned=$(grep -c . <<<"$old" || true)"
    fi
  else
    rsync -a -e "ssh -o BatchMode=yes" "$file" "$file.sha256" "$BACKUP_REMOTE/"
  fi
  remote="$BACKUP_REMOTE"
fi

find "$BACKUP_DIR" -maxdepth 1 -name 'astrawms-*.sql.gz.enc*' -mtime +"$KEEP_DAYS" -delete
echo "$(date -u +%FT%TZ) backup ok file=$(basename "$file") bytes=$size seconds=$(( $(date +%s) - start )) remote=$remote$pruned" \
  | tee -a "$BACKUP_DIR/backups.log"
