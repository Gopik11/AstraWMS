#!/usr/bin/env bash
# End-to-end outbound smoke test against the local stack (deploy/docker-compose.yml):
#   SAP DELVRY07 (outbound) -> sap-adapter -> OutboundOrder -> outbound-service allocates in inventory (FEFO)
#   -> PickRequested -> task-service PICK tasks -> RF picks (check digit, serials, one short pick)
#   -> order PICKED -> ship -> inventory issue -> ShipmentConfirmation -> BAPI_OUTB_DELIVERY_CONFIRM_DEC (PGI)
#   in simulated SAP -> ErpPostingResult -> order CONFIRMED.
set -euo pipefail

MD="${MD_URL:-http://localhost:8081}"
INV="${INV_URL:-http://localhost:8082}"
TSK="${TSK_URL:-http://localhost:8084}"
OUT="${OUT_URL:-http://localhost:8085}"
SAP="${SAP_URL:-http://localhost:8090}"
TENANT="out-$(date +%s)"
H=(-H "X-Tenant-Id: $TENANT" -H "X-User-Id: supervisor1" -H "Content-Type: application/json")
P=(-H "X-Tenant-Id: $TENANT" -H "X-User-Id: picker1" -H "Content-Type: application/json")

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
order_status() { curl -sf "$OUT/api/v1/sites/DC1/outbound/orders/$1" "${H[@]}" | json "['status']"; }
has_status() { [[ "$(order_status "$1")" == "$2" ]]; }

step "Waiting for services"
for url in "$MD" "$INV" "$TSK" "$OUT" "$SAP"; do wait_for "$url healthy" curl -sf "$url/actuator/health"; done

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
  code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$INV/api/v1/sites/DC1/inventory/receipts" "${H[@]}" -H "Idempotency-Key: rcv-1" \
    -d '{"ownerId":"ACME","itemNo":"SKU-1","qty":10,"uom":"EA","locationId":"R-01","lpnId":"LPN-OB-1"}')"
  [[ "$code" =~ ^20 ]] && break; sleep 1
done
[[ "$code" =~ ^20 ]] || fail "stock receipt not accepted (master data projection?)"
expect 201 -X POST "$INV/api/v1/sites/DC1/inventory/receipts" "${H[@]}" -H "Idempotency-Key: rcv-2" \
  -d '{"ownerId":"ACME","itemNo":"SKU-SER","qty":3,"uom":"EA","locationId":"R-02","lpnId":"LPN-OB-2","serials":["SN-A","SN-B","SN-C"]}'

step "SAP distributes outbound delivery 0080000001 (4 x SKU-1, 2 x SKU-SER)"
expect 202 -X POST "$SAP/api/v1/sap/idocs/delvry07" "${H[@]}" -d '{"DOCNUM":"0000000000002001","MESTYP":"SHP_OBDLV_SAVE_REPLICA",
 "E1EDL20":{"VBELN":"0080000001","LFART":"LF","WERKS":"1000"},
 "E1ADRM1":[{"PARTNER_Q":"WE","PARTNER_ID":"C-1","NAME1":"Customer One","CITY1":"Austin","COUNTRY1":"US"},{"PARTNER_Q":"SP","PARTNER_ID":"UPSN"}],
 "E1EDT13":[{"QUALF":"006","NTANF":"20261002","NTANZ":"150000"}],
 "E1EDL24":[{"POSNR":"000010","MATNR":"SKU-1","LFIMG":"4","VRKME":"ST"},{"POSNR":"000020","MATNR":"SKU-SER","LFIMG":"2","VRKME":"ST"}]}'
wait_for "order released" has_status 0080000001 RELEASED
wait_for "IDoc status 53" sh -c "curl -sf '$SAP/api/v1/sap/idocs/0000000000002001' -H 'X-Tenant-Id: $TENANT' | grep -q '\"status\":\"53\"'"
echo "Order 0080000001 allocated and released to picking; IDoc 53"

step "RF picking: 2 pick tasks (SKU-1 picked 3 of 4 = short pick, SKU-SER with serial scans)"
wait_for "2 pick tasks" sh -c "curl -sf '$TSK/api/v1/sites/DC1/tasks?status=RELEASED' -H 'X-Tenant-Id: $TENANT' | grep -o '\"taskType\":\"PICK\"' | wc -l | grep -q 2"
for _ in 1 2; do
  expect 200 -X POST "$TSK/api/v1/sites/DC1/tasks/next" "${P[@]}"
  task="$(json "['id']" <<<"$BODY")"; item="$(json "['itemNo']" <<<"$BODY")"; from="$(json "['fromLocation']" <<<"$BODY")"
  qty="$(json "['qty']" <<<"$BODY")"
  cd="$(curl -sf "$MD/api/v1/sites/DC1/locations/$from" "${H[@]}" | json "['checkDigit']")"
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
expect 200 -X POST "$OUT/api/v1/sites/DC1/outbound/orders/0080000001/ship" "${H[@]}" -d '{"carrierScac":"UPSN","trackingNo":"1Z999AA10123456784"}'
wait_for "order confirmed" has_status 0080000001 CONFIRMED
doc="$(curl -sf "$OUT/api/v1/sites/DC1/outbound/orders/0080000001" "${H[@]}" | json "['erp_document']")"
echo "Goods issue material document in (simulated) SAP: $doc"
curl -sf "$SAP/mock-sap/documents?vbeln=0080000001" -H "X-Tenant-Id: $TENANT" | grep -q 'GI_OUTBOUND_DELIVERY' || fail "no GI document"
curl -sf "$SAP/mock-sap/documents?vbeln=0080000001" -H "X-Tenant-Id: $TENANT" | grep -q 'SERIALNO.*SN-A' || fail "serial not posted"

step "Inventory after shipment"
expect 200 "$INV/api/v1/sites/DC1/inventory/balances?locationId=STAGE-OUT" "${H[@]}"
[[ "$(json "['items']" <<<"$BODY")" == "[]" ]] || fail "stock left in outbound staging: $BODY"
expect 200 "$INV/api/v1/sites/DC1/inventory/serials/ACME/SKU-SER/SN-A" "${H[@]}"
grep -q '"status":"SHIPPED"' <<<"$BODY" || fail "SN-A not shipped"
expect 200 "$INV/api/v1/sites/DC1/inventory/items/ACME/SKU-1/summary" "${H[@]}"
echo "SKU-1 remaining: $(json "['byStatus'][0]['qty']" <<<"$BODY") (10 received - 3 shipped)"
[[ "$(json "['byStatus'][0]['qty']" <<<"$BODY")" == "7" ]] || fail "unexpected SKU-1 stock"

printf '\nOUTBOUND SMOKE TEST PASSED (tenant %s)\n' "$TENANT"
