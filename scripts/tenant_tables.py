#!/usr/bin/env python3
"""Tenant data map, derived from the Flyway migrations (ADR-0030, phase 0).

For every service database, lists each table with:
  - whether it carries tenant_id and owner_id;
  - whether row-level security is enabled, forced and has a policy (ADR-0003), and whether a restrictive
    owner_isolation policy separates owners within the tenant (ADR-0030 C);
  - a sequence-generated id (an export cannot keep those ids, so nothing may refer to them);
  - the tables it references, and the order in which a tenant's rows load (parents first).

It fails (--check) when:
  - docs/security/tenant-data-map.md is stale;
  - a table without tenant_id is not on the reviewed list of global tables (GLOBAL below);
  - a table with tenant_id lacks forced RLS and is not on the reviewed list of RLS-free tables (RLS_FREE below);
  - a reviewed entry no longer matches a table (stale list);
  - a table refers to another table's sequence-generated id;
  - a table has more than one permissive policy (permissive policies are combined with OR, so a second one widens
    access beyond the tenant), or an owner_isolation policy is permissive or on a table without owner_id.

  python3 scripts/tenant_tables.py            # regenerate the map
  python3 scripts/tenant_tables.py --check    # CI
  python3 scripts/tenant_tables.py --rls-free # tenant tables without RLS, one per line (scripts/check-architecture.sh)
  python3 scripts/tenant_tables.py --plan     # per database: tenant tables and their sequence columns (JSON), which
                                              # deploy/vps/tenant-data.py must find in a migrated database (TenantDataIT)
"""
import collections
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "security" / "tenant-data-map.md"
COMMON = ROOT / "platform/astra-common/src/main/resources/db/migration/common"
MODULES = ["services/master-data-service", "services/inventory-service", "services/inbound-service",
           "services/task-service", "services/outbound-service", "adapters/sap-adapter"]

# Tables without tenant_id. Each holds no tenant's business data, or holds it only inside an opaque key.
GLOBAL = {
    "inbox": "Platform: processed-message record keyed by source system, message id and consumer group (ADR-0002).",
    "outbox_key_sequence": "Platform: per-key sequence counter of the outbox; the key starts with the tenant (ADR-0002).",
    "uom_catalogue": "Reference data shared by every tenant: the units of measure items may use.",
}

# Tables with tenant_id but without row-level security. Each is read across tenants by a background worker; every
# other read must filter by tenant_id (scripts/check-architecture.sh rule 3).
RLS_FREE = {
    "outbox": "The relay publishes every tenant's messages (ADR-0002, ADR-0029).",
    "dlq_replay": "Replay log of the dead-letter tools (ADR-0018, ADR-0029); every read filters tenant_id.",
    "reservation_compensation": "The compensation retrier releases reservations of every tenant (ADR-0029 E).",
    "webhook_delivery": "The webhook dispatcher delivers every tenant's signed bodies (ADR-0025).",
    "tenant": "The tenant registry (ADR-0030 A): the platform API reads and changes every tenant.",
    "tenant_status_change": "The registry's status history (ADR-0030 A), across tenants like the registry.",
    "tenant_admin_account": "Accounts the platform API created in Keycloak (ADR-0030 A), across tenants like the registry.",
}

IDENT = r"[a-z_][a-z0-9_]*"
CONSTRAINT_WORDS = {"primary", "unique", "foreign", "constraint", "check", "exclude"}


class Table:
    def __init__(self, name):
        self.name = name
        self.columns = set()
        self.sequence_columns = set()
        self.references = set()
        self.rls_enabled = self.rls_forced = False
        self.policies = {}  # name -> restrictive

    @property
    def policy(self):
        return any(not restrictive for restrictive in self.policies.values())

    @property
    def owner_rls(self):
        return self.policies.get("owner_isolation") is True

    @property
    def tenant(self):
        return "tenant_id" in self.columns

    @property
    def rls(self):
        return self.rls_enabled and self.rls_forced and self.policy


def version_key(path):
    m = re.match(r"V(\d+(?:[._]\d+)*)__", path.name)
    if not m:
        raise SystemExit(f"tenant_tables.py: unexpected migration name {path}")
    return tuple(int(x) for x in re.split(r"[._]", m.group(1)))


def strip_comments(sql):
    sql = re.sub(r"/\*.*?\*/", " ", sql, flags=re.S)
    return re.sub(r"--[^\n]*", " ", sql)


def matching_paren(text, start):
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return i
    raise SystemExit("tenant_tables.py: unbalanced parentheses in a create table")


def split_top_level(body):
    parts, depth, cur = [], 0, []
    for ch in body:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    parts.append("".join(cur))
    return [p.strip() for p in parts if p.strip()]


def add_column(table, definition):
    words = definition.split()
    name, ctype = words[0], (words[1] if len(words) > 1 else "")
    table.columns.add(name)
    if ctype in ("serial", "bigserial", "smallserial") or "generated" in definition and "as identity" in definition:
        table.sequence_columns.add(name)


def apply(tables, sql):
    sql = strip_comments(sql.lower())
    for m in re.finditer(rf"create\s+table\s+(?:if\s+not\s+exists\s+)?({IDENT})\s*\(", sql):
        table = tables.setdefault(m.group(1), Table(m.group(1)))
        end = matching_paren(sql, m.end() - 1)
        for part in split_top_level(sql[m.end():end]):
            if part.split()[0] not in CONSTRAINT_WORDS:
                add_column(table, part)
            table.references.update(re.findall(rf"\breferences\s+({IDENT})", part))
    for m in re.finditer(rf"drop\s+table\s+(?:if\s+exists\s+)?({IDENT})", sql):
        tables.pop(m.group(1), None)
    for m in re.finditer(rf"alter\s+table\s+(?:if\s+exists\s+)?(?:only\s+)?({IDENT})\s+([^;]*);", sql):
        table = tables.get(m.group(1))
        if table is None:
            continue
        action = m.group(2)
        for c in re.finditer(rf"add\s+column\s+(?:if\s+not\s+exists\s+)?({IDENT}\s+[^,]*)", action):
            add_column(table, c.group(1))
        table.references.update(re.findall(rf"\breferences\s+({IDENT})", action))
        if re.search(r"\benable\s+row\s+level\s+security", action):
            table.rls_enabled = True
        if re.search(r"\bforce\s+row\s+level\s+security", action):
            table.rls_forced = True
    for m in re.finditer(rf"create\s+policy\s+({IDENT})\s+on\s+({IDENT})(\s+as\s+(restrictive|permissive))?", sql):
        if m.group(2) in tables:
            tables[m.group(2)].policies[m.group(1)] = m.group(4) == "restrictive"
    for m in re.finditer(rf"drop\s+policy\s+(?:if\s+exists\s+)?({IDENT})\s+on\s+({IDENT})", sql):
        if m.group(2) in tables:
            tables[m.group(2)].policies.pop(m.group(1), None)
    # do $$ ... foreach t in array array['a', 'b'] loop execute format('alter table %I enable ...', t); ... end loop
    for m in re.finditer(r"array\s*\[([^\]]*)\]\s*loop(.*?)end\s+loop", sql, re.S):
        loop = m.group(2)
        for name in re.findall(rf"'({IDENT})'", m.group(1)):
            table = tables.get(name)
            if table is None:
                continue
            table.rls_enabled |= bool(re.search(r"enable\s+row\s+level\s+security", loop))
            table.rls_forced |= bool(re.search(r"force\s+row\s+level\s+security", loop))
            for pm in re.finditer(rf"create\s+policy\s+({IDENT})\s+on\s+%i(\s+as\s+(restrictive|permissive))?", loop):
                table.policies[pm.group(1)] = pm.group(3) == "restrictive"


def database_of(module):
    yml = (ROOT / module / "src/main/resources/application.yml").read_text(encoding="utf-8")
    m = re.search(r"DB_NAME:(\w+)", yml)
    if not m:
        raise SystemExit(f"tenant_tables.py: no DB_NAME default in {module}/src/main/resources/application.yml")
    return m.group(1), "db/migration/common" in yml


def scan():
    databases = {}
    for module in MODULES:
        db, with_common = database_of(module)
        files = sorted((ROOT / module / "src/main/resources/db/migration").glob("*.sql"), key=version_key)
        if with_common:
            files = sorted(list(COMMON.glob("*.sql")) + files, key=version_key)
        tables = {}
        for f in files:
            apply(tables, f.read_text(encoding="utf-8"))
        databases[db] = (module, tables)
    return databases


def load_order(tables):
    """Parents before children (Kahn), alphabetical among equals, so the order is stable."""
    deps = {n: {r for r in t.references if r in tables and r != n} for n, t in tables.items()}
    order, ready = [], sorted(n for n, d in deps.items() if not d)
    while ready:
        n = ready.pop(0)
        order.append(n)
        for m in sorted(deps):
            if n in deps[m]:
                deps[m].discard(n)
                if not deps[m] and m not in order and m not in ready:
                    ready.append(m)
        ready.sort()
    cyclic = sorted(set(tables) - set(order))
    return order + cyclic, cyclic


def problems(databases):
    out, seen = [], set()
    for db, (module, tables) in databases.items():
        for t in tables.values():
            seen.add(t.name)
            if not t.tenant and t.name not in GLOBAL:
                out.append(f"{db}.{t.name} has no tenant_id: add tenant_id with an RLS policy, or review it into GLOBAL "
                           f"in scripts/tenant_tables.py with a reason")
            if t.tenant and not t.rls and t.name not in RLS_FREE:
                missing = [w for w, ok in (("enable", t.rls_enabled), ("force", t.rls_forced), ("policy", t.policy))
                           if not ok]
                out.append(f"{db}.{t.name} has tenant_id but no complete row-level security (missing: "
                           f"{', '.join(missing)}): add it (ADR-0003), or review it into RLS_FREE with a reason")
            permissive = sorted(n for n, r in t.policies.items() if not r)
            if len(permissive) > 1:
                out.append(f"{db}.{t.name} has {len(permissive)} permissive policies ({', '.join(permissive)}): they are "
                           f"combined with OR, so all but the tenant policy must be AS RESTRICTIVE")
            if "owner_isolation" in t.policies and not t.policies["owner_isolation"]:
                out.append(f"{db}.{t.name}.owner_isolation is permissive: it must be AS RESTRICTIVE (ADR-0030 C)")
            if "owner_isolation" in t.policies and "owner_id" not in t.columns:
                out.append(f"{db}.{t.name} has an owner_isolation policy but no owner_id column")
            if t.name in GLOBAL and t.tenant:
                out.append(f"{db}.{t.name} is listed in GLOBAL but has tenant_id: remove it from GLOBAL")
            if t.name in RLS_FREE and t.rls:
                out.append(f"{db}.{t.name} is listed in RLS_FREE but has row-level security: remove it from RLS_FREE")
            for r in sorted(t.references):
                parent = tables.get(r)
                if parent is not None and parent.sequence_columns:
                    out.append(f"{db}.{t.name} references {r}, whose id comes from a sequence: a tenant export "
                               f"cannot keep that id (ADR-0030 D)")
        _, cyclic = load_order(tables)
        if cyclic:
            out.append(f"{db}: foreign keys form a cycle between {', '.join(cyclic)}: a tenant import needs an order")
    for name in sorted((set(GLOBAL) | set(RLS_FREE)) - seen):
        out.append(f"{name} is reviewed in scripts/tenant_tables.py but no migration creates it: remove the entry")
    return out


def yes(flag):
    return "yes" if flag else ""


def render(databases):
    lines = ["# Tenant data map", "",
             "Generated by `scripts/tenant_tables.py` from the Flyway migrations (ADR-0030, phase 0). Do not edit:",
             "change the migrations and regenerate. CI fails when this file is stale, when a table without `tenant_id` is",
             "not reviewed as global, or when a table with `tenant_id` has no forced row-level security and is not",
             "reviewed as RLS-free.", "",
             "- **RLS**: row-level security enabled, forced, with a policy (ADR-0003).",
             "- **Owner RLS**: a restrictive `owner_isolation` policy limits rows to the session's owners (ADR-0030 C);",
             "  checks that must see every owner go through `OwnerVisibility.allOwners`.",
             "- **Sequence id**: a column the database numbers. A tenant import leaves it out and inserts the rows in id",
             "  order, so the target assigns new values in the same order. No table may refer to such a column.",
             "- **Load order**: the order in which a tenant's rows are imported, parents first (ADR-0030 D).", "",
             "## Summary", "",
             "| Database | Module | Tables | Tenant, with RLS | Tenant, RLS-free (reviewed) | Global (reviewed) "
             "| With owner_id | Owner RLS |",
             "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |"]
    totals = [0, 0, 0, 0, 0, 0]
    for db, (module, tables) in databases.items():
        ts = tables.values()
        row = [len(tables), sum(t.tenant and t.rls for t in ts), sum(t.tenant and not t.rls for t in ts),
               sum(not t.tenant for t in ts), sum("owner_id" in t.columns for t in ts), sum(t.owner_rls for t in ts)]
        totals = [a + b for a, b in zip(totals, row)]
        lines.append(f"| `{db}` | `{module}` | " + " | ".join(map(str, row)) + " |")
    lines.append("| **All** | | " + " | ".join(f"**{n}**" for n in totals) + " |")
    lines += ["", "## Reviewed exceptions", "", "| Table | Kind | Why |", "| --- | --- | --- |"]
    for name, why in sorted(GLOBAL.items()):
        lines.append(f"| `{name}` | global (no `tenant_id`) | {why} |")
    for name, why in sorted(RLS_FREE.items()):
        lines.append(f"| `{name}` | tenant, RLS-free | {why} |")
    for db, (module, tables) in databases.items():
        order, _ = load_order(tables)
        lines += ["", f"## `{db}` ({module})", "",
                  "| Table | tenant_id | RLS | owner_id | Owner RLS | Sequence id | References |",
                  "| --- | --- | --- | --- | --- | --- | --- |"]
        for name in sorted(tables):
            t = tables[name]
            refs = ", ".join(f"`{r}`" for r in sorted(t.references) if r != name)
            seq = ", ".join(f"`{c}`" for c in sorted(t.sequence_columns))
            lines.append(f"| `{name}` | {yes(t.tenant)} | {yes(t.rls)} | {yes('owner_id' in t.columns)} | {yes(t.owner_rls)} | {seq} | {refs} |")
        lines += ["", "Load order: " + ", ".join(f"`{n}`" for n in order if tables[n].tenant) + "."]
    return "\n".join(lines) + "\n"


def main():
    databases = scan()
    if "--plan" in sys.argv:
        import json
        print(json.dumps({db: {name: sorted(t.sequence_columns) for name, t in sorted(tables.items()) if t.tenant}
                          for db, (_, tables) in databases.items()}, indent=2, sort_keys=True))
        return
    if "--rls-free" in sys.argv:
        names = {t.name for _, tables in databases.values() for t in tables.values() if t.tenant and not t.rls}
        print("\n".join(sorted(names)))
        return
    found = problems(databases)
    text = render(databases)
    if "--check" in sys.argv:
        current = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if current != text:
            found.append("docs/security/tenant-data-map.md is stale: run python3 scripts/tenant_tables.py")
        for p in found:
            print(f"TENANCY: {p}", file=sys.stderr)
        if found:
            sys.exit(1)
        print("Tenant data map: up to date; every table is tenant-scoped with RLS or reviewed")
        return
    for p in found:
        print(f"TENANCY: {p}", file=sys.stderr)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    print(f"Wrote {OUT.relative_to(ROOT)}")
    if found:
        sys.exit(1)


if __name__ == "__main__":
    main()
