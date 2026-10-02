#!/usr/bin/env bash
# End-to-end outbound smoke test against the local stack (deploy/docker-compose.yml):
#   SAP DELVRY07 (outbound) -> sap-adapter -> OutboundOrder -> outbound-service allocates in inventory (FEFO)
#   -> PickRequested -> task-service PICK tasks -> RF picks (check digit, serials, one short pick)
#   -> order PICKED -> ship -> inventory issue -> ShipmentConfirmation -> BAPI_OUTB_DELIVERY_CONFIRM_DEC (PGI)
#   in simulated SAP -> ErpPostingResult -> order CONFIRMED.
# Every call goes through the API gateway with the Keycloak token of a user who holds the role the step needs.
set -euo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
MD="$GW"; INV="$GW"; TSK="$GW"; OUT="$GW"; SAP="$GW"
TENANT="out-$(date +%s)"
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
order_status() { curl -sf "$OUT/api/v1/sites/DC1/outbound/orders/$1" "${SUP[@]}" | json "['status']"; }
has_status() { [[ "$(order_status "$1")" == "$2" ]]; }

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
bearer "$TENANT-admin";      H=("${AUTH[@]}" -H "Content-Type: application/json"); ADMIN_AUTH="${AUTH[1]}"
bearer "$TENANT-sap";        SAPH=("${AUTH[@]}" -H "Content-Type: application/json"); SAP_AUTH="${AUTH[1]}"
bearer "$TENANT-receiver";   RCV=("${AUTH[@]}" -H "Content-Type: application/json")
bearer "$TENANT-picker";     P=("${AUTH[@]}" -H "Content-Type: application/json"); PICK_AUTH="${AUTH[1]}"
bearer "$TENANT-supervisor"; SUP=("${AUTH[@]}" -H "Content-Type: application/json")

step "Master data: storage, outbound staging, items (one serial-tracked); SAP plant 1000 -> DC1"
expect 204 -X PUT "$MD/api/v1/sites/DC1" "${H[@]}" -d '{"name":"Dallas DC","timeZone":"America/Chicago","erpSite":"1000"}'
expect 200 -X PUT "$MD/api/v1/sites/DC1/zones/STOR" "${H[@]}" -d '{"zoneType":"RESERVE","erpBucket":"0001"}'
expect 200 -X PUT "$MD/api/v1/sites/DC1/zones/SHIP" "${H[@]}" -d '{"zoneType":"SHIPPING","erpBucket":"0001"}'
expect 200 -X PUT "$MD/api/v1/sites/DC1/locations/R-01" "${H[@]}" -d '{"zoneId":"STOR","locationType":"RACK","pickSeq":1}'
expect 200 -X PUT "$MD/api/v1/sites/DC1/locations/R-02" "${H[@]}" -d '{"zoneId":"STOR","locationType":"RACK","pickSeq":2}'
expect 200 -X PUT "$MD/api/v1/sites/DC1/locations/STAGE-OUT" "${H[@]}" -d '{"zoneId":"SHIP","locationType":"STAGING_OUT"}'
expect 200 -X PUT "$MD/api/v1/items/ACME/SKU-1" "${H[@]}" \
  -d '{"description":"Speaker","baseUom":"EA","status":"ACTIVE","sites":[{"siteId":"DC1","lotControlled":false,"serialControl":"NONE"}]}'
expect 200 -X PUT "$MD/api/v1/items/ACME/SKU-SER" "${H[@]}" \
  -d '{"description":"Scanner","baseUom":"EA","status":"ACTIVE","sites":[{"siteId":"DC1","lotControlled":false,"serialControl":"FULL"}]}'
expect 204 -X PUT "$SAP/api/v1/sap/site-map/1000" "${H[@]}" -d '{"siteId":"DC1","timeZone":"America/Chicago","defaultOwner":"ACME"}'

step "Stock in storage: 10 x SKU-1 in R-01, 3 serialised SKU-SER in R-02"
for _ in $(seq 1 30); do
  code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$INV/api/v1/sites/DC1/inventory/receipts" "${RCV[@]}" -H "Idempotency-Key: rcv-1" \
    -d '{"ownerId":"ACME","itemNo":"SKU-1","qty":10,"uom":"EA","locationId":"R-01","lpnId":"LPN-OB-1"}')"
  [[ "$code" =~ ^20 ]] && break; sleep 1
done
[[ "$code" =~ ^20 ]] || fail "stock receipt not accepted (master data projection?)"
for _ in $(seq 1 30); do   # the item reaches inventory through the master data event projection
  code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$INV/api/v1/sites/DC1/inventory/receipts" "${RCV[@]}" -H "Idempotency-Key: rcv-2" \
    -d '{"ownerId":"ACME","itemNo":"SKU-SER","qty":3,"uom":"EA","locationId":"R-02","lpnId":"LPN-OB-2","serials":["SN-A","SN-B","SN-C"]}')"
  [[ "$code" =~ ^20 ]] && break; sleep 1
done
[[ "$code" =~ ^20 ]] || fail "serialised stock receipt not accepted"

step "SAP distributes outbound delivery 0080000001 (4 x SKU-1, 2 x SKU-SER)"
expect 202 -X POST "$SAP/api/v1/sap/idocs/delvry07" "${SAPH[@]}" -d '{"DOCNUM":"0000000000002001","MESTYP":"SHP_OBDLV_SAVE_REPLICA",
 "E1EDL20":{"VBELN":"0080000001","LFART":"LF","WERKS":"1000"},
 "E1ADRM1":[{"PARTNER_Q":"WE","PARTNER_ID":"C-1","NAME1":"Customer One","CITY1":"Austin","COUNTRY1":"US"},{"PARTNER_Q":"SP","PARTNER_ID":"UPSN"}],
 "E1EDT13":[{"QUALF":"006","NTANF":"20261002","NTANZ":"150000"}],
 "E1EDL24":[{"POSNR":"000010","MATNR":"SKU-1","LFIMG":"4","VRKME":"ST"},{"POSNR":"000020","MATNR":"SKU-SER","LFIMG":"2","VRKME":"ST"}]}'
wait_for "order released" has_status 0080000001 RELEASED
wait_for "IDoc status 53" sh -c "curl -sf '$SAP/api/v1/sap/idocs/0000000000002001' -H '$SAP_AUTH' | grep -q '\"status\":\"53\"'"
echo "Order 0080000001 allocated and released to picking; IDoc 53"

step "RF picking: 2 pick tasks (SKU-1 picked 3 of 4 = short pick, SKU-SER with serial scans)"
wait_for "2 pick tasks" sh -c "curl -sf '$TSK/api/v1/sites/DC1/tasks?status=RELEASED' -H '$PICK_AUTH' | grep -o '\"taskType\":\"PICK\"' | wc -l | grep -q 2"
picks=0
while (( picks < 2 )); do
  expect 200 -X POST "$TSK/api/v1/sites/DC1/tasks/next" "${P[@]}"
  task="$(json "['id']" <<<"$BODY")"; item="$(json "['itemNo']" <<<"$BODY")"; from="$(json "['fromLocation']" <<<"$BODY")"
  qty="$(json "['qty']" <<<"$BODY")"
  cd="$(curl -sf "$MD/api/v1/sites/DC1/locations/$from" "${P[@]}" | json "['checkDigit']")"
  if [[ "$(json "['taskType']" <<<"$BODY")" == "COUNT" ]]; then
    # The short pick opened a cycle count of R-01 (PCK-003 b); work follows the travel path, so it comes next.
    held="$(curl -sf "$INV/api/v1/sites/DC1/inventory/balances?locationId=$from" "${SUP[@]}" \
      | python -c "import json,sys; print(sum(b['qty'] for b in json.load(sys.stdin)['items']))")"
    expect 200 -X POST "$TSK/api/v1/sites/DC1/tasks/$task/count" "${P[@]}" \
      -d "{\"checkDigit\":\"$cd\",\"lines\":[{\"ownerId\":\"ACME\",\"itemNo\":\"SKU-1\",\"qty\":$held}]}"
    echo "Counted $from (count opened by the short pick): $held"
    continue
  fi
  picks=$((picks + 1))
  if [[ "$item" == "SKU-1" ]]; then
    body="{\"checkDigit\":\"$cd\",\"qty\":3}"
  else
    body="{\"checkDigit\":\"$cd\",\"qty\":$qty,\"serials\":[\"SN-A\",\"SN-B\"]}"
  fi
  expect 200 -X POST "$TSK/api/v1/sites/DC1/tasks/$task/pick" "${P[@]}" -d "$body"
  echo "Picked $item from $from: $(json "['qtyPicked']" <<<"$BODY") (exception: $(json "['exceptionReason']" <<<"$BODY"))"
done
wait_for "order picked" has_status 0080000001 PICKED

step "Ship -> inventory issue -> ShipmentConfirmation -> PGI in simulated SAP -> CONFIRMED"
expect 403 -X POST "$OUT/api/v1/sites/DC1/outbound/orders/0080000001/ship" "${P[@]}" -d '{"carrierScac":"UPSN"}'
echo "Picker may not ship (403); the supervisor ships"
expect 200 -X POST "$OUT/api/v1/sites/DC1/outbound/orders/0080000001/ship" "${SUP[@]}" -d '{"carrierScac":"UPSN","trackingNo":"1Z999AA10123456784"}'
wait_for "order confirmed" has_status 0080000001 CONFIRMED
doc="$(curl -sf "$OUT/api/v1/sites/DC1/outbound/orders/0080000001" "${SUP[@]}" | json "['erp_document']")"
echo "Goods issue material document in (simulated) SAP: $doc"
curl -sf "$SAP/mock-sap/documents?vbeln=0080000001" "${H[@]}" | grep -q 'GI_OUTBOUND_DELIVERY' || fail "no GI document"
curl -sf "$SAP/mock-sap/documents?vbeln=0080000001" "${H[@]}" | grep -q 'SERIALNO.*SN-A' || fail "serial not posted"

step "Inventory after shipment"
expect 200 "$INV/api/v1/sites/DC1/inventory/balances?locationId=STAGE-OUT" "${SUP[@]}"
[[ "$(json "['items']" <<<"$BODY")" == "[]" ]] || fail "stock left in outbound staging: $BODY"
expect 200 "$INV/api/v1/sites/DC1/inventory/serials/ACME/SKU-SER/SN-A" "${SUP[@]}"
grep -q '"status":"SHIPPED"' <<<"$BODY" || fail "SN-A not shipped"
expect 200 "$INV/api/v1/sites/DC1/inventory/items/ACME/SKU-1/summary" "${SUP[@]}"
echo "SKU-1 remaining: $(json "['byStatus'][0]['qty']" <<<"$BODY") (10 received - 3 shipped)"
[[ "$(json "['byStatus'][0]['qty']" <<<"$BODY")" == "7" ]] || fail "unexpected SKU-1 stock"

printf '\nOUTBOUND SMOKE TEST PASSED (tenant %s)\n' "$TENANT"
