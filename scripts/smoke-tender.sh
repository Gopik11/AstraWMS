#!/usr/bin/env bash
# End-to-end smoke test of the tender gaps (ADR-0022) through the gateway with Keycloak tokens:
#   SAP material master (MATMAS05) -> item with GTIN; location, item and LPN labels;
#   material issue to a cost centre: request -> approval -> RF issue by GS1 scan -> SAP 201 in the simulated SAP;
#   full physical inventory of a zone: freeze -> RF counts -> post differences by a non-counter.
set -euo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
TENANT="tnd-$(date +%s)"
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
check_digit() { curl -sf "$GW/api/v1/sites/DC1/locations/$1" "${ADM[@]}" | json "['checkDigit']"; }
on_hand() { curl -sf "$GW/api/v1/sites/DC1/inventory/balances?locationId=$1" "${BOSS[@]}" | json "['items'][0]['qty'] if d['items'] else 0"; }
next_count() { # next_count <auth-array-name>: the user's next COUNT task id
  local -n auth="$1"
  for _ in $(seq 1 30); do
    out="$(curl -s -w '\n%{http_code}' -X POST "$GW/api/v1/sites/DC1/tasks/next" "${auth[@]}")"
    if [[ "${out##*$'\n'}" == "200" ]]; then
      BODY="${out%$'\n'*}"; [[ "$(json "['taskType']" <<<"$BODY")" == "COUNT" ]] && { json "['id']" <<<"$BODY"; return 0; }
    fi
    sleep 1
  done
  return 1
}

step "Waiting for Keycloak and services"
wait_for_keycloak
for svc in master-data-service inventory-service task-service sap-adapter; do wait_for "$svc healthy" curl -sf "$GW/health/$svc"; done

step "Identity: admin + SAP middleware, store keeper, supervisor, counter"
provision "$TENANT" "$TENANT-admin" SOLUTION_ADMIN ERP_INTEGRATION RECEIVER
provision "$TENANT" "$TENANT-keeper" PICKER
provision "$TENANT" "$TENANT-boss" SUPERVISOR
provision "$TENANT" "$TENANT-cathy" INV_ANALYST
bearer "$TENANT-admin";  ADM=("${AUTH[@]}" -H "Content-Type: application/json")
bearer "$TENANT-keeper"; KEEPER=("${AUTH[@]}" -H "Content-Type: application/json")
bearer "$TENANT-boss";   BOSS=("${AUTH[@]}" -H "Content-Type: application/json")
bearer "$TENANT-cathy";  CATHY=("${AUTH[@]}" -H "Content-Type: application/json")

step "Site DC1, zone STOR with M-01 and M-02, SAP plant 1000 -> DC1"
expect 204 -X PUT "$GW/api/v1/sites/DC1" "${ADM[@]}" -d '{"name":"Main warehouse","timeZone":"America/Chicago","erpSite":"1000"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/zones/STOR" "${ADM[@]}" -d '{"zoneType":"RESERVE","erpBucket":"0001"}'
for loc in M-01 M-02; do expect 200 -X PUT "$GW/api/v1/sites/DC1/locations/$loc" "${ADM[@]}" -d '{"zoneId":"STOR","locationType":"RACK"}'; done
expect 204 -X PUT "$GW/api/v1/sap/site-map/1000" "${ADM[@]}" -d '{"siteId":"DC1","timeZone":"America/Chicago","defaultOwner":"ACME"}'

step "SAP material master MATMAS05 -> item 100300 with its EAN"
expect 200 -X POST "$GW/api/v1/sap/idocs/matmas05" "${ADM[@]}" -d '{"DOCNUM":"0000000000009001","MESTYP":"MATMAS",
 "E1MARAM":{"MATNR":"000000000000100300","MTART":"HAWA","MEINS":"ST",
  "E1MAKTM":[{"SPRAS_ISO":"EN","MAKTX":"Safety gloves L"}],
  "E1MARMM":[{"MEINH":"ST","UMREZ":1,"UMREN":1,"EAN11":"4012345678901"}],
  "E1MARCM":[{"WERKS":"1000"}],"E1MBEWM":[{"VPRSV":"S","STPRS":2.5,"PEINH":1}]}}'
[[ "$(json "['status']" <<<"$BODY")" == "53" ]] || fail "IDoc not posted: $BODY"
expect 200 "$GW/api/v1/items/ACME/100300" "${ADM[@]}"
[[ "$(json "['uoms'][0]['gtin']" <<<"$BODY")" == "4012345678901" ]] || fail "GTIN missing: $BODY"
echo "Item 100300 '$(json "['description']" <<<"$BODY")' from SAP, GTIN 4012345678901"

step "Labels: bins with check digits, item GS1-128, LPN series"
expect 200 -X POST "$GW/api/v1/sites/DC1/labels/locations" "${ADM[@]}" -d '{"zoneId":"STOR"}'
[[ "$(json "['count']" <<<"$BODY")" == "2" ]] || fail "expected 2 location labels: $BODY"
expect 200 -X POST "$GW/api/v1/sites/DC1/labels/items" "${ADM[@]}" -d '{"ownerId":"ACME","itemNos":["100300"]}'
[[ "$(json "['labels'][0]['barcode']" <<<"$BODY")" == "(01)04012345678901" ]] || fail "item label: $BODY"
expect 200 -X POST "$GW/api/v1/sites/DC1/labels/lpns" "${ADM[@]}" -d '{"count":2}'
echo "LPN labels $(json "['labels'][0]['barcode']" <<<"$BODY") .. $(json "['labels'][1]['barcode']" <<<"$BODY")"

step "Stock: 20 at M-01, 3 at M-02"
for _ in $(seq 1 30); do
  code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GW/api/v1/sites/DC1/inventory/receipts" "${ADM[@]}" -H "Idempotency-Key: t1" \
    -d '{"ownerId":"ACME","itemNo":"100300","qty":20,"uom":"EA","locationId":"M-01"}')"
  [[ "$code" =~ ^20 ]] && break; sleep 1
done
expect 201 -X POST "$GW/api/v1/sites/DC1/inventory/receipts" "${ADM[@]}" -H "Idempotency-Key: t2" \
  -d '{"ownerId":"ACME","itemNo":"100300","qty":3,"uom":"EA","locationId":"M-02"}'

step "Material issue to cost centre CC100: request, approval, RF issue by GS1 scan"
expect 200 -X PUT "$GW/api/v1/sites/DC1/inventory/cost-objects/COST_CENTER/CC100" "${ADM[@]}" \
  -d '{"description":"Maintenance","department":"MAINT"}'
expect 201 -X POST "$GW/api/v1/sites/DC1/inventory/material-issues" "${KEEPER[@]}" \
  -d '{"ownerId":"ACME","objectType":"COST_CENTER","objectCode":"CC100","recipient":"Maintenance crew","lines":[{"itemNo":"100300","qty":5,"uom":"EA"}]}'
ISSUE="$(json "['issue_no']" <<<"$BODY")"
expect 422 -X POST "$GW/api/v1/sites/DC1/inventory/material-issues/$ISSUE/lines/10/issue" "${KEEPER[@]}" -H "Idempotency-Key: i0" \
  -d '{"locationId":"M-01","itemScan":"100300","qty":5}'
grep -q INV_ISSUE_STATUS <<<"$BODY" || fail "issue before approval must be refused: $BODY"
expect 200 -X POST "$GW/api/v1/sites/DC1/inventory/material-issues/$ISSUE/approve" "${BOSS[@]}" -d '{}'
expect 200 -X POST "$GW/api/v1/sites/DC1/inventory/material-issues/$ISSUE/lines/10/issue" "${KEEPER[@]}" -H "Idempotency-Key: i1" \
  -d '{"locationId":"M-01","itemScan":"(01)04012345678901","qty":5}'
[[ "$(json "['status']" <<<"$BODY")" == "ISSUED" ]] || fail "not issued: $BODY"
TXN="$(json "['moves'][0]['wms_txn_id']" <<<"$BODY")"
[[ "$(on_hand M-01)" == "15" ]] || fail "M-01 should hold 15"
wait_for "SAP 201 to CC100" sh -c "curl -sf '$GW/mock-sap/documents?xblnr=$TXN' -H '${ADM[1]}' | grep -q 'MOVE_TYPE[^0-9]*201'"
echo "Issue $ISSUE: 5 issued to CC100, SAP goods issue 201 for $TXN"

step "Physical inventory of zone STOR: frozen, counted on RF, posted by the supervisor"
expect 201 -X POST "$GW/api/v1/sites/DC1/inventory/physical-inventories" "${BOSS[@]}" -d '{"zones":["STOR"],"freeze":true}'
PI="$(json "['pi_no']" <<<"$BODY")"
expect 200 -X POST "$GW/api/v1/sites/DC1/inventory/physical-inventories/$PI/start" "${BOSS[@]}"
[[ "$(json "['frozenLocations']" <<<"$BODY")" == "2" ]] || fail "expected 2 frozen locations: $BODY"
expect 422 -X POST "$GW/api/v1/sites/DC1/inventory/receipts" "${ADM[@]}" -H "Idempotency-Key: t3" \
  -d '{"ownerId":"ACME","itemNo":"100300","qty":1,"uom":"EA","locationId":"M-01"}'
grep -q INV_LOCATION_FROZEN <<<"$BODY" || fail "frozen location accepted stock: $BODY"
for _ in 1 2; do
  task="$(next_count CATHY)" || fail "no count task"
  loc="$(curl -sf "$GW/api/v1/sites/DC1/tasks/$task" "${CATHY[@]}" | json "['fromLocation']")"
  qty=15; [[ "$loc" == "M-02" ]] && qty=2
  expect 200 -X POST "$GW/api/v1/sites/DC1/tasks/$task/count" "${CATHY[@]}" \
    -d "{\"checkDigit\":\"$(check_digit "$loc")\",\"lines\":[{\"ownerId\":\"ACME\",\"itemNo\":\"100300\",\"qty\":$qty}]}"
done
wait_for "all counted" sh -c "curl -sf '$GW/api/v1/sites/DC1/inventory/physical-inventories/$PI' -H '${BOSS[1]}' | python -c 'import json,sys; p=json.load(sys.stdin)[\"progress\"]; sys.exit(0 if p.get(\"OPEN\",0)+p.get(\"RECOUNT\",0)==0 else 1)'"
expect 200 -X POST "$GW/api/v1/sites/DC1/inventory/physical-inventories/$PI/post" "${BOSS[@]}"
[[ "$(json "['status']" <<<"$BODY")" == "POSTED" ]] || fail "not posted: $BODY"
[[ "$(on_hand M-02)" == "2" ]] || fail "M-02 should hold 2 after the posting"
echo "$PI posted: M-02 corrected 3 -> 2, freeze lifted"

printf '\nTENDER SMOKE TEST PASSED (tenant %s)\n' "$TENANT"
