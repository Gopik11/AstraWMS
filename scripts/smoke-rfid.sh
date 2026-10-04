#!/usr/bin/env bash
# RFID smoke test (ADR-0027) through the API gateway with Keycloak tokens, in a fresh tenant:
#   tags resolve to units (EA and case GTINs, serial-tracked serials) and to the pallet LPN; a location read reconciles;
#   the WMS encodes and commissions an SGTIN-96 tag.
# Against the VPS: GATEWAY_URL=https://astrawms.cloud KC_URL=<tunnel> PROVISIONER_SECRET=... scripts/smoke-rfid.sh
set -euo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
TENANT="smoke-rfid-$(date +%s)"
source "$(dirname "$0")/lib/auth.sh"

step() { printf '\n== %s\n' "$*"; }
fail() { echo "FAILED: $*" >&2; exit 1; }
expect() { # expect <http-code> <curl args...>
  local want="$1"; shift
  local out code
  out="$(curl -s -w '\n%{http_code}' "$@")"; code="${out##*$'\n'}"; BODY="${out%$'\n'*}"
  [[ "$code" == "$want" ]] || fail "expected HTTP $want, got $code: $BODY"
}
check() { # check <python expression over r (the JSON body)> <description>
  python -c 'import json,sys; r=json.loads(sys.argv[1]); sys.exit(0 if eval(sys.argv[2]) else 1)' "$BODY" "$1" \
    || fail "$2: $BODY"
  echo "ok: $2"
}

RFID="$GW/api/v1/sites/DC1/inventory/rfid"
PALLET="106141412345678908"                 # SSCC; its SSCC-96 tag is 3174257BF4499602D2000000 (GS1 TDS example)
CASE_TAG="3074257BF7194E4000001A85"          # SGTIN-96 of GTIN 80614141123458 (a case of 12), serial 6789
UNIT_TAG="urn:epc:id:sgtin:0614141.012345.1" # GTIN 00614141123452 (EA), serial 1
SER_TAG="urn:epc:id:sgtin:0614141.099999.1001"

step "Waiting for Keycloak and services"
wait_for_keycloak
for svc in master-data-service inventory-service; do
  for _ in $(seq 1 60); do curl -sf "$GW/health/$svc" >/dev/null && break; sleep 2; done
  curl -sf "$GW/health/$svc" >/dev/null || fail "$svc not healthy"
done

step "Identity: tenant $TENANT with an administrator and a receiver / inventory analyst"
provision "$TENANT" "$TENANT-admin" SOLUTION_ADMIN
provision "$TENANT" "$TENANT-operator" RECEIVER INV_ANALYST
bearer "$TENANT-admin";    H=("${AUTH[@]}" -H "Content-Type: application/json")
bearer "$TENANT-operator"; OP=("${AUTH[@]}" -H "Content-Type: application/json")
expect 401 -X POST "$RFID/resolve" -H "Content-Type: application/json" -d "{\"reads\":[\"$CASE_TAG\"]}"
echo "No token -> 401"

step "Master data: site, zone, two locations, a tagged item (EA + CS GTINs) and a serial-tracked item"
expect 204 -X PUT "$GW/api/v1/sites/DC1" "${H[@]}" -d '{"name":"RFID DC","timeZone":"UTC","erpSite":"1000"}'
expect 200 -X PUT "$GW/api/v1/sites/DC1/zones/STOR" "${H[@]}" -d '{"zoneType":"RESERVE","erpBucket":"0001"}'
expect 200 -X POST "$GW/api/v1/sites/DC1/zones/STOR/locations/generate" "${H[@]}" \
  -d '{"aisles":["A"],"bayFrom":1,"bayTo":1,"levels":["1"],"positionFrom":1,"positionTo":2,"pattern":"{aisle}-{bay}-{level}{position}","locationType":"RACK"}'
expect 200 -X PUT "$GW/api/v1/items/ACME/SKU-RF" "${H[@]}" \
  -d '{"description":"RFID tagged item","baseUom":"EA","status":"ACTIVE","sites":[{"siteId":"DC1","lotControlled":false,"serialControl":"NONE"}],"uoms":[{"uom":"EA","numerator":1,"denominator":1,"gtin":"00614141123452"},{"uom":"CS","numerator":12,"denominator":1,"gtin":"80614141123458"}]}'
expect 200 -X PUT "$GW/api/v1/items/ACME/SKU-RFS" "${H[@]}" \
  -d '{"description":"RFID serial-tracked item","baseUom":"EA","status":"ACTIVE","sites":[{"siteId":"DC1","lotControlled":false,"serialControl":"FULL"}],"uoms":[{"uom":"EA","numerator":1,"denominator":1,"gtin":"00614141999996"}]}'

step "Inventory: a pallet of 24 EA on SSCC $PALLET at A-01-101; serials 1001 and 1002 at A-01-102"
receive() { # receive <key> <json>: retried while the master-data projection catches up
  local code
  for _ in $(seq 1 30); do
    code="$(curl -s -o /tmp/astra-smoke-rfid.json -w '%{http_code}' -X POST "$GW/api/v1/sites/DC1/inventory/receipts" \
            "${OP[@]}" -H "Idempotency-Key: $1" -d "$2")"
    [[ "$code" == "201" ]] && return 0
    sleep 1
  done
  fail "receipt $1 not accepted after 30 s: $(cat /tmp/astra-smoke-rfid.json)"
}
receive rfid-1 "{\"ownerId\":\"ACME\",\"itemNo\":\"SKU-RF\",\"qty\":24,\"uom\":\"EA\",\"locationId\":\"A-01-101\",\"lpnId\":\"$PALLET\"}"
receive rfid-2 '{"ownerId":"ACME","itemNo":"SKU-RFS","qty":2,"uom":"EA","locationId":"A-01-102","serials":["1001","1002"]}'

step "Resolve: case tag, unit tag, pallet tag (hex and URI = one tag), serial unit, unknown read"
expect 200 -X POST "$RFID/resolve" "${OP[@]}" \
  -d "{\"reads\":[\"$CASE_TAG\",\"$UNIT_TAG\",\"0x3174257bf4499602d2000000\",\"urn:epc:id:sscc:0614141.1234567890\",\"$SER_TAG\",\"not-a-tag\"]}"
echo "$BODY"
check 'len(r) == 5' "five tags (the pallet read twice is one)"
check 'r[0]["kind"] == "ITEM" and r[0]["itemNo"] == "SKU-RF" and r[0]["uom"] == "CS" and r[0]["baseQty"] == 12 and r[0]["baseUom"] == "EA"' "case tag = 12 EA of SKU-RF"
check 'r[1]["itemNo"] == "SKU-RF" and r[1]["uom"] == "EA" and r[1]["baseQty"] == 1' "unit tag = 1 EA of SKU-RF"
check 'r[2]["kind"] == "LPN" and r[2]["lpnId"] == "'"$PALLET"'" and r[2]["locationId"] == "A-01-101"' "pallet tag = the LPN at A-01-101"
check 'r[3]["serialNo"] == "1001" and r[3]["locationId"] == "A-01-102" and r[3]["problem"] is None' "serial unit found at A-01-102"
check 'r[4]["kind"] == "UNKNOWN" and r[4]["problem"] == "NOT_AN_EPC"' "garbage read reported, not dropped"

step "Reconcile A-01-101: the pallet tag counts the pallet"
expect 200 -X POST "$RFID/locations/A-01-101/reconcile" "${OP[@]}" -d '{"reads":["3174257BF4499602D2000000"]}'
check 'r["lpns"] == [{"lpnId": "'"$PALLET"'", "result": "FOUND", "systemLocation": "A-01-101"}]' "pallet FOUND"
check 'r["countLines"][0]["qty"] == 24 and r["countLines"][0]["lpnId"] == "'"$PALLET"'"' "count line 24 EA on the pallet"

step "Reconcile A-01-102: serial 1002 not read"
expect 200 -X POST "$RFID/locations/A-01-102/reconcile" "${OP[@]}" -d "{\"reads\":[\"$SER_TAG\"]}"
check '[s["serialNo"] for s in r["serialsNotRead"]] == ["1002"]' "serial 1002 reported as not read"

step "Commission: the WMS encodes an SGTIN-96 for SKU-RF serial 4711, then looks it up"
expect 201 -X POST "$RFID/tags" "${OP[@]}" -H "Idempotency-Key: rfid-tag-1" \
  -d '{"ownerId":"ACME","itemNo":"SKU-RF","serialNo":"4711","companyPrefixLength":7}'
check 'r["scheme"] == "SGTIN" and r["uri"] == "urn:epc:id:sgtin:0614141.012345.4711" and r["status"] == "ACTIVE"' "tag encoded and active"
EPC="$(python -c 'import json,sys; print(json.loads(sys.argv[1])["epc"])' "$BODY")"
expect 200 "$RFID/tags/$EPC" "${OP[@]}"
check 'r["itemNo"] == "SKU-RF" and r["serialNo"] == "4711"' "GET tags/$EPC"

printf '\nRFID smoke test passed (tenant %s)\n' "$TENANT"
