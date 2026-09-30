package com.astrawms.inventory.support;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.tenancy.TenantFilter;
import com.astrawms.common.contracts.MasterDataEvents.ItemUpserted;
import com.astrawms.common.contracts.MasterDataEvents.LocationUpserted;
import com.astrawms.inventory.reference.ReferenceRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import com.astrawms.test.AstraContainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Base class for inventory integration tests: one Postgres and one Kafka container per JVM, the application
 * connecting as the non-owner role {@code astra_app} so that row-level security is really enforced.
 * Every test gets its own tenant, which isolates test data without truncating tables.
 */
@SpringBootTest(properties = {"astra.outbox.relay-interval-ms=200", "astra.kafka.retry.initial-interval-ms=100",
        "astra.kafka.retry.max-elapsed-ms=1000"})
public abstract class IntegrationTest {

    protected static final org.testcontainers.kafka.KafkaContainer KAFKA = AstraContainers.KAFKA;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry r) {
        AstraContainers.register(r);
    }

    protected static final String SITE = "DC1";
    protected static final String OWNER = "ACME";

    @Autowired
    protected WebApplicationContext context;
    @Autowired
    protected ReferenceRepository refs;
    @Autowired
    protected TransactionTemplate tx;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected tools.jackson.databind.json.JsonMapper jsonMapper;

    protected MockMvc mvc;
    protected String tenant;

    @BeforeEach
    void setUpTenant() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new TenantFilter()).build();
        tenant = "t-" + UUID.randomUUID().toString().substring(0, 8);
        // Reference data used by most tests.
        item("SKU-EA", false, null, null, false);                  // plain item, base EA, 1 CS = 12 EA
        item("SKU-LOT", true, 365, null, false);                   // lot + shelf-life controlled
        item("SKU-FROZEN", false, null, "FROZEN", false);
        item("SKU-HAZ", false, null, null, true);
        item("SKU-SER", false, null, null, false, "FULL");         // serial-tracked from receipt
        item("SKU-OUTSER", false, null, null, false, "OUTBOUND");  // serials captured at pack only
        location("A-01-01", "STOR", "0001", null, false, true, true);
        location("A-01-02", "STOR", "0001", null, false, true, true);
        location("A-02-01", "STOR", "0001", null, false, true, false);   // single lot per item
        location("F-01-01", "FRZ", "0001", "FROZEN", false, true, true);
        location("H-01-01", "HAZ", "0001", null, true, true, true);
        location("Q-01-01", "QC", "0002", null, false, true, true);      // different ERP bucket
    }

    // ------------------------------------------------------------------ reference data

    protected void item(String itemNo, boolean lot, Integer shelfLife, String temperature, boolean hazardous) {
        item(itemNo, lot, shelfLife, temperature, hazardous, "NONE");
    }

    protected void item(String itemNo, boolean lot, Integer shelfLife, String temperature, boolean hazardous,
                        String serialControl) {
        asTenant(() -> refs.upsertItem(new ItemUpserted(OWNER, itemNo, "EA", "ACTIVE", shelfLife, temperature,
                hazardous, List.of(new ItemUpserted.Site(SITE, lot, serialControl, "ACTIVE")),
                List.of(new ItemUpserted.Uom("CS", 12, 1)), Instant.now())));
    }

    protected void location(String id, String zone, String bucket, String temperature, boolean hazmat,
                            boolean mixedItems, boolean mixedLots) {
        asTenant(() -> refs.upsertLocation(new LocationUpserted(SITE, id, zone, "RACK", bucket, temperature, hazmat,
                mixedItems, mixedLots, "ACTIVE", Instant.now())));
    }

    protected void asTenant(Runnable work) {
        TenantContext.runAs(new TenantContext.Scope(tenant, "test-setup", "TEST"),
                () -> tx.executeWithoutResult(s -> work.run()));
    }

    protected <T> T queryAsTenant(java.util.function.Supplier<T> work) {
        return TenantContext.callAs(new TenantContext.Scope(tenant, "test-setup", "TEST"),
                () -> tx.execute(s -> work.get()));
    }

    // ------------------------------------------------------------------ HTTP helpers

    protected ResultActions postAs(String user, String path, String key, String json) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/sites/" + SITE + "/inventory" + path)
                .header(TenantFilter.TENANT_HEADER, tenant)
                .header(TenantFilter.USER_HEADER, user)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    protected ResultActions post(String path, String json) throws Exception {
        return postAs("operator1", path, UUID.randomUUID().toString(), json);
    }

    protected ResultActions getJson(String path) throws Exception {
        return mvc.perform(get("/api/v1/sites/" + SITE + "/inventory" + path)
                .header(TenantFilter.TENANT_HEADER, tenant));
    }

    protected ResultActions receive(String itemNo, String qty, String uom, String location, String lpn, String lot)
            throws Exception {
        return post("/receipts", """
                {"ownerId":"%s","itemNo":"%s","qty":%s,"uom":"%s","locationId":"%s"%s%s%s}"""
                .formatted(OWNER, itemNo, qty, uom, location,
                        lpn == null ? "" : ",\"lpnId\":\"" + lpn + "\"",
                        lot == null ? "" : ",\"lotNo\":\"" + lot + "\"",
                        lot == null ? "" : ",\"expiryDate\":\"2027-12-31\""));
    }

    // ------------------------------------------------------------------ database helpers (as tenant)

    protected java.math.BigDecimal onHand(String itemNo, String location, String status) {
        return queryAsTenant(() -> jdbc.sql("""
                        select coalesce(sum(qty), 0) from inventory_balance
                        where item_no = :item and location_id = :loc and stock_status = :status""")
                .param("item", itemNo).param("loc", location).param("status", status)
                .query(java.math.BigDecimal.class).single());
    }

    protected List<String> outboxTypes() {
        return queryAsTenant(() -> jdbc.sql("select message_type from outbox where tenant_id = :t order by id")
                .param("t", tenant).query(String.class).list());
    }

    /** Outbox envelopes re-serialised compactly (Postgres jsonb text output adds spaces after colons). */
    protected List<String> outboxEnvelopes(String messageType) {
        return queryAsTenant(() -> jdbc.sql("""
                        select envelope::text from outbox where tenant_id = :t and message_type = :type order by id""")
                .param("t", tenant).param("type", messageType).query(String.class).list())
                .stream().map(e -> jsonMapper.writeValueAsString(jsonMapper.readTree(e))).toList();
    }
}
