#!/usr/bin/env bash
# End-to-end smoke test against the local stack (deploy/docker-compose.yml), through the API gateway with Keycloak
# tokens:
#   master data REST -> outbox -> Kafka -> inventory projection -> inventory commands -> GoodsMovement outbox -> Kafka
set -euo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
MD="$GW"; INV="$GW"
TENANT="smoke-$(date +%s)"
source "$(dirname "$0")/lib/auth.sh"
COMPOSE=(docker compose -f "$(dirname "$0")/../deploy/docker-compose.yml")

step() { printf '\n== %s\n' "$*"; }
fail() { echo "FAILED: $*" >&2; exit 1; }
expect() { # expect <http-code> <curl args...>
  local want="$1"; shift
  local out code
  out="$(curl -s -w '\n%{http_code}' "$@")"; code="${out##*$'\n'}"; BODY="${out%$'\n'*}"
  [[ "$code" == "$want" ]] || fail "expected HTTP $want, got $code: $BODY"
}

step "Waiting for Keycloak and services"
wait_for_keycloak
for svc in master-data-service inventory-service; do
  for _ in $(seq 1 60); do curl -sf "$GW/health/$svc" >/dev/null && break; sleep 2; done
  curl -sf "$GW/health/$svc" >/dev/null || fail "$svc not healthy"
done

step "Identity: tenant $TENANT with an administrator and an inventory operator"
provision "$TENANT" "$TENANT-admin" SOLUTION_ADMIN
provision "$TENANT" "$TENANT-operator" RECEIVER INV_ANALYST
bearer "$TENANT-admin";    H=("${AUTH[@]}" -H "Content-Type: application/json")
bearer "$TENANT-operator"; OP=("${AUTH[@]}" -H "Content-Type: application/json")
expect 401 "$INV/api/v1/sites/DC1/inventory/balances"
expect 403 -X PUT "$MD/api/v1/sites/DC1" "${OP[@]}" -d '{"name":"x","timeZone":"UTC"}'
echo "No token -> 401; operator changing master data -> 403"

step "Master data: site, zones, 24 generated locations, item"
expect 204 -X PUT "$MD/api/v1/sites/DC1" "${H[@]}" -d '{"name":"Dallas DC","timeZone":"America/Chicago","erpSite":"1000"}'
expect 200 -X PUT "$MD/api/v1/sites/DC1/zones/STOR" "${H[@]}" -d '{"zoneType":"RESERVE","erpBucket":"0001"}'
expect 200 -X PUT "$MD/api/v1/sites/DC1/zones/QC" "${H[@]}" -d '{"zoneType":"QC","erpBucket":"0002"}'
expect 200 -X POST "$MD/api/v1/sites/DC1/zones/STOR/locations/generate" "${H[@]}" \
  -d '{"aisles":["A","B"],"bayFrom":1,"bayTo":3,"levels":["1","2"],"positionFrom":1,"positionTo":2,"pattern":"{aisle}-{bay}-{level}{position}","locationType":"RACK"}'
echo "$BODY"
expect 200 -X PUT "$MD/api/v1/sites/DC1/locations/QC-01" "${H[@]}" -d '{"zoneId":"QC","locationType":"FLOOR"}'
expect 200 -X PUT "$MD/api/v1/items/ACME/SKU-100" "${H[@]}" \
  -d '{"description":"Bluetooth Speaker","baseUom":"EA","status":"ACTIVE","sites":[{"siteId":"DC1","lotControlled":false,"serialControl":"NONE"}],"uoms":[{"uom":"CS","numerator":12,"denominator":1,"gtin":"10614141000415"}]}'

step "Inventory: receive 10 CS into A-01-101 (waits for the event-driven projection)"
RECEIPT='{"ownerId":"ACME","itemNo":"SKU-100","qty":10,"uom":"CS","locationId":"A-01-101","lpnId":"LPN-SMOKE-1"}'
for i in $(seq 1 30); do
  code="$(curl -s -o /tmp/astra-smoke.json -w '%{http_code}' -X POST "$INV/api/v1/sites/DC1/inventory/receipts" \
          "${OP[@]}" -H "Idempotency-Key: receipt-1" -d "$RECEIPT")"
  [[ "$code" == "201" ]] && break
  sleep 1
done
[[ "$code" == "201" ]] || fail "receipt not accepted after 30 s: $(cat /tmp/astra-smoke.json)"
cat /tmp/astra-smoke.json; echo

step "Inventory: move LPN to QC-01 (different ERP bucket -> BUCKET_TRANSFER)"
expect 201 -X POST "$INV/api/v1/sites/DC1/inventory/moves" "${OP[@]}" -H "Idempotency-Key: move-1" \
  -d '{"fromLocationId":"A-01-101","lpnId":"LPN-SMOKE-1","toLocationId":"QC-01"}'
grep -q '"BUCKET_TRANSFER"' <<<"$BODY" || fail "no BUCKET_TRANSFER in $BODY"

step "Inventory: cycle count adjustment -2 EA (ADJ_NEG)"
expect 201 -X POST "$INV/api/v1/sites/DC1/inventory/adjustments" "${OP[@]}" -H "Idempotency-Key: adj-1" \
  -d '{"ownerId":"ACME","itemNo":"SKU-100","locationId":"QC-01","lpnId":"LPN-SMOKE-1","qtyDelta":-2,"uom":"EA","reasonCode":"CC_TOL"}'
grep -q '"ADJ_NEG"' <<<"$BODY" || fail "no ADJ_NEG in $BODY"

step "Inventory: balance check (118 EA in LPN-SMOKE-1 at QC-01)"
expect 200 "$INV/api/v1/sites/DC1/inventory/lpns/LPN-SMOKE-1" "${OP[@]}"
echo "$BODY"
grep -q '"locationId":"QC-01"' <<<"$BODY" && grep -q '"qty":118' <<<"$BODY" || fail "unexpected LPN state"

step "Outbox: all events published to Kafka"
sleep 2
pending="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d inventory -tAc \
           "select count(*) from outbox where tenant_id = '$TENANT' and published_at is null")"
[[ "$pending" == "0" ]] || fail "$pending inventory outbox messages not published"
movements="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d inventory -tAc \
           "select count(*) from outbox where tenant_id = '$TENANT' and message_type = 'GoodsMovement'")"
echo "GoodsMovement messages published for ERP adapter: $movements"
[[ "$movements" == "2" ]] || fail "expected 2 GoodsMovement messages, got $movements"

printf '\nSMOKE TEST PASSED (tenant %s)\n' "$TENANT"
