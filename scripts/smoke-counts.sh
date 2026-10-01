#!/usr/bin/env bash
# End-to-end cycle count smoke test (§6.3) against the stack, through the gateway with Keycloak tokens:
#   ad-hoc count within tolerance -> auto-adjusted (CC_TOL);
#   large variance -> recount by another counter -> approval by an inventory manager -> adjusted (CC_VAR) and
#   posted to simulated SAP; a counter cannot recount their own count or approve it.
set -euo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
TENANT="cnt-$(date +%s)"
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
count_status() { curl -sf "$GW/api/v1/sites/DC1/inventory/counts/$1" "${MGR[@]}" | json "['status']"; }
on_hand() { curl -sf "$GW/api/v1/sites/DC1/inventory/balances?locationId=$1" "${MGR[@]}" | json "['items'][0]['qty']"; }

step "Waiting for Keycloak and services"
wait_for_keycloak
for svc in master-data-service inventory-service task-service sap-adapter; do wait_for "$svc healthy" curl -sf "$GW/health/$svc"; done

step "Identity: admin, two counters, an inventory manager (approval limit 500)"
provision "$TENANT" "$TENANT-admin" SOLUTION_ADMIN RECEIVER
provision "$TENANT" "$TENANT-cathy" INV_ANALYST
provision "$TENANT" "$TENANT-dave" PICKER
APPROVAL_LIMIT=500 provision "$TENANT" "$TENANT-mona" INV_MANAGER
bearer "$TENANT-admin"; ADM=("${AUTH[@]}" -H "Content-Type: application/json")
bearer "$TENANT-cathy"; CATHY=("${AUTH[@]}" -H "Content-Type: application/json")
bearer "$TENANT-dave";  DAVE=("${AUTH[@]}" -H "Content-Type: application/json")
bearer "$TENANT-mona";  MGR=("${AUTH[@]}" -H "Content-Type: application/json")

step "Master data and stock: 20 x SKU-1 (cost 10.00) in C-01, 5 in C-02; SAP plant 1000"
expect 204 -X PUT "$GW/api/v1/sites/DC1" "${ADM[@]}" -d '{"name":"Dallas DC","timeZone":"America/Chicago","erpSite":"1000"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/zones/STOR" "${ADM[@]}" -d '{"zoneType":"RESERVE","erpBucket":"0001"}'
for loc in C-01 C-02; do expect 200 -X PUT "$GW/api/v1/sites/DC1/locations/$loc" "${ADM[@]}" -d '{"zoneId":"STOR","locationType":"RACK"}'; done
expect 200 -X PUT "$GW/api/v1/items/ACME/SKU-1" "${ADM[@]}" \
  -d '{"description":"Speaker","baseUom":"EA","status":"ACTIVE","standardCost":10,"sites":[{"siteId":"DC1","lotControlled":false,"serialControl":"NONE"}]}'
expect 204 -X PUT "$GW/api/v1/sap/site-map/1000" "${ADM[@]}" -d '{"siteId":"DC1","timeZone":"America/Chicago","defaultOwner":"ACME"}'
for _ in $(seq 1 30); do
  code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GW/api/v1/sites/DC1/inventory/receipts" "${ADM[@]}" -H "Idempotency-Key: r1" \
    -d '{"ownerId":"ACME","itemNo":"SKU-1","qty":20,"uom":"EA","locationId":"C-01"}')"
  [[ "$code" =~ ^20 ]] && break; sleep 1
done
expect 201 -X POST "$GW/api/v1/sites/DC1/inventory/receipts" "${ADM[@]}" -H "Idempotency-Key: r2" \
  -d '{"ownerId":"ACME","itemNo":"SKU-1","qty":5,"uom":"EA","locationId":"C-02"}'

step "Ad-hoc counts of C-01 and C-02"
expect 201 -X POST "$GW/api/v1/sites/DC1/inventory/counts" "${CATHY[@]}" -d '{"locationIds":["C-01","C-02"],"note":"smoke"}'
C1="$(json "['countIds'][0]" <<<"$BODY")"; C2="$(json "['countIds'][1]" <<<"$BODY")"

step "C-02: counted 4 of 5 (within tolerance) -> auto-adjusted CC_TOL"
task="$(next_count CATHY)" || fail "no count task for cathy"
loc="$(curl -sf "$GW/api/v1/sites/DC1/tasks/$task" "${CATHY[@]}" | json "['fromLocation']")"
if [[ "$loc" != "C-02" ]]; then   # take the other one first: C-01 gets a large variance
  expect 200 -X POST "$GW/api/v1/sites/DC1/tasks/$task/count" "${CATHY[@]}" \
    -d "{\"checkDigit\":\"$(check_digit C-01)\",\"lines\":[{\"ownerId\":\"ACME\",\"itemNo\":\"SKU-1\",\"qty\":12}]}"
  task="$(next_count CATHY)" || fail "no second count task"
  FIRST_C01=done
fi
expect 200 -X POST "$GW/api/v1/sites/DC1/tasks/$task/count" "${CATHY[@]}" \
  -d "{\"checkDigit\":\"$(check_digit C-02)\",\"lines\":[{\"ownerId\":\"ACME\",\"itemNo\":\"SKU-1\",\"qty\":4}]}"
wait_for "C-02 adjusted" sh -c "[ \"\$(curl -sf '$GW/api/v1/sites/DC1/inventory/counts/$C2' -H '${MGR[1]}' | python -c 'import json,sys; print(json.load(sys.stdin)[\"status\"])')\" = ADJUSTED ]"
[[ "$(on_hand C-02)" == "4" ]] || fail "C-02 should hold 4"
echo "C-02 adjusted automatically to 4 (CC_TOL)"

step "C-01: counted 12 of 20 (80.00 variance) -> recount by another user -> pending approval"
if [[ -z "${FIRST_C01:-}" ]]; then
  task="$(next_count CATHY)" || fail "no count task for C-01"
  expect 200 -X POST "$GW/api/v1/sites/DC1/tasks/$task/count" "${CATHY[@]}" \
    -d "{\"checkDigit\":\"$(check_digit C-01)\",\"lines\":[{\"ownerId\":\"ACME\",\"itemNo\":\"SKU-1\",\"qty\":12}]}"
fi
sleep 2
expect 204 -X POST "$GW/api/v1/sites/DC1/tasks/next" "${CATHY[@]}"
echo "The recount is not offered to cathy, who counted already"
task="$(next_count DAVE)" || fail "no recount task for dave"
expect 200 -X POST "$GW/api/v1/sites/DC1/tasks/$task/count" "${DAVE[@]}" \
  -d "{\"checkDigit\":\"$(check_digit C-01)\",\"lines\":[{\"ownerId\":\"ACME\",\"itemNo\":\"SKU-1\",\"qty\":12}]}"
wait_for "pending approval" sh -c "[ \"\$(curl -sf '$GW/api/v1/sites/DC1/inventory/counts/$C1' -H '${MGR[1]}' | python -c 'import json,sys; print(json.load(sys.stdin)[\"status\"])')\" = PENDING_APPROVAL ]"
expect 200 "$GW/api/v1/sites/DC1/inventory/counts/$C1" "${DAVE[@]}"
[[ "$(json "['variances']" <<<"$BODY")" == "[]" ]] || fail "a counter must not see the variance"
echo "Pending approval; counters do not see system quantities"

step "Approval: the manager approves -> adjusted (CC_VAR) -> goods movement in simulated SAP"
expect 200 -X POST "$GW/api/v1/sites/DC1/inventory/counts/$C1/approve" "${MGR[@]}"
[[ "$(json "['status']" <<<"$BODY")" == "ADJUSTED" ]] || fail "not adjusted: $BODY"
[[ "$(on_hand C-01)" == "12" ]] || fail "C-01 should hold 12"
echo "C-01 adjusted to 12 by $(json "['decidedBy']" <<<"$BODY"), variance value $(json "['varianceValue']" <<<"$BODY")"
wait_for "ERP posting" sh -c "curl -sf '$GW/mock-sap/documents' -H '${ADM[1]}' | grep -q GOODS_MOVEMENT"

printf '\nCOUNT SMOKE TEST PASSED (tenant %s)\n' "$TENANT"
