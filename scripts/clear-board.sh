#!/usr/bin/env bash
# Clears the stale work on a site's control tower with the same supervisor actions the tiles offer (ADR-0024/0025),
# after showing what it will do and asking once:
#   - dock stock older than 15 min gets a putaway (never to STAGE-OUT)       = tile "Pallets or loose stock waiting on dock"
#   - tasks assigned longer than 30 min go back to the queue                  = tile "Tasks assigned > 30 min" → Unassign
#   - appointments 15 min past their start and not arrived: no-show           = tile "Appointments late" → Mark no-show
#   - trailers in the yard longer than 12 h: checked out                      = tile "Trailers in the yard" → Check out
# Billing events without a rate stay on the "unrated" tile (set rates; they are never shown as 0.00).
#
#   GATEWAY_URL=https://astrawms.cloud SEED_USER=test2 SITE=DC1 scripts/clear-board.sh
# The user needs SUPERVISOR. The password is asked for and never stored.
set -euo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
KC_URL="${KC_URL:-$([[ "$GW" == http://localhost* ]] && echo http://localhost:8180 || echo "$GW")}"
REALM="$KC_URL/realms/astrawms"
SITE="${SITE:-DC1}"
[[ -n "${SEED_USER:-}" ]] || { echo "Set SEED_USER (a supervisor's Keycloak username)" >&2; exit 1; }

fail() { echo "FAILED: $*" >&2; exit 1; }
read -r -s -p "Password for $SEED_USER: " PASSWORD; echo
out="$(curl -s -X POST "$REALM/protocol/openid-connect/token" -d grant_type=password -d client_id=astra-dev-cli \
  -d scope=openid --data-urlencode "username=$SEED_USER" --data-urlencode "password=$PASSWORD")"
unset PASSWORD
TOKEN="$(python -c 'import json,sys; print(json.loads(sys.argv[1])["access_token"])' "$out" 2>/dev/null)" \
  || fail "sign-in of $SEED_USER refused: $(python -c 'import json,sys; d=json.loads(sys.argv[1]); print(d.get("error_description") or d.get("error"))' "$out" 2>/dev/null || echo "$out" | head -c 200)"
AUTH=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json")
api() { curl -sf "${AUTH[@]}" "$@"; }

# ----------------------------------------------------------------------------------------------- what is stale
dock="$(api "$GW/api/v1/sites/$SITE/inventory/inbound-staging" | python -c '
import json, sys, datetime as d
now = d.datetime.now(d.timezone.utc)
rows = [b for b in json.load(sys.stdin) if b["stock_status"] == "AVAILABLE"
        and now - d.datetime.fromisoformat(b["receipt_date"].replace("Z", "+00:00")) > d.timedelta(minutes=15)]
print(len({(b["location_id"], b["lpn_id"]) for b in rows}))')"
stale="$(api "$GW/api/v1/sites/$SITE/tasks?status=ASSIGNED" | python -c '
import json, sys, datetime as d
now = d.datetime.now(d.timezone.utc)
for t in json.load(sys.stdin):
    at = t.get("assignedAt")
    if at and now - d.datetime.fromisoformat(at.replace("Z", "+00:00")) > d.timedelta(minutes=30):
        print(t["taskType"], t.get("orderRef") or t.get("lpnId") or "", "held by", t.get("assignedTo"))')"
yard="$(api "$GW/api/v1/sites/$SITE/yard/summary")"
late="$(python -c 'import json,sys; print(" ".join(a["appt_no"] for a in json.loads(sys.argv[1])["late"]))' "$yard")"
dwell="$(python -c '
import json, sys, datetime as d
now = d.datetime.now(d.timezone.utc)
print(" ".join(a["appt_no"] for a in json.loads(sys.argv[1])["inYard"]
               if a.get("checked_in_at") and now - d.datetime.fromisoformat(a["checked_in_at"].replace("Z", "+00:00")) > d.timedelta(hours=12)))' "$yard")"

echo "Site $SITE:"
echo "  dock: $dock pallet(s) or loose quantities older than 15 min -> putaway tasks"
echo "  tasks assigned over 30 min -> back to the queue:"; [[ -n "$stale" ]] && sed 's/^/    /' <<<"$stale" || echo "    none"
echo "  late appointments -> no-show: ${late:-none}"
echo "  trailers in the yard over 12 h -> checked out: ${dwell:-none}"
read -r -p "Apply these changes on $GW? [y/N] " ok
[[ "$ok" =~ ^[yY]$ ]] || { echo "Nothing changed."; exit 0; }

# ----------------------------------------------------------------------------------------------- clear
(( dock > 0 )) && api -X POST "$GW/api/v1/sites/$SITE/tasks/sweep-dock" | python -c 'import json,sys; r=json.load(sys.stdin); print("dock:", r["putawaysCreated"], "putaway(s),", r["lpnsCreated"], "loose quantity(ies) put on an LPN")'
[[ -n "$stale" ]] && api -X POST "$GW/api/v1/sites/$SITE/tasks/unassign-stale?minutes=30" | python -c 'import json,sys; print("tasks:", len(json.load(sys.stdin)), "back in the queue")'
for a in $late; do api -X POST "$GW/api/v1/sites/$SITE/yard/appointments/$a/no-show" >/dev/null && echo "appointment $a: no-show"; done
for a in $dwell; do api -X POST "$GW/api/v1/sites/$SITE/yard/appointments/$a/check-out" -d '{}' >/dev/null && echo "trailer $a: checked out"; done
echo "Done. Confirm the dock putaways on RF; unrated billing events stay on their tile until rates are set."
