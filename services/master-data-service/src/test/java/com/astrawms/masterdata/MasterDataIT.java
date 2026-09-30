package com.astrawms.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.tenancy.TenantFilter;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "astra.outbox.relay-enabled=false")
class MasterDataIT {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("db/init-roles.sql");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "astra_app");
        r.add("spring.datasource.password", () -> "astra_app_test");
        r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
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
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new TenantFilter()).build();
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

        mvc.perform(get("/api/v1/sites/DC1/locations/A-01-101").header(TenantFilter.TENANT_HEADER, tenant))
                .andExpect(jsonPath("$.checkDigit", matchesPattern("[1-9][0-9]")))
                .andExpect(jsonPath("$.erpBucket", is("0001")));
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

        mvc.perform(get("/api/v1/sites/DC1/locations/L-INHERIT").header(TenantFilter.TENANT_HEADER, tenant))
                .andExpect(jsonPath("$.temperatureClass", is("FROZEN")));
        mvc.perform(get("/api/v1/sites/DC1/locations/L-OWN").header(TenantFilter.TENANT_HEADER, tenant))
                .andExpect(jsonPath("$.temperatureClass", is("CHILLED")));
        JsonNode last = outbox("LocationUpserted").stream()
                .filter(e -> e.get("businessKey").asString().equals("DC1:L-INHERIT")).toList().getLast();
        assertThat(last.get("payload").get("temperatureClass").asString()).isEqualTo("FROZEN");

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
        mvc.perform(get("/api/v1/items/ACME/SKU-9").header(TenantFilter.TENANT_HEADER, tenant))
                .andExpect(status().isNotFound());
        tenant = mine;
        mvc.perform(get("/api/v1/items/ACME/SKU-9").header(TenantFilter.TENANT_HEADER, tenant))
                .andExpect(status().isOk());
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.header(TenantFilter.TENANT_HEADER, tenant).header(TenantFilter.USER_HEADER, "md-admin")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private List<JsonNode> outbox(String type) {
        return TenantContext.callAs(new TenantContext.Scope(tenant, "test", "TEST"), () -> tx.execute(s ->
                jdbc.sql("select envelope::text from outbox where tenant_id = :t and message_type = :type order by id")
                        .param("t", tenant).param("type", type).query(String.class).list()
                        .stream().map(json::readTree).toList()));
    }
}
