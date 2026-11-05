package com.astrawms.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * deploy/vps/tenant-data.py against two PostgreSQL servers migrated with every service's real Flyway migrations
 * (ADR-0030 D): one tenant is exported from the source, where another tenant also has data, and imported into an
 * empty target with the same rows; what must be refused is refused, and changes nothing.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantDataIT {

    static final Path ROOT = repositoryRoot();
    static final Path TOOL = ROOT.resolve("deploy/vps/tenant-data.py");
    static final Map<String, String> MODULES = new LinkedHashMap<>();

    static {
        MODULES.put("masterdata", "services/master-data-service");
        MODULES.put("inventory", "services/inventory-service");
        MODULES.put("inbound", "services/inbound-service");
        MODULES.put("tasks", "services/task-service");
        MODULES.put("outbound", "services/outbound-service");
        MODULES.put("sapadapter", "adapters/sap-adapter");
    }

    static final PostgreSQLContainer SOURCE = server();
    static final PostgreSQLContainer TARGET = server();
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path work;

    static Path archive;

    private static PostgreSQLContainer server() {
        return new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("postgres");
    }

    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("deploy/vps/tenant-data.py"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("deploy/vps/tenant-data.py not found above the working directory");
        }
        return dir;
    }

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        SOURCE.start();
        TARGET.start();
        for (PostgreSQLContainer pg : List.of(SOURCE, TARGET)) {
            // as deploy/vps/postgres-init/01-init.sh
            sql(pg, "postgres", "create role astra_owner login password 'owner'",
                    "create role astra_app login password 'app' nosuperuser nobypassrls");
            for (String db : MODULES.keySet()) {
                sql(pg, "postgres", "create database " + db + " owner astra_owner");
                migrate(pg, db);
            }
        }
        // the tenant to move, and one that must stay behind; rows interleaved so database-numbered ids interleave too
        for (int round = 1; round <= 3; round++) {
            for (String t : List.of("ACME", "OTHER")) {
                seed(t, round);
            }
        }
        // numbers that must not repeat for the tenant in the target
        sql(SOURCE, "inbound", "select setval('blind_return_seq', 41)");
        sql(SOURCE, "outbound", "select setval('carton_serial_seq', 523)");
        // neither the table's order (an update moves a row to its end) nor an index's order (transfer numbers run against
        // the ids) is the id order: only "order by id" keeps the oldest transit line first
        sql(SOURCE, "inventory", "update stock_in_transit set qty_received = 0 where tenant_id = 'ACME' and transfer_no = 'TR-3'");
    }

    @AfterAll
    static void stop() {
        SOURCE.stop();
        TARGET.stop();
    }

    private static void migrate(PostgreSQLContainer pg, String db) throws IOException {
        Path module = ROOT.resolve(MODULES.get(db));
        String yml = Files.readString(module.resolve("src/main/resources/application.yml"));
        List<String> locations = new ArrayList<>();
        if (yml.contains("db/migration/common")) {
            locations.add("filesystem:" + ROOT.resolve("platform/astra-common/src/main/resources/db/migration/common"));
        }
        locations.add("filesystem:" + module.resolve("src/main/resources/db/migration"));
        Flyway.configure().dataSource(url(pg, db), "astra_owner", "owner").locations(locations.toArray(String[]::new))
                .placeholders(Map.of("appRole", "astra_app")).load().migrate();
    }

    private static void seed(String t, int round) throws SQLException {
        String op = UUID.randomUUID().toString();
        String task = UUID.randomUUID().toString();
        String q = "'" + t + "'";
        if (round == 1) {
            sql(SOURCE, "masterdata",
                    "insert into site (tenant_id, site_id, name, time_zone, erp_site) values (" + q + ", 'S1', 'Main', 'UTC', '1000')",
                    "insert into zone (tenant_id, site_id, zone_id, zone_type, erp_bucket) values (" + q + ", 'S1', 'Z1', 'STORAGE', '0001')",
                    "insert into item (tenant_id, owner_id, item_no, description, base_uom, status, source, source_changed_at, updated_at, updated_by) "
                            + "values (" + q + ", 'OWN', 'I1', 'Widget, \"large\"\nsecond line', 'EA', 'ACTIVE', 'ERP', now(), now(), 'seed')",
                    "insert into tenant (tenant_id, display_name, status, created_at, created_by, status_changed_at, status_changed_by, version, published_version) "
                            + "values (" + q + ", " + q + ", 'ACTIVE', now(), 'seed', now(), 'seed', 3, 3)");
            sql(SOURCE, "inbound", "insert into return_order (id, tenant_id, site_id, rma_no, return_type, revision, source_system, "
                    + "customer_name, status, created_at, updated_at) values (gen_random_uuid(), " + q + ", 'S1', 'RMA-1', 'CUSTOMER', 1, "
                    + "'SAP', null, 'EXPECTED', now(), now())");
            sql(SOURCE, "outbound", "insert into outbound_order (id, tenant_id, site_id, erp_doc_no, order_type, revision, source_system, "
                    + "ship_to, status, staging_location, pick_lpn, created_at, updated_at) values (gen_random_uuid(), " + q
                    + ", 'S1', 'SO-1', 'STANDARD', 1, 'SAP', '{\"name\": \"Ada\", \"city\": \"Köln\"}', 'RELEASED', 'STAGE', 'LPN1', now(), now())");
        }
        sql(SOURCE, "masterdata",
                "insert into tenant_status_change (tenant_id, from_status, to_status, reason, changed_by, changed_at) values ("
                        + q + ", null, 'ACTIVE', 'round " + round + "', 'seed', now())",
                "insert into location (tenant_id, site_id, location_id, zone_id, location_type, check_digit, status, updated_at) values ("
                        + q + ", 'S1', 'L" + round + "', 'Z1', 'RACK', '0" + round + "', 'ACTIVE', now())");
        sql(SOURCE, "inventory",
                "insert into inventory_operation (id, tenant_id, site_id, idempotency_key, request_hash, op_type, wms_txn_id, created_by, channel, created_at) "
                        + "values ('" + op + "', " + q + ", 'S1', 'k" + round + "', 'h', 'RECEIVE', 'W" + round + "', 'seed', 'UI', now())",
                "insert into inventory_balance (tenant_id, site_id, owner_id, item_no, location_id, stock_status, qty, receipt_date) values ("
                        + q + ", 'S1', 'OWN', 'I1', 'L" + round + "', 'AVAILABLE', " + (10 * round) + ", now())",
                "insert into inventory_txn (tenant_id, site_id, operation_id, txn_type, owner_id, item_no, lot_no, lpn_id, location_id, "
                        + "stock_status, qty_delta, qty_after, user_id, channel, occurred_at, serials) values (" + q + ", 'S1', '" + op
                        + "', 'RECEIPT', 'OWN', 'I1', '', '', 'L" + round + "', 'AVAILABLE', 10, " + (10 * round)
                        + ", 'seed', 'UI', timestamptz '2026-01-01' , '{SN1,SN2}')",
                "insert into stock_in_transit (tenant_id, transfer_no, from_site, to_site, owner_id, item_no, qty, shipped_at, operation_id) values ("
                        + q + ", 'TR-" + (4 - round) + "', 'S1', 'S2', 'OWN', 'I1', " + round + ", now(), '" + op + "')",
                // published: not exported, and no obstacle
                "insert into outbox (message_id, topic, message_key, message_type, tenant_id, envelope, created_at, published_at) values ("
                        + "gen_random_uuid(), 'wms.inventory.v1', '" + t + ":x', 'StockChanged', " + q + ", '{}', now(), now())");
        sql(SOURCE, "tasks",
                "insert into task (id, tenant_id, site_id, task_type, status, owner_id, lpn_id, from_location, source_operation_id, created_at, updated_at) "
                        + "values ('" + task + "', " + q + ", 'S1', 'PUTAWAY', 'RELEASED', 'OWN', 'LPN" + round + "', 'DOCK', gen_random_uuid(), now(), now())",
                "insert into task_event (tenant_id, task_id, event, user_id, at) values (" + q + ", '" + task + "', 'CREATED', 'seed', now())");
    }

    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    void theToolFindsTheTablesOfTheDataMap() throws Exception {
        Result plan = run(SOURCE, "plan");
        assertThat(plan.code).as(plan.err).isZero();
        Result map = python(ROOT.resolve("scripts/tenant_tables.py").toString(), "--plan");
        assertThat(map.code).as(map.err).isZero();
        JsonNode found = JSON.readTree(plan.out);
        JsonNode reviewed = JSON.readTree(map.out);
        for (String db : MODULES.keySet()) {
            Map<String, TreeSet<String>> fromCatalog = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = found.get(db).get("tables").properties().iterator(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                fromCatalog.put(e.getKey(), strings(e.getValue().get("sequence")));
            }
            Map<String, TreeSet<String>> fromMap = new TreeMap<>();
            reviewed.get(db).properties().forEach(e -> fromMap.put(e.getKey(), strings(e.getValue())));
            assertThat(fromCatalog).as(db).isEqualTo(fromMap);
        }
    }

    @Test
    @Order(2)
    void anUnpublishedOutboxMessageBlocksTheExport() throws Exception {
        sql(SOURCE, "outbound", "insert into outbox (message_id, topic, message_key, message_type, tenant_id, envelope, created_at) "
                + "values (gen_random_uuid(), 'wms.outbound.v1', 'ACME:x', 'OrderShipped', 'ACME', '{}', now())");
        try {
            Result r = run(SOURCE, "export", "ACME", "--out", work.resolve("refused.tar.gz.enc").toString());
            assertThat(r.code).as(r.err).isEqualTo(3);
            assertThat(r.err).contains("outbound").contains("not published");
            assertThat(work.resolve("refused.tar.gz.enc")).doesNotExist();
        } finally {
            sql(SOURCE, "outbound", "update outbox set published_at = now() where published_at is null");
        }
    }

    @Test
    @Order(3)
    void aTenantIsExportedAndImportedIntoAnEmptyDeploymentWithTheSameRows() throws Exception {
        archive = work.resolve("acme.tar.gz.enc");
        Result export = run(SOURCE, "export", "ACME", "--out", archive.toString());
        assertThat(export.code).as(export.err).isZero();
        assertThat(Path.of(archive + ".sha256")).exists();
        assertThat(Files.readString(work.resolve("tenant-exports.log"))).contains("export tenant=ACME file=acme.tar.gz.enc");

        Result imported = run(TARGET, "import", archive.toString());
        assertThat(imported.code).as(imported.err).isZero();
        assertThat(imported.out).contains("registry=imported");

        JsonNode plan = JSON.readTree(run(SOURCE, "plan").out);
        int compared = 0;
        for (String db : MODULES.keySet()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = plan.get(db).get("tables").properties().iterator(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String table = e.getKey();
                if (table.equals("outbox")) {
                    assertThat(count(TARGET, db, "select count(*) from outbox")).as("outbox is not exported").isZero();
                    continue;
                }
                List<String> columns = new ArrayList<>();
                e.getValue().get("columns").forEach(c -> columns.add('"' + c.asString() + '"'));
                if (table.equals("tenant")) {
                    columns.remove("\"published_version\"");
                }
                // every column but the database-numbered ones, as one fingerprint per tenant
                String fingerprint = "select count(*) || ':' || coalesce(md5(string_agg(r::text, '|' order by r::text)), '') from (select "
                        + String.join(", ", columns) + " from " + table + " where tenant_id = '%s') r";
                String expected = text(SOURCE, db, fingerprint.formatted("ACME"));
                String actual = table.equals("tenant_status_change")
                        ? text(TARGET, db, fingerprint.formatted("ACME").replace("where tenant_id = 'ACME'",
                        "where tenant_id = 'ACME' and changed_by <> 'tenant-data.py'"))
                        : text(TARGET, db, fingerprint.formatted("ACME"));
                assertThat(actual).as(db + "." + table).isEqualTo(expected);
                assertThat(count(TARGET, db, "select count(*) from " + table + " where tenant_id = 'OTHER'"))
                        .as(db + "." + table + " of the other tenant").isZero();
                compared++;
            }
        }
        assertThat(compared).isGreaterThan(80);

        // the ledger keeps its order under new ids
        assertThat(text(TARGET, "inventory", "select string_agg(qty_after::text, ',' order by id) from inventory_txn"))
                .isEqualTo(text(SOURCE, "inventory", "select string_agg(qty_after::text, ',' order by id) from inventory_txn where tenant_id = 'ACME'"))
                .isEqualTo("10.000,20.000,30.000");
        assertThat(text(TARGET, "inventory", "select string_agg(transfer_no, ',' order by id) from stock_in_transit"))
                .isEqualTo("TR-3,TR-2,TR-1");
        // numbers already handed out are never handed out again
        assertThat(count(TARGET, "inbound", "select last_value from blind_return_seq")).isGreaterThanOrEqualTo(41);
        assertThat(count(TARGET, "outbound", "select last_value from carton_serial_seq")).isGreaterThanOrEqualTo(523);
        // the registry row is published again, and the import is on the record
        assertThat(count(TARGET, "masterdata", "select published_version from tenant where tenant_id = 'ACME'")).isZero();
        assertThat(text(TARGET, "masterdata", "select reason from tenant_status_change where changed_by = 'tenant-data.py'"))
                .startsWith("Imported from acme.tar.gz.enc");
    }

    @Test
    @Order(4)
    void aSecondImportIsRefusedAndChangesNothing() throws Exception {
        long before = count(TARGET, "inventory", "select count(*) from inventory_txn");
        Result r = run(TARGET, "import", archive.toString());
        assertThat(r.code).as(r.err).isEqualTo(3);
        assertThat(r.err).contains("already has rows");
        assertThat(count(TARGET, "inventory", "select count(*) from inventory_txn")).isEqualTo(before);
    }

    @Test
    @Order(5)
    void aDamagedArchiveOrAWrongPassphraseIsRefused() throws Exception {
        Path damaged = work.resolve("damaged.tar.gz.enc");
        byte[] bytes = Files.readAllBytes(archive);
        bytes[bytes.length / 2] ^= 1;
        Files.write(damaged, bytes);
        Files.copy(Path.of(archive + ".sha256"), Path.of(damaged + ".sha256"));
        Result r = run(TARGET, "import", damaged.toString());
        assertThat(r.code).as(r.err).isEqualTo(3);
        assertThat(r.err).contains("checksum mismatch");

        Result wrong = run(TARGET, Map.of("BACKUP_PASSPHRASE", "not-the-passphrase"), "import", archive.toString());
        assertThat(wrong.code).as(wrong.err).isEqualTo(3);
        assertThat(wrong.err).contains("cannot decrypt");
    }

    @Test
    @Order(6)
    void anotherSchemaVersionIsRefused() throws Exception {
        Path other = work.resolve("other.tar.gz.enc");
        assertThat(run(SOURCE, "export", "OTHER", "--out", other.toString()).code).isZero();
        sql(TARGET, "tasks", "insert into flyway_schema_history (installed_rank, version, description, type, script, installed_by, "
                + "execution_time, success) values (9999, '9999', 'later release', 'SQL', 'V9999__x.sql', 'test', 0, true)");
        try {
            Result r = run(TARGET, "import", other.toString());
            assertThat(r.code).as(r.err).isEqualTo(3);
            assertThat(r.err).contains("tasks is at schema version 9999");
            assertThat(count(TARGET, "masterdata", "select count(*) from site where tenant_id = 'OTHER'")).isZero();
        } finally {
            sql(TARGET, "tasks", "delete from flyway_schema_history where installed_rank = 9999");
        }
    }

    @Test
    @Order(7)
    void aRegisteredTenantWithoutDataKeepsItsRegistryRow() throws Exception {
        // as in a point restore: the tenant stays registered (suspended) while its data is replaced
        sql(TARGET, "masterdata", "insert into tenant (tenant_id, display_name, status, created_at, created_by, status_changed_at, "
                + "status_changed_by, status_reason) values ('OTHER', 'Other Ltd', 'SUSPENDED', now(), 'ops', now(), 'ops', 'Restore')");
        Result r = run(TARGET, "import", work.resolve("other.tar.gz.enc").toString());
        assertThat(r.code).as(r.err).isZero();
        assertThat(r.out).contains("registry=kept");
        assertThat(text(TARGET, "masterdata", "select display_name || '/' || status from tenant where tenant_id = 'OTHER'"))
                .isEqualTo("Other Ltd/SUSPENDED");
        assertThat(count(TARGET, "masterdata", "select count(*) from tenant_status_change where tenant_id = 'OTHER'")).isEqualTo(1);
        assertThat(count(TARGET, "masterdata", "select count(*) from location where tenant_id = 'OTHER'")).isEqualTo(3);
    }

    // ---------------------------------------------------------------------------------------------------------------

    record Result(int code, String out, String err) {
    }

    private static Result run(PostgreSQLContainer pg, String... args) throws Exception {
        return run(pg, Map.of(), args);
    }

    private static Result run(PostgreSQLContainer pg, Map<String, String> env, String... args) throws Exception {
        Map<String, String> e = new LinkedHashMap<>(Map.of("PG_CONTAINER", pg.getContainerId(), "APP_DIR", work.toString(),
                "BACKUP_DIR", work.toString(), "BACKUP_PASSPHRASE", "test-passphrase"));
        e.putAll(env);
        List<String> command = new ArrayList<>(List.of(TOOL.toString()));
        command.addAll(List.of(args));
        return python(e, command.toArray(String[]::new));
    }

    private static Result python(String... args) throws Exception {
        return python(Map.of(), args);
    }

    private static Result python(Map<String, String> env, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("python3"));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(env);
        Path out = Files.createTempFile(work, "out", ".txt");
        Path err = Files.createTempFile(work, "err", ".txt");
        pb.redirectOutput(out.toFile()).redirectError(err.toFile());
        int code = pb.start().waitFor();
        return new Result(code, Files.readString(out, StandardCharsets.UTF_8), Files.readString(err, StandardCharsets.UTF_8));
    }

    private static String url(PostgreSQLContainer pg, String db) {
        return "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + db;
    }

    private static void sql(PostgreSQLContainer pg, String db, String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(url(pg, db), "postgres", "postgres"); Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        }
    }

    private static String text(PostgreSQLContainer pg, String db, String query) throws SQLException {
        try (Connection c = DriverManager.getConnection(url(pg, db), "postgres", "postgres"); Statement s = c.createStatement();
             ResultSet r = s.executeQuery(query)) {
            r.next();
            return r.getString(1);
        }
    }

    private static long count(PostgreSQLContainer pg, String db, String query) throws SQLException {
        return Long.parseLong(text(pg, db, query));
    }

    private static TreeSet<String> strings(JsonNode array) {
        TreeSet<String> s = new TreeSet<>();
        array.forEach(n -> s.add(n.asString()));
        return s;
    }
}
