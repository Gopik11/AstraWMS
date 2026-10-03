#!/usr/bin/env bash
# Turns on passkey sign-in (ADR-0023) for an existing astrawms realm: the sign-in page then offers "Sign in with
# Passkey" (the device's face, fingerprint or PIN unlock) next to the password, and users register one from the app
# ("Set up passkey"). Passwords keep working. New realms get this from deploy/keycloak/astrawms-realm.json.
#
#   scripts/keycloak-passkeys.sh                 # the local compose stack
#   KC_CONTAINER=astrawms-keycloak-1 SSH="ssh -i ~/.ssh/astrawms_vps root@host" scripts/keycloak-passkeys.sh
#
# The admin credentials are read inside the Keycloak container from its own environment; nothing is printed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KC_CONTAINER="${KC_CONTAINER:-}"
SSH="${SSH:-}"

read -r -d '' REMOTE <<'SCRIPT' || true
K=/opt/keycloak/bin/kcadm.sh
$K config credentials --config /tmp/kc-passkeys.cfg --server http://localhost:8080 --realm master \
  --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null 2>&1 || { echo "admin login failed"; exit 1; }
$K update realms/astrawms --config /tmp/kc-passkeys.cfg \
  -s webAuthnPolicyPasswordlessPasskeysEnabled=true \
  -s webAuthnPolicyPasswordlessRpEntityName=AstraWMS \
  -s webAuthnPolicyPasswordlessUserVerificationRequirement=required \
  -s webAuthnPolicyPasswordlessRequireResidentKey=Yes
$K get authentication/required-actions/webauthn-register-passwordless -r astrawms --config /tmp/kc-passkeys.cfg \
  | grep -q '"enabled" : true' || $K update authentication/required-actions/webauthn-register-passwordless \
  -r astrawms --config /tmp/kc-passkeys.cfg -s enabled=true
$K get realms/astrawms --config /tmp/kc-passkeys.cfg | grep -E 'PasswordlessPasskeysEnabled|PasswordlessRpEntityName'
rm -f /tmp/kc-passkeys.cfg
SCRIPT

if [[ -n "$KC_CONTAINER" ]]; then
  # shellcheck disable=SC2086
  $SSH docker exec -i "$KC_CONTAINER" sh <<<"$REMOTE"
else
  docker compose -f "$ROOT/deploy/docker-compose.yml" exec -T keycloak sh <<<"$REMOTE"
fi
echo "Passkey sign-in is on for realm astrawms"
