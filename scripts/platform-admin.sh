#!/usr/bin/env bash
# Creates a platform administrator (ADR-0031): an operator account with the role PLATFORM_ADMIN and no tenant. It signs
# in to the platform console (tenants only, no tenant's data). The temporary password is printed once; at first
# sign-in Keycloak makes the operator choose a new password and set up an authenticator app (TOTP), which it then asks
# for at every sign-in.
#
#   scripts/platform-admin.sh <username> [email]                 # the local stack (deploy/docker-compose.yml)
#   VPS=root@145.223.90.247 scripts/platform-admin.sh <username> [email]   # a VPS deployment in /opt/astrawms
#
# The API never grants PLATFORM_ADMIN (ADR-0030 A), so this runs with Keycloak's own administrator, inside the Keycloak
# container. Secrets travel on standard input only, never on a command line.
set -euo pipefail
cd "$(dirname "$0")/.."

USER_NAME="${1:-}"
EMAIL="${2:-}"
[[ "$USER_NAME" =~ ^[a-z0-9][a-z0-9._@-]{2,63}$ ]] || {
  echo "Usage: [VPS=root@host] $0 <username> [email]   (username: 3-64 lower-case letters, digits, . _ @ -)" >&2; exit 2; }
[[ -z "$EMAIL" || "$EMAIL" =~ ^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$ ]] || { echo "Not an e-mail address: $EMAIL" >&2; exit 2; }

TEMP_PASSWORD="$(python -c 'import secrets; print(secrets.token_urlsafe(15))' 2>/dev/null \
  || python3 -c 'import secrets; print(secrets.token_urlsafe(15))')"

# Runs in the Keycloak container. Reads the Keycloak admin password and the new user's password from standard input.
read -r -d '' IN_KEYCLOAK <<'KC' || true
read -r KCPW; read -r NEWPW
K=/opt/keycloak/bin/kcadm.sh; C="--config /tmp/kcadm-platform.cfg"
trap 'rm -f /tmp/kcadm-platform.cfg' EXIT
$K config credentials --server http://localhost:8080 --realm master --user admin --password "$KCPW" $C >/dev/null 2>&1 \
  || { echo "FAILED: Keycloak refused its administrator" >&2; exit 1; }
if $K get users -r astrawms -q "username=$U" -q exact=true --fields id $C | grep -q '"id"'; then
  echo "FAILED: user $U exists already; choose another username" >&2; exit 3
fi
# realms imported before ADR-0030 lack the role (remote-deploy.sh adds it on the VPS)
$K get roles/PLATFORM_ADMIN -r astrawms $C >/dev/null 2>&1 ||
  $K create roles -r astrawms -s name=PLATFORM_ADMIN -s "description=Operator: manages the tenant registry (ADR-0030)" $C >/dev/null ||
  { echo "FAILED: realm astrawms has no PLATFORM_ADMIN role and it could not be created" >&2; exit 1; }
set -- -r astrawms -s "username=$U" -s enabled=true -s 'requiredActions=["UPDATE_PASSWORD","CONFIGURE_TOTP"]'
[ -n "$E" ] && set -- "$@" -s "email=$E" -s emailVerified=true
$K create users "$@" $C >/dev/null || { echo "FAILED: could not create $U" >&2; exit 1; }
if ! { $K set-password -r astrawms --username "$U" --new-password "$NEWPW" --temporary $C &&
       $K add-roles -r astrawms --uusername "$U" --rolename PLATFORM_ADMIN $C; }; then
  id=$($K get users -r astrawms -q "username=$U" -q exact=true --fields id $C | grep -o '[0-9a-f-]\{36\}')
  [ -n "$id" ] && $K delete "users/$id" -r astrawms $C
  echo "FAILED: could not set the password or role of $U; the account was removed" >&2; exit 1
fi
KC

if [[ -n "${VPS:-}" ]]; then
  SSH_KEY="${SSH_KEY:-$HOME/.ssh/astrawms_vps}"
  # The script, with the new password, goes over SSH on standard input; the Keycloak admin password is read on the
  # server from /opt/astrawms/.env and never leaves it.
  {
    printf 'NEWPW=%q\nU=%q\nE=%q\n' "$TEMP_PASSWORD" "$USER_NAME" "$EMAIL"
    printf 'IN_KEYCLOAK=%q\n' "$IN_KEYCLOAK"
    cat <<'REMOTE'
set -euo pipefail
KCPW="$(grep '^KC_ADMIN_PASSWORD=' /opt/astrawms/.env | cut -d= -f2-)"
[ -n "$KCPW" ] || { echo "FAILED: KC_ADMIN_PASSWORD not found in /opt/astrawms/.env" >&2; exit 1; }
kc="$(docker ps -qf name=astrawms-keycloak-1)"
[ -n "$kc" ] || { echo "FAILED: the astrawms Keycloak container is not running" >&2; exit 1; }
printf '%s\n%s\n' "$KCPW" "$NEWPW" | docker exec -i -e U="$U" -e E="$E" "$kc" sh -c "$IN_KEYCLOAK"
REMOTE
  } | ssh -i "$SSH_KEY" -o BatchMode=yes "$VPS" bash -s
  WHERE="${PUBLIC_URL:-https://astrawms.cloud}"
else
  kc="$(docker compose -f deploy/docker-compose.yml ps -q keycloak)"
  [[ -n "$kc" ]] || { echo "FAILED: the local Keycloak is not running (docker compose -f deploy/docker-compose.yml up -d)" >&2; exit 1; }
  printf '%s\n%s\n' "${KC_ADMIN_PASSWORD:-admin}" "$TEMP_PASSWORD" | docker exec -i -e U="$USER_NAME" -e E="$EMAIL" "$kc" sh -c "$IN_KEYCLOAK"
  WHERE="${PUBLIC_URL:-http://localhost:8080}"
fi

cat <<EOF
Platform administrator $USER_NAME created (role PLATFORM_ADMIN, no tenant).
  Sign in at:          $WHERE
  Temporary password:  $TEMP_PASSWORD   (shown once; not stored anywhere)
At first sign-in: choose a new password, then scan the QR code with an authenticator app. The code is asked for at
every sign-in from then on.
EOF
