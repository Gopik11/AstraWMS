package com.astrawms.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.security.Roles;
import com.astrawms.common.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import com.astrawms.test.AstraContainers;
import com.astrawms.test.AstraMockMvc;
import com.astrawms.test.TestTokens;
import com.jayway.jsonpath.JsonPath;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "astra.outbox.relay-enabled=false")
class MasterDataIT {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry r) {
        AstraContainers.register(r);
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JsonMapper json;

    MockMvc mvc;
    String tenant;

    @BeforeEach
    void setUp() throws Exception {
        mvc = AstraMockMvc.create(context);
        tenant = "t-" + UUID.randomUUID().toString().substring(0, 8);
        call(put("/api/v1/sites/DC1"), """
                {"name":"Dallas DC","timeZone":"America/Chicago","erpSite":"1000"}""").andExpect(status().isNoContent());
        call(put("/api/v1/sites/DC1/zones/STOR"), """
                {"zoneType":"RESERVE","erpBucket":"0001"}""").andExpect(status().isOk());
    }

    private static final String ITEM = """
            {"description":"Bluetooth Speaker","baseUom":"EA","status":"ACTIVE",
             "sites":[{"siteId":"DC1","lotControlled":false,"serialControl":"NONE"}],
             "uoms":[{"uom":"CS","numerator":12,"denominator":1,"gtin":"%s"}]}""";

    @Test
    void itemUpsertPublishesEventAndAddsBaseUom_IFMD001() throws Exception {
        call(put("/api/v1/items/ACME/SKU-1"), ITEM.formatted("10614141000415"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.uoms", hasSize(2)))
                .andExpect(jsonPath("$.uoms[0].uom", is("EA")))
                .andExpect(jsonPath("$.source", is("API")));

        List<JsonNode> events = outbox("ItemUpserted");
        assertThat(events).hasSize(1);
        JsonNode e = events.getFirst();
        assertThat(e.get("businessKey").asString()).isEqualTo("ACME:SKU-1");
        assertThat(e.get("payload").get("uoms")).hasSize(2);
        assertThat(e.get("payload").get("sites").get(0).get("siteId").asString()).isEqualTo("DC1");
    }

    @Test
    void optimisticConcurrencyWithIfMatch_NFR124() throws Exception {
        call(put("/api/v1/items/ACME/SKU-2"), ITEM.formatted("4006381333931")).andExpect(status().isOk());
        call(put("/api/v1/items/ACME/SKU-2").header("If-Match", "\"0\""), ITEM.formatted("4006381333931"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
        call(put("/api/v1/items/ACME/SKU-2").header("If-Match", "\"0\""), ITEM.formatted("4006381333931"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code", is("MD_VERSION_MISMATCH")));
    }

    @Test
    void uomAndGtinValidation_MD001() throws Exception {
        call(put("/api/v1/items/ACME/SKU-3"), ITEM.formatted("4006381333932"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("MD_GTIN_INVALID")));
        call(put("/api/v1/items/ACME/SKU-3"), ITEM.formatted("4006381333931").replace("\"CS\"", "\"XX\""))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("MD_UOM_UNKNOWN")));
        call(put("/api/v1/items/ACME/SKU-3"), ITEM.formatted("4006381333931").replace("\"DC1\"", "\"NOPE\""))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("MD_SITE_UNKNOWN")));

        call(put("/api/v1/items/ACME/SKU-3"), ITEM.formatted("96385074")).andExpect(status().isOk());
        call(put("/api/v1/items/ACME/SKU-4"), ITEM.formatted("96385074"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("MD_GTIN_DUPLICATE")));
        assertThat(outbox("ItemUpserted")).hasSize(1); // failed upserts published nothing
    }

    @Test
    void locationGenerationIsIdempotentAndPublishesOnlyChanges() throws Exception {
        String generate = """
                {"aisles":["A","B"],"bayFrom":1,"bayTo":3,"levels":["1","2"],"positionFrom":1,"positionTo":2,
                 "pattern":"{aisle}-{bay}-{level}{position}","locationType":"RACK"}""";
        call(post("/api/v1/sites/DC1/zones/STOR/locations/generate"), generate)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created", is(24)))
                .andExpect(jsonPath("$.sampleIds[0]", is("A-01-101")));
        assertThat(outbox("LocationUpserted")).hasSize(24);

        call(post("/api/v1/sites/DC1/zones/STOR/locations/generate"), generate)
                .andExpect(jsonPath("$.created", is(0)))
                .andExpect(jsonPath("$.unchanged", is(24)));
        assertThat(outbox("LocationUpserted")).hasSize(24);

        mvc.perform(get("/api/v1/sites/DC1/locations/A-01-101").with(TestTokens.as(tenant, "md-admin", TestTokens.ALL_ROLES)))
                .andExpect(jsonPath("$.checkDigit", matchesPattern("[1-9][0-9]")))
                .andExpect(jsonPath("$.erpBucket", is("0001")))
                .andExpect(jsonPath("$.zoneType", is("RESERVE")));

        // Republish (ADR-0019): every location again, so projections built before 1.2 learn the zone type.
        call(post("/api/v1/sites/DC1/locations/republish"), "").andExpect(jsonPath("$.locationsRepublished", is(24)));
        assertThat(outbox("LocationUpserted")).hasSize(48);
    }

    @Test
    void zoneChangesPropagateToInheritingLocationsOnly() throws Exception {
        call(put("/api/v1/sites/DC1/locations/L-INHERIT"), """
                {"zoneId":"STOR","locationType":"RACK"}""").andExpect(status().isOk());
        call(put("/api/v1/sites/DC1/locations/L-OWN"), """
                {"zoneId":"STOR","locationType":"RACK","temperatureClass":"CHILLED"}""").andExpect(status().isOk());

        call(put("/api/v1/sites/DC1/zones/STOR"), """
                {"zoneType":"RESERVE","erpBucket":"0001","temperatureClass":"FROZEN"}""")
                .andExpect(jsonPath("$.locationsRepublished", is(2)));

        mvc.perform(get("/api/v1/sites/DC1/locations/L-INHERIT").with(TestTokens.as(tenant, "md-admin", TestTokens.ALL_ROLES)))
                .andExpect(jsonPath("$.temperatureClass", is("FROZEN")));
        mvc.perform(get("/api/v1/sites/DC1/locations/L-OWN").with(TestTokens.as(tenant, "md-admin", TestTokens.ALL_ROLES)))
                .andExpect(jsonPath("$.temperatureClass", is("CHILLED")));
        JsonNode last = outbox("LocationUpserted").stream()
                .filter(e -> e.get("businessKey").asString().equals("DC1:L-INHERIT")).toList().getLast();
        assertThat(last.get("payload").get("temperatureClass").asString()).isEqualTo("FROZEN");
        assertThat(last.get("payload").get("zoneType").asString()).isEqualTo("RESERVE");   // 1.2, ADR-0019

        // Unchanged zone update republishes nothing.
        call(put("/api/v1/sites/DC1/zones/STOR"), """
                {"zoneType":"RESERVE","erpBucket":"0001","temperatureClass":"FROZEN"}""")
                .andExpect(jsonPath("$.locationsRepublished", is(0)));
    }

    @Test
    void itemsAreTenantIsolated() throws Exception {
        call(put("/api/v1/items/ACME/SKU-9"), ITEM.formatted("036000291452")).andExpect(status().isOk());
        String mine = tenant;
        tenant = "t-intruder";
        mvc.perform(get("/api/v1/items/ACME/SKU-9").with(TestTokens.as(tenant, "md-admin", TestTokens.ALL_ROLES)))
                .andExpect(status().isNotFound());
        tenant = mine;
        mvc.perform(get("/api/v1/items/ACME/SKU-9").with(TestTokens.as(tenant, "md-admin", TestTokens.ALL_ROLES)))
                .andExpect(status().isOk());
    }

    @Test
    void standardCostIsStoredAndPublished() throws Exception {
        call(put("/api/v1/items/ACME/SKU-COST"), ITEM.formatted("036000291452").replace("\"status\":\"ACTIVE\",",
                "\"status\":\"ACTIVE\",\"standardCost\":12.5,"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.standardCost", is(12.5)));
        JsonNode event = outbox("ItemUpserted").getLast();
        assertThat(event.get("payload").get("standardCost").decimalValue()).isEqualByComparingTo("12.5");
        assertThat(event.get("schemaVersion").asString()).isEqualTo("1.3");
    }

    @Test
    void itemsOfOtherOwnersAreOutOfScope_G5() throws Exception {
        mvc.perform(get("/api/v1/items/ACME").with(TestTokens.bearer(TestTokens.token().tenant(tenant).user("beta")
                        .roles(Roles.RECEIVER).owners("BETA").sign())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("SCOPE_OWNER_DENIED")));
    }

    @Test
    void onlySolutionAdminsChangeMasterData_G5() throws Exception {
        mvc.perform(put("/api/v1/sites/DC1/locations/L-X").with(TestTokens.as(tenant, "rita", Roles.RECEIVER, Roles.SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"zoneId":"STOR","locationType":"RACK"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("FORBIDDEN")));
        mvc.perform(get("/api/v1/items/ACME").with(TestTokens.as(tenant, "rita", Roles.RECEIVER)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ ADR-0022 labels

    @Test
    void locationItemAndLpnLabelsAreZplAndGoToTheSitePrinter() throws Exception {
        call(post("/api/v1/sites/DC1/zones/STOR/locations/generate"), """
                {"aisles":["A"],"bayFrom":1,"bayTo":2,"levels":["1"],"positionFrom":1,"positionTo":1,
                 "pattern":"{aisle}-{bay}-{level}{position}","locationType":"RACK"}""").andExpect(status().isOk());
        String checkDigit = JsonPath.read(mvc.perform(get("/api/v1/sites/DC1/locations/A-01-101")
                .with(TestTokens.as(tenant, "md-admin", TestTokens.ALL_ROLES))).andReturn().getResponse().getContentAsString(),
                "$.checkDigit");
        call(post("/api/v1/sites/DC1/labels/locations"), "{\"zoneId\":\"STOR\"}")
                .andExpect(jsonPath("$.count", is(2)))
                .andExpect(jsonPath("$.labels[0].barcode", is("A-01-101")))
                .andExpect(jsonPath("$.labels[0].lines[1]", is("Check " + checkDigit)))
                .andExpect(jsonPath("$.zpl", containsString("^BCN,150,N,N,N^FDA-01-101^FS")));

        call(put("/api/v1/items/ACME/SKU-1"), ITEM.formatted("10614141000415")).andExpect(status().isOk());
        call(post("/api/v1/sites/DC1/labels/items"), """
                {"ownerId":"ACME","itemNos":["SKU-1"],"uom":"CS","copies":2}""")
                .andExpect(jsonPath("$.count", is(2)))
                .andExpect(jsonPath("$.labels[0].symbology", is("GS1-128")))
                .andExpect(jsonPath("$.labels[0].barcode", is("(01)10614141000415")));
        call(post("/api/v1/sites/DC1/labels/items"), """
                {"ownerId":"ACME","itemNos":["SKU-1"]}""")
                .andExpect(jsonPath("$.labels[0].symbology", is("CODE128")))          // the base unit has no GTIN
                .andExpect(jsonPath("$.labels[0].barcode", is("SKU-1")));

        call(post("/api/v1/sites/DC1/labels/lpns"), "{\"count\":2}")
                .andExpect(jsonPath("$.labels[0].barcode", is("LDC1000000001")))
                .andExpect(jsonPath("$.labels[1].barcode", is("LDC1000000002")));
        call(post("/api/v1/sites/DC1/labels/lpns"), "{\"count\":1}")
                .andExpect(jsonPath("$.labels[0].barcode", is("LDC1000000003")));       // never reused

        // A network printer on its raw port receives the ZPL.
        call(put("/api/v1/sites/DC1/labels/printers/p1"), "{\"host\":\"127.0.0.1\",\"port\":5432}")
                .andExpect(jsonPath("$.code", is("MD_PRINTER_INVALID")));               // not a print port
        try (java.net.ServerSocket printer = new java.net.ServerSocket(9107)) {
            java.util.concurrent.CompletableFuture<String> received = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (java.net.Socket c = printer.accept()) {
                    return new String(c.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            call(put("/api/v1/sites/DC1/labels/printers/dock-zebra"), """
                    {"host":"127.0.0.1","port":9107,"purpose":"LPN"}""").andExpect(jsonPath("$[0].name", is("DOCK-ZEBRA")));
            call(post("/api/v1/sites/DC1/labels/lpns"), "{\"count\":1,\"printer\":\"DEFAULT\"}")
                    .andExpect(jsonPath("$.printedOn", is("DOCK-ZEBRA")));
            assertThat(received.get(5, java.util.concurrent.TimeUnit.SECONDS)).contains("^XA", "LDC1000000004", "^XZ");
        }
        mvc.perform(post("/api/v1/sites/DC1/labels/lpns").with(TestTokens.as(tenant, "pete", Roles.PICKER))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.with(TestTokens.as(tenant, "md-admin", TestTokens.ALL_ROLES))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private List<JsonNode> outbox(String type) {
        return TenantContext.callAs(new TenantContext.Scope(tenant, "test", "TEST"), () -> tx.execute(s ->
                jdbc.sql("select envelope::text from outbox where tenant_id = :t and message_type = :type order by id")
                        .param("t", tenant).param("type", type).query(String.class).list()
                        .stream().map(json::readTree).toList()));
    }
}
