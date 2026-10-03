#!/usr/bin/env bash
# Transfer between sites started in the WMS (ADR-0023), end to end through the gateway with Keycloak users:
#   main warehouse DC1 -> store ST01 without an SAP stock transport order: transfer created -> RF pick -> shipped
#   -> SAP 303 (plant 1000 -> 2000) -> ST01 gets the expected receipt -> RF RECEIVE at ST01 -> closed -> SAP 305.
set -euo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
TENANT="trf-$(date +%s)"
source "$(dirname "$0")/lib/auth.sh"

step() { printf '\n== %s\n' "$*"; }
fail() { echo "FAILED: $*" >&2; exit 1; }
expect() { local want="$1"; shift; local out code
  out="$(curl -s -w '\n%{http_code}' "$@")"; code="${out##*$'\n'}"; BODY="${out%$'\n'*}"
  [[ "$code" == "$want" ]] || fail "expected HTTP $want, got $code: $BODY"; }
json() { python -c "import json,sys; d=json.load(sys.stdin); print(eval('d'+sys.argv[1]))" "$1"; }
wait_for() { local what="$1"; shift; for _ in $(seq 1 90); do "$@" >/dev/null 2>&1 && return 0; sleep 1; done; fail "timed out: $what"; }
check_digit() { curl -sf "$GW/api/v1/sites/$1/locations/$2" "${ADM[@]}" | json "['checkDigit']"; }
order_is() { [[ "$(curl -sf "$GW/api/v1/sites/DC1/outbound/orders/$1" "${SUP[@]}" | json "['status']")" == "$2" ]]; }
receipt_is() { [[ "$(curl -sf "$GW/api/v1/sites/ST01/receipts/$1" "${SUP[@]}" | json "['header']['status']")" == "$2" ]]; }
# next_task <site> <auth-array-name> <task type>: polls RF "next" until a task of that type is assigned; sets TASK / BODY.
next_task() { local site="$1"; local -n who="$2"; local out
  for _ in $(seq 1 60); do
    out="$(curl -s -w '\n%{http_code}' -X POST "$GW/api/v1/sites/$site/tasks/next" "${who[@]}")"
    if [[ "${out##*$'\n'}" == 200 ]]; then
      BODY="${out%$'\n'*}"
      [[ "$(json "['taskType']" <<<"$BODY")" == "$3" ]] || fail "expected a $3 task, got: $BODY"
      TASK="$(json "['id']" <<<"$BODY")"; return 0
    fi
    sleep 1
  done
  fail "no $3 task at $site"; }

step "Waiting for Keycloak and services"
wait_for_keycloak
for svc in master-data-service inventory-service inbound-service task-service outbound-service sap-adapter; do
  wait_for "$svc" curl -sf "$GW/health/$svc"; done

step "Two sites: main warehouse DC1 (plant 1000) and store ST01 (plant 2000)"
provision "$TENANT" "$TENANT-admin" SOLUTION_ADMIN RECEIVER
provision "$TENANT" "$TENANT-picker" PICKER
provision "$TENANT" "$TENANT-receiver" RECEIVER
provision "$TENANT" "$TENANT-supervisor" SUPERVISOR
bearer "$TENANT-admin";      ADM=("${AUTH[@]}" -H "Content-Type: application/json"); ADM_AUTH="${AUTH[1]}"
bearer "$TENANT-picker";     P=("${AUTH[@]}" -H "Content-Type: application/json")
bearer "$TENANT-receiver";   RCV=("${AUTH[@]}" -H "Content-Type: application/json")
bearer "$TENANT-supervisor"; SUP=("${AUTH[@]}" -H "Content-Type: application/json")
for s in DC1:1000 ST01:2000; do
  site="${s%%:*}"; plant="${s##*:}"
  expect 204 -X PUT "$GW/api/v1/sites/$site" "${ADM[@]}" -d "{\"name\":\"$site\",\"timeZone\":\"America/Chicago\",\"erpSite\":\"$plant\"}"
  expect 200 -X PUT "$GW/api/v1/sites/$site/zones/DOCK" "${ADM[@]}" -d '{"zoneType":"DOCK","erpBucket":"0001"}'
  expect 200 -X PUT "$GW/api/v1/sites/$site/zones/STOR" "${ADM[@]}" -d '{"zoneType":"RESERVE","erpBucket":"0001"}'
  expect 200 -X PUT "$GW/api/v1/sites/$site/zones/SHIP" "${ADM[@]}" -d '{"zoneType":"SHIPPING","erpBucket":"0001"}'
  expect 200 -X PUT "$GW/api/v1/sites/$site/locations/DOCK-01" "${ADM[@]}" -d '{"zoneId":"DOCK","locationType":"DOOR"}'
  expect 200 -X PUT "$GW/api/v1/sites/$site/locations/STAGE-OUT" "${ADM[@]}" -d '{"zoneId":"SHIP","locationType":"STAGING_OUT","pickSeq":1}'
  expect 200 -X PUT "$GW/api/v1/sites/$site/locations/A-01-10" "${ADM[@]}" -d '{"zoneId":"STOR","locationType":"RACK","pickSeq":10}'
  expect 204 -X PUT "$GW/api/v1/sap/site-map/$plant" "${ADM[@]}" -d "{\"siteId\":\"$site\",\"timeZone\":\"America/Chicago\",\"defaultOwner\":\"ACME\"}"
done
expect 200 -X PUT "$GW/api/v1/items/ACME/SKU-1" "${ADM[@]}" -d '{"description":"Speaker","baseUom":"EA","status":"ACTIVE",
  "sites":[{"siteId":"DC1","lotControlled":false,"serialControl":"NONE"},{"siteId":"ST01","lotControlled":false,"serialControl":"NONE"}]}'
for _ in $(seq 1 30); do
  code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GW/api/v1/sites/DC1/inventory/receipts" "${ADM[@]}" -H "Idempotency-Key: s1" \
    -d '{"ownerId":"ACME","itemNo":"SKU-1","qty":30,"uom":"EA","locationId":"A-01-10"}')"
  [[ "$code" =~ ^20 ]] && break; sleep 1
done
[[ "$code" =~ ^20 ]] || fail "stock receipt at DC1: HTTP $code"

step "Transfer DC1 -> ST01 (6 EA), picked on RF at DC1, shipped"
expect 201 -X POST "$GW/api/v1/sites/DC1/outbound/transfers" "${SUP[@]}" \
  -d '{"toSiteId":"ST01","note":"Store replenishment","lines":[{"ownerId":"ACME","itemNo":"SKU-1","qty":6,"uom":"EA"}]}'
TR="$(json "['erp_doc_no']" <<<"$BODY")"
echo "Transfer $TR ($(json "['status']" <<<"$BODY"))"
next_task DC1 P PICK
expect 200 -X POST "$GW/api/v1/sites/DC1/tasks/$TASK/pick" "${P[@]}" \
  -d "{\"checkDigit\":\"$(check_digit DC1 "$(json "['fromLocation']" <<<"$BODY")")\",\"item\":\"SKU-1\",\"qty\":$(json "['qty']" <<<"$BODY")}"
wait_for "$TR picked" order_is "$TR" PICKED
expect 200 -X POST "$GW/api/v1/sites/DC1/outbound/orders/$TR/ship" "${SUP[@]}" -d '{}'
TXN="$(json "['shipment_txn_id']" <<<"$BODY")"
wait_for "$TR posted to SAP (303)" order_is "$TR" CONFIRMED
curl -sf "$GW/mock-sap/documents?xblnr=$TXN" -H "$ADM_AUTH" | grep -q 'MOVE_TYPE[^0-9]*303' || fail "no 303 for $TXN"
echo "$TR shipped; SAP 303 plant 1000 -> 2000 (in transit)"

step "ST01: the transfer is an expected receipt; RF RECEIVE, close -> SAP 305"
expect 200 "$GW/api/v1/sites/ST01/outbound/transfers?direction=IN" "${SUP[@]}"
[[ "$(json "[0]['erp_doc_no']" <<<"$BODY")" == "$TR" ]] || fail "ST01 does not see $TR coming: $BODY"
next_task ST01 RCV RECEIVE
[[ "$(json "['docNo']" <<<"$BODY")" == "$TR" ]] || fail "RECEIVE task is not for $TR: $BODY"
expect 200 -X POST "$GW/api/v1/sites/ST01/tasks/$TASK/receive" "${RCV[@]}" -d "{\"scanId\":\"$(date +%s%N)\",
  \"docNo\":\"$TR\",\"itemNo\":\"SKU-1\",\"qty\":6,\"uom\":\"EA\",\"locationId\":\"DOCK-01\",
  \"checkDigit\":\"$(check_digit ST01 DOCK-01)\"}"
expect 200 -X POST "$GW/api/v1/sites/ST01/tasks/$TASK/receive/close" "${RCV[@]}" -d '{}'
wait_for "$TR received at ST01 and posted (305)" receipt_is "$TR" CONFIRMED
expect 200 "$GW/api/v1/sites/ST01/receipts/$TR" "${SUP[@]}"
echo "$TR received at ST01, SAP document $(json "['header']['erpDocument']" <<<"$BODY") (305)"

printf '\nTRANSFER SMOKE TEST PASSED (tenant %s)\n' "$TENANT"
