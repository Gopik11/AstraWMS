package com.astrawms.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.contracts.PlatformContracts;
import com.astrawms.common.contracts.PlatformContracts.TenantChanged;
import com.astrawms.common.security.Roles;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.tenancy.TenantRegistry;
import com.astrawms.masterdata.platform.TenantDirectory;
import com.astrawms.test.AstraContainers;
import com.astrawms.test.AstraMockMvc;
import com.astrawms.test.TestTokens;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * The tenant registry end to end (ADR-0030 A): the platform API changes a tenant, the change travels through the
 * outbox and the compacted tenants topic into this service's TenantRegistry, and TenantFilter then admits or refuses
 * the tenant's requests.
 */
@SpringBootTest
class PlatformIT {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry r) {
        AstraContainers.register(r);
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    TenantRegistry registry;
    @Autowired
    TenantDirectory directory;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TransactionTemplate tx;

    MockMvc mvc;
    String tenant;
    final RequestPostProcessor platform = TestTokens.bearer(TestTokens.token().user("ops").roles(Roles.PLATFORM_ADMIN).sign());

    @BeforeEach
    void setUp() {
        mvc = AstraMockMvc.create(context);
        tenant = "t-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void reportAgain() {
        registry.mode(TenantRegistry.Mode.REPORT);
    }

    private org.springframework.test.web.servlet.ResultActions call(MockHttpServletRequestBuilder r, RequestPostProcessor who,
                                                                    String body) throws Exception {
        return mvc.perform(r.with(who).contentType(MediaType.APPLICATION_JSON).content(body == null ? "" : body));
    }

    private RequestPostProcessor userOf(String t) {
        return TestTokens.as(t, "md-admin", TestTokens.ALL_ROLES);
    }

    private void awaitStatus(String t, String status) throws InterruptedException {
        await(() -> registry.loaded() && registry.tenant(t).map(TenantChanged::status).map(status::equals).orElse(false));
    }

    @Test
    void onlyPlatformAdminsManageTenantsAndServicesMayReadThem() throws Exception {
        call(get("/api/v1/platform/tenants"), userOf(tenant), null).andExpect(status().isForbidden());
        call(post("/api/v1/platform/tenants"), userOf(tenant), "{\"tenantId\":\"X1\"}").andExpect(status().isForbidden());
        call(get("/api/v1/platform/tenants"), TestTokens.service("astra-task"), null).andExpect(status().isOk());
        call(post("/api/v1/platform/tenants"), TestTokens.service("astra-task"), "{\"tenantId\":\"X1\"}")
                .andExpect(status().isForbidden());
        call(post("/api/v1/platform/tenants"), platform, "{\"tenantId\":\"bad id!\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("PLATFORM_TENANT_ID_INVALID")));
    }

    @Test
    void aTenantIsRegisteredSuspendedOffboardedAndReactivatedAndEveryServiceFollows() throws Exception {
        call(post("/api/v1/platform/tenants"), platform, """
                {"tenantId":"%s","displayName":"Acme Logistics","timeZone":"Europe/Berlin","reportingCurrency":"EUR"}"""
                .formatted(tenant))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("ACTIVE"))).andExpect(jsonPath("$.reporting_currency", is("EUR")));
        call(post("/api/v1/platform/tenants"), platform, "{\"tenantId\":\"%s\"}".formatted(tenant))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("PLATFORM_TENANT_EXISTS")));
        awaitStatus(tenant, PlatformContracts.ACTIVE);
        registry.mode(TenantRegistry.Mode.ENFORCE);

        String unknown = "t-" + UUID.randomUUID().toString().substring(0, 8);
        call(get("/api/v1/items/ACME"), userOf(unknown), null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("TENANT_UNKNOWN")));
        call(get("/api/v1/items/ACME"), userOf(tenant), null).andExpect(status().isOk());

        // suspended: refused everywhere, with a recorded reason
        call(post("/api/v1/platform/tenants/" + tenant + "/status"), platform, "{\"status\":\"SUSPENDED\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("PLATFORM_REASON_REQUIRED")));
        call(post("/api/v1/platform/tenants/" + tenant + "/status"), platform,
                "{\"status\":\"SUSPENDED\",\"reason\":\"Invoice overdue\"}").andExpect(jsonPath("$.status", is("SUSPENDED")));
        awaitStatus(tenant, PlatformContracts.SUSPENDED);
        call(get("/api/v1/items/ACME"), userOf(tenant), null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("TENANT_SUSPENDED")));
        // service calls acting for it too
        mvc.perform(get("/api/v1/items/ACME").with(TestTokens.service("astra-task")).header("X-Tenant-Id", tenant))
                .andExpect(status().isForbidden());

        // offboarding: readable, not changeable
        call(post("/api/v1/platform/tenants/" + tenant + "/status"), platform,
                "{\"status\":\"OFFBOARDING\",\"reason\":\"Contract ended\"}").andExpect(status().isOk());
        awaitStatus(tenant, PlatformContracts.OFFBOARDING);
        call(get("/api/v1/items/ACME"), userOf(tenant), null).andExpect(status().isOk());
        call(put("/api/v1/sites/DC1"), userOf(tenant), "{\"name\":\"Dallas DC\",\"timeZone\":\"America/Chicago\",\"erpSite\":\"1000\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("TENANT_OFFBOARDING")));

        // purging is not the API's; reactivation is
        call(post("/api/v1/platform/tenants/" + tenant + "/status"), platform, "{\"status\":\"PURGED\",\"reason\":\"x\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("PLATFORM_STATUS_TRANSITION")));
        call(post("/api/v1/platform/tenants/" + tenant + "/status"), platform,
                "{\"status\":\"ACTIVE\",\"reason\":\"Renewed\"}").andExpect(status().isOk());
        awaitStatus(tenant, PlatformContracts.ACTIVE);
        call(put("/api/v1/sites/DC1"), userOf(tenant), "{\"name\":\"Dallas DC\",\"timeZone\":\"America/Chicago\",\"erpSite\":\"1000\"}")
                .andExpect(status().isNoContent());

        call(get("/api/v1/platform/tenants/" + tenant + "/history"), platform, null)
                .andExpect(jsonPath("$[*].to_status", org.hamcrest.Matchers.contains("ACTIVE", "SUSPENDED", "OFFBOARDING", "ACTIVE")))
                .andExpect(jsonPath("$[1].reason", is("Invoice overdue")))
                .andExpect(jsonPath("$[1].changed_by", is("ops")));
        // the Operations page shows the registry
        call(get("/api/v1/ops/status"), userOf(tenant), null)
                .andExpect(jsonPath("$.tenantRegistry.mode", is("ENFORCE")))
                .andExpect(jsonPath("$.tenantRegistry.loaded", is(true)));
    }

    @Test
    void tenantsRegisteredByTheMigrationArePublishedAtStartAndAfterwards() throws Exception {
        // as the migration registers the tenants master data already holds: a version not yet published
        TenantContext.runAs(new TenantContext.Scope(tenant, "test", "TEST"), () -> tx.executeWithoutResult(s -> jdbc.sql("""
                        insert into tenant (tenant_id, display_name, status, created_at, created_by, status_changed_at,
                                            status_changed_by, status_reason)
                        values (:t, :t, 'ACTIVE', now(), 'migration', now(), 'migration', 'seeded')""")
                .param("t", tenant).update()));
        directory.publishAllPending();
        awaitStatus(tenant, PlatformContracts.ACTIVE);
        List<Long> published = jdbc.sql("select published_version from tenant where tenant_id = :t").param("t", tenant)
                .query(Long.class).list();
        assertThat(published).containsExactly(1L);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Condition not met within 30 s");
    }
}
