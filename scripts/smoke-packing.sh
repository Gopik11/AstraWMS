#!/usr/bin/env bash
# End-to-end packing and loading smoke test (§5) through the gateway with Keycloak tokens:
#   site requires packing -> SAP delivery -> RF pick -> ship refused (not packed) -> pack station: carton with SSCC,
#   items scanned, closed with weight -> carrier label + tracking -> load for UPSN, cross-load refused for another
#   carrier -> carton scanned onto the trailer -> trailer closed with seal -> goods issue in simulated SAP.
set -euo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
TENANT="pak-$(date +%s)"
source "$(dirname "$0")/lib/auth.sh"

step() { printf '\n== %s\n' "$*"; }
fail() { echo "FAILED: $*" >&2; exit 1; }
expect() { local want="$1"; shift; local out code
  out="$(curl -s -w '\n%{http_code}' "$@")"; code="${out##*$'\n'}"; BODY="${out%$'\n'*}"
  [[ "$code" == "$want" ]] || fail "expected HTTP $want, got $code: $BODY"; }
json() { python -c "import json,sys; d=json.load(sys.stdin); print(eval('d'+sys.argv[1]))" "$1"; }
wait_for() { local what="$1"; shift; for _ in $(seq 1 60); do "$@" >/dev/null 2>&1 && return 0; sleep 1; done; fail "timed out: $what"; }
check_digit() { curl -sf "$GW/api/v1/sites/DC1/locations/$1" "${ADM[@]}" | json "['checkDigit']"; }
order_status() { curl -sf "$GW/api/v1/sites/DC1/outbound/orders/$1" "${SUP[@]}" | json "['status']"; }
has_status() { [[ "$(order_status "$1")" == "$2" ]]; }

step "Waiting for Keycloak and services"
wait_for_keycloak
for svc in master-data-service inventory-service task-service outbound-service sap-adapter; do wait_for "$svc" curl -sf "$GW/health/$svc"; done

step "Identity, master data, packing required at DC1"
provision "$TENANT" "$TENANT-admin" SOLUTION_ADMIN RECEIVER ERP_INTEGRATION
provision "$TENANT" "$TENANT-picker" PICKER
provision "$TENANT" "$TENANT-supervisor" SUPERVISOR
bearer "$TENANT-admin";      ADM=("${AUTH[@]}" -H "Content-Type: application/json"); ADM_AUTH="${AUTH[1]}"
bearer "$TENANT-picker";     P=("${AUTH[@]}" -H "Content-Type: application/json")
bearer "$TENANT-supervisor"; SUP=("${AUTH[@]}" -H "Content-Type: application/json")
expect 204 -X PUT "$GW/api/v1/sites/DC1" "${ADM[@]}" -d '{"name":"Dallas DC","timeZone":"America/Chicago","erpSite":"1000"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/zones/STOR" "${ADM[@]}" -d '{"zoneType":"RESERVE","erpBucket":"0001"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/zones/SHIP" "${ADM[@]}" -d '{"zoneType":"SHIPPING","erpBucket":"0001"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/locations/R-01" "${ADM[@]}" -d '{"zoneId":"STOR","locationType":"RACK"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/locations/STAGE-OUT" "${ADM[@]}" -d '{"zoneId":"SHIP","locationType":"STAGING_OUT"}'
expect 200 -X PUT "$GW/api/v1/items/ACME/SKU-1" "${ADM[@]}" \
  -d '{"description":"Speaker","baseUom":"EA","status":"ACTIVE","sites":[{"siteId":"DC1","lotControlled":false,"serialControl":"NONE"}]}'
expect 204 -X PUT "$GW/api/v1/sap/site-map/1000" "${ADM[@]}" -d '{"siteId":"DC1","timeZone":"America/Chicago","defaultOwner":"ACME"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/outbound/config" "${ADM[@]}" -d '{"packRequired":true}'
for _ in $(seq 1 30); do
  code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GW/api/v1/sites/DC1/inventory/receipts" "${ADM[@]}" -H "Idempotency-Key: r1" \
    -d '{"ownerId":"ACME","itemNo":"SKU-1","qty":10,"uom":"EA","locationId":"R-01"}')"
  [[ "$code" =~ ^20 ]] && break; sleep 1
done

step "SAP delivery 0080000601 (5 x SKU-1, carrier UPSN) -> RF pick"
expect 202 -X POST "$GW/api/v1/sap/idocs/delvry07" "${ADM[@]}" -d '{"DOCNUM":"0000000000006001","MESTYP":"SHP_OBDLV_SAVE_REPLICA",
 "E1EDL20":{"VBELN":"0080000601","LFART":"LF","WERKS":"1000"},
 "E1ADRM1":[{"PARTNER_Q":"WE","PARTNER_ID":"C-1","NAME1":"Customer One","CITY1":"Austin","COUNTRY1":"US"},{"PARTNER_Q":"SP","PARTNER_ID":"UPSN"}],
 "E1EDL24":[{"POSNR":"000010","MATNR":"SKU-1","LFIMG":"5","VRKME":"ST"}]}'
wait_for "released" has_status 0080000601 RELEASED
for _ in $(seq 1 20); do
  out="$(curl -s -w '\n%{http_code}' -X POST "$GW/api/v1/sites/DC1/tasks/next" "${P[@]}")"; [[ "${out##*$'\n'}" == "200" ]] && break; sleep 1
done
BODY="${out%$'\n'*}"; task="$(json "['id']" <<<"$BODY")"
expect 200 -X POST "$GW/api/v1/sites/DC1/tasks/$task/pick" "${P[@]}" -d "{\"checkDigit\":\"$(check_digit R-01)\",\"item\":\"SKU-1\",\"qty\":5}"
wait_for "picked" has_status 0080000601 PICKED
expect 422 -X POST "$GW/api/v1/sites/DC1/outbound/orders/0080000601/ship" "${SUP[@]}" -d '{}'
grep -q OUT_NOT_PACKED <<<"$BODY" || fail "expected OUT_NOT_PACKED: $BODY"
echo "Ship refused: order not packed (SHP-002)"

step "Pack station: carton with SSCC, 5 x SKU-1 scanned, closed at 4.8 kg -> label"
expect 201 -X POST "$GW/api/v1/sites/DC1/outbound/orders/0080000601/cartons" "${P[@]}" -d '{}'
sscc="$(json "['sscc']" <<<"$BODY")"
expect 422 -X POST "$GW/api/v1/sites/DC1/outbound/cartons/$sscc/items" "${P[@]}" -d '{"erpLineRef":"000010","qty":6}'
expect 200 -X POST "$GW/api/v1/sites/DC1/outbound/cartons/$sscc/items" "${P[@]}" -d '{"erpLineRef":"000010","qty":5}'
expect 200 -X POST "$GW/api/v1/sites/DC1/outbound/cartons/$sscc/close" "${P[@]}" -d '{"weightKg":4.8}'
tracking="$(json "['tracking_no']" <<<"$BODY")"
echo "Carton (00)$sscc closed; carrier $(json "['carrier_scac']" <<<"$BODY"), tracking $tracking"

step "Loading: cross-load refused, carton scanned onto the UPSN trailer, trailer closed -> shipped -> SAP goods issue"
expect 201 -X POST "$GW/api/v1/sites/DC1/outbound/loads" "${SUP[@]}" -d '{"carrierScac":"FDEG","door":"D1"}'
other="$(json "['load_no']" <<<"$BODY")"
expect 422 -X POST "$GW/api/v1/sites/DC1/outbound/loads/$other/orders" "${P[@]}" -d "{\"sscc\":\"(00)$sscc\"}"
grep -q OUT_CROSS_LOAD <<<"$BODY" || fail "expected OUT_CROSS_LOAD: $BODY"
expect 201 -X POST "$GW/api/v1/sites/DC1/outbound/loads" "${SUP[@]}" -d '{"carrierScac":"UPSN","door":"D2","trailerNo":"TR-42"}'
load="$(json "['load_no']" <<<"$BODY")"
expect 200 -X POST "$GW/api/v1/sites/DC1/outbound/loads/$load/orders" "${P[@]}" -d "{\"sscc\":\"(00)$sscc\"}"
expect 200 -X POST "$GW/api/v1/sites/DC1/outbound/loads/$load/close" "${SUP[@]}" -d '{"sealNo":"SEAL-123"}'
echo "Trailer closed: BOL $(json "['bol_no']" <<<"$BODY")"
wait_for "confirmed" has_status 0080000601 CONFIRMED
curl -sf "$GW/mock-sap/documents?vbeln=0080000601" -H "$ADM_AUTH" | grep -q GI_OUTBOUND_DELIVERY || fail "no goods issue"
echo "Goods issue posted in simulated SAP"

printf '\nPACKING SMOKE TEST PASSED (tenant %s)\n' "$TENANT"
