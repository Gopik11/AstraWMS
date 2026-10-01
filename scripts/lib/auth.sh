# Sourced by the smoke tests. Provisions users of a fresh tenant in the local Keycloak (development realm only) and
# signs them in, the way a customer IdP + SCIM provisioning would in a real environment (NFR-100).
#   provision <tenant> <username> <role...>   creates the user with tenant_id and realm roles; access scope from
#                                             SCOPE_SITES / SCOPE_OWNERS / SCOPE_ZONES (default *) and APPROVAL_LIMIT
#   bearer <username>                         sets AUTH=(-H "Authorization: Bearer <token>")

KC="${KC_URL:-http://localhost:8180}"
REALM="$KC/realms/astrawms"
SMOKE_PASSWORD="$(python -c 'import secrets; print(secrets.token_urlsafe(18))')"

_kc_admin_token() {
  curl -sf -X POST "$REALM/protocol/openid-connect/token" \
    -d grant_type=client_credentials -d client_id=astra-provisioner -d client_secret=provisioner-dev-secret \
    | python -c 'import json,sys; print(json.load(sys.stdin)["access_token"])'
}

_json_list() { python -c 'import json,sys; print(json.dumps([v.strip() for v in sys.argv[1].split(",") if v.strip()]))' "$1"; }

_scope_attributes() {
  local out
  out="\"wms_sites\":$(_json_list "${SCOPE_SITES:-*}"),\"wms_owners\":$(_json_list "${SCOPE_OWNERS:-*}"),\"wms_zones\":$(_json_list "${SCOPE_ZONES:-*}")"
  [[ -n "${APPROVAL_LIMIT:-}" ]] && out="$out,\"approval_limit\":[\"$APPROVAL_LIMIT\"]"
  printf '%s' "$out"
}

wait_for_keycloak() {
  for _ in $(seq 1 90); do curl -sf "$REALM/.well-known/openid-configuration" >/dev/null && return 0; sleep 2; done
  echo "FAILED: Keycloak realm astrawms not reachable at $REALM" >&2; exit 1
}

provision() { # provision <tenant> <username> <role...>
  local tenant="$1" user="$2"; shift 2
  local admin location id roles
  admin="$(_kc_admin_token)"
  location="$(curl -s -D - -o /dev/null -X POST "$KC/admin/realms/astrawms/users" \
    -H "Authorization: Bearer $admin" -H "Content-Type: application/json" \
    -d "{\"username\":\"$user\",\"enabled\":true,\"emailVerified\":true,\"firstName\":\"Smoke\",\"lastName\":\"$user\",
         \"email\":\"$user@smoke.astrawms.local\",\"attributes\":{\"tenant_id\":[\"$tenant\"],$(_scope_attributes)},
         \"credentials\":[{\"type\":\"password\",\"value\":\"$SMOKE_PASSWORD\",\"temporary\":false}]}" \
    | tr -d '\r' | sed -n 's/^[Ll]ocation: //p')"
  [[ -n "$location" ]] || { echo "FAILED: could not create user $user" >&2; exit 1; }
  id="${location##*/}"
  roles="$(for r in "$@"; do curl -sf "$KC/admin/realms/astrawms/roles/$r" -H "Authorization: Bearer $admin"; echo; done \
    | python -c 'import json,sys; print(json.dumps([json.loads(l) for l in sys.stdin if l.strip()]))')"
  curl -sf -X POST "$KC/admin/realms/astrawms/users/$id/role-mappings/realm" \
    -H "Authorization: Bearer $admin" -H "Content-Type: application/json" -d "$roles" \
    || { echo "FAILED: could not assign roles to $user" >&2; exit 1; }
}

bearer() { # bearer <username>  -> AUTH array
  local token
  token="$(curl -sf -X POST "$REALM/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=astra-dev-cli -d scope=openid \
    --data-urlencode "username=$1" --data-urlencode "password=$SMOKE_PASSWORD" \
    | python -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')" \
    || { echo "FAILED: sign-in of $1" >&2; exit 1; }
  AUTH=(-H "Authorization: Bearer $token")
}
