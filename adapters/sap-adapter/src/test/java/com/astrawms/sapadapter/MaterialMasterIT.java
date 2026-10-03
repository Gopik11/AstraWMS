package com.astrawms.sapadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.security.Roles;
import com.astrawms.sapadapter.flow.MasterDataClient;
import com.astrawms.sapadapter.mapping.MatmasMapper;
import com.astrawms.test.AstraContainers;
import com.astrawms.test.AstraMockMvc;
import com.astrawms.test.TestTokens;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** MATMAS05 → item master (ADR-0022): mapping, merge with what the WMS knows, IDoc status 53 / 51. */
@SpringBootTest(properties = "astra.outbox.relay-enabled=false")
@Import(MaterialMasterIT.Stubs.class)
class MaterialMasterIT {

    /** Master data in memory: items by owner/item, as the API returns them. */
    static class StubMasterData implements MasterDataClient {
        final Map<String, JsonNode> items = new ConcurrentHashMap<>();
        final JsonMapper json = JsonMapper.builder().build();

        @Override
        public Optional<JsonNode> getItem(String ownerId, String itemNo) {
            return Optional.ofNullable(items.get(ownerId + "/" + itemNo));
        }

        @Override
        public void putItem(String ownerId, String itemNo, MatmasMapper.Item item) {
            if (item.uoms().stream().anyMatch(u -> "XYZ".equals(u.uom()))) {
                throw com.astrawms.common.web.ApiException.unprocessable("MD_UOM_UNKNOWN", "UoM XYZ is not in the UoM catalogue");
            }
            items.put(ownerId + "/" + itemNo, json.valueToTree(item));
        }
    }

    @TestConfiguration
    static class Stubs {
        @Bean
        @Primary
        StubMasterData stubMasterData() {
            return new StubMasterData();
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry r) {
        AstraContainers.register(r);
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    StubMasterData masterData;

    MockMvc mvc;
    String tenant;

    @BeforeEach
    void setUp() throws Exception {
        mvc = AstraMockMvc.create(context);
        tenant = "t-" + UUID.randomUUID().toString().substring(0, 8);
        call(put("/api/v1/sap/site-map/1000"), """
                {"siteId":"DC1","timeZone":"America/Chicago","defaultOwner":"ACME"}""").andExpect(status().isNoContent());
        call(put("/api/v1/sap/site-map/2000"), """
                {"siteId":"ST01","timeZone":"America/Chicago","defaultOwner":"ACME"}""").andExpect(status().isNoContent());
    }

    static final String MATMAS = """
            {"DOCNUM":"%s","MESTYP":"MATMAS",
             "E1MARAM":{"MATNR":"000000000000100200","MTART":"HAWA","MEINS":"ST","XCHPF":"","MHDHB":365,"MHDRZ":30,
               "STOFF":"",
               "E1MAKTM":[{"SPRAS_ISO":"DE","MAKTX":"Schraube M8"},{"SPRAS_ISO":"EN","MAKTX":"Screw M8 x 40"}],
               "E1MARMM":[{"MEINH":"ST","UMREZ":1,"UMREN":1,"EAN11":"4012345678901"},
                          {"MEINH":"KAR","UMREZ":100,"UMREN":1,"EAN11":"04012345678918","LAENG":300,"BREIT":200,"HOEHE":100,
                           "MEABM":"MM","BRGEW":4500,"GEWEI":"G"},
                          {"MEINH":"%s","UMREZ":10,"UMREN":1}],
               "E1MARCM":[{"WERKS":"1000","XCHPF":"X","SERNP":""},{"WERKS":"2000","SERNP":"Z001"},{"WERKS":"9999"}],
               "E1MBEWM":[{"BWKEY":"1000","VPRSV":"S","STPRS":12.50,"PEINH":10}]}}""";

    @Test
    void materialMasterBecomesTheItemAndKeepsWhatTheWmsKnows() throws Exception {
        masterData.items.put("ACME/100200", masterData.json.readTree("""
                {"temperatureClass":"AMBIENT","sites":[{"siteId":"DC9","lotControlled":false,"serialControl":"NONE","status":"ACTIVE"},
                                                     {"siteId":"DC1","lotControlled":false,"serialControl":"NONE","status":"ACTIVE"}]}"""));
        call(post("/api/v1/sap/idocs/matmas05"), MATMAS.formatted("0000000000005001", "PAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("53")))
                .andExpect(jsonPath("$.statusText", containsString("plant 9999 not mapped")));
        JsonNode item = masterData.items.get("ACME/100200");
        assertThat(item.get("description").asString()).isEqualTo("Screw M8 x 40");
        assertThat(item.get("baseUom").asString()).isEqualTo("EA");
        assertThat(item.get("itemType").asString()).isEqualTo("HAWA");
        assertThat(item.get("shelfLifeDays").asInt()).isEqualTo(365);
        assertThat(item.get("temperatureClass").asString()).isEqualTo("AMBIENT");          // the WMS's own value kept
        assertThat(item.get("standardCost").decimalValue()).isEqualByComparingTo("1.25");   // 12.50 per 10
        JsonNode sites = item.get("sites");
        assertThat(sites).hasSize(3);                                                       // DC1, ST01, and DC9 kept
        assertThat(sites.get(0).get("siteId").asString()).isEqualTo("DC1");
        assertThat(sites.get(0).get("lotControlled").asBoolean()).isTrue();
        assertThat(sites.get(1).get("serialControl").asString()).isEqualTo("FULL");
        JsonNode carton = item.get("uoms").get(1);
        assertThat(carton.get("uom").asString()).isEqualTo("CS");
        assertThat(carton.get("numerator").asInt()).isEqualTo(100);
        assertThat(carton.get("gtin").asString()).isEqualTo("04012345678918");
        assertThat(carton.get("lengthCm").decimalValue()).isEqualByComparingTo(new BigDecimal("30"));
        assertThat(carton.get("grossWeightKg").decimalValue()).isEqualByComparingTo("4.5");

        // A unit the item master does not know: IDoc status 51 with master data's reason.
        call(post("/api/v1/sap/idocs/matmas05"), MATMAS.formatted("0000000000005002", "XYZ"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("MD_UOM_UNKNOWN")));
        call(get("/api/v1/sap/idocs/0000000000005002"), "").andExpect(jsonPath("$.status", is("51")))
                .andExpect(jsonPath("$.vbeln", is("000000000000100200")));
        mvc.perform(post("/api/v1/sap/idocs/matmas05").with(TestTokens.as(tenant, "sue", Roles.SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON).content(MATMAS.formatted("0000000000005003", "PAL")))
                .andExpect(status().isForbidden());
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.with(TestTokens.as(tenant, "middleware", TestTokens.ALL_ROLES))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
