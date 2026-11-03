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

# 3. Platform tables have no row-level security (they are read across tenants by the relay and housekeeping). Any
#    other read of them must filter by tenant: outbox and dlq_replay carry tenant_id.
while IFS= read -r f; do
  case "$f" in
    */com/astrawms/common/messaging/*|*/com/astrawms/common/ops/MessagingMetrics.java) continue ;;
  esac
  # SQL text blocks / strings that read the outbox or the replay log
  if grep -Eq 'from (outbox|dlq_replay)\b' "$f"; then
    while IFS= read -r line; do
      n="${line%%:*}"
      window="$(sed -n "${n},$((n + 3))p" "$f")"
      grep -q 'tenant_id' <<<"$window" || fail "$f:$n reads a platform table without a tenant_id filter"
    done < <(grep -nE 'from (outbox|dlq_replay)\b' "$f")
  fi
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

if (( failures > 0 )); then
  echo "$failures architecture rule violation(s)" >&2
  exit 1
fi
echo "Architecture rules: OK"
