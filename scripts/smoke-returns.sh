#!/usr/bin/env bash
# End-to-end customer returns smoke test (§8) through the gateway with Keycloak tokens:
#   SAP returns delivery (LFART LR) -> RMA in AstraWMS -> unit graded A restocked to AVAILABLE, unit graded D to RTV
#   (BLOCKED) -> over-RMA refused for a receiver -> close -> simulated SAP posts the receipt (651) and the restock
#   (453) under two references -> RMA CONFIRMED; then a blind return without RMA.
set -euo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
TENANT="ret-$(date +%s)"
source "$(dirname "$0")/lib/auth.sh"

step() { printf '\n== %s\n' "$*"; }
fail() { echo "FAILED: $*" >&2; exit 1; }
expect() { local want="$1"; shift; local out code
  out="$(curl -s -w '\n%{http_code}' "$@")"; code="${out##*$'\n'}"; BODY="${out%$'\n'*}"
  [[ "$code" == "$want" ]] || fail "expected HTTP $want, got $code: $BODY"; }
json() { python -c "import json,sys; d=json.load(sys.stdin); print(eval('d'+sys.argv[1]))" "$1"; }
wait_for() { local what="$1"; shift; for _ in $(seq 1 60); do "$@" >/dev/null 2>&1 && return 0; sleep 1; done; fail "timed out: $what"; }
rma_status() { curl -sf "$GW/api/v1/sites/DC1/returns/$1" "${R[@]}" | json "['status']"; }
has_status() { [[ "$(rma_status "$1")" == "$2" ]]; }
stock() { curl -sf "$GW/api/v1/sites/DC1/inventory/balances?itemNo=SKU-1&locationId=RET-01" "${ADM[@]}"; }

step "Waiting for Keycloak and services"
wait_for_keycloak
for svc in master-data-service inventory-service inbound-service sap-adapter; do wait_for "$svc" curl -sf "$GW/health/$svc"; done

step "Identity and master data (returns location RET-01)"
provision "$TENANT" "$TENANT-admin" SOLUTION_ADMIN ERP_INTEGRATION INV_ANALYST
provision "$TENANT" "$TENANT-receiver" RECEIVER
provision "$TENANT" "$TENANT-supervisor" SUPERVISOR
bearer "$TENANT-admin";    ADM=("${AUTH[@]}" -H "Content-Type: application/json"); ADM_AUTH="${AUTH[1]}"
bearer "$TENANT-receiver"; R=("${AUTH[@]}" -H "Content-Type: application/json")
# Receiving on the desktop is a supervisor's exception (ADR-0021); receivers receive on RF (see smoke-flow.sh).
bearer "$TENANT-supervisor"; S=("${AUTH[@]}" -H "Content-Type: application/json")
expect 204 -X PUT "$GW/api/v1/sites/DC1" "${ADM[@]}" -d '{"name":"Dallas DC","timeZone":"America/Chicago","erpSite":"1000"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/zones/RET" "${ADM[@]}" -d '{"zoneType":"RETURNS","erpBucket":"0001"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/locations/RET-01" "${ADM[@]}" -d '{"zoneId":"RET","locationType":"FLOOR"}'
expect 200 -X PUT "$GW/api/v1/items/ACME/SKU-1" "${ADM[@]}" \
  -d '{"description":"Speaker","baseUom":"EA","status":"ACTIVE","sites":[{"siteId":"DC1","lotControlled":false,"serialControl":"NONE"}]}'
expect 204 -X PUT "$GW/api/v1/sap/site-map/1000" "${ADM[@]}" -d '{"siteId":"DC1","timeZone":"America/Chicago","defaultOwner":"ACME"}'

step "SAP returns delivery 0060000701 (2 x SKU-1, customer C-1) -> RMA"
expect 202 -X POST "$GW/api/v1/sap/idocs/delvry07" "${ADM[@]}" -d '{"DOCNUM":"0000000000007001","MESTYP":"SHP_IBDLV_SAVE_REPLICA",
 "E1EDL20":{"VBELN":"0060000701","LFART":"LR","WERKS":"1000"},
 "E1ADRM1":[{"PARTNER_Q":"AG","PARTNER_ID":"C-1","NAME1":"Customer One"}],
 "E1EDL24":[{"POSNR":"000010","MATNR":"SKU-1","LFIMG":"2","VRKME":"ST"}]}'
wait_for "RMA expected" has_status 0060000701 EXPECTED
echo "RMA 0060000701 EXPECTED"

step "Receive: grade A -> RESTOCK (AVAILABLE), grade D -> RTV (BLOCKED), a third unit refused (over RMA)"
unit() { expect "$1" -X POST "$GW/api/v1/sites/DC1/returns/0060000701/units" "${S[@]}" -H "Idempotency-Key: $2" -d "$3"; }
# Inventory may still be catching up on the item and location projections; retry the first unit.
for _ in $(seq 1 30); do
  code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GW/api/v1/sites/DC1/returns/0060000701/units" "${S[@]}" -H "Idempotency-Key: u1" \
    -d '{"erpLineRef":"000010","itemNo":"SKU-1","qty":1,"uom":"EA","conditionGrade":"A","locationId":"RET-01"}')"
  [[ "$code" == 201 ]] && break; sleep 1
done
expect 403 -X POST "$GW/api/v1/sites/DC1/returns/0060000701/units" "${R[@]}" -H "Idempotency-Key: desk"   -d '{"erpLineRef":"000010","itemNo":"SKU-1","qty":1,"uom":"EA","conditionGrade":"A","locationId":"RET-01"}'
grep -q RF_ONLY <<<"$BODY" || fail "expected RF_ONLY for a receiver at the desktop: $BODY"
unit 201 u1 '{"erpLineRef":"000010","itemNo":"SKU-1","qty":1,"uom":"EA","conditionGrade":"A","locationId":"RET-01"}'
[[ "$(json "['disposition']" <<<"$BODY")" == RESTOCK ]] || fail "expected RESTOCK: $BODY"
unit 201 u2 '{"erpLineRef":"000010","itemNo":"SKU-1","qty":1,"uom":"EA","conditionGrade":"D","locationId":"RET-01"}'
[[ "$(json "['disposition']" <<<"$BODY")" == RTV ]] || fail "expected RTV: $BODY"
unit 422 u3 '{"erpLineRef":"000010","itemNo":"SKU-1","qty":1,"uom":"EA","conditionGrade":"A","locationId":"RET-01"}'  # no override: refused even for a supervisor
grep -q RET_QTY_OVER_RMA <<<"$BODY" || fail "expected RET_QTY_OVER_RMA: $BODY"
stock | grep -q AVAILABLE || fail "no AVAILABLE stock at RET-01"
stock | grep -q BLOCKED || fail "no BLOCKED stock at RET-01"
echo "Stock at RET-01: 1 AVAILABLE, 1 BLOCKED"

step "Close -> SAP posts 651 receipt and 453 restock -> CONFIRMED"
expect 200 -X POST "$GW/api/v1/sites/DC1/returns/0060000701/close" "${R[@]}"
wait_for "confirmed" has_status 0060000701 CONFIRMED
curl -sf "$GW/api/v1/sites/DC1/returns/0060000701" "${R[@]}" > /tmp/rma.json
receipt="$(json "['receipt_txn_id']" < /tmp/rma.json)"; disposition="$(json "['disposition_txn_id']" < /tmp/rma.json)"
curl -sf "$GW/mock-sap/documents?xblnr=$receipt" -H "$ADM_AUTH" | grep -q 'MOVE_TYPE[^0-9]*651' || fail "no 651 receipt for $receipt"
curl -sf "$GW/mock-sap/documents?xblnr=$disposition" -H "$ADM_AUTH" | grep -q 'MOVE_TYPE[^0-9]*453' || fail "no 453 restock for $disposition"
echo "SAP: receipt $receipt (651), restock $disposition (453), RMA document $(json "['erp_document']" < /tmp/rma.json)"

step "Blind return (no RMA)"
expect 201 -X POST "$GW/api/v1/sites/DC1/returns" "${R[@]}" -d '{"customerName":"Walk-in"}'
blind="$(json "['rma_no']" <<<"$BODY")"
expect 201 -X POST "$GW/api/v1/sites/DC1/returns/$blind/units" "${S[@]}" -H "Idempotency-Key: b1" \
  -d '{"itemNo":"SKU-1","ownerId":"ACME","qty":1,"uom":"EA","conditionGrade":"C","locationId":"RET-01"}'
expect 200 -X POST "$GW/api/v1/sites/DC1/returns/$blind/close" "${R[@]}"
wait_for "blind confirmed" has_status "$blind" CONFIRMED
echo "Blind return $blind CONFIRMED"

printf '\nRETURNS SMOKE TEST PASSED (tenant %s)\n' "$TENANT"
