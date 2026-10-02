#!/usr/bin/env bash
# End-to-end replenishment smoke test (§7 min/max, ADR-0019 demand replenishment) through the gateway:
#   rule on forward location F-01 (min 3, max 12) -> an order of 4 is allocated from F-01, leaving 2 free -> the
#   replenishment from reserve R-01 is created at once and comes first on RF -> then the pick -> F-01 at 12.
set -euo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
TENANT="rpl-$(date +%s)"
source "$(dirname "$0")/lib/auth.sh"

step() { printf '\n== %s\n' "$*"; }
fail() { echo "FAILED: $*" >&2; exit 1; }
expect() { local want="$1"; shift; local out code
  out="$(curl -s -w '\n%{http_code}' "$@")"; code="${out##*$'\n'}"; BODY="${out%$'\n'*}"
  [[ "$code" == "$want" ]] || fail "expected HTTP $want, got $code: $BODY"; }
json() { python -c "import json,sys; d=json.load(sys.stdin); print(eval('d'+sys.argv[1]))" "$1"; }
wait_for() { local what="$1"; shift; for _ in $(seq 1 60); do "$@" >/dev/null 2>&1 && return 0; sleep 1; done; fail "timed out: $what"; }
check_digit() { curl -sf "$GW/api/v1/sites/DC1/locations/$1" "${ADM[@]}" | json "['checkDigit']"; }
qty_at() { curl -sf "$GW/api/v1/sites/DC1/inventory/balances?locationId=$1" "${ADM[@]}" | python -c "import json,sys; print(sum(b['qty'] for b in json.load(sys.stdin)['items']))"; }
next_task() { local -n auth="$1"; local type="$2"
  for _ in $(seq 1 30); do
    out="$(curl -s -w '\n%{http_code}' -X POST "$GW/api/v1/sites/DC1/tasks/next" "${auth[@]}")"
    if [[ "${out##*$'\n'}" == "200" ]]; then BODY="${out%$'\n'*}"
      [[ "$(json "['taskType']" <<<"$BODY")" == "$type" ]] && return 0
      fail "expected a $type task, got $(json "['taskType']" <<<"$BODY")"
    fi
    sleep 1
  done; fail "no $type task"; }

step "Waiting for Keycloak and services"
wait_for_keycloak
for svc in master-data-service inventory-service task-service outbound-service sap-adapter; do wait_for "$svc" curl -sf "$GW/health/$svc"; done

step "Identity and master data: forward F-01 (pick zone), reserve R-01, staging; SKU-1"
provision "$TENANT" "$TENANT-admin" SOLUTION_ADMIN RECEIVER ERP_INTEGRATION SUPERVISOR
provision "$TENANT" "$TENANT-picker" PICKER
bearer "$TENANT-admin";  ADM=("${AUTH[@]}" -H "Content-Type: application/json")
bearer "$TENANT-picker"; P=("${AUTH[@]}" -H "Content-Type: application/json")
expect 204 -X PUT "$GW/api/v1/sites/DC1" "${ADM[@]}" -d '{"name":"Dallas DC","timeZone":"America/Chicago","erpSite":"1000"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/zones/PICK" "${ADM[@]}" -d '{"zoneType":"PICK","erpBucket":"0001"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/zones/STOR" "${ADM[@]}" -d '{"zoneType":"RESERVE","erpBucket":"0001"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/zones/SHIP" "${ADM[@]}" -d '{"zoneType":"SHIPPING","erpBucket":"0001"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/locations/F-01" "${ADM[@]}" -d '{"zoneId":"PICK","locationType":"SHELF","pickSeq":1}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/locations/R-01" "${ADM[@]}" -d '{"zoneId":"STOR","locationType":"RACK","pickSeq":9}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/locations/STAGE-OUT" "${ADM[@]}" -d '{"zoneId":"SHIP","locationType":"STAGING_OUT"}'
expect 200 -X PUT "$GW/api/v1/items/ACME/SKU-1" "${ADM[@]}" \
  -d '{"description":"Speaker","baseUom":"EA","status":"ACTIVE","sites":[{"siteId":"DC1","lotControlled":false,"serialControl":"NONE"}]}'
expect 204 -X PUT "$GW/api/v1/sap/site-map/1000" "${ADM[@]}" -d '{"siteId":"DC1","timeZone":"America/Chicago","defaultOwner":"ACME"}'

step "Stock: 6 in F-01 (received first), 40 on pallet LPN-RES in R-01; rule F-01 min 3 max 12"
for _ in $(seq 1 30); do
  code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GW/api/v1/sites/DC1/inventory/receipts" "${ADM[@]}" -H "Idempotency-Key: r1" \
    -d '{"ownerId":"ACME","itemNo":"SKU-1","qty":6,"uom":"EA","locationId":"F-01"}')"
  [[ "$code" =~ ^20 ]] && break; sleep 1
done
sleep 1
expect 201 -X POST "$GW/api/v1/sites/DC1/inventory/receipts" "${ADM[@]}" -H "Idempotency-Key: r2" \
  -d '{"ownerId":"ACME","itemNo":"SKU-1","qty":40,"uom":"EA","locationId":"R-01","lpnId":"LPN-RES"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/inventory/replenishment-rules/F-01/ACME/SKU-1" "${ADM[@]}" -d '{"minQty":3,"maxQty":12}'

step "Order of 4 allocated from the pick face F-01 -> F-01 free at 2 (below min 3) -> replenishment of 10 from R-01"
# Demand replenishment (ADR-0019): the allocation itself triggers it, so the REPLEN task comes before the pick.
expect 202 -X POST "$GW/api/v1/sap/idocs/delvry07" "${ADM[@]}" -d '{"DOCNUM":"0000000000004001","MESTYP":"SHP_OBDLV_SAVE_REPLICA",
 "E1EDL20":{"VBELN":"0080000401","LFART":"LF","WERKS":"1000"},"E1ADRM1":[{"PARTNER_Q":"WE","PARTNER_ID":"C-1"}],
 "E1EDL24":[{"POSNR":"000010","MATNR":"SKU-1","LFIMG":"4","VRKME":"ST"}]}'
wait_for "replenishment" sh -c "curl -sf '$GW/api/v1/sites/DC1/inventory/replenishments?status=OPEN' -H '${ADM[1]}' | grep -q F-01"
expect 200 "$GW/api/v1/sites/DC1/inventory/replenishments?status=OPEN" "${ADM[@]}"
echo "Replenishment: $(json "[0]['qty']" <<<"$BODY") from $(json "[0]['source_location']" <<<"$BODY") to $(json "[0]['location_id']" <<<"$BODY")"

step "RF: REPLEN task first (higher priority) -> drop at F-01; then the pick of 4 from F-01 -> F-01 at 12"
next_task P REPLEN
task="$(json "['id']" <<<"$BODY")"
expect 200 -X POST "$GW/api/v1/sites/DC1/tasks/$task/replenish" "${P[@]}" -d "{\"checkDigit\":\"$(check_digit F-01)\"}"
next_task P PICK
task="$(json "['id']" <<<"$BODY")"; from="$(json "['fromLocation']" <<<"$BODY")"
[[ "$from" == "F-01" ]] || fail "expected the pick from the pick face F-01, got $from"
expect 200 -X POST "$GW/api/v1/sites/DC1/tasks/$task/pick" "${P[@]}" -d "{\"checkDigit\":\"$(check_digit F-01)\",\"qty\":4}"
[[ "$(qty_at F-01)" == "12" ]] || fail "F-01 should hold 12, holds $(qty_at F-01)"
[[ "$(qty_at R-01)" == "30" ]] || fail "R-01 should hold 30, holds $(qty_at R-01)"
echo "F-01 replenished before the pick and holds 12; R-01 down to 30"

printf '\nREPLENISHMENT SMOKE TEST PASSED (tenant %s)\n' "$TENANT"
