#!/usr/bin/env python3
"""Per-tenant data lifecycle (ADR-0030 D): export one tenant's rows from every AstraWMS database, and import them into
a deployment where that tenant has no data.

  tenant-data.py plan                       the tenant tables, load order and sequence columns, per database (JSON)
  tenant-data.py export <tenant> [--out F]  writes F (default BACKUP_DIR/tenant-<tenant>-<UTC stamp>.tar.gz.enc)
  tenant-data.py import <archive>           loads an archive; refuses when the tenant already has rows here

What it does:
  - The tables come from the database catalog at run time: every table in schema public with a tenant_id column,
    parents before children by foreign key. The outbox is not exported: a tenant is exported only once its outbox has
    drained (suspend it first for an exact copy; services keep writing for an active tenant).
  - Export: per database, one psql session in a REPEATABLE READ, READ ONLY transaction (one snapshot) runs COPY for
    every table to files inside the PostgreSQL container, with the row counts and the last value of every sequence.
    The files are taken out, checksummed into manifest.json, tarred, gzipped and encrypted with BACKUP_PASSPHRASE
    exactly like pg-backup.sh (AES-256-CBC, PBKDF2, 200,000 iterations). A sha256 file is written next to it, and a
    line appended to BACKUP_DIR/tenant-exports.log.
  - Columns the database numbers (serial, identity: the data map's "Sequence id") are left out, and their rows written
    in id order, so the target assigns new ids in the same order (ADR-0030 D).
  - Import: checks the archive's checksums, that every database is at the archive's schema version, and that the
    tenant has no rows here. Then, per database, one transaction loads every table in foreign-key order, compares
    each table's count with the manifest (any difference rolls the database back), raises every sequence to at least
    the source's value (numbers such as blind_return_seq never repeat for the tenant), and records the import in the
    tenant's status history. The registry rows (tenant, its history and admin accounts) are loaded only when the
    tenant is not registered here; an imported registry row is published again on the tenants topic.
  - Databases are processed one after another: an import that fails part-way leaves the databases already loaded,
    and says which; delete the tenant's rows there before trying again.

Runs as the postgres superuser in the container (RLS does not apply to it). Environment: APP_DIR (/opt/astrawms; its
.env is read), PG_CONTAINER (default: the compose project's postgres), BACKUP_DIR (APP_DIR/backups), BACKUP_PASSPHRASE,
TENANT_DATABASES (masterdata inventory inbound tasks outbound sapadapter).
"""
import datetime
import hashlib
import io
import json
import os
import pathlib
import re
import shutil
import socket
import subprocess
import sys
import tarfile
import tempfile

TENANT_ID = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
REGISTRY = ("tenant", "tenant_status_change", "tenant_admin_account")
NOT_EXPORTED = ("outbox",)
FORMAT = 1


class Refused(Exception):
    """The operation would be unsafe or inconsistent; nothing was changed."""


def load_env():
    app = pathlib.Path(os.environ.get("APP_DIR", "/opt/astrawms"))
    env = app / ".env"
    if env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            m = re.match(r"^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$", line)
            if m and m.group(1) not in os.environ:
                os.environ[m.group(1)] = m.group(2).strip().strip("'\"")
    return app


def container(app):
    c = os.environ.get("PG_CONTAINER")
    if not c:
        c = subprocess.run(["docker", "compose", "--env-file", ".env", "ps", "-q", "postgres"], cwd=app,
                           capture_output=True, text=True).stdout.strip()
    if not c:
        raise Refused("the postgres container is not running (set PG_CONTAINER)")
    return c


def databases():
    return os.environ.get("TENANT_DATABASES", "masterdata inventory inbound tasks outbound sapadapter").split()


def psql(pg, db, sql, *, user="postgres"):
    """Runs a script with ON_ERROR_STOP; returns stdout (unaligned, tuples only)."""
    r = subprocess.run(["docker", "exec", "-i", "-u", user, pg, "psql", "-X", "-q", "-tA", "-v", "ON_ERROR_STOP=1",
                        "-U", "postgres", "-d", db], input=sql, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"psql on {db} failed: {r.stderr.strip()}")
    return r.stdout


def query_json(pg, db, sql):
    out = psql(pg, db, f"select coalesce(json_agg(q), '[]') from ({sql}) q;").strip()
    return json.loads(out or "[]")


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def ident(name):
    if not re.match(r"^[a-z_][a-z0-9_]*$", name):
        raise RuntimeError(f"unexpected identifier {name!r}")
    return '"' + name + '"'


def plan(pg, db):
    """Tenant tables of one database, parents first, with their columns and database-numbered columns."""
    cols = query_json(pg, db, """
        select c.table_name as t, c.column_name as c, c.ordinal_position as pos,
               (c.is_identity = 'YES' or coalesce(c.column_default, '') like 'nextval(%') as seq
        from information_schema.columns c join information_schema.tables t
          on t.table_schema = c.table_schema and t.table_name = c.table_name and t.table_type = 'BASE TABLE'
        where c.table_schema = 'public' order by c.table_name, c.ordinal_position""")
    tables = {}
    for r in cols:
        tables.setdefault(r["t"], {"columns": [], "sequence": []})
        (tables[r["t"]]["sequence"] if r["seq"] else tables[r["t"]]["columns"]).append(r["c"])
    tenant = {t: d for t, d in tables.items() if "tenant_id" in d["columns"]}
    refs = query_json(pg, db, """
        select distinct child.relname as child, parent.relname as parent
        from pg_constraint k join pg_class child on child.oid = k.conrelid join pg_class parent on parent.oid = k.confrelid
        join pg_namespace n on n.oid = child.relnamespace
        where k.contype = 'f' and n.nspname = 'public'""")
    deps = {t: set() for t in tenant}
    for r in refs:
        if r["child"] in tenant and r["parent"] in tenant and r["child"] != r["parent"]:
            deps[r["child"]].add(r["parent"])
    order, ready = [], sorted(t for t, d in deps.items() if not d)
    while ready:
        t = ready.pop(0)
        order.append(t)
        for m in sorted(deps):
            if t in deps[m]:
                deps[m].discard(t)
                if not deps[m] and m not in order and m not in ready:
                    ready.append(m)
        ready.sort()
    if len(order) != len(tenant):
        raise Refused(f"{db}: foreign keys form a cycle between {sorted(set(tenant) - set(order))}")
    return {"order": order, "tables": {t: tenant[t] for t in order}}


def schema_version(pg, db):
    rows = query_json(pg, db, "select version from flyway_schema_history where success and version is not null "
                              "order by installed_rank desc limit 1")
    return rows[0]["version"] if rows else None


def sequences_here(pg, db):
    return {r["name"] for r in query_json(pg, db, "select sequencename as name from pg_sequences "
                                                  "where schemaname = 'public'")}


def tenant_rows(pg, db, tenant, tables):
    """Rows of the tenant per table (only tables with rows)."""
    if not tables:
        return {}
    union = " union all ".join(f"select {literal(t)} as t, count(*) as n from {ident(t)} where tenant_id = {literal(tenant)}"
                               for t in tables)
    return {r["t"]: r["n"] for r in query_json(pg, db, union) if r["n"]}


def workdir(pg):
    path = f"/tmp/astra-tenant-{os.getpid()}"
    subprocess.run(["docker", "exec", "-u", "postgres", pg, "mkdir", "-p", path], check=True)
    return path


def drop_workdir(pg, path):
    subprocess.run(["docker", "exec", "-u", "0", pg, "rm", "-rf", path], capture_output=True)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def encrypt(src, dst):
    passphrase = os.environ.get("BACKUP_PASSPHRASE")
    if not passphrase:
        raise Refused("BACKUP_PASSPHRASE is not set")
    with open(src, "rb") as i, open(dst, "wb") as o:
        subprocess.run(["openssl", "enc", "-aes-256-cbc", "-pbkdf2", "-iter", "200000", "-salt",
                        "-pass", "env:BACKUP_PASSPHRASE"], stdin=i, stdout=o, check=True,
                       env={**os.environ, "BACKUP_PASSPHRASE": passphrase})


def decrypt(src, dst):
    passphrase = os.environ.get("BACKUP_PASSPHRASE")
    if not passphrase:
        raise Refused("BACKUP_PASSPHRASE is not set")
    with open(src, "rb") as i, open(dst, "wb") as o:
        r = subprocess.run(["openssl", "enc", "-d", "-aes-256-cbc", "-pbkdf2", "-iter", "200000",
                            "-pass", "env:BACKUP_PASSPHRASE"], stdin=i, stdout=o, stderr=subprocess.PIPE,
                           env={**os.environ, "BACKUP_PASSPHRASE": passphrase})
    if r.returncode != 0:
        raise Refused(f"cannot decrypt {src}: wrong BACKUP_PASSPHRASE or a damaged archive")


def export(pg, tenant, out, backup_dir):
    if not TENANT_ID.match(tenant):
        raise Refused(f"not a tenant id: {tenant!r}")
    manifest = {"format": FORMAT, "tenant": tenant, "exportedAt": now(), "sourceHost": socket.gethostname(),
                "databases": {}}
    found = False
    with tempfile.TemporaryDirectory(prefix="astra-tenant-") as tmp:
        root = pathlib.Path(tmp) / "archive"
        root.mkdir()
        for db in databases():
            p = plan(pg, db)
            pending = query_json(pg, db, f"select count(*) as n from outbox where tenant_id = {literal(tenant)} "
                                         f"and published_at is null")[0]["n"] if "outbox" in p["tables"] else 0
            if pending:
                raise Refused(f"{db}: {pending} outbox messages of {tenant} are not published yet; suspend the tenant "
                              f"and export once its outbox has drained")
            tables = [t for t in p["order"] if t not in NOT_EXPORTED]
            path = workdir(pg)
            try:
                script = ["begin transaction isolation level repeatable read read only;"]
                for t in tables:
                    d = p["tables"][t]
                    order = (" order by " + ", ".join(ident(c) for c in d["sequence"])) if d["sequence"] else ""
                    script.append(f"copy (select {', '.join(ident(c) for c in d['columns'])} from {ident(t)} "
                                  f"where tenant_id = {literal(tenant)}{order}) to '{path}/{t}.csv' "
                                  f"with (format csv, header true);")
                    script.append(f"select 'rows', {literal(t)}, count(*) from {ident(t)} where tenant_id = {literal(tenant)};")
                script.append("select 'seq', sequencename, last_value from pg_sequences where schemaname = 'public' "
                              "and last_value is not null;")
                script.append("select 'version', version from flyway_schema_history where success and version is not "
                              "null order by installed_rank desc limit 1;")
                script.append("commit;")
                out_lines = psql(pg, db, "\n".join(script)).splitlines()
                subprocess.run(["docker", "cp", f"{pg}:{path}/.", str(root / db)], check=True, capture_output=True)
            finally:
                drop_workdir(pg, path)
            counts, seqs, version = {}, {}, None
            for line in out_lines:
                parts = line.split("|")
                if parts[0] == "rows":
                    counts[parts[1]] = int(parts[2])
                elif parts[0] == "seq":
                    seqs[parts[1]] = int(parts[2])
                elif parts[0] == "version":
                    version = parts[1]
            entry = {"schemaVersion": version, "sequences": seqs, "tables": {}}
            for t in tables:
                f = root / db / f"{t}.csv"
                entry["tables"][t] = {"rows": counts[t], "sha256": sha256(f), "columns": p["tables"][t]["columns"],
                                      "omitted": p["tables"][t]["sequence"]}
                found |= counts[t] > 0
            manifest["databases"][db] = entry
        if not found:
            raise Refused(f"no rows of tenant {tenant} in any database")
        (root / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True), encoding="utf-8")
        tgz = pathlib.Path(tmp) / "archive.tar.gz"
        with tarfile.open(tgz, "w:gz") as tar:
            tar.add(root, arcname=f"tenant-{tenant}")
        out = pathlib.Path(out or backup_dir / f"tenant-{tenant}-{stamp()}.tar.gz.enc")
        out.parent.mkdir(parents=True, exist_ok=True)
        partial = out.with_name(out.name + ".partial")
        encrypt(tgz, partial)
        partial.replace(out)
    pathlib.Path(str(out) + ".sha256").write_text(sha256(out) + "\n", encoding="utf-8")
    total = sum(t["rows"] for d in manifest["databases"].values() for t in d["tables"].values())
    line = f"{now()} export tenant={tenant} file={out.name} rows={total} sha256={sha256(out)}"
    backup_dir.mkdir(parents=True, exist_ok=True)
    with open(backup_dir / "tenant-exports.log", "a", encoding="utf-8") as log:
        log.write(line + "\n")
    print(line)
    return out, manifest


def unpack(archive, tmp):
    archive = pathlib.Path(archive)
    side = pathlib.Path(str(archive) + ".sha256")
    if side.exists() and side.read_text().split()[0] != sha256(archive):
        raise Refused(f"checksum mismatch: {archive}")
    tgz = pathlib.Path(tmp) / "archive.tar.gz"
    decrypt(archive, tgz)
    with tarfile.open(tgz, "r:gz") as tar:
        for m in tar.getmembers():
            if not (m.isdir() or m.isreg()) or m.name.startswith("/") or ".." in pathlib.PurePosixPath(m.name).parts:
                raise Refused(f"unexpected entry in the archive: {m.name}")
        if hasattr(tarfile, "data_filter"):
            tar.extractall(tmp, filter="data")
        else:  # older Python: the members were checked above (no links, no absolute or parent paths)
            tar.extractall(tmp)
    dirs = [d for d in pathlib.Path(tmp).iterdir() if d.is_dir() and d.name.startswith("tenant-")]
    if len(dirs) != 1:
        raise Refused("the archive does not hold exactly one tenant")
    root = dirs[0]
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("format") != FORMAT or not TENANT_ID.match(manifest.get("tenant", "")):
        raise Refused("not a tenant archive of this format")
    for db, entry in manifest["databases"].items():
        for t, meta in entry["tables"].items():
            f = root / db / f"{t}.csv"
            if not f.exists() or sha256(f) != meta["sha256"]:
                raise Refused(f"{db}.{t}: the data does not match the manifest")
    return root, manifest


def import_archive(pg, archive):
    with tempfile.TemporaryDirectory(prefix="astra-tenant-") as tmp:
        root, manifest = unpack(archive, tmp)
        tenant = manifest["tenant"]
        # every check before the first write
        plans = {}
        registered = False
        for db, entry in manifest["databases"].items():
            version = schema_version(pg, db)
            if version != entry["schemaVersion"]:
                raise Refused(f"{db} is at schema version {version}, the archive at {entry['schemaVersion']}: "
                              f"import into a deployment of the same release")
            p = plan(pg, db)
            missing = sorted(set(entry["tables"]) - set(p["tables"]))
            if missing:
                raise Refused(f"{db}: the archive has tables this deployment lacks: {missing}")
            for t, meta in entry["tables"].items():
                if meta["columns"] != p["tables"][t]["columns"]:
                    raise Refused(f"{db}.{t}: the columns differ from the archive's")
            here = tenant_rows(pg, db, tenant, [t for t in p["order"] if t not in REGISTRY])
            if here:
                raise Refused(f"tenant {tenant} already has rows in {db} ({', '.join(sorted(here))}): delete them "
                              f"first, or import into another deployment")
            if "tenant" in p["tables"]:
                registered = bool(tenant_rows(pg, db, tenant, ["tenant"]))
            plans[db] = (p, sequences_here(pg, db))
        done = []
        for db, entry in manifest["databases"].items():
            p, target_sequences = plans[db]
            tables = [t for t in p["order"] if t in entry["tables"] and not (registered and t in REGISTRY)]
            path = workdir(pg)
            try:
                subprocess.run(["docker", "cp", f"{root / db}/.", f"{pg}:{path}"], check=True, capture_output=True)
                subprocess.run(["docker", "exec", "-u", "0", pg, "chown", "-R", "postgres", path], check=True)
                script = ["begin;"]
                for t in tables:
                    meta = entry["tables"][t]
                    if meta["rows"] == 0:
                        continue
                    script.append(f"copy {ident(t)} ({', '.join(ident(c) for c in meta['columns'])}) from "
                                  f"'{path}/{t}.csv' with (format csv, header true);")
                for t in tables:
                    n = entry["tables"][t]["rows"]
                    script.append(f"do $$ begin if (select count(*) from {ident(t)} where tenant_id = {literal(tenant)})"
                                  f" <> {n} then raise exception 'count of {t} differs from the manifest ({n})'; end if;"
                                  f" end $$;")
                for name, value in sorted(entry["sequences"].items()):
                    if name in target_sequences:
                        script.append(f"select setval({literal(name)}, greatest({int(value)}, "
                                      f"(select last_value from {ident(name)})));")
                if "tenant" in tables and entry["tables"]["tenant"]["rows"]:
                    script.append(f"update tenant set published_version = 0 where tenant_id = {literal(tenant)};")
                if "tenant_status_change" in p["tables"] and (registered or entry["tables"].get("tenant", {}).get("rows")):
                    reason = (f"Imported from {pathlib.Path(archive).name} (exported {manifest['exportedAt']} on "
                              f"{manifest['sourceHost']})")
                    script.append(f"insert into tenant_status_change (tenant_id, from_status, to_status, reason, "
                                  f"changed_by, changed_at) select tenant_id, status, status, {literal(reason)}, "
                                  f"'tenant-data.py', now() from tenant where tenant_id = {literal(tenant)};")
                script.append("commit;")
                try:
                    psql(pg, db, "\n".join(script))
                except RuntimeError as e:
                    loaded = f" Already loaded: {', '.join(done)}." if done else ""
                    raise RuntimeError(f"{e}\n{db} was rolled back.{loaded}") from None
            finally:
                drop_workdir(pg, path)
            done.append(db)
        total = sum(t["rows"] for d in manifest["databases"].values() for t in d["tables"].values())
        print(f"{now()} import tenant={tenant} file={pathlib.Path(archive).name} rows={total} "
              f"registry={'kept' if registered else 'imported'}")
        return manifest


def now():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def stamp():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def main(argv):
    if len(argv) < 2 or argv[1] in ("-h", "--help"):
        print(__doc__)
        return 0 if len(argv) >= 2 else 2
    app = load_env()
    os.umask(0o077)
    backup_dir = pathlib.Path(os.environ.get("BACKUP_DIR", app / "backups"))
    try:
        pg = container(app)
        if argv[1] == "plan":
            print(json.dumps({db: plan(pg, db) for db in databases()}, indent=2, sort_keys=True))
        elif argv[1] == "export" and len(argv) in (3, 5):
            out = argv[4] if len(argv) == 5 and argv[3] == "--out" else None
            export(pg, argv[2], out, backup_dir)
        elif argv[1] == "import" and len(argv) == 3:
            import_archive(pg, argv[2])
        else:
            print(__doc__, file=sys.stderr)
            return 2
    except Refused as e:
        print(f"REFUSED: {e}", file=sys.stderr)
        return 3
    except (RuntimeError, subprocess.CalledProcessError) as e:
        print(f"FAILED: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
