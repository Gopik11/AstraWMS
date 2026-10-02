#!/usr/bin/env bash
# Replays the live SAP-style flow test (ADR-0019) end to end through the gateway, with Keycloak users per role:
#   order 8000001 before any stock -> BACKORDERED
#   ASN 1800001 (24 EA) -> RF RECEIVE task: scan to DOCK-01, close -> ERP confirmation
#   putaway: suggested location is storage (never STAGE-OUT); override to A-01-11 is what the task shows
#   -> 8000001 leaves BACKORDERED by itself (backorder recovery) and gets a pick task
#   order 8000002 (4 EA) -> allocated from A-01-11 / LPN1800001 -> RF pick -> carton SSCC -> picker adds it to the
#   load the supervisor opened (a picker cannot open one) -> trailer closed -> goods issue CONFIRMED
#   RMA 9000001 (1 EA) -> RF RECEIVE task: grade A -> RESTOCK -> close -> CONFIRMED -> the unit gets a putaway task
set -euo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
TENANT="flow-$(date +%s)"
source "$(dirname "$0")/lib/auth.sh"

step() { printf '\n== %s\n' "$*"; }
fail() { echo "FAILED: $*" >&2; exit 1; }
expect() { local want="$1"; shift; local out code
  out="$(curl -s -w '\n%{http_code}' "$@")"; code="${out##*$'\n'}"; BODY="${out%$'\n'*}"
  [[ "$code" == "$want" ]] || fail "expected HTTP $want, got $code: $BODY"; }
json() { python -c "import json,sys; d=json.load(sys.stdin); print(eval('d'+sys.argv[1]))" "$1"; }
wait_for() { local what="$1"; shift; for _ in $(seq 1 90); do "$@" >/dev/null 2>&1 && return 0; sleep 1; done; fail "timed out: $what"; }
check_digit() { curl -sf "$GW/api/v1/sites/DC1/locations/$1" "${ADM[@]}" | json "['checkDigit']"; }
order_status() { curl -sf "$GW/api/v1/sites/DC1/outbound/orders/$1" "${SUP[@]}" | json "['status']"; }
order_is() { [[ "$(order_status "$1")" == "$2" ]]; }
receipt_is() { [[ "$(curl -sf "$GW/api/v1/sites/DC1/receipts/$1" "${SUP[@]}" | json "['header']['status']")" == "$2" ]]; }
return_is() { [[ "$(curl -sf "$GW/api/v1/sites/DC1/returns/$1" "${SUP[@]}" | json "['status']")" == "$2" ]]; }
# next_task <auth-array-name> <task type>: polls RF "next" until a task of that type is assigned; sets TASK / BODY.
next_task() { local -n who="$1"; local out
  for _ in $(seq 1 60); do
    out="$(curl -s -w '\n%{http_code}' -X POST "$GW/api/v1/sites/DC1/tasks/next" "${who[@]}")"
    if [[ "${out##*$'\n'}" == 200 ]]; then
      BODY="${out%$'\n'*}"
      [[ "$(json "['taskType']" <<<"$BODY")" == "$2" ]] || fail "expected a $2 task, got: $BODY"
      TASK="$(json "['id']" <<<"$BODY")"; return 0
    fi
    sleep 1
  done
  fail "no $2 task"; }
idoc() { expect 202 -X POST "$GW/api/v1/sap/idocs/delvry07" "${SAPH[@]}" -d "$1"; }

step "Waiting for Keycloak and services"
wait_for_keycloak
for svc in master-data-service inventory-service inbound-service task-service outbound-service sap-adapter; do
  wait_for "$svc" curl -sf "$GW/health/$svc"; done

step "Identity and master data: dock, reserve storage, outbound staging, packing required"
provision "$TENANT" "$TENANT-admin" SOLUTION_ADMIN
provision "$TENANT" "$TENANT-sap" ERP_INTEGRATION
provision "$TENANT" "$TENANT-receiver" RECEIVER
provision "$TENANT" "$TENANT-picker" PICKER
provision "$TENANT" "$TENANT-supervisor" SUPERVISOR
bearer "$TENANT-admin";      ADM=("${AUTH[@]}" -H "Content-Type: application/json"); ADM_AUTH="${AUTH[1]}"
bearer "$TENANT-sap";        SAPH=("${AUTH[@]}" -H "Content-Type: application/json")
bearer "$TENANT-receiver";   RCV=("${AUTH[@]}" -H "Content-Type: application/json")
bearer "$TENANT-picker";     P=("${AUTH[@]}" -H "Content-Type: application/json")
bearer "$TENANT-supervisor"; SUP=("${AUTH[@]}" -H "Content-Type: application/json")
expect 204 -X PUT "$GW/api/v1/sites/DC1" "${ADM[@]}" -d '{"name":"Dallas DC","timeZone":"America/Chicago","erpSite":"1000"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/zones/DOCK" "${ADM[@]}" -d '{"zoneType":"DOCK","erpBucket":"0001"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/zones/STOR" "${ADM[@]}" -d '{"zoneType":"RESERVE","erpBucket":"0001"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/zones/SHIP" "${ADM[@]}" -d '{"zoneType":"SHIPPING","erpBucket":"0001"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/locations/DOCK-01" "${ADM[@]}" -d '{"zoneId":"DOCK","locationType":"DOOR"}'
# STAGE-OUT has the lowest pick sequence: the old engine took it as the "nearest empty" location.
expect 200 -X PUT "$GW/api/v1/sites/DC1/locations/STAGE-OUT" "${ADM[@]}" -d '{"zoneId":"SHIP","locationType":"STAGING_OUT","pickSeq":1}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/locations/A-01-10" "${ADM[@]}" -d '{"zoneId":"STOR","locationType":"RACK","pickSeq":10}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/locations/A-01-11" "${ADM[@]}" -d '{"zoneId":"STOR","locationType":"RACK","pickSeq":11}'
expect 200 -X PUT "$GW/api/v1/items/ACME/SKU-1" "${ADM[@]}" \
  -d '{"description":"Speaker","baseUom":"EA","status":"ACTIVE","sites":[{"siteId":"DC1","lotControlled":false,"serialControl":"NONE"}]}'
expect 204 -X PUT "$GW/api/v1/sap/site-map/1000" "${ADM[@]}" -d '{"siteId":"DC1","timeZone":"America/Chicago","defaultOwner":"ACME"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/outbound/config" "${ADM[@]}" -d '{"packRequired":true}'

step "Order 8000001 (20 EA) before any stock -> BACKORDERED"
idoc '{"DOCNUM":"0000000000008001","MESTYP":"SHP_OBDLV_SAVE_REPLICA","E1EDL20":{"VBELN":"8000001","LFART":"LF","WERKS":"1000"},
 "E1ADRM1":[{"PARTNER_Q":"WE","PARTNER_ID":"C-1","NAME1":"Customer One"},{"PARTNER_Q":"SP","PARTNER_ID":"UPSN"}],
 "E1EDT13":[{"QUALF":"006","NTANF":"20261003","NTANZ":"090000"}],
 "E1EDL24":[{"POSNR":"000010","MATNR":"SKU-1","LFIMG":"20","VRKME":"ST"}]}'
wait_for "8000001 backordered" order_is 8000001 BACKORDERED
echo "8000001 BACKORDERED"

step "ASN 1800001 (24 EA) -> RF RECEIVE task: scan to DOCK-01, close -> ERP"
idoc '{"DOCNUM":"0000000000001801","MESTYP":"SHP_IBDLV_SAVE_REPLICA","E1EDL20":{"VBELN":"1800001","LFART":"EL","WERKS":"1000"},
 "E1ADRM1":[{"PARTNER_Q":"LF","PARTNER_ID":"V-100"}],"E1EDT13":[{"QUALF":"007","NTANF":"20261002","NTANZ":"080000"}],
 "E1EDL24":[{"POSNR":"000010","MATNR":"SKU-1","LFIMG":"24","VRKME":"ST"}]}'
next_task RCV RECEIVE
[[ "$(json "['docNo']" <<<"$BODY")" == 1800001 ]] || fail "RECEIVE task is not for 1800001: $BODY"
receive_task="$TASK"
expect 200 -X POST "$GW/api/v1/sites/DC1/tasks/$receive_task/receive" "${RCV[@]}" -d "{\"scanId\":\"$(date +%s%N)\",
  \"docNo\":\"1800001\",\"itemNo\":\"SKU-1\",\"qty\":24,\"uom\":\"EA\",\"lpnId\":\"LPN1800001\",\"locationId\":\"DOCK-01\",
  \"checkDigit\":\"$(check_digit DOCK-01)\"}"
expect 200 -X POST "$GW/api/v1/sites/DC1/tasks/$receive_task/receive/close" "${RCV[@]}" -d '{}'
wait_for "1800001 confirmed by the ERP" receipt_is 1800001 CONFIRMED
echo "1800001 received on RF and CONFIRMED"

step "Putaway: suggested storage location (not STAGE-OUT); override to A-01-11 is persisted"
next_task RCV PUTAWAY
putaway="$TASK"; suggested="$(json "['targetLocation']" <<<"$BODY")"
[[ "$suggested" != STAGE-OUT && "$suggested" =~ ^A-01- ]] || fail "putaway suggested $suggested"
echo "Suggested $suggested ($(json "['strategy']" <<<"$BODY"))"
expect 422 -X POST "$GW/api/v1/sites/DC1/tasks/$putaway/confirm" "${RCV[@]}" \
  -d "{\"lpnId\":\"LPN1800001\",\"locationId\":\"STAGE-OUT\",\"checkDigit\":\"$(check_digit STAGE-OUT)\"}"
grep -q TSK_LOCATION_NOT_ALLOWED <<<"$BODY" || fail "override to STAGE-OUT should be refused: $BODY"
expect 200 -X POST "$GW/api/v1/sites/DC1/tasks/$putaway/confirm" "${RCV[@]}" \
  -d "{\"lpnId\":\"LPN1800001\",\"locationId\":\"A-01-11\",\"checkDigit\":\"$(check_digit A-01-11)\"}"
expect 200 "$GW/api/v1/sites/DC1/tasks?q=LPN1800001&type=PUTAWAY" "${SUP[@]}"
[[ "$(json "[0]['targetLocation']" <<<"$BODY")" == A-01-11 ]] || fail "task list does not show A-01-11: $BODY"
echo "Task list shows A-01-11 (suggested $(json "[0]['suggestedLocation']" <<<"$BODY"))"

step "8000001 leaves BACKORDERED once the stock is in storage"
wait_for "8000001 released" order_is 8000001 RELEASED
echo "8000001 RELEASED by backorder recovery"

step "Order 8000002 (4 EA) -> A-01-11 / LPN1800001 -> RF picks"
idoc '{"DOCNUM":"0000000000008002","MESTYP":"SHP_OBDLV_SAVE_REPLICA","E1EDL20":{"VBELN":"8000002","LFART":"LF","WERKS":"1000"},
 "E1ADRM1":[{"PARTNER_Q":"WE","PARTNER_ID":"C-2","NAME1":"Customer Two"},{"PARTNER_Q":"SP","PARTNER_ID":"UPSN"}],
 "E1EDT13":[{"QUALF":"006","NTANF":"20261003","NTANZ":"100000"}],
 "E1EDL24":[{"POSNR":"000010","MATNR":"SKU-1","LFIMG":"4","VRKME":"ST"}]}'
wait_for "8000002 released" order_is 8000002 RELEASED
expect 200 "$GW/api/v1/sites/DC1/outbound/orders/8000002" "${SUP[@]}"
[[ "$(json "['allocations'][0]['location_id']" <<<"$BODY")/$(json "['allocations'][0]['lpn_id']" <<<"$BODY")" == A-01-11/LPN1800001 ]] \
  || fail "8000002 allocation: $BODY"
for _ in 1 2; do                                  # the picks of 8000001 and 8000002, in whatever order they come
  next_task P PICK
  # ADR-0020: a location-only pick is refused; the item (or its GTIN) must be scanned.
  expect 422 -X POST "$GW/api/v1/sites/DC1/tasks/$TASK/pick" "${P[@]}" \
    -d "{\"checkDigit\":\"$(check_digit "$(json "['fromLocation']" <<<"$BODY")")\",\"qty\":1}"
  grep -q TSK_ITEM_SCAN_REQUIRED <<<"$BODY" || fail "location-only pick should be refused: $BODY"
  expect 200 "$GW/api/v1/sites/DC1/tasks/$TASK" "${P[@]}"                                  # the task again
  expect 200 -X POST "$GW/api/v1/sites/DC1/tasks/$TASK/pick" "${P[@]}" \
    -d "{\"checkDigit\":\"$(check_digit "$(json "['fromLocation']" <<<"$BODY")")\",\"item\":\"SKU-1\",\"qty\":$(json "['qty']" <<<"$BODY")}"
done
wait_for "8000002 picked" order_is 8000002 PICKED

step "Pack 8000002, picker adds the carton to the supervisor's load, trailer closed -> goods issue"
expect 201 -X POST "$GW/api/v1/sites/DC1/outbound/orders/8000002/cartons" "${P[@]}" -d '{}'
sscc="$(json "['sscc']" <<<"$BODY")"
expect 200 -X POST "$GW/api/v1/sites/DC1/outbound/cartons/$sscc/items" "${P[@]}" -d '{"erpLineRef":"000010","qty":4}'
expect 200 -X POST "$GW/api/v1/sites/DC1/outbound/cartons/$sscc/close" "${P[@]}" -d '{"weightKg":1.2}'
expect 403 -X POST "$GW/api/v1/sites/DC1/outbound/loads" "${P[@]}" -d '{"carrierScac":"UPSN"}'
expect 201 -X POST "$GW/api/v1/sites/DC1/outbound/loads" "${SUP[@]}" -d '{"carrierScac":"UPSN","door":"D1"}'
load="$(json "['load_no']" <<<"$BODY")"
expect 200 -X POST "$GW/api/v1/sites/DC1/outbound/loads/$load/orders" "${P[@]}" -d "{\"sscc\":\"(00)$sscc\"}"
expect 200 -X POST "$GW/api/v1/sites/DC1/outbound/loads/$load/close" "${SUP[@]}" -d '{"sealNo":"SEAL-1"}'
wait_for "8000002 confirmed" order_is 8000002 CONFIRMED
echo "8000002 shipped on $load with $(json "['bol_no']" <<<"$BODY") and CONFIRMED"

step "RMA 9000001 (1 EA) -> RF RECEIVE task: grade A -> RESTOCK -> ERP -> putaway task for the unit"
idoc '{"DOCNUM":"0000000000009001","MESTYP":"SHP_IBDLV_SAVE_REPLICA","E1EDL20":{"VBELN":"9000001","LFART":"LR","WERKS":"1000"},
 "E1ADRM1":[{"PARTNER_Q":"AG","PARTNER_ID":"C-2","NAME1":"Customer Two"}],
 "E1EDL24":[{"POSNR":"000010","MATNR":"SKU-1","LFIMG":"1","VRKME":"ST"}]}'
next_task RCV RECEIVE
[[ "$(json "['receiveKind']" <<<"$BODY")" == RMA ]] || fail "expected the RMA task: $BODY"
rma_task="$TASK"
expect 200 -X POST "$GW/api/v1/sites/DC1/tasks/$rma_task/receive" "${RCV[@]}" -d "{\"scanId\":\"$(date +%s%N)\",
  \"docNo\":\"9000001\",\"itemNo\":\"SKU-1\",\"qty\":1,\"uom\":\"EA\",\"conditionGrade\":\"A\",\"locationId\":\"DOCK-01\",
  \"checkDigit\":\"$(check_digit DOCK-01)\"}"
[[ "$(json "['result']['disposition']" <<<"$BODY")/$(json "['result']['stock_status']" <<<"$BODY")" == RESTOCK/AVAILABLE ]] \
  || fail "unexpected disposition: $BODY"
unit_lpn="$(json "['result']['lpn_id']" <<<"$BODY")"
expect 200 -X POST "$GW/api/v1/sites/DC1/tasks/$rma_task/receive/close" "${RCV[@]}" -d '{}'
wait_for "9000001 confirmed" return_is 9000001 CONFIRMED
next_task RCV PUTAWAY
[[ "$(json "['lpnId']" <<<"$BODY")" == "$unit_lpn" ]] || fail "putaway is not for the returned unit $unit_lpn: $BODY"
echo "Returned unit $unit_lpn RESTOCK -> putaway to $(json "['targetLocation']" <<<"$BODY")"

step "Dock sweep: a loose unit left on DOCK-01 gets an LPN and a putaway (ADR-0020)"
expect 201 -X POST "$GW/api/v1/sites/DC1/inventory/receipts" "${RCV[@]}" -H "Idempotency-Key: loose-1" \
  -d '{"ownerId":"ACME","itemNo":"SKU-1","qty":1,"uom":"EA","locationId":"DOCK-01"}'
# The task service learns about the receipt asynchronously; sweep until it has.
for _ in $(seq 1 30); do
  expect 200 -X POST "$GW/api/v1/sites/DC1/tasks/sweep-dock" "${SUP[@]}"
  [[ "$(json "['lpnsCreated']" <<<"$BODY")" == 1 ]] && break
  sleep 1
done
[[ "$(json "['lpnsCreated']" <<<"$BODY")" == 1 ]] || fail "sweep should palletise the loose unit: $BODY"
swept() { curl -sf "$GW/api/v1/sites/DC1/tasks?type=PUTAWAY&q=DK" "${SUP[@]}" | grep -q '"fromLocation":"DOCK-01"'; }
wait_for "putaway for the swept unit" swept
expect 200 "$GW/api/v1/sites/DC1/tasks?type=PUTAWAY&q=DK" "${SUP[@]}"
echo "Loose unit put on $(json "[0]['lpnId']" <<<"$BODY") -> putaway to $(json "[0]['targetLocation']" <<<"$BODY")"

printf '\nFLOW SMOKE TEST PASSED (tenant %s)\n' "$TENANT"
