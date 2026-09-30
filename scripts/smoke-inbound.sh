#!/usr/bin/env bash
# End-to-end inbound smoke test against the local stack (deploy/docker-compose.yml):
#   SAP DELVRY07 -> sap-adapter -> ReceiptExpectation -> inbound-service -> RF receiving (SSCC + line)
#   -> inventory-service stock -> close -> ReceiptConfirmation -> sap-adapter -> BAPI_INB_DELIVERY_CONFIRM_DEC
#   (simulated SAP) -> ErpPostingResult -> CONFIRMED; fault injection + repost; IF-INV-001 goods movement to SAP.
set -euo pipefail

MD="${MD_URL:-http://localhost:8081}"
INV="${INV_URL:-http://localhost:8082}"
INB="${INB_URL:-http://localhost:8083}"
SAP="${SAP_URL:-http://localhost:8090}"
TENANT="inb-$(date +%s)"
H=(-H "X-Tenant-Id: $TENANT" -H "X-User-Id: receiver1" -H "Content-Type: application/json")

step() { printf '\n== %s\n' "$*"; }
fail() { echo "FAILED: $*" >&2; exit 1; }
expect() { # expect <http-code> <curl args...>; body in $BODY
  local want="$1"; shift
  local out code
  out="$(curl -s -w '\n%{http_code}' "$@")"; code="${out##*$'\n'}"; BODY="${out%$'\n'*}"
  [[ "$code" == "$want" ]] || fail "expected HTTP $want, got $code: $BODY"
}
json() { python -c "import json,sys; d=json.load(sys.stdin); print(eval('d'+sys.argv[1]))" "$1"; }
wait_for() { # wait_for <description> <command...>
  local what="$1"; shift
  for _ in $(seq 1 60); do "$@" >/dev/null 2>&1 && return 0; sleep 1; done
  fail "timed out waiting for: $what"
}
receipt_status() { curl -sf "$INB/api/v1/sites/DC1/receipts/$1" "${H[@]}" | json "['header']['status']"; }
has_status() { [[ "$(receipt_status "$1")" == "$2" ]]; }

step "Waiting for services"
for url in "$MD" "$INV" "$INB" "$SAP"; do
  wait_for "$url healthy" curl -sf "$url/actuator/health"
done

step "Master data: site DC1, dock zone, DOCK-01, items; SAP plant 1000 -> DC1"
expect 204 -X PUT "$MD/api/v1/sites/DC1" "${H[@]}" -d '{"name":"Dallas DC","timeZone":"America/Chicago","erpSite":"1000"}'
expect 200 -X PUT "$MD/api/v1/sites/DC1/zones/DOCK" "${H[@]}" -d '{"zoneType":"DOCK","erpBucket":"0001"}'
expect 200 -X PUT "$MD/api/v1/sites/DC1/zones/QC" "${H[@]}" -d '{"zoneType":"QC","erpBucket":"0002"}'
expect 200 -X PUT "$MD/api/v1/sites/DC1/locations/DOCK-01" "${H[@]}" -d '{"zoneId":"DOCK","locationType":"DOOR"}'
for sku in SKU-1 SKU-2; do
  expect 200 -X PUT "$MD/api/v1/items/ACME/$sku" "${H[@]}" \
    -d '{"description":"Smoke item","baseUom":"EA","status":"ACTIVE","sites":[{"siteId":"DC1","lotControlled":false,"serialControl":"NONE"}],"uoms":[{"uom":"CS","numerator":12,"denominator":1}]}'
done
expect 204 -X PUT "$SAP/api/v1/sap/site-map/1000" "${H[@]}" -d '{"siteId":"DC1","timeZone":"America/Chicago","defaultOwner":"ACME"}'

delvry() { # delvry <docnum> <vbeln>
  cat <<EOF
{"DOCNUM":"$1","MESTYP":"SHP_IBDLV_SAVE_REPLICA",
 "E1EDL20":{"VBELN":"$2","LFART":"EL","LIFEX":"ASN-$2","WERKS":"1000"},
 "E1ADRM1":[{"PARTNER_Q":"LF","PARTNER_ID":"V-100"}],
 "E1EDT13":[{"QUALF":"007","NTANF":"20261002","NTANZ":"083000"}],
 "E1EDL24":[{"POSNR":"000010","MATNR":"SKU-1","LFIMG":"24","VRKME":"ST","UEBTO":"5"},
            {"POSNR":"000020","MATNR":"SKU-2","LFIMG":"10","VRKME":"KAR"}],
 "E1EDL37":[{"EXIDV":"00106141410000000019","VHILM":"PAL01","E1EDL44":[{"POSNR":"000010","VEMNG":"24","VEMEH":"ST"}]}]}
EOF
}

step "SAP distributes inbound delivery 0180000001 (DELVRY07)"
expect 202 -X POST "$SAP/api/v1/sap/idocs/delvry07" "${H[@]}" -d "$(delvry 0000000000001001 0180000001)"
echo "$BODY"
wait_for "expectation in inbound-service" curl -sf "$INB/api/v1/sites/DC1/receipts/0180000001" "${H[@]}"
wait_for "IDoc status 53" sh -c "curl -sf '$SAP/api/v1/sap/idocs/0000000000001001' -H 'X-Tenant-Id: $TENANT' | grep -q '\"status\":\"53\"'"
echo "IDoc 0000000000001001 status 53 (accepted by AstraWMS)"

step "RF: SSCC single-scan receipt of the pallet (line 000010, 24 EA)"
expect 201 -X POST "$INB/api/v1/sites/DC1/receipts/0180000001/sscc/106141410000000019/receive" "${H[@]}" \
  -H "Idempotency-Key: rf-sscc-1" -d '{"locationId":"DOCK-01"}'
echo "$BODY"

step "RF: line receipt 000020, 8 of 10 CS"
expect 201 -X POST "$INB/api/v1/sites/DC1/receipts/0180000001/lines/000020/receive" "${H[@]}" \
  -H "Idempotency-Key: rf-line-1" -d '{"qty":8,"uom":"CS","lpnId":"LPN-INB-2","locationId":"DOCK-01"}'

step "Inventory: stock on the dock (24 EA in LPN = SSCC, 96 EA in LPN-INB-2)"
expect 200 "$INV/api/v1/sites/DC1/inventory/lpns/106141410000000019" "${H[@]}"
grep -q '"qty":24' <<<"$BODY" || fail "SSCC LPN not in inventory: $BODY"
expect 200 "$INV/api/v1/sites/DC1/inventory/lpns/LPN-INB-2" "${H[@]}"
grep -q '"qty":96' <<<"$BODY" || fail "LPN-INB-2 not in inventory: $BODY"

step "Close receipt (short reason for 000020) -> confirmation to SAP -> CONFIRMED"
expect 200 -X POST "$INB/api/v1/sites/DC1/receipts/0180000001/close" "${H[@]}" -d '{"shortReasons":{"000020":"SHORT_VENDOR"}}'
wait_for "CONFIRMED" has_status 0180000001 CONFIRMED
expect 200 "$INB/api/v1/sites/DC1/receipts/0180000001" "${H[@]}"
doc="$(json "['header']['erpDocument']" <<<"$BODY")"
echo "Goods receipt material document in (simulated) SAP: $doc"
expect 200 "$SAP/mock-sap/documents?vbeln=0180000001" "${H[@]}"
grep -q '"900001"\|"KAR"' <<<"$BODY" || true
grep -q "\"$doc\"" <<<"$BODY" || fail "document $doc not in mock SAP"

step "Fault path: posting period closed for delivery 0180000002 -> POSTING_FAILED -> fix -> repost -> CONFIRMED"
expect 204 -X POST "$SAP/mock-sap/faults" "${H[@]}" -d '{"faultKey":"0180000002","fault":"PERIOD_CLOSED"}'
expect 202 -X POST "$SAP/api/v1/sap/idocs/delvry07" "${H[@]}" -d "$(delvry 0000000000001002 0180000002 | sed 's/00106141410000000019/00106141410000000026/')"
wait_for "expectation 0180000002" curl -sf "$INB/api/v1/sites/DC1/receipts/0180000002" "${H[@]}"
expect 201 -X POST "$INB/api/v1/sites/DC1/receipts/0180000002/lines/000010/receive" "${H[@]}" \
  -H "Idempotency-Key: rf-line-2" -d '{"qty":24,"uom":"EA","locationId":"DOCK-01"}'
expect 201 -X POST "$INB/api/v1/sites/DC1/receipts/0180000002/lines/000020/receive" "${H[@]}" \
  -H "Idempotency-Key: rf-line-3" -d '{"qty":10,"uom":"CS","locationId":"DOCK-01"}'
expect 200 -X POST "$INB/api/v1/sites/DC1/receipts/0180000002/close" "${H[@]}" -d '{}'
wait_for "POSTING_FAILED" has_status 0180000002 POSTING_FAILED
expect 200 "$INB/api/v1/sites/DC1/receipts/0180000002" "${H[@]}"
echo "ERP error: $(json "['header']['erpErrorText']" <<<"$BODY")"
expect 204 -X DELETE "$SAP/mock-sap/faults/0180000002" "${H[@]}"
expect 200 -X POST "$INB/api/v1/sites/DC1/receipts/0180000002/repost" "${H[@]}"
wait_for "CONFIRMED after repost" has_status 0180000002 CONFIRMED
echo "Delivery 0180000002 confirmed after repost"

step "IF-INV-001: cycle-count adjustment in inventory -> BAPI_GOODSMVT_CREATE (702) in simulated SAP"
expect 201 -X POST "$INV/api/v1/sites/DC1/inventory/adjustments" "${H[@]}" -H "Idempotency-Key: adj-inb-1" \
  -d '{"ownerId":"ACME","itemNo":"SKU-1","locationId":"DOCK-01","lpnId":"106141410000000019","qtyDelta":-1,"uom":"EA","reasonCode":"CC_TOL"}'
txn="$(json "['erpMovements'][0]['wmsTxnId']" <<<"$BODY")"
wait_for "goods movement $txn in SAP" sh -c "curl -sf '$SAP/mock-sap/documents?xblnr=$txn' -H 'X-Tenant-Id: $TENANT' | grep -q GOODS_MOVEMENT"
curl -sf "$SAP/mock-sap/documents?xblnr=$txn" -H "X-Tenant-Id: $TENANT" | grep -q '702' || fail "movement type 702 not posted"
echo "Goods movement $txn posted with movement type 702"

printf '\nINBOUND SMOKE TEST PASSED (tenant %s)\n' "$TENANT"
