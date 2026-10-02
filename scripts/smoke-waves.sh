#!/usr/bin/env bash
# End-to-end wave smoke test against the local stack (deploy/docker-compose.yml), through the API gateway with
# Keycloak tokens (ADR-0011):
#   site in WAVE mode -> two SAP outbound deliveries wait in the pool -> wave plan (preview), create, release
#   -> RF picks: one short pick is re-allocated from another location (PCK-003) and picked there
#   -> SAP cancels the second (already picked) delivery -> RETURN task puts the stock back -> cancel acknowledged
#   -> the first order ships complete -> goods issue in simulated SAP -> CONFIRMED.
set -euo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
TENANT="wave-$(date +%s)"
source "$(dirname "$0")/lib/auth.sh"

step() { printf '\n== %s\n' "$*"; }
fail() { echo "FAILED: $*" >&2; exit 1; }
expect() { # expect <http-code> <curl args...>; body in $BODY
  local want="$1"; shift
  local out code
  out="$(curl -s -w '\n%{http_code}' "$@")"; code="${out##*$'\n'}"; BODY="${out%$'\n'*}"
  [[ "$code" == "$want" ]] || fail "expected HTTP $want, got $code: $BODY"
}
json() { python -c "import json,sys; d=json.load(sys.stdin); print(eval('d'+sys.argv[1]))" "$1"; }
wait_for() { local what="$1"; shift; for _ in $(seq 1 60); do "$@" >/dev/null 2>&1 && return 0; sleep 1; done; fail "timed out: $what"; }
order_status() { curl -sf "$GW/api/v1/sites/DC1/outbound/orders/$1" "${SUP[@]}" | json "['status']"; }
has_status() { [[ "$(order_status "$1")" == "$2" ]]; }
idoc_status() { curl -sf "$GW/api/v1/sap/idocs/$1" "${SAPH[@]}" | json "['status']"; }
has_idoc_status() { [[ "$(idoc_status "$1")" == "$2" ]]; }
check_digit() { curl -sf "$GW/api/v1/sites/DC1/locations/$1" "${SUP[@]}" | json "['checkDigit']"; }

step "Waiting for Keycloak and services"
wait_for_keycloak
for svc in master-data-service inventory-service task-service outbound-service sap-adapter; do
  wait_for "$svc healthy" curl -sf "$GW/health/$svc"
done

step "Identity: tenant $TENANT with administrator, SAP middleware, receiver, picker and supervisor"
provision "$TENANT" "$TENANT-admin" SOLUTION_ADMIN
provision "$TENANT" "$TENANT-sap" ERP_INTEGRATION
provision "$TENANT" "$TENANT-receiver" RECEIVER
provision "$TENANT" "$TENANT-picker" PICKER
provision "$TENANT" "$TENANT-supervisor" SUPERVISOR
bearer "$TENANT-admin";      H=("${AUTH[@]}" -H "Content-Type: application/json")
bearer "$TENANT-sap";        SAPH=("${AUTH[@]}" -H "Content-Type: application/json")
bearer "$TENANT-receiver";   RCV=("${AUTH[@]}" -H "Content-Type: application/json")
bearer "$TENANT-picker";     P=("${AUTH[@]}" -H "Content-Type: application/json")
bearer "$TENANT-supervisor"; SUP=("${AUTH[@]}" -H "Content-Type: application/json")

step "Master data, SAP plant 1000 -> DC1, and the site in WAVE release mode"
expect 204 -X PUT "$GW/api/v1/sites/DC1" "${H[@]}" -d '{"name":"Dallas DC","timeZone":"America/Chicago","erpSite":"1000"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/zones/STOR" "${H[@]}" -d '{"zoneType":"RESERVE","erpBucket":"0001"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/zones/SHIP" "${H[@]}" -d '{"zoneType":"SHIPPING","erpBucket":"0001"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/locations/R-01" "${H[@]}" -d '{"zoneId":"STOR","locationType":"RACK","pickSeq":1}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/locations/R-02" "${H[@]}" -d '{"zoneId":"STOR","locationType":"RACK","pickSeq":2}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/locations/STAGE-OUT" "${H[@]}" -d '{"zoneId":"SHIP","locationType":"STAGING_OUT"}'
expect 200 -X PUT "$GW/api/v1/items/ACME/SKU-1" "${H[@]}" \
  -d '{"description":"Speaker","baseUom":"EA","status":"ACTIVE","sites":[{"siteId":"DC1","lotControlled":false,"serialControl":"NONE"}]}'
expect 204 -X PUT "$GW/api/v1/sap/site-map/1000" "${H[@]}" -d '{"siteId":"DC1","timeZone":"America/Chicago","defaultOwner":"ACME"}'
expect 403 -X PUT "$GW/api/v1/sites/DC1/outbound/config" "${SUP[@]}" -d '{"releaseMode":"WAVE"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/outbound/config" "${H[@]}" -d '{"releaseMode":"WAVE"}'

step "Stock: 5 x SKU-1 in R-01 (received first), 5 x SKU-1 in R-02"
for _ in $(seq 1 30); do
  code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GW/api/v1/sites/DC1/inventory/receipts" "${RCV[@]}" -H "Idempotency-Key: rcv-1" \
    -d '{"ownerId":"ACME","itemNo":"SKU-1","qty":5,"uom":"EA","locationId":"R-01","lpnId":"LPN-W-1"}')"
  [[ "$code" =~ ^20 ]] && break; sleep 1
done
[[ "$code" =~ ^20 ]] || fail "stock receipt not accepted (master data projection?)"
sleep 1
expect 201 -X POST "$GW/api/v1/sites/DC1/inventory/receipts" "${RCV[@]}" -H "Idempotency-Key: rcv-2" \
  -d '{"ownerId":"ACME","itemNo":"SKU-1","qty":5,"uom":"EA","locationId":"R-02","lpnId":"LPN-W-2"}'

delivery() { # delivery <docnum> <vbeln> <qty>
  cat <<EOF
{"DOCNUM":"$1","MESTYP":"SHP_OBDLV_SAVE_REPLICA","E1EDL20":{"VBELN":"$2","LFART":"LF","WERKS":"1000"},
 "E1ADRM1":[{"PARTNER_Q":"WE","PARTNER_ID":"C-1","NAME1":"Customer One"},{"PARTNER_Q":"SP","PARTNER_ID":"UPSN"}],
 "E1EDT13":[{"QUALF":"006","NTANF":"20261002","NTANZ":"150000"}],
 "E1EDL24":[{"POSNR":"000010","MATNR":"SKU-1","LFIMG":"$3","VRKME":"ST"}]}
EOF
}

step "SAP distributes deliveries 0080000101 (6 EA) and 0080000102 (2 EA): both wait in the pool"
expect 202 -X POST "$GW/api/v1/sap/idocs/delvry07" "${SAPH[@]}" -d "$(delivery 0000000000003001 0080000101 6)"
expect 202 -X POST "$GW/api/v1/sap/idocs/delvry07" "${SAPH[@]}" -d "$(delivery 0000000000003002 0080000102 2)"
wait_for "0080000101 pooled" has_status 0080000101 POOLED
wait_for "0080000102 pooled" has_status 0080000102 POOLED
wait_for "IDoc 53" has_idoc_status 0000000000003002 53
echo "Both orders POOLED, IDocs 53; nothing allocated yet"

step "Wave: plan (preview), create, release"
expect 200 -X POST "$GW/api/v1/sites/DC1/outbound/waves/plan" "${SUP[@]}" -d '{"carrierScac":"UPSN","maxOrders":50}'
[[ "$(json "['orderCount']" <<<"$BODY")" == "2" ]] || fail "plan should contain 2 orders: $BODY"
expect 201 -X POST "$GW/api/v1/sites/DC1/outbound/waves" "${SUP[@]}" -d '{"carrierScac":"UPSN","maxOrders":50}'
wave="$(json "['wave_no']" <<<"$BODY")"
expect 403 -X POST "$GW/api/v1/sites/DC1/outbound/waves/$wave/release" "${P[@]}"
expect 200 -X POST "$GW/api/v1/sites/DC1/outbound/waves/$wave/release" "${SUP[@]}"
echo "Wave $wave released: $(json "['orders']" <<<"$BODY")"
has_status 0080000101 RELEASED && has_status 0080000102 RELEASED || fail "orders not released"

step "RF picking: R-01 comes up short (3 of 5) -> re-allocated from R-02 and picked there"
for _ in $(seq 1 10); do
  for _ in $(seq 1 15); do
    out="$(curl -s -w '\n%{http_code}' -X POST "$GW/api/v1/sites/DC1/tasks/next" "${P[@]}")"
    [[ "${out##*$'\n'}" == "200" ]] && break; sleep 1
  done
  [[ "${out##*$'\n'}" == "200" ]] || break
  BODY="${out%$'\n'*}"
  task="$(json "['id']" <<<"$BODY")"; order="$(json "['orderRef']" <<<"$BODY")"; from="$(json "['fromLocation']" <<<"$BODY")"
  if [[ "$(json "['taskType']" <<<"$BODY")" == "COUNT" ]]; then
    # The short pick opened a cycle count of the location (PCK-003 b); count what the system expects there.
    held="$(curl -sf "$GW/api/v1/sites/DC1/inventory/balances?locationId=$from" "${SUP[@]}" \
      | python -c "import json,sys; print(sum(b['qty'] for b in json.load(sys.stdin)['items']))")"
    expect 200 -X POST "$GW/api/v1/sites/DC1/tasks/$task/count" "${P[@]}" \
      -d "{\"checkDigit\":\"$(check_digit "$from")\",\"lines\":[{\"ownerId\":\"ACME\",\"itemNo\":\"SKU-1\",\"qty\":$held}]}"
    echo "Counted $from (count opened by the short pick): $held"
    continue
  fi
  qty="$(json "['qty']" <<<"$BODY")"
  [[ "$order" == "0080000101" && "$from" == "R-01" ]] && qty=3
  extra=""
  [[ "$qty" != "$(json "['qty']" <<<"$BODY")" ]] && extra=",\"shortReason\":\"NOT_FOUND\",\"shortAction\":\"REALLOCATE\""
  expect 200 -X POST "$GW/api/v1/sites/DC1/tasks/$task/pick" "${P[@]}" \
    -d "{\"checkDigit\":\"$(check_digit "$from")\",\"item\":\"$(json "['itemNo']" <<<"$BODY")\",\"qty\":$qty$extra}"
  echo "Picked $qty for $order from $from ($(json "['exceptionReason']" <<<"$BODY"))"
  if has_status 0080000101 PICKED && has_status 0080000102 PICKED; then break; fi
done
wait_for "0080000101 picked" has_status 0080000101 PICKED
wait_for "0080000102 picked" has_status 0080000102 PICKED
expect 200 "$GW/api/v1/sites/DC1/outbound/orders/0080000101" "${SUP[@]}"
[[ "$(json "['lines'][0]['qty_picked']" <<<"$BODY")" == "6" ]] || fail "0080000101 should be fully picked: $BODY"
echo "0080000101 fully picked (6 of 6); allocations:"
python -c "import json,sys; [print('  ', a['location_id'], a['qty'], a['status'], 're-allocation' if a['replaces'] else '') for a in json.load(sys.stdin)['allocations']]" <<<"$BODY"

step "SAP cancels 0080000102 after picking -> RETURN task -> stock back in R-02 -> cancel acknowledged"
expect 202 -X POST "$GW/api/v1/sap/idocs/delvry07" "${SAPH[@]}" -d '{"DOCNUM":"0000000000003003","MESTYP":"SHP_OBDLV_CHANGE",
 "E1EDL20":{"VBELN":"0080000102","LFART":"LF","WERKS":"1000"},"E1EDL18":[{"QUALF":"DEL"}]}'
wait_for "cancel requested" has_status 0080000102 CANCEL_REQUESTED
has_idoc_status 0000000000003003 53 && fail "cancel acknowledged before the stock was returned"
echo "0080000102 CANCEL_REQUESTED; cancel IDoc not yet acknowledged"
for _ in $(seq 1 30); do
  out="$(curl -s -w '\n%{http_code}' -X POST "$GW/api/v1/sites/DC1/tasks/next" "${RCV[@]}")"
  [[ "${out##*$'\n'}" == "200" ]] && break; sleep 1
done
BODY="${out%$'\n'*}"
[[ "$(json "['taskType']" <<<"$BODY")" == "RETURN" ]] || fail "expected a RETURN task: $BODY"
task="$(json "['id']" <<<"$BODY")"; target="$(json "['targetLocation']" <<<"$BODY")"
expect 200 -X POST "$GW/api/v1/sites/DC1/tasks/$task/return" "${RCV[@]}" -d "{\"checkDigit\":\"$(check_digit "$target")\"}"
echo "Returned $(json "['qty']" <<<"$BODY") EA from STAGE-OUT to $target"
wait_for "cancelled" has_status 0080000102 CANCELLED
wait_for "cancel IDoc 53" has_idoc_status 0000000000003003 53
echo "0080000102 CANCELLED; cancel IDoc 53"

step "Ship 0080000101 complete -> goods issue in simulated SAP -> CONFIRMED"
expect 200 -X POST "$GW/api/v1/sites/DC1/outbound/orders/0080000101/ship" "${SUP[@]}" -d '{"carrierScac":"UPSN"}'
wait_for "confirmed" has_status 0080000101 CONFIRMED
expect 200 "$GW/mock-sap/documents?vbeln=0080000101" "${H[@]}"
grep -q 'GI_OUTBOUND_DELIVERY' <<<"$BODY" || fail "no goods issue document"

step "Inventory after wave: staging empty, 4 EA left (10 - 6 shipped)"
expect 200 "$GW/api/v1/sites/DC1/inventory/balances?locationId=STAGE-OUT" "${SUP[@]}"
[[ "$(json "['items']" <<<"$BODY")" == "[]" ]] || fail "stock left in outbound staging: $BODY"
expect 200 "$GW/api/v1/sites/DC1/inventory/items/ACME/SKU-1/summary" "${SUP[@]}"
[[ "$(json "['byStatus'][0]['qty']" <<<"$BODY")" == "4" ]] || fail "unexpected SKU-1 stock: $BODY"
echo "SKU-1 on hand: 4"

printf '\nWAVE SMOKE TEST PASSED (tenant %s)\n' "$TENANT"
