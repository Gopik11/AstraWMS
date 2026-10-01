#!/usr/bin/env bash
# Creates (or resets) a local development user in the local Keycloak with every business role, scope "*", and a
# generated password, and writes the credentials to deploy/keycloak/.dev-user (git-ignored). Local stack only.
#   scripts/dev-user.sh [username] [tenant]
set -euo pipefail
cd "$(dirname "$0")/.."
USER_NAME="${1:-ui-tester}"
TENANT="${2:-uidemo}"
source scripts/lib/auth.sh
wait_for_keycloak
admin="$(_kc_admin_token)"
existing="$(curl -sf "$KC/admin/realms/astrawms/users?username=$USER_NAME&exact=true" -H "Authorization: Bearer $admin" \
  | python -c 'import json,sys; u=json.load(sys.stdin); print(u[0]["id"] if u else "")')"
if [[ -n "$existing" ]]; then
  curl -sf -X DELETE "$KC/admin/realms/astrawms/users/$existing" -H "Authorization: Bearer $admin"
fi
APPROVAL_LIMIT=10000 provision "$TENANT" "$USER_NAME" RECEIVER PICKER INV_ANALYST INV_MANAGER SUPERVISOR QA_MANAGER SOLUTION_ADMIN ERP_INTEGRATION
umask 077
printf 'DEV_USER=%s\nDEV_PASSWORD=%s\nDEV_TENANT=%s\n' "$USER_NAME" "$SMOKE_PASSWORD" "$TENANT" > deploy/keycloak/.dev-user
echo "User $USER_NAME (tenant $TENANT) created; credentials in deploy/keycloak/.dev-user"
