#!/usr/bin/env python3
"""API permission matrix from the source (ADR-0029, gap analysis 'Security and SoD evidence').

Reads every @RestController of the services, adapters and platform: class and method request mappings and their
@PreAuthorize roles. Writes docs/security/permission-matrix.md:
  - per role, which state-changing endpoints it may call;
  - per service, every endpoint with its roles (reads without @PreAuthorize need an authenticated user of the tenant;
    every role is further limited by its site, owner and zone scope claims, ADR-0012).

  python3 scripts/permission_matrix.py          # regenerate
  python3 scripts/permission_matrix.py --check  # fail when the committed matrix is stale (CI)
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "security" / "permission-matrix.md"
ROLES = ["RECEIVER", "PICKER", "INV_ANALYST", "INV_MANAGER", "SUPERVISOR", "QA_MANAGER", "SOLUTION_ADMIN",
         "ERP_INTEGRATION", "AUTOMATION", "WMS_SERVICE"]
MAPPING = re.compile(r'@(?:org\.springframework\.web\.bind\.annotation\.)?(Get|Post|Put|Delete|Patch|Request)Mapping'
                     r'(?:\((?:value\s*=\s*|path\s*=\s*)?"([^"]*)"[^)]*\)|\(\)|(?=\s|$))')
PREAUTH = re.compile(r'@(?:org\.springframework\.security\.access\.prepost\.)?PreAuthorize\(\s*("(?:[^"\\]|\\.)*"|[A-Z_]+)\s*\)')
CONST = re.compile(r'static final String ([A-Z_]+)\s*=\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+);', re.S)
METHOD_DECL = re.compile(r'^\s*(?:public|protected|private)?\s*[\w<>\[\], .?]+\s+(\w+)\s*\(', re.M)


def service_of(path: pathlib.Path) -> str:
    parts = path.relative_to(ROOT).parts
    return parts[1]


def roles_of(expr: str) -> list[str]:
    if "permitAll" in expr:
        return ["(anyone)"]
    found = re.findall(r"'([A-Z_]+)'", expr)
    return sorted(set(found), key=lambda r: ROLES.index(r) if r in ROLES else 99)


def parse(path: pathlib.Path):
    src = path.read_text(encoding="utf-8")
    if not re.search(r'@(?:[a-z]+\.)*RestController\b', src):
        return []
    consts = {m.group(1): "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(2))) for m in CONST.finditer(src)}
    class_at = re.search(r'^(?:public\s+)?(?:final\s+)?class\s+\w+', src, re.M)
    if not class_at:
        return []
    header, body = src[:class_at.start()], src[class_at.start():]
    base = ""
    m = re.search(r'@(?:[a-z]+\.)*RequestMapping\(\s*(?:value\s*=\s*|path\s*=\s*)?"([^"]*)"', header)
    if m:
        base = m.group(1)
    class_roles = None
    m = PREAUTH.search(header)
    if m:
        expr = m.group(1)
        class_roles = roles_of(consts.get(expr, expr))
    endpoints = []
    lines = body.split("\n")
    pending_roles = None
    pending = []
    for line in lines:
        pa = PREAUTH.search(line)
        if pa:
            expr = pa.group(1)
            pending_roles = roles_of(consts.get(expr, expr))
            continue
        for mm in MAPPING.finditer(line):
            kind, sub = mm.group(1), mm.group(2) or ""
            if kind == "Request":
                continue
            pending.append((kind.upper() if kind != "Request" else "ANY", sub))
        if pending and METHOD_DECL.search(line) and not line.strip().startswith("@"):
            for verb, sub in pending:
                roles = pending_roles if pending_roles is not None else class_roles
                endpoints.append((verb, base + sub, roles))
            pending, pending_roles = [], None
        elif not pending and line.strip() and not line.strip().startswith("@") and pending_roles is not None \
                and METHOD_DECL.search(line):
            pending_roles = None
    return endpoints


def collect():
    out = {}
    for path in sorted(ROOT.glob("*/*/src/main/java/**/*.java")):
        rel = path.relative_to(ROOT).parts
        if rel[0] not in ("services", "adapters", "platform"):
            continue
        for verb, url, roles in parse(path):
            svc = "all services (astra-common)" if rel[0] == "platform" else service_of(path)
            out.setdefault(svc, []).append((url, verb, roles, path.stem))
    for svc in out:
        out[svc].sort(key=lambda e: (e[0], e[1]))
    return out


def render(matrix) -> str:
    lines = ["# API permission matrix", "",
             "Generated from the source by `scripts/permission_matrix.py`; CI fails when it is stale (ADR-0029).", "",
             "- Every request needs a bearer token of the tenant (ADR-0010); a GET without roles is open to any",
             "  authenticated user of the tenant.",
             "- Every state-changing endpoint declares its roles; services refuse to start otherwise",
             "  (SecuredEndpointsVerifier), and `scripts/check-architecture.sh` checks it in CI.",
             "- Roles are further limited by the scope claims `wms_sites`, `wms_owners`, `wms_zones` and `approval_limit`",
             "  (ADR-0012). Approvals need a second person's token in `X-Approval-Token` (segregation of duties).",
             "- `WMS_SERVICE` is the service-to-service account, `AUTOMATION` a device controller, `ERP_INTEGRATION` the ERP",
             "  middleware; none of them is a person's role.", ""]
    # per role: state-changing endpoints
    lines += ["## State-changing endpoints by role", "", "| Role | Endpoints it may change | Services |", "|---|---:|---|"]
    for role in ROLES:
        count, services = 0, set()
        for svc, eps in matrix.items():
            for url, verb, roles, _ in eps:
                if verb != "GET" and roles and role in roles:
                    count += 1
                    services.add(svc)
        lines.append(f"| {role} | {count} | {', '.join(sorted(services))} |")
    lines.append("")
    unsecured = [(svc, url, verb) for svc, eps in matrix.items() for url, verb, roles, _ in eps
                 if verb != "GET" and not roles]
    if unsecured:
        lines += ["**State-changing endpoints without roles:**", ""]
        lines += [f"- {svc}: `{verb} {url}`" for svc, url, verb in unsecured]
        lines.append("")
    for svc in sorted(matrix):
        lines += [f"## {svc}", "", "| Endpoint | Method | Roles | Controller |", "|---|---|---|---|"]
        for url, verb, roles, ctl in matrix[svc]:
            who = ", ".join(roles) if roles else ("any user of the tenant" if verb == "GET" else "**none**")
            lines.append(f"| `{url}` | {verb} | {who} | {ctl} |")
        lines.append("")
    return "\n".join(lines)


def main():
    text = render(collect())
    if "--check" in sys.argv:
        current = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if current != text:
            print("docs/security/permission-matrix.md is stale: run python3 scripts/permission_matrix.py", file=sys.stderr)
            sys.exit(1)
        print("Permission matrix: up to date")
        return
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    print(f"Wrote {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
