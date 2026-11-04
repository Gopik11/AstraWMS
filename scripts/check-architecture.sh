#!/usr/bin/env bash
# Static architecture rules (ADR-0029), run in CI. Each rule is a convention the platform's guarantees rest on; the
# source review found them documented but not enforced.
#   scripts/check-architecture.sh
set -euo pipefail
cd "$(dirname "$0")/.."

failures=0
fail() { echo "ARCHITECTURE: $*" >&2; failures=$((failures + 1)); }

main_sources() { find services adapters platform -path '*/src/main/java/*' -name '*.java'; }

# 1. Every Kafka listener applies messages through InboxProcessing: tenant scope, one transaction with the inbox
#    record, once per consumer group (at-least-once delivery is only safe with it).
while IFS= read -r f; do
  if grep -Eq '@([a-z]+\.)*KafkaListener\b' "$f" && ! grep -q 'InboxProcessing' "$f"; then
    fail "$f has a @KafkaListener but does not use InboxProcessing"
  fi
done < <(main_sources)

# 2. Listeners and services never call InboxGuard directly (only InboxProcessing and the DLQ replay check do).
while IFS= read -r f; do
  case "$f" in
    */com/astrawms/common/messaging/*|*/com/astrawms/common/ops/*|*/com/astrawms/common/config/*) continue ;;
  esac
  if grep -q 'firstDelivery(' "$f"; then
    fail "$f calls InboxGuard.firstDelivery directly; use InboxProcessing.once"
  fi
done < <(main_sources)

# 3. Tenant tables without row-level security (ADR-0030 G): the list comes from the migrations
#    (scripts/tenant_tables.py --rls-free), so a new one is covered without editing this script. Nothing in the database
#    keeps one tenant from another's rows there, so every statement that reads or changes them must filter by tenant_id
#    within its first lines (an insert must list it), or say why it works across tenants in a "cross-tenant: <reason>"
#    comment at most six lines above (the relay, housekeeping, metrics, the retriers, the webhook dispatcher).
rls_free="$(python3 scripts/tenant_tables.py --rls-free | paste -sd'|' -)"
[[ -n "$rls_free" ]] || fail "scripts/tenant_tables.py --rls-free listed no table; the tenancy rule would check nothing"
tenant_sql="\b(from|join|update|into)[[:space:]]+(${rls_free})\b"
while IFS= read -r f; do
  grep -Eiq "$tenant_sql" "$f" || continue
  mapfile -t hits < <(grep -Ein "$tenant_sql" "$f")
  for i in "${!hits[@]}"; do
    line="${hits[$i]}"
    n="${line%%:*}"
    # a statement's lines: up to 3 more, but never into the next statement on such a table (its filter is not ours)
    end=$((n + 3))
    if (( i + 1 < ${#hits[@]} )); then
      next="${hits[$((i + 1))]%%:*}"
      (( next - 1 < end )) && end=$((next - 1))
    fi
    # an insert lists tenant_id among its columns; any other statement filters by it (selecting it is not enough)
    if grep -Eiq "\binto[[:space:]]+(${rls_free})\b" <<<"${line#*:}"; then
      sed -n "${n},${end}p" "$f" | grep -q 'tenant_id' && continue
    else
      sed -n "${n},${end}p" "$f" | grep -Eiq 'tenant_id[[:space:]]*(=|in[[:space:]]*\()' && continue
    fi
    sed -n "$(( n > 6 ? n - 6 : 1 )),$((n + 1))p" "$f" | grep -Eq 'cross-tenant: [^[:space:]]' && continue
    fail "$f:$n uses a tenant table without row-level security, without a tenant_id filter or a cross-tenant: reason"
  done
done < <(main_sources)

# 4. State-changing endpoints declare roles. Also enforced at start-up (SecuredEndpointsVerifier); this catches it
#    before a service is started: a @PostMapping/@PutMapping/@DeleteMapping/@PatchMapping in a controller without
#    @PreAuthorize on the method or the class.
while IFS= read -r f; do
  grep -Eq '@([a-z]+\.)*(Post|Put|Delete|Patch)Mapping' "$f" || continue
  grep -Eq '@([a-z]+\.)*RestController' "$f" || continue
  class_secured=false
  awk '/@([a-z]+\.)*PreAuthorize/{p=1} /^public class|^class /{if(p) {print "yes"}; exit}' "$f" | grep -q yes && class_secured=true
  $class_secured && continue
  unsecured=$(awk -v file="$f" '
    /@([a-z]+\.)*PreAuthorize/ { secured = NR }
    /@([a-z]+\.)*(Post|Put|Delete|Patch)Mapping/ { if (!secured || NR - secured > 3) printf "%s:%d\n", file, NR }
    /^[[:space:]]*(public|private|protected)[^=]*\(/ { secured = 0 }
  ' "$f")
  while IFS= read -r where; do
    [[ -n "$where" ]] && fail "$where changes state without @PreAuthorize"
  done <<<"$unsecured"
done < <(main_sources)

# 5. Seeing every owner's rows (ADR-0030 C) is a reviewed exception: each OwnerVisibility.allOwners call says why in
#    an "all-owners: <reason>" comment at most three lines above it. Such a check returns a decision, never another
#    owner's rows.
while IFS= read -r f; do
  case "$f" in */com/astrawms/common/tenancy/OwnerVisibility.java) continue ;; esac
  while IFS= read -r line; do
    n="${line%%:*}"
    sed -n "$(( n > 3 ? n - 3 : 1 )),${n}p" "$f" | grep -Eq 'all-owners: [^[:space:]]' && continue
    fail "$f:$n sees every owner's rows without an all-owners: reason"
  done < <(grep -n 'allOwners(' "$f")
done < <(main_sources)

if (( failures > 0 )); then
  echo "$failures architecture rule violation(s)" >&2
  exit 1
fi
echo "Architecture rules: OK"
