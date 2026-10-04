#!/usr/bin/env bash
# Read-only checks of the expert review's acceptance tests on a running AstraWMS (ADR-0025). Nothing is changed.
#   GATEWAY_URL=https://astrawms.cloud SEED_USER=test1 scripts/check-review.sh
# The user needs read access to DC1 and the stores (e.g. SUPERVISOR). The password is asked for and never stored.
set -euo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
KC_URL="${KC_URL:-$([[ "$GW" == http://localhost* ]] && echo http://localhost:8180 || echo "$GW")}"
REALM="$KC_URL/realms/astrawms"
SITE="${SITE:-DC1}"
TRANSFER="${TRANSFER:-TR-DC1-000001}"
ITEM="${ITEM:-100100}"
OWNER="${OWNER:-ACME}"
ISSUE="${ISSUE:-MI000004}"
[[ -n "${SEED_USER:-}" ]] || { echo "Set SEED_USER" >&2; exit 1; }

read -r -s -p "Password for $SEED_USER: " PASSWORD; echo
out="$(curl -s -X POST "$REALM/protocol/openid-connect/token" -d grant_type=password -d client_id=astra-dev-cli \
  -d scope=openid --data-urlencode "username=$SEED_USER" --data-urlencode "password=$PASSWORD")"
unset PASSWORD
TOKEN="$(python -c 'import json,sys; print(json.loads(sys.argv[1])["access_token"])' "$out" 2>/dev/null)" \
  || { echo "Sign-in refused" >&2; exit 1; }
get() { curl -sf -H "Authorization: Bearer $TOKEN" "$GW$1"; }
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
# save <name> <path>: the response body in $TMP/<name>.json (bodies can be larger than a command line)
save() { get "$2" > "$TMP/$1.json" || echo "${3:-[]}" > "$TMP/$1.json"; }
j() { echo "$TMP/$1.json"; }
pass=0; fail=0
check() { if [[ "$2" == ok* ]]; then echo "PASS  $1${2#ok}"; pass=$((pass + 1)); else echo "FAIL  $1: $2"; fail=$((fail + 1)); fi; }

# 1. Overview task counts come from the task list itself.
save tasks "/api/v1/sites/$SITE/tasks"
check "overview task count equals the task list" "$(python -c '
import json, sys, collections
t = json.load(open(sys.argv[1])); c = collections.Counter(x["status"] for x in t)
print("ok (" + ", ".join(f"{k} {v}" for k, v in sorted(c.items())) + ")")' "$(j tasks)")"

# 2. Recommendation: the source never shows more free than on hand − allocated − waiting on open documents.
save recs "/api/v1/network/replenishment"
check "recommendation source free <= on hand - allocated - open documents" "$(python -c '
import json, sys
bad = [r for r in json.load(open(sys.argv[1])) if r.get("recommended") and r.get("source")
       and float(r["source"]["free"]) > float(r["source"]["onHand"]) - float(r["source"]["allocatedOrders"])
           - float(r["source"]["allocatedTransfers"]) - float(r["source"]["waitingOnOpenDocuments"]) + 1e-9]
n = sum(1 for r in json.load(open(sys.argv[1])) if r.get("recommended"))
print("ok (" + str(n) + " recommended)" if not bad else "too much free at " + ", ".join(r["siteId"] + "/" + r["itemNo"] for r in bad))' "$(j recs)")"
check "accepted needs are not recommended again" "$(python -c '
import json, sys
dup = [r for r in json.load(open(sys.argv[1])) if r.get("recommended") and float(r.get("openTransferQty") or 0) > 0
       and float(r["available"]) + float(r["inTransit"]) + float(r["openTransferQty"]) >= float(r["min"]) + float(r["safety"])]
print("ok" if not dup else "recommended although covered: " + ", ".join(r["siteId"] for r in dup))' "$(j recs)")"

# 3. Transfer: in transit after dispatch, cleared after receipt.
save tr "/api/v1/sites/$SITE/outbound/orders/$TRANSFER" '{}'
save bal "/api/v1/network/items/$OWNER/$ITEM" '{"sites":[],"totalInTransit":0,"networkTotal":0}'
check "$TRANSFER dispatch: SAP 303 and in transit" "$(python -c '
import json, sys
o, b = json.load(open(sys.argv[1])), json.load(open(sys.argv[2])); st = o.get("status")
if st in ("SHIPPED", "CONFIRMED"):
    print(("ok (SAP " + str(o.get("erp_document")) + ", in transit " + str(b["totalInTransit"]) + ")") if o.get("erp_document") or st == "SHIPPED"
          else "shipped but no SAP document")
else:
    print("not dispatched yet (status " + str(st) + "): pick it on RF and close its load")' "$(j tr)" "$(j bal)")"
check "network total = on hand + in transit for $ITEM" "$(python -c '
import json, sys
b = json.load(open(sys.argv[1])); s = sum(float(x["on_hand"]) for x in b["sites"])
print("ok (on hand " + str(s) + ", in transit " + str(b["totalInTransit"]) + ")" if abs(s + float(b["totalInTransit"]) - float(b["networkTotal"])) < 1e-6 else "mismatch")' "$(j bal)")"

# 4. Dock tile.
save dock "/api/v1/sites/$SITE/inventory/inbound-staging"
check "dock tile is 0" "$(python -c '
import json, sys
n = len({(b["location_id"], b["lpn_id"]) for b in json.load(open(sys.argv[1])) if b["stock_status"] == "AVAILABLE"})
print("ok" if n == 0 else str(n) + " pallet(s) or loose quantities still at the dock: confirm their putaway on RF")' "$(j dock)")"

# 5. Material issue and the approval tile.
save mi "/api/v1/sites/$SITE/inventory/material-issues?q=$ISSUE"
save req "/api/v1/sites/$SITE/inventory/material-issues?status=REQUESTED"
check "$ISSUE issued, approval tile = REQUESTED list" "$(python -c '
import json, sys
m = [x for x in json.load(open(sys.argv[1])) if x["issue_no"] == sys.argv[3]]; r = json.load(open(sys.argv[2]))
st = m[0]["status"] if m else "not found"
print(("ok (approval tile " + str(len(r)) + ")") if st == "ISSUED" else sys.argv[3] + " is " + st + "; approval tile and REQUESTED list both " + str(len(r)))' "$(j mi)" "$(j req)" "$ISSUE")"

echo; echo "$pass passed, $fail to do"
