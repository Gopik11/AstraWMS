#!/usr/bin/env bash
# Seeds a large, connected demo data set into one tenant for hands-on testing of every feature, through the public
# APIs and the SAP IDoc port (nothing is written to databases directly):
#   sites      main warehouse DC1 (plant 1000, MAIN) + stores ST01..ST25 (plants 2001..2025, STORE supplied by DC1;
#              STORE_COUNT=5 for fewer), zones and locations; ST01..ST05 get documents, every store some stock
#   owners     ACME (own stock) and BETA (3PL client: ship complete, retail labels, lot affinity, billing rates)
#   items      12 ACME materials from SAP (MATMAS05: GTINs, cartons, batch/serial, standard cost), 8 BETA items
#              (frozen, hazardous, lot/shelf-life controlled)
#   stock      reserve pallets, pick faces with min/max rules, lots with expiries, store stock, a QC hold
#   documents  ASNs, customer orders (one backordered), RMAs, SAP stock transport orders, WMS transfers,
#              material issues to cost centres / WBS / orders, yard appointments, a planned physical inventory,
#              a pooled wave at ST05
#   policies   carrier cutoffs, owner rules, allocation policies, slotting, labor standards, billing rates
#
# Local stack (default): signs in as the local dev user (deploy/keycloak/.dev-user, scripts/dev-user.sh) and creates a
# second local user, seed-approver, for approvals that must be done by another person.
#   scripts/seed-demo.sh
# Another environment: your own user (needs SOLUTION_ADMIN, ERP_INTEGRATION, SUPERVISOR, INV_MANAGER, RECEIVER);
# the password is asked for and never stored. Approvals are skipped unless APPROVER_USER is given.
#   GATEWAY_URL=https://astrawms.cloud SEED_USER=me@example.com scripts/seed-demo.sh
# A tenant seeded before the site network (ADR-0024): add NETWORK_ONLY=1 (safe to re-run; no documents again).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GW="${GATEWAY_URL:-http://localhost:8080}"
KC_URL="${KC_URL:-$([[ "$GW" == http://localhost* ]] && echo http://localhost:8180 || echo "$GW")}"
REALM="$KC_URL/realms/astrawms"
STORE_COUNT="${STORE_COUNT:-25}"
STORES=(); for i in $(seq 1 "$STORE_COUNT"); do STORES+=("$(printf 'ST%02d' "$i")"); done
(( STORE_COUNT >= 5 )) || { echo "STORE_COUNT must be at least 5 (ST01..ST05 carry the documents)" >&2; exit 1; }
store_no() { echo $((10#${1#ST})); }          # ST08 -> 8 (no octal surprise)
plant_of() { printf '2%03d' "$(store_no "$1")"; }   # ST07 -> 2007

step() { printf '\n== %s\n' "$*"; }
fail() { echo "FAILED: $*" >&2; exit 1; }
json() { python -c "import json,sys; d=json.load(sys.stdin); print(eval('d'+sys.argv[1]))" "$1"; }
signin() { # signin <user> <password> -> prints the access token; on failure says what Keycloak answered
  local out
  out="$(curl -s -X POST "$REALM/protocol/openid-connect/token" -d grant_type=password -d client_id=astra-dev-cli \
    -d scope=openid --data-urlencode "username=$1" --data-urlencode "password=$2")"
  python -c 'import json,sys; print(json.loads(sys.argv[1])["access_token"])' "$out" 2>/dev/null && return 0
  echo "FAILED: sign-in of $1 at $REALM: $(python -c 'import json,sys
try:
    d = json.loads(sys.argv[1]); print(d.get("error_description") or d.get("error"))
except Exception:
    print(sys.argv[1][:200])' "$out")" >&2
  echo "        Use the Keycloak username (not the e-mail address) and the account password; a passkey-only account cannot be used here." >&2
  exit 1
}
# call <expected codes regex> <method> <path> [json] [extra curl args...]; body in $BODY
call() {
  local want="$1" method="$2" path="$3" data="${4:-}"; shift 4 2>/dev/null || shift $#
  local args=(-s -w '\n%{http_code}' -X "$method" "$GW$path" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json")
  [[ -n "$data" ]] && args+=(-d "$data")
  local out; out="$(curl "${args[@]}" "$@")"; CODE="${out##*$'\n'}"; BODY="${out%$'\n'*}"
  [[ "$CODE" =~ ^($want)$ ]] || fail "$method $path -> HTTP $CODE: $BODY"
}
put() { call '200|201|204' PUT "$@"; }
post() { call '200|201|202|204' POST "$@"; }
# Already there from an earlier run is fine for documents created by POST.
post_once() { call '200|201|202|204|409|422' POST "$@"; }
receipt() { # receipt <site> <key> <json>; retried while the services learn the new master data
  for _ in $(seq 1 40); do
    call '201|200|422|404' POST "/api/v1/sites/$1/inventory/receipts" "$3" -H "Idempotency-Key: seed-$2"
    [[ "$CODE" =~ ^20 ]] && return 0
    sleep 1
  done
  fail "receipt $2 at $1: $BODY"
}
iso() { python -c "import datetime as d,sys; print((d.datetime.now(d.timezone.utc)+d.timedelta(hours=float(sys.argv[1]))).replace(microsecond=0).isoformat().replace('+00:00','Z'))" "$1"; }
sapdate() { python -c "import datetime as d,sys; print((d.date.today()+d.timedelta(days=int(sys.argv[1]))).strftime('%Y%m%d'))" "$1"; }

# ----------------------------------------------------------------------------------------------- sign-in
step "Signing in"
if [[ -n "${SEED_USER:-}" ]]; then
  read -r -s -p "Password for $SEED_USER: " SEED_PASSWORD; echo
  TOKEN="$(signin "$SEED_USER" "$SEED_PASSWORD")"
  if [[ -n "${APPROVER_USER:-}" ]]; then
    read -r -s -p "Password for $APPROVER_USER: " APPROVER_PASSWORD; echo
    APPROVER_TOKEN="$(signin "$APPROVER_USER" "$APPROVER_PASSWORD")"
  fi
  unset SEED_PASSWORD APPROVER_PASSWORD
else
  [[ -f "$ROOT/deploy/keycloak/.dev-user" ]] || fail "no local dev user: run scripts/dev-user.sh first"
  # shellcheck disable=SC1091
  source "$ROOT/deploy/keycloak/.dev-user"
  source "$ROOT/scripts/lib/auth.sh"
  TOKEN="$(signin "$DEV_USER" "$DEV_PASSWORD")"
  admin="$(_kc_admin_token)"
  old="$(curl -sf "$KC/admin/realms/astrawms/users?username=seed-approver&exact=true" -H "Authorization: Bearer $admin" \
    | python -c 'import json,sys; u=json.load(sys.stdin); print(u[0]["id"] if u else "")')"
  [[ -n "$old" ]] && curl -sf -X DELETE "$KC/admin/realms/astrawms/users/$old" -H "Authorization: Bearer $admin"
  APPROVAL_LIMIT=100000 provision "$DEV_TENANT" seed-approver SUPERVISOR INV_MANAGER
  APPROVER_TOKEN="$(signin seed-approver "$SMOKE_PASSWORD")"
  echo "Seeding tenant $DEV_TENANT as $DEV_USER (approvals by seed-approver)"
fi
for svc in master-data-service inventory-service inbound-service task-service outbound-service sap-adapter; do
  curl -sf "$GW/health/$svc" >/dev/null || fail "$svc is not healthy"
done

# ----------------------------------------------------------------------------------------------- sites and locations
step "Sites: DC1 main warehouse and ${#STORES[@]} stores (${STORES[0]}..${STORES[-1]}), zones, locations, SAP plants"
put "/api/v1/sites/DC1" '{"name":"Main warehouse","timeZone":"America/Chicago","erpSite":"1000","siteType":"MAIN"}'
put "/api/v1/sap/site-map/1000" '{"siteId":"DC1","timeZone":"America/Chicago","defaultOwner":"ACME"}'
zones_dc='DOCK:DOCK RECV:RECEIVING STOR:RESERVE PICK:PICK SHIP:SHIPPING QC:QC RET:RETURNS'
for z in $zones_dc; do put "/api/v1/sites/DC1/zones/${z%%:*}" "{\"zoneType\":\"${z##*:}\",\"erpBucket\":\"0001\"}"; done
put "/api/v1/sites/DC1/zones/FRZ" '{"zoneType":"RESERVE","erpBucket":"0001","temperatureClass":"FROZEN"}'
put "/api/v1/sites/DC1/zones/HAZ" '{"zoneType":"RESERVE","erpBucket":"0001","hazmatAllowed":true}'
for d in 1 2 3 4; do put "/api/v1/sites/DC1/locations/DOOR-0$d" '{"zoneId":"DOCK","locationType":"DOOR"}'; done
put "/api/v1/sites/DC1/locations/RECV-01" '{"zoneId":"RECV","locationType":"FLOOR"}'
put "/api/v1/sites/DC1/locations/STAGE-OUT" '{"zoneId":"SHIP","locationType":"STAGING_OUT","pickSeq":1}'
put "/api/v1/sites/DC1/locations/QC-01" '{"zoneId":"QC","locationType":"FLOOR"}'
put "/api/v1/sites/DC1/locations/RET-01" '{"zoneId":"RET","locationType":"FLOOR"}'
# Reserve racks A..C, 6 bays, 3 levels (54); pick faces P-01..P-24 along the pick path; frozen and hazmat bays.
post "/api/v1/sites/DC1/zones/STOR/locations/generate" '{"aisles":["A","B","C"],"bayFrom":1,"bayTo":6,"levels":["1","2","3"],
  "positionFrom":1,"positionTo":1,"pattern":"{aisle}-{bay}-{level}","locationType":"RACK"}'
for i in $(seq 1 24); do
  put "/api/v1/sites/DC1/locations/$(printf 'P-%02d' "$i")" "{\"zoneId\":\"PICK\",\"locationType\":\"SHELF\",\"pickSeq\":$((10 + i))}"
done
for i in 1 2 3 4; do put "/api/v1/sites/DC1/locations/F-0$i" "{\"zoneId\":\"FRZ\",\"locationType\":\"RACK\",\"pickSeq\":$((60 + i))}"; done
for i in 1 2; do put "/api/v1/sites/DC1/locations/H-0$i" "{\"zoneId\":\"HAZ\",\"locationType\":\"RACK\",\"pickSeq\":$((70 + i))}"; done
for s in "${STORES[@]}"; do
  plant="$(plant_of "$s")"
  put "/api/v1/sites/$s" "{\"name\":\"Store $s\",\"timeZone\":\"America/Chicago\",\"erpSite\":\"$plant\",\"siteType\":\"STORE\",\"supplyingSite\":\"DC1\"}"
  put "/api/v1/sap/site-map/$plant" "{\"siteId\":\"$s\",\"timeZone\":\"America/Chicago\",\"defaultOwner\":\"ACME\"}"
  for z in DOCK:DOCK STOR:RESERVE PICK:PICK SHIP:SHIPPING RET:RETURNS; do
    put "/api/v1/sites/$s/zones/${z%%:*}" "{\"zoneType\":\"${z##*:}\",\"erpBucket\":\"0001\"}"
  done
  put "/api/v1/sites/$s/locations/DOOR-01" '{"zoneId":"DOCK","locationType":"DOOR"}'
  put "/api/v1/sites/$s/locations/STAGE-OUT" '{"zoneId":"SHIP","locationType":"STAGING_OUT","pickSeq":1}'
  put "/api/v1/sites/$s/locations/RET-01" '{"zoneId":"RET","locationType":"FLOOR"}'
  for i in $(seq 1 8); do put "/api/v1/sites/$s/locations/$(printf 'S-%02d' "$i")" "{\"zoneId\":\"PICK\",\"locationType\":\"SHELF\",\"pickSeq\":$((10 + i))}"; done
  for i in 1 2 3 4; do put "/api/v1/sites/$s/locations/R-0$i" "{\"zoneId\":\"STOR\",\"locationType\":\"RACK\",\"pickSeq\":$((30 + i))}"; done
done
echo "DC1: $(curl -sf "$GW/api/v1/sites/DC1/locations?limit=200" -H "Authorization: Bearer $TOKEN" | json "['items'].__len__()") locations; ${#STORES[@]} stores x 16"

# ----------------------------------------------------------------------------------------------- items
step "Items: 12 ACME materials from SAP (MATMAS05), 8 BETA items"
plant_list() { # plant_list <extra json fields> -> the E1MARCM segments of DC1 and every store
  local out="{\"WERKS\":\"1000\"$1}"
  for s in "${STORES[@]}"; do out+=",{\"WERKS\":\"$(plant_of "$s")\"$1}"; done
  echo "[$out]"
}
plants="$(plant_list '')"
batch_plants="$(plant_list ',"XCHPF":"X"')"
serial_plants="$(plant_list ',"SERNP":"Z001"')"
gtin() { python -c "
b=sys_b='$1'
s=sum(int(c)*(3 if i%2==0 else 1) for i,c in enumerate(reversed(b)))
print(b+str((10-s%10)%10))"; }
# number | description | EAN prefix (12 digits w/o check) | carton qty | price | plants | shelf life
materials=(
  "100100|Safety gloves L|400638133300|12|2.40|$plants|"
  "100110|Safety gloves M|400638133301|12|2.40|$plants|"
  "100200|Hard hat white|400638133302|10|11.50|$plants|"
  "100300|Hi-vis vest XL|400638133303|20|6.90|$plants|"
  "100400|Work boots 43|400638133304|6|54.00|$plants|"
  "100500|Cable ties 300mm (100)|400638133305|50|3.10|$plants|"
  "100600|Duct tape 50m|400638133306|24|4.80|$plants|"
  "100700|Hand sanitizer 500ml|400638133307|12|3.95|$batch_plants|365"
  "100800|Sealant cartridge|400638133308|24|7.25|$batch_plants|540"
  "100900|Cordless drill 18V|400638133309|4|129.00|$serial_plants|"
  "101000|Torch LED|400638133310|20|14.20|$plants|"
  "101100|Batteries AA (4)|400638133311|48|3.50|$batch_plants|1825"
)
k=0
for m in "${materials[@]}"; do
  IFS='|' read -r no text ean12 carton price mplants life <<<"$m"
  k=$((k + 1))
  each="$(gtin "$ean12")"; case_gtin="$(gtin "1${ean12:1}")"
  post "/api/v1/sap/idocs/matmas05" "{\"DOCNUM\":\"$(printf '00000000000071%02d' "$k")\",\"MESTYP\":\"MATMAS\",
    \"E1MARAM\":{\"MATNR\":\"$no\",\"MTART\":\"HAWA\",\"MEINS\":\"ST\",${life:+\"MHDHB\":$life,\"MHDRZ\":30,}
      \"E1MAKTM\":[{\"SPRAS_ISO\":\"EN\",\"MAKTX\":\"$text\"}],
      \"E1MARMM\":[{\"MEINH\":\"ST\",\"UMREZ\":1,\"UMREN\":1,\"EAN11\":\"$each\"},
                   {\"MEINH\":\"KAR\",\"UMREZ\":$carton,\"UMREN\":1,\"EAN11\":\"$case_gtin\",\"LAENG\":400,\"BREIT\":300,\"HOEHE\":250,\"MEABM\":\"MM\",\"BRGEW\":$((carton * 300)),\"GEWEI\":\"G\"}],
      \"E1MARCM\":$mplants,\"E1MBEWM\":[{\"VPRSV\":\"S\",\"STPRS\":$price,\"PEINH\":1}]}}"
  [[ "$(json "['status']" <<<"$BODY")" == 53 ]] || fail "MATMAS $no: $BODY"
done
sites_json() { # sites_json <lotControlled> -> the item's site settings for DC1 and every store
  local out="{\"siteId\":\"DC1\",\"lotControlled\":$1,\"serialControl\":\"NONE\"}"
  for s in "${STORES[@]}"; do out+=",{\"siteId\":\"$s\",\"lotControlled\":$1,\"serialControl\":\"NONE\"}"; done
  echo "[$out]"
}
beta=(
  "B-200|Frozen peas 1kg|FROZEN|false|true|180|1.80"
  "B-210|Ice cream 2l|FROZEN|false|true|270|4.20"
  "B-300|Lithium batteries pack||true|false||12.00"
  "B-310|Aerosol paint red||true|true|720|5.60"
  "B-400|Olive oil 1l||false|true|540|7.80"
  "B-410|Pasta 500g||false|true|365|1.10"
  "B-420|Coffee beans 1kg||false|true|365|13.50"
  "B-430|Tea bags (80)||false|false||3.20"
)
for b in "${beta[@]}"; do
  IFS='|' read -r no text temp haz lot life cost <<<"$b"
  put "/api/v1/items/BETA/$no" "{\"description\":\"$text\",\"baseUom\":\"EA\",\"status\":\"ACTIVE\",${temp:+\"temperatureClass\":\"$temp\",}
    \"hazardous\":$haz,${life:+\"shelfLifeDays\":$life,}\"standardCost\":$cost,\"sites\":$(sites_json "$lot"),
    \"uoms\":[{\"uom\":\"CS\",\"numerator\":12,\"denominator\":1}]}"
done
echo "20 items: 12 from SAP with GTINs, 8 BETA"

# NETWORK_ONLY=1: for a tenant seeded before the site network (ADR-0024). Sites, locations and items above are
# upserts, so this marks DC1 as MAIN and every store as STORE, adds the new stores and extends the items to them; then
# it saves DC1's allocation policy and puts a starter shelf in stores ST06 and up. Documents are not created again.
if [[ "${NETWORK_ONLY:-}" == 1 ]]; then
  step "Network only: DC1 allocation policy, starter stock in the new stores"
  put "/api/v1/sites/DC1/inventory/allocation-policy" '{"lotRotation":"FEFO","otherRotation":"FIFO","pickFaceFirst":true,"fullLpn":"COVERED_ONLY"}'
  added=0
  for s in "${STORES[@]}"; do
    (( $(store_no "$s") > 5 )) || continue
    j=0
    for i in 100100 100300 100600; do
      j=$((j + 1)); added=$((added + 1))
      receipt "$s" "net-$s-$i" "{\"ownerId\":\"ACME\",\"itemNo\":\"$i\",\"qty\":$((10 + RANDOM % 30)),\"uom\":\"EA\",\"locationId\":\"$(printf 'S-%02d' "$j")\"}"
    done
  done
  printf '
SEED DONE (network only): DC1 + %s stores typed and supplied by DC1, %s stock receipts in new stores.
' "${#STORES[@]}" "$added"
  exit 0
fi

# ----------------------------------------------------------------------------------------------- policies
step "Policies: release, cutoffs, owner rules, allocation, slotting, labor standards, billing rates, cost objects"
put "/api/v1/sites/DC1/outbound/config" '{"releaseMode":"WAVELESS","timezone":"America/Chicago","packRequired":false}'
put "/api/v1/sites/ST05/outbound/config" '{"releaseMode":"WAVE","timezone":"America/Chicago"}'
for c in UPSN:16:30 FDEG:17:45 DHLX:15:00; do put "/api/v1/sites/DC1/outbound/carrier-cutoffs/${c%%:*}" "{\"cutoffTime\":\"${c#*:}\"}"; done
put "/api/v1/sites/DC1/outbound/owner-policies/BETA" '{"shipComplete":true,"packList":true,"labelTemplate":"RETAIL"}'
# The site's allocation policy saved explicitly (ADR-0024: not left to the built-in default), then BETA's own.
put "/api/v1/sites/DC1/inventory/allocation-policy" '{"lotRotation":"FEFO","otherRotation":"FIFO","pickFaceFirst":true,"fullLpn":"COVERED_ONLY"}'
put "/api/v1/sites/DC1/inventory/allocation-policy" '{"ownerId":"BETA","lotRotation":"FEFO","lotAffinity":true}'
for i in 100100 100110 100200 100500 100600; do put "/api/v1/sites/DC1/inventory/slotting/ACME/$i" '{"reserveZone":"STOR","unitsPerPallet":240,"velocityClass":"A"}'; done
put "/api/v1/sites/DC1/inventory/slotting/ACME/100900" '{"reserveZone":"STOR","unitsPerPallet":16,"velocityClass":"C"}'
for t in PICK:30:4 PUTAWAY:150:0 RECEIVE:240:2 REPLEN:200:0 COUNT:90:1; do
  IFS=: read -r type base unit <<<"$t"
  put "/api/v1/sites/DC1/tasks/standards/$type" "{\"baseSeconds\":$base,\"perUnitSeconds\":$unit}"
done
for r in RECEIPT:LPN:4.50 PICK:UNIT:0.22 RETURN:EVENT:3.00 STORAGE:LPN:0.85; do
  IFS=: read -r type basis rate <<<"$r"
  put "/api/v1/sites/DC1/inventory/billing/rates" "{\"ownerId\":\"BETA\",\"eventType\":\"$type\",\"basis\":\"$basis\",\"rate\":$rate}"
done
put "/api/v1/sites/DC1/inventory/billing/rates" '{"ownerId":"BETA","eventType":"VAS","service":"LABEL","basis":"UNIT","rate":0.15}'
for co in "COST_CENTER/CC-4100|Maintenance|MAINT" "COST_CENTER/CC-4200|Facilities|FACIL" "WBS/P-2026-001|Store refit Dallas|PROJ" "ORDER/700123|Forklift repair|MAINT"; do
  IFS='|' read -r path desc dept <<<"$co"
  put "/api/v1/sites/DC1/inventory/cost-objects/$path" "{\"description\":\"$desc\",\"department\":\"$dept\"}"
done

# ----------------------------------------------------------------------------------------------- stock
step "Stock: reserve pallets, pick faces with min/max rules, lots, stores, a QC hold"
lpn=0
pallet() { # pallet <site> <owner> <item> <qty> <location> [lot] [expiry]
  lpn=$((lpn + 1))
  local extra=""; [[ -n "${6:-}" ]] && extra=",\"lotNo\":\"$6\",\"expiryDate\":\"$7\""
  receipt "$1" "$1-$lpn" "{\"ownerId\":\"$2\",\"itemNo\":\"$3\",\"qty\":$4,\"uom\":\"EA\",\"locationId\":\"$5\",\"lpnId\":\"$(printf 'SEED%s%05d' "$1" "$lpn")\"$extra}"
}
loose() { # loose <site> <owner> <item> <qty> <location> [lot] [expiry]
  lpn=$((lpn + 1))
  local extra=""; [[ -n "${6:-}" ]] && extra=",\"lotNo\":\"$6\",\"expiryDate\":\"$7\""
  receipt "$1" "$1-$lpn" "{\"ownerId\":\"$2\",\"itemNo\":\"$3\",\"qty\":$4,\"uom\":\"EA\",\"locationId\":\"$5\"$extra}"
}
face=0
for i in 100100 100110 100200 100300 100400 100500 100600 101000; do
  face=$((face + 1)); f="$(printf 'P-%02d' "$face")"
  loose DC1 ACME "$i" 40 "$f"
  put "/api/v1/sites/DC1/inventory/replenishment-rules/$f/ACME/$i" '{"minQty":15,"maxQty":60}'
done
row=0
for i in 100100 100110 100200 100300 100400 100500 100600 101000; do
  row=$((row + 1)); pallet DC1 ACME "$i" 240 "A-$(printf '%02d' $(( (row - 1) % 6 + 1 )))-1"
  pallet DC1 ACME "$i" 120 "B-$(printf '%02d' $(( (row - 1) % 6 + 1 )))-2"
done
pallet DC1 ACME 100700 96 C-01-1 SAN-2601 "$(python -c 'import datetime as d; print(d.date.today()+d.timedelta(days=60))')"
pallet DC1 ACME 100700 96 C-02-1 SAN-2603 "$(python -c 'import datetime as d; print(d.date.today()+d.timedelta(days=200))')"
pallet DC1 ACME 100800 72 C-03-1 SEA-0412 "$(python -c 'import datetime as d; print(d.date.today()+d.timedelta(days=330))')"
pallet DC1 ACME 101100 192 C-04-1 BAT-2509 "$(python -c 'import datetime as d; print(d.date.today()+d.timedelta(days=1400))')"
receipt DC1 drills '{"ownerId":"ACME","itemNo":"100900","qty":4,"uom":"EA","locationId":"C-05-1","lpnId":"SEEDDRILL1","serials":["DRL-0001","DRL-0002","DRL-0003","DRL-0004"]}'
pallet DC1 BETA B-200 120 F-01 PEA-2611 "$(python -c 'import datetime as d; print(d.date.today()+d.timedelta(days=150))')"
pallet DC1 BETA B-210 60 F-02 ICE-2610 "$(python -c 'import datetime as d; print(d.date.today()+d.timedelta(days=40))')"
pallet DC1 BETA B-300 50 H-01
pallet DC1 BETA B-310 80 H-02 AER-2607 "$(python -c 'import datetime as d; print(d.date.today()+d.timedelta(days=500))')"
pallet DC1 BETA B-400 96 A-05-3 OIL-2601 "$(python -c 'import datetime as d; print(d.date.today()+d.timedelta(days=90))')"
pallet DC1 BETA B-400 96 A-06-3 OIL-2604 "$(python -c 'import datetime as d; print(d.date.today()+d.timedelta(days=300))')"
pallet DC1 BETA B-410 240 B-05-3 PAS-2512 "$(python -c 'import datetime as d; print(d.date.today()+d.timedelta(days=250))')"
pallet DC1 BETA B-420 60 B-06-3 COF-2602 "$(python -c 'import datetime as d; print(d.date.today()+d.timedelta(days=280))')"
pallet DC1 BETA B-430 144 C-06-3
receipt DC1 qc '{"ownerId":"ACME","itemNo":"100400","qty":12,"uom":"EA","locationId":"QC-01","status":"QI","lpnId":"SEEDQC1"}'
for s in "${STORES[@]}"; do
  j=0
  # ST01..ST05 get the full shelf; the other stores a smaller one (keeps the seed under a few minutes).
  items=(100100 100200 100300 100500 100600 101000); (( $(store_no "$s") <= 5 )) || items=(100100 100300 100600)
  for i in "${items[@]}"; do
    j=$((j + 1)); loose "$s" ACME "$i" $((10 + RANDOM % 30)) "$(printf 'S-%02d' "$j")"
  done
  if (( $(store_no "$s") <= 5 )); then pallet "$s" ACME 100100 60 R-01; fi
done
echo "$lpn stock receipts"

# ----------------------------------------------------------------------------------------------- SAP documents
step "SAP documents: ASNs, customer orders, RMAs, stock transport orders"
idoc() { post "/api/v1/sap/idocs/delvry07" "$1"; }
asn() { # asn <docnum> <vbeln> <plant> <vendor> <days> <lines-json>
  idoc "{\"DOCNUM\":\"$1\",\"MESTYP\":\"SHP_IBDLV_SAVE_REPLICA\",\"E1EDL20\":{\"VBELN\":\"$2\",\"LFART\":\"EL\",\"WERKS\":\"$3\"},
    \"E1ADRM1\":[{\"PARTNER_Q\":\"LF\",\"PARTNER_ID\":\"$4\"}],\"E1EDT13\":[{\"QUALF\":\"007\",\"NTANF\":\"$(sapdate "$5")\",\"NTANZ\":\"080000\"}],
    \"E1EDL24\":$6}"
}
asn 0000000000081001 0180100001 1000 V-100 0 '[{"POSNR":"000010","MATNR":"100100","LFIMG":"240","VRKME":"ST"},{"POSNR":"000020","MATNR":"100200","LFIMG":"100","VRKME":"ST"}]'
asn 0000000000081002 0180100002 1000 V-100 0 '[{"POSNR":"000010","MATNR":"100700","LFIMG":"96","VRKME":"ST","CHARG":"SAN-2611"}]'
asn 0000000000081003 0180100003 1000 V-220 1 '[{"POSNR":"000010","MATNR":"100400","LFIMG":"36","VRKME":"ST"},{"POSNR":"000020","MATNR":"101000","LFIMG":"80","VRKME":"ST"},{"POSNR":"000030","MATNR":"100600","LFIMG":"48","VRKME":"ST"}]'
asn 0000000000081004 0180100004 1000 V-330 2 '[{"POSNR":"000010","MATNR":"100900","LFIMG":"8","VRKME":"ST"}]'
asn 0000000000081005 0180100005 2001 V-100 1 '[{"POSNR":"000010","MATNR":"100300","LFIMG":"40","VRKME":"ST"}]'
asn 0000000000081006 0180100006 2002 V-100 1 '[{"POSNR":"000010","MATNR":"100500","LFIMG":"100","VRKME":"ST"}]'
order() { # order <docnum> <vbeln> <plant> <lfart> <customer> <name> <carrier> <days> <lines-json>
  idoc "{\"DOCNUM\":\"$1\",\"MESTYP\":\"SHP_OBDLV_SAVE_REPLICA\",\"E1EDL20\":{\"VBELN\":\"$2\",\"LFART\":\"$4\",\"WERKS\":\"$3\"},
    \"E1ADRM1\":[{\"PARTNER_Q\":\"WE\",\"PARTNER_ID\":\"$5\",\"NAME1\":\"$6\"},{\"PARTNER_Q\":\"SP\",\"PARTNER_ID\":\"$7\"}],
    \"E1EDT13\":[{\"QUALF\":\"006\",\"NTANF\":\"$(sapdate "$8")\",\"NTANZ\":\"140000\"}],\"E1EDL24\":$9}"
}
order 0000000000082001 0080200001 1000 LF C-100 "Builders Supply North" UPSN 0 '[{"POSNR":"000010","MATNR":"100100","LFIMG":"24","VRKME":"ST"},{"POSNR":"000020","MATNR":"100300","LFIMG":"10","VRKME":"ST"}]'
order 0000000000082002 0080200002 1000 LF C-110 "City Maintenance" FDEG 0 '[{"POSNR":"000010","MATNR":"100500","LFIMG":"50","VRKME":"ST"},{"POSNR":"000020","MATNR":"100600","LFIMG":"12","VRKME":"ST"},{"POSNR":"000030","MATNR":"101000","LFIMG":"6","VRKME":"ST"}]'
order 0000000000082003 0080200003 1000 LF C-120 "Harbor Works" UPSN 1 '[{"POSNR":"000010","MATNR":"100700","LFIMG":"24","VRKME":"ST"}]'
order 0000000000082004 0080200004 1000 LF C-130 "Metro Hospital" DHLX 0 '[{"POSNR":"000010","MATNR":"100800","LFIMG":"500","VRKME":"ST"}]'
order 0000000000082005 0080200005 1000 LF C-140 "Rail Depot East" FDEG 2 '[{"POSNR":"000010","MATNR":"100900","LFIMG":"2","VRKME":"ST"},{"POSNR":"000020","MATNR":"100400","LFIMG":"6","VRKME":"ST"}]'
order 0000000000082006 0080200006 1000 LF C-150 "Airport Services" UPSN 0 '[{"POSNR":"000010","MATNR":"100200","LFIMG":"20","VRKME":"ST"},{"POSNR":"000020","MATNR":"100110","LFIMG":"36","VRKME":"ST"},{"POSNR":"000030","MATNR":"101100","LFIMG":"48","VRKME":"ST"}]'
for i in 1 2 3; do
  order "00000000000830$(printf '%02d' "$i")" "00803000$(printf '%02d' "$i")" 2005 LF "C-5$i" "Store customer $i" UPSN 1 \
    '[{"POSNR":"000010","MATNR":"100100","LFIMG":"4","VRKME":"ST"},{"POSNR":"000020","MATNR":"100600","LFIMG":"2","VRKME":"ST"}]'
done
for r in "0000000000084001|0060400001|1000|C-100|Builders Supply North|100300|2" "0000000000084002|0060400002|2001|C-201|Walk-in customer|100100|1"; do
  IFS='|' read -r dn vb pl cu nm it q <<<"$r"
  idoc "{\"DOCNUM\":\"$dn\",\"MESTYP\":\"SHP_IBDLV_SAVE_REPLICA\",\"E1EDL20\":{\"VBELN\":\"$vb\",\"LFART\":\"LR\",\"WERKS\":\"$pl\"},
    \"E1ADRM1\":[{\"PARTNER_Q\":\"AG\",\"PARTNER_ID\":\"$cu\",\"NAME1\":\"$nm\"}],\"E1EDL24\":[{\"POSNR\":\"000010\",\"MATNR\":\"$it\",\"LFIMG\":\"$q\",\"VRKME\":\"ST\"}]}"
done
# SAP stock transport orders: replenishment delivery out of DC1, and the matching inbound delivery at the store.
n=0
for s in ST01 ST02; do
  n=$((n + 1))
  order "00000000000850$n" "00805000$n" 1000 NL "$s" "Store $s" UPSN 1 '[{"POSNR":"000010","MATNR":"100200","LFIMG":"20","VRKME":"ST"},{"POSNR":"000020","MATNR":"100500","LFIMG":"40","VRKME":"ST"}]'
  idoc "{\"DOCNUM\":\"00000000000851$n\",\"MESTYP\":\"SHP_IBDLV_SAVE_REPLICA\",\"E1EDL20\":{\"VBELN\":\"01805100$n\",\"LFART\":\"NL\",\"WERKS\":\"200$n\"},
    \"E1ADRM1\":[{\"PARTNER_Q\":\"LF\",\"PARTNER_ID\":\"DC1\"}],\"E1EDT13\":[{\"QUALF\":\"007\",\"NTANF\":\"$(sapdate 2)\",\"NTANZ\":\"090000\"}],
    \"E1EDL24\":[{\"POSNR\":\"000010\",\"MATNR\":\"100200\",\"LFIMG\":\"20\",\"VRKME\":\"ST\"},{\"POSNR\":\"000020\",\"MATNR\":\"100500\",\"LFIMG\":\"40\",\"VRKME\":\"ST\"}]}"
done
echo "6 ASNs, 9 customer orders (0080200004 is short: backorder), 2 RMAs, 2 SAP stock transports"

# ----------------------------------------------------------------------------------------------- WMS documents
step "WMS documents: transfers, material issues, yard appointments, physical inventory"
for s in ST03 ST04 ST05; do
  post "/api/v1/sites/DC1/outbound/transfers" "{\"toSiteId\":\"$s\",\"carrierScac\":\"UPSN\",\"note\":\"Weekly store replenishment\",
    \"lines\":[{\"ownerId\":\"ACME\",\"itemNo\":\"100100\",\"qty\":24,\"uom\":\"EA\"},{\"ownerId\":\"ACME\",\"itemNo\":\"100300\",\"qty\":10,\"uom\":\"EA\"},{\"ownerId\":\"ACME\",\"itemNo\":\"101000\",\"qty\":6,\"uom\":\"EA\"}]}"
done
post "/api/v1/sites/ST02/outbound/transfers" '{"toSiteId":"ST01","note":"Store to store: overstock","lines":[{"ownerId":"ACME","itemNo":"100600","qty":3,"uom":"EA"}]}'
issues=()
for mi in "COST_CENTER|CC-4100|Maintenance team A|100100:12,100600:4" "WBS|P-2026-001|Refit crew Dallas|100200:6,100300:6,101000:2" \
          "ORDER|700123|Forklift workshop|100500:20" "COST_CENTER|CC-4200|Facilities desk|101100:8"; do
  IFS='|' read -r type code who items <<<"$mi"
  lines="$(python -c "import json,sys; print(json.dumps([{'itemNo':p.split(':')[0],'qty':int(p.split(':')[1]),'uom':'EA'} for p in sys.argv[1].split(',')]))" "$items")"
  post "/api/v1/sites/DC1/inventory/material-issues" "{\"ownerId\":\"ACME\",\"objectType\":\"$type\",\"objectCode\":\"$code\",\"recipient\":\"$who\",\"lines\":$lines}"
  issues+=("$(json "['issue_no']" <<<"$BODY")")
done
if [[ -n "${APPROVER_TOKEN:-}" ]]; then
  for no in "${issues[@]:0:3}"; do
    curl -sf -X POST "$GW/api/v1/sites/DC1/inventory/material-issues/$no/approve" -H "Authorization: Bearer $APPROVER_TOKEN" \
      -H "Content-Type: application/json" -d '{}' >/dev/null || fail "approval of $no"
  done
  echo "Material issues ${issues[*]}: first three approved, last one waits for approval"
else
  echo "Material issues ${issues[*]} wait for approval (no APPROVER_USER)"
fi
appt=0
for a in "0.5|DOOR-01|UPSN|0180100001|INBOUND" "1.5|DOOR-02|FDEG|0180100003|INBOUND" "3|DOOR-03|V330||INBOUND" \
         "5|DOOR-04|UPSN|0080200001|OUTBOUND" "-1|DOOR-01|DHLX|0180100002|INBOUND"; do
  IFS='|' read -r h door scac doc dir <<<"$a"
  appt=$((appt + 1))
  post "/api/v1/sites/DC1/yard/appointments" "{\"direction\":\"$dir\",\"door\":\"$door\",\"carrierScac\":\"$scac\",
    \"trailerNo\":\"TR-$((4400 + appt))\",${doc:+\"docNo\":\"$doc\",}\"start\":\"$(iso "$h")\"}"
  [[ "$h" == -1 ]] && post "/api/v1/sites/DC1/yard/appointments/$(json "['appt_no']" <<<"$BODY")/check-in" '{}'
done
post_once "/api/v1/sites/ST01/inventory/physical-inventories" '{"zones":["PICK","STOR"],"freeze":true,"note":"Quarter-end count (plan only; start it to freeze)"}'
echo "4 WMS transfers, ${#issues[@]} material issues, $appt yard appointments (one trailer already in the yard), PI planned at ST01"

step "Billing: capture what the ledger holds so far"
post "/api/v1/sites/DC1/inventory/billing/capture" ''
echo "Captured: $(json "['operationEvents']" <<<"$BODY") operation events"

printf '\nSEED DONE: DC1 + %s stores, owners ACME and BETA, %s stock receipts, documents across every flow.\n' "${#STORES[@]}" "$lpn"
