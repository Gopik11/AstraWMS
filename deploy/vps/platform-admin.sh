#!/usr/bin/env bash
# Creates (or resets) a platform operator account (ADR-0030 A) in the astrawms realm on the VPS: role PLATFORM_ADMIN only,
# no tenant_id, a temporary password, and at first sign-in a new password plus an authenticator app (TOTP): the ADR
# requires MFA for this role, the most powerful in the system. Run on the server, in /opt/astrawms:
#
#   ./platform-admin.sh <username> [email]
#
# The temporary password is printed once and stored nowhere. The operator signs in at PUBLIC_URL and gets only the
# platform view (tenants, their status, their first administrators). Keep these accounts to the operator.
set -euo pipefail
cd "$(dirname "$0")"

USER_NAME="${1:?usage: $0 <username> [email]}"
EMAIL="${2:-}"
[[ "$USER_NAME" =~ ^[a-z0-9][a-z0-9._@-]{2,63}$ ]] || { echo "Username: 3-64 lower-case letters, digits, '.', '_', '@' or '-'" >&2; exit 1; }
[[ -z "$EMAIL" || "$EMAIL" =~ ^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$ ]] || { echo "Not an e-mail address: $EMAIL" >&2; exit 1; }

set -a; . ./.env; set +a
: "${KC_ADMIN_PASSWORD:?KC_ADMIN_PASSWORD missing from .env}"
KC="$(docker compose --env-file .env ps -q keycloak)"
[[ -n "$KC" ]] || { echo "The keycloak container is not running" >&2; exit 1; }
PASSWORD="$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-20)"

docker exec -e KCPW="$KC_ADMIN_PASSWORD" -e U="$USER_NAME" -e EMAIL="$EMAIL" -e PW="$PASSWORD" "$KC" sh -c '
  K=/opt/keycloak/bin/kcadm.sh; C="--config /tmp/kcadm-pa.cfg"
  trap "rm -f /tmp/kcadm-pa.cfg" EXIT
  $K config credentials --server http://localhost:8080 --realm master --user admin --password "$KCPW" $C >/dev/null 2>&1 \
    || { echo "Could not sign in to Keycloak as admin" >&2; exit 1; }
  id=$($K get users -r astrawms -q username="$U" -q exact=true --fields id $C | grep -o "[0-9a-f-]\{36\}" || true)
  if [ -z "$id" ]; then
    $K create users -r astrawms -s username="$U" -s enabled=true ${EMAIL:+-s email="$EMAIL"} $C >/dev/null
    echo "Created $U"
  else
    echo "$U exists: resetting its password and second factor setup"
  fi
  $K update users/$($K get users -r astrawms -q username="$U" -q exact=true --fields id $C | grep -o "[0-9a-f-]\{36\}") \
    -r astrawms -s "requiredActions=[\"UPDATE_PASSWORD\",\"CONFIGURE_TOTP\"]" $C
  $K set-password -r astrawms --username "$U" --new-password "$PW" --temporary $C
  $K add-roles -r astrawms --uusername "$U" --rolename PLATFORM_ADMIN $C
'

cat <<EOF

Platform operator: $USER_NAME
Temporary password: $PASSWORD   (shown once; not stored)
Sign in at ${PUBLIC_URL:-the AstraWMS address}: you will set a new password and register an authenticator app,
then see the platform view (Tenants). From there, create a tenant and its first administrator.
EOF
