#!/usr/bin/env bash
# Transfer between sites started in the WMS (ADR-0023), end to end through the gateway with Keycloak users:
#   main warehouse DC1 -> store ST03 without an SAP stock transport order: transfer TR-DC1-000001 created -> RF pick
#   -> loaded and dispatched (load closed) -> SAP 303 (plant 1000 -> 2003) -> in transit -> RF RECEIVE at ST03 ->
#   available at ST03 -> SAP 305. ST03 is a STORE supplied by DC1 (ADR-0024).
# ADR-0025: the enterprise item balance shows the 6 in transit while the transfer travels and none after receipt;
#   DC1's reduction equals ST03's increase plus what is in transit (no spreadsheet reconciliation).
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
receipt_is() { [[ "$(curl -sf "$GW/api/v1/sites/ST03/receipts/$1" "${SUP[@]}" | json "['header']['status']")" == "$2" ]]; }
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

step "Two sites: main warehouse DC1 (plant 1000) and store ST03 (plant 2003)"
provision "$TENANT" "$TENANT-admin" SOLUTION_ADMIN RECEIVER
provision "$TENANT" "$TENANT-picker" PICKER
provision "$TENANT" "$TENANT-receiver" RECEIVER
provision "$TENANT" "$TENANT-supervisor" SUPERVISOR
bearer "$TENANT-admin";      ADM=("${AUTH[@]}" -H "Content-Type: application/json"); ADM_AUTH="${AUTH[1]}"
bearer "$TENANT-picker";     P=("${AUTH[@]}" -H "Content-Type: application/json")
bearer "$TENANT-receiver";   RCV=("${AUTH[@]}" -H "Content-Type: application/json")
bearer "$TENANT-supervisor"; SUP=("${AUTH[@]}" -H "Content-Type: application/json")
for s in DC1:1000:MAIN ST03:2003:STORE; do
  IFS=: read -r site plant type <<<"$s"
  supplier=""; [[ "$type" == STORE ]] && supplier=',"supplyingSite":"DC1"'
  expect 204 -X PUT "$GW/api/v1/sites/$site" "${ADM[@]}" -d "{\"name\":\"$site\",\"timeZone\":\"America/Chicago\",\"erpSite\":\"$plant\",\"siteType\":\"$type\"$supplier}"
  expect 200 -X PUT "$GW/api/v1/sites/$site/zones/DOCK" "${ADM[@]}" -d '{"zoneType":"DOCK","erpBucket":"0001"}'
  expect 200 -X PUT "$GW/api/v1/sites/$site/zones/STOR" "${ADM[@]}" -d '{"zoneType":"RESERVE","erpBucket":"0001"}'
  expect 200 -X PUT "$GW/api/v1/sites/$site/zones/SHIP" "${ADM[@]}" -d '{"zoneType":"SHIPPING","erpBucket":"0001"}'
  expect 200 -X PUT "$GW/api/v1/sites/$site/locations/DOCK-01" "${ADM[@]}" -d '{"zoneId":"DOCK","locationType":"DOOR"}'
  expect 200 -X PUT "$GW/api/v1/sites/$site/locations/STAGE-OUT" "${ADM[@]}" -d '{"zoneId":"SHIP","locationType":"STAGING_OUT","pickSeq":1}'
  expect 200 -X PUT "$GW/api/v1/sites/$site/locations/A-01-10" "${ADM[@]}" -d '{"zoneId":"STOR","locationType":"RACK","pickSeq":10}'
  expect 204 -X PUT "$GW/api/v1/sap/site-map/$plant" "${ADM[@]}" -d "{\"siteId\":\"$site\",\"timeZone\":\"America/Chicago\",\"defaultOwner\":\"ACME\"}"
done
expect 200 -X PUT "$GW/api/v1/items/ACME/SKU-1" "${ADM[@]}" -d '{"description":"Speaker","baseUom":"EA","status":"ACTIVE",
  "sites":[{"siteId":"DC1","lotControlled":false,"serialControl":"NONE"},{"siteId":"ST03","lotControlled":false,"serialControl":"NONE"}]}'
for _ in $(seq 1 30); do
  code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GW/api/v1/sites/DC1/inventory/receipts" "${ADM[@]}" -H "Idempotency-Key: s1" \
    -d '{"ownerId":"ACME","itemNo":"SKU-1","qty":30,"uom":"EA","locationId":"A-01-10"}')"
  [[ "$code" =~ ^20 ]] && break; sleep 1
done
[[ "$code" =~ ^20 ]] || fail "stock receipt at DC1: HTTP $code"

step "Transfer DC1 -> ST03 (6 EA), picked on RF at DC1, shipped"
expect 201 -X POST "$GW/api/v1/sites/DC1/outbound/transfers" "${SUP[@]}" \
  -d '{"toSiteId":"ST03","note":"Store replenishment","lines":[{"ownerId":"ACME","itemNo":"SKU-1","qty":6,"uom":"EA"}]}'
TR="$(json "['erp_doc_no']" <<<"$BODY")"
[[ "$TR" == TR-DC1-000001 ]] || fail "first transfer of the tenant should be TR-DC1-000001, got $TR"
echo "Transfer $TR ($(json "['status']" <<<"$BODY"))"
next_task DC1 P PICK
expect 200 -X POST "$GW/api/v1/sites/DC1/tasks/$TASK/pick" "${P[@]}" \
  -d "{\"checkDigit\":\"$(check_digit DC1 "$(json "['fromLocation']" <<<"$BODY")")\",\"item\":\"SKU-1\",\"qty\":$(json "['qty']" <<<"$BODY")}"
wait_for "$TR picked" order_is "$TR" PICKED
balance() { curl -sf "$GW/api/v1/network/items/ACME/SKU-1" "${SUP[@]}" | python -c "import json,sys
b = json.load(sys.stdin); s = {r['site_id']: r for r in b['sites']}
print(float(s.get('DC1', {}).get('on_hand', 0)), float(s.get('ST03', {}).get('on_hand', 0)), float(b['totalInTransit']), float(b['networkTotal']))"; }
read -r dc1_before st03_before transit_before total_before <<<"$(balance)"
expect 201 -X POST "$GW/api/v1/sites/DC1/outbound/loads" "${SUP[@]}" -d '{"carrierScac":"UPSN","door":"DOCK-01","trailerNo":"TRL-303"}'
LOAD="$(json "['load_no']" <<<"$BODY")"
expect 200 -X POST "$GW/api/v1/sites/DC1/outbound/loads/$LOAD/orders" "${SUP[@]}" -d "{\"erpDocNo\":\"$TR\"}"
expect 200 -X POST "$GW/api/v1/sites/DC1/outbound/loads/$LOAD/close" "${SUP[@]}" -d '{"sealNo":"SEAL-303"}'
echo "$TR loaded on $LOAD and dispatched"
expect 200 "$GW/api/v1/sites/DC1/outbound/orders/$TR" "${SUP[@]}"
TXN="$(json "['shipment_txn_id']" <<<"$BODY")"
read -r dc1_transit st03_transit transit_mid total_mid <<<"$(balance)"
python -c "import sys; a=[float(x) for x in sys.argv[1:]]; sys.exit(0 if a[2]-a[3] == 6 and a[4] == 6 and a[5] == a[1] else 1)" \
  "$dc1_before" "$total_before" "$dc1_before" "$dc1_transit" "$transit_mid" "$total_mid" \
  || fail "in transit: DC1 $dc1_before -> $dc1_transit, in transit $transit_mid, network $total_before -> $total_mid"
echo "Item SKU-1: DC1 $dc1_before -> $dc1_transit, in transit $transit_mid, network total unchanged ($total_mid)"
wait_for "$TR posted to SAP (303)" order_is "$TR" CONFIRMED
curl -sf "$GW/mock-sap/documents?xblnr=$TXN" -H "$ADM_AUTH" | grep -q 'MOVE_TYPE[^0-9]*303' || fail "no 303 for $TXN"
echo "$TR shipped; SAP 303 plant 1000 -> 2003 (in transit)"
expect 200 "$GW/api/v1/sites" "${SUP[@]}"
[[ "$(json "[1]['siteType']" <<<"$BODY")" == STORE ]] || fail "ST03 is not listed as a store: $BODY"
in_transit() { curl -sf "$GW/api/v1/network/inbound" "${SUP[@]}" | python -c "import json,sys
rows = {r['site_id']: r for r in json.load(sys.stdin)}
sys.exit(0 if int(rows.get('ST03', {}).get('transfers_in_transit', 0)) == 1 else 1)"; }
wait_for "network view shows $TR in transit to ST03" in_transit
for half in inventory inbound outbound tasks; do expect 200 "$GW/api/v1/network/$half" "${SUP[@]}"; done
expect 200 "$GW/api/v1/sites/DC1/inventory/aisles" "${SUP[@]}"
grep -q '"aisle":"A-01"' <<<"$BODY" || fail "DC1 aisle A-01 missing from the twin: $BODY"
expect 200 "$GW/api/v1/sites/DC1/tasks/aisles" "${SUP[@]}"
echo "Network view: 1 transfer in transit to ST03; all four halves and the DC1 aisles answer"

step "ST03: the transfer is an expected receipt; RF RECEIVE, close -> SAP 305"
expect 200 "$GW/api/v1/sites/ST03/outbound/transfers?direction=IN" "${SUP[@]}"
[[ "$(json "[0]['erp_doc_no']" <<<"$BODY")" == "$TR" ]] || fail "ST03 does not see $TR coming: $BODY"
next_task ST03 RCV RECEIVE
[[ "$(json "['docNo']" <<<"$BODY")" == "$TR" ]] || fail "RECEIVE task is not for $TR: $BODY"
expect 200 -X POST "$GW/api/v1/sites/ST03/tasks/$TASK/receive" "${RCV[@]}" -d "{\"scanId\":\"$(date +%s%N)\",
  \"docNo\":\"$TR\",\"itemNo\":\"SKU-1\",\"qty\":6,\"uom\":\"EA\",\"locationId\":\"DOCK-01\",
  \"checkDigit\":\"$(check_digit ST03 DOCK-01)\"}"
expect 200 -X POST "$GW/api/v1/sites/ST03/tasks/$TASK/receive/close" "${RCV[@]}" -d '{}'
wait_for "$TR received at ST03 and posted (305)" receipt_is "$TR" CONFIRMED
read -r dc1_after st03_after transit_after total_after <<<"$(balance)"
python -c "import sys; a=[float(x) for x in sys.argv[1:]]; sys.exit(0 if a[1]-a[0] == 6 and a[2] == 0 and a[3] == a[4] else 1)" \
  "$st03_before" "$st03_after" "$transit_after" "$total_after" "$total_before" \
  || fail "after receipt: ST03 $st03_before -> $st03_after, in transit $transit_after, network $total_before -> $total_after"
echo "Item SKU-1: ST03 $st03_before -> $st03_after (+6 = DC1 reduction), in transit $transit_after"
expect 200 "$GW/api/v1/sites/ST03/receipts/$TR" "${SUP[@]}"
echo "$TR received at ST03, SAP document $(json "['header']['erpDocument']" <<<"$BODY") (305)"

printf '\nTRANSFER SMOKE TEST PASSED (tenant %s)\n' "$TENANT"
