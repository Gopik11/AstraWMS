package com.astrawms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.contracts.MasterDataEvents.ItemUpserted;
import com.astrawms.common.contracts.MasterDataEvents.LocationUpserted;
import com.astrawms.common.security.AccessScope;
import com.astrawms.common.security.Roles;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.inventory.support.IntegrationTest;
import com.astrawms.test.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Owner isolation on the other inventory tables (ADR-0030 C, increment 2, inventory V22): every table with owner_id
 * has a restrictive owner_isolation policy; shared defaults and shared tags keep working for every owner; and the checks
 * that must see every owner (location counts, issue numbering, RFID tag identity, pick faces) still do.
 */
class OwnerIsolationInventoryIT extends IntegrationTest {

    private static final String BETA = "BETA";

    @BeforeEach
    void secondOwnerAndValuedItem() {
        asTenant(() -> refs.upsertItem(new ItemUpserted(BETA, "SKU-B", "EA", "ACTIVE", null, null, false,
                List.of(new ItemUpserted.Site(SITE, false, "NONE", "ACTIVE")), List.of(), Instant.now())));
        asTenant(() -> refs.upsertItem(new ItemUpserted(OWNER, "SKU-VAL", "EA", "ACTIVE", null, null, false,
                List.of(new ItemUpserted.Site(SITE, false, "NONE", "ACTIVE")), List.of(), Instant.now(), new BigDecimal("10"))));
    }

    private ResultActions call(String method, String path, RequestPostProcessor who, String key, String json) throws Exception {
        var r = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), "/api/v1/sites/" + SITE + "/inventory" + path)
                .with(who).contentType(MediaType.APPLICATION_JSON).content(json == null ? "" : json);
        if (key != null) {
            r.header("Idempotency-Key", key);
        }
        return mvc.perform(r);
    }

    private RequestPostProcessor beta(String user, String... roles) {
        return TestTokens.bearer(TestTokens.token().tenant(tenant).user(user).roles(roles).owners(BETA).sign());
    }

    private RequestPostProcessor anyOwner(String user, String... roles) {
        return TestTokens.as(tenant, user, roles);
    }

    private <T> T asOwners(Set<String> owners, Supplier<T> work) {
        return TenantContext.callAs(new TenantContext.Scope(tenant, "scoped", "TEST", new AccessScope(null, owners, null, null)),
                () -> tx.execute(s -> work.get()));
    }

    // ------------------------------------------------------------------ the policies

    @Test
    void everyInventoryTableWithAnOwnerHasARestrictiveOwnerPolicy() {
        List<String> missing = queryAsTenant(() -> jdbc.sql("""
                        select c.table_name from information_schema.columns c
                        where c.table_schema = 'public' and c.column_name = 'owner_id'
                          and not exists (select 1 from pg_policies p where p.schemaname = 'public'
                                            and p.tablename = c.table_name and p.policyname = 'owner_isolation'
                                            and p.permissive = 'RESTRICTIVE')
                        order by 1""").query(String.class).list());
        assertThat(missing).isEmpty();
    }

    @Test
    void anOwnerScopedSessionSeesItsOwnersItemsAndTheSharedDefaultsButWritesNoDefault() {
        assertThat(asOwners(Set.of(BETA), () -> jdbc.sql("select distinct owner_id from ref_item order by 1")
                .query(String.class).list())).containsExactly(BETA);
        // a site default allocation policy and a default billing rate, set by an unrestricted user
        queryAsTenant(() -> jdbc.sql("""
                        insert into allocation_policy (tenant_id, site_id, owner_id, lot_rotation, other_rotation,
                            pick_face_first, full_lpn, updated_by, updated_at)
                        values (:t, 'DC1', '', 'FEFO', 'FIFO', true, 'SPLIT_ALLOWED', 'admin', now())""")
                .param("t", tenant).update());
        queryAsTenant(() -> jdbc.sql("""
                        insert into billing_rate (tenant_id, site_id, owner_id, event_type, basis, rate, updated_by, updated_at)
                        values (:t, 'DC1', '', 'PICK', 'LINE', 0.5, 'admin', now())""").param("t", tenant).update());
        assertThat(asOwners(Set.of(BETA), () -> jdbc.sql("select count(*) from allocation_policy where owner_id = ''")
                .query(Integer.class).single())).isEqualTo(1);
        assertThat(asOwners(Set.of(BETA), () -> jdbc.sql("select count(*) from billing_rate where owner_id = ''")
                .query(Integer.class).single())).isEqualTo(1);
        // an owner-scoped user changes its own rate, not what applies to every other owner
        asOwners(Set.of(BETA), () -> jdbc.sql("""
                        insert into billing_rate (tenant_id, site_id, owner_id, event_type, basis, rate, updated_by, updated_at)
                        values (:t, 'DC1', 'BETA', 'PICK', 'LINE', 0.4, 'beta', now())""").param("t", tenant).update());
        assertThatThrownBy(() -> asOwners(Set.of(BETA), () -> jdbc.sql(
                        "update billing_rate set rate = 0 where owner_id = ''").update()))
                .hasStackTraceContaining("row-level security");
        assertThatThrownBy(() -> asOwners(Set.of(BETA), () -> jdbc.sql("""
                        insert into billing_rate (tenant_id, site_id, owner_id, event_type, basis, rate, updated_by, updated_at)
                        values (:t, 'DC1', '', 'RECEIPT', 'LINE', 0, 'beta', now())""").param("t", tenant).update()))
                .hasStackTraceContaining("row-level security");
    }

    @Test
    void lpnAndLocationTagsStayVisibleAndWritableForEveryOwner() {
        asOwners(Set.of(BETA), () -> jdbc.sql("""
                        insert into rfid_tag (tenant_id, epc, scheme, site_id, location_id, status, commissioned_by, commissioned_at)
                        values (:t, 'E28011606000020000000AAA', 'RAW', 'DC1', 'A-01-01', 'ACTIVE', 'beta', now())""")
                .param("t", tenant).update());
        assertThat(asOwners(Set.of("ACME"), () -> jdbc.sql("select count(*) from rfid_tag where location_id = 'A-01-01'")
                .query(Integer.class).single())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ checks that see every owner

    @Test
    void aLocationCountNeedsAUserAuthorisedForEveryOwnerInTheLocation() throws Exception {
        receive("SKU-VAL", "20", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        post("/receipts", """
                {"ownerId":"BETA","itemNo":"SKU-B","qty":3,"uom":"EA","locationId":"A-01-01"}""").andExpect(status().isCreated());
        post("/receipts", """
                {"ownerId":"BETA","itemNo":"SKU-B","qty":3,"uom":"EA","locationId":"A-01-02"}""").andExpect(status().isCreated());

        // a BETA counter cannot count a location that also holds ACME's stock: ACME would be judged missing
        String mixed = openCount("A-01-01");
        call("POST", "/counts/" + mixed + "/results", beta("bea", Roles.PICKER), "b-1", """
                {"lines":[{"ownerId":"BETA","itemNo":"SKU-B","qty":3}]}""")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("INV_COUNT_OWNER_SCOPE")))
                .andExpect(jsonPath("$.detail", not(containsString("ACME"))));
        assertThat(onHand("SKU-VAL", "A-01-01", "AVAILABLE")).isEqualByComparingTo("20");
        // a location with BETA's stock only, it can
        String own = openCount("A-01-02");
        call("POST", "/counts/" + own + "/results", beta("bea", Roles.PICKER), "b-2", """
                {"lines":[{"ownerId":"BETA","itemNo":"SKU-B","qty":3}]}""")
                .andExpect(jsonPath("$.status", is("CLOSED")));

        // the mixed location counted by unrestricted counters: a BETA approver may not decide it, an unrestricted may
        String acme = "{\"ownerId\":\"ACME\",\"itemNo\":\"SKU-VAL\",\"qty\":12}";
        String b = "{\"ownerId\":\"BETA\",\"itemNo\":\"SKU-B\",\"qty\":3}";
        call("POST", "/counts/" + mixed + "/results", anyOwner("cathy", Roles.PICKER), "c-1", "{\"lines\":[" + acme + "," + b + "]}")
                .andExpect(jsonPath("$.status", is("RECOUNT")));
        call("POST", "/counts/" + mixed + "/results", anyOwner("dave", Roles.PICKER), "c-2", "{\"lines\":[" + acme + "," + b + "]}")
                .andExpect(jsonPath("$.status", is("PENDING_APPROVAL")));
        call("POST", "/counts/" + mixed + "/approve", beta("bob", Roles.INV_MANAGER), null, "{}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("INV_COUNT_OWNER_SCOPE")));
        assertThat(onHand("SKU-VAL", "A-01-01", "AVAILABLE")).isEqualByComparingTo("20");
        call("POST", "/counts/" + mixed + "/approve", anyOwner("mona", Roles.INV_MANAGER), null, "{}")
                .andExpect(jsonPath("$.status", is("ADJUSTED")));
        assertThat(onHand("SKU-VAL", "A-01-01", "AVAILABLE")).isEqualByComparingTo("12");
        assertThat(onHand("SKU-B", "A-01-01", "AVAILABLE")).isEqualByComparingTo("3");
    }

    private String openCount(String location) throws Exception {
        String body = call("POST", "/counts", anyOwner("sue", Roles.SUPERVISOR), null, """
                {"locationIds":["%s"],"note":"owner isolation"}""".formatted(location))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.countIds[0]");
    }

    @Test
    void materialIssueNumbersStayUniqueAcrossOwners() throws Exception {
        call("PUT", "/cost-objects/COST_CENTER/CC100", anyOwner("ada", Roles.SOLUTION_ADMIN), null,
                "{\"description\":\"Maintenance\",\"department\":\"MAINT\"}").andExpect(status().isOk());
        call("POST", "/material-issues", anyOwner("rita", Roles.RECEIVER), null, """
                {"ownerId":"ACME","objectType":"COST_CENTER","objectCode":"CC100","recipient":"crew",
                 "lines":[{"itemNo":"SKU-EA","qty":1,"uom":"EA"}]}""")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.issue_no", is("MI000001")));
        // BETA cannot see ACME's issue, but its number is still taken
        call("POST", "/material-issues", beta("bea", Roles.RECEIVER), null, """
                {"ownerId":"BETA","objectType":"COST_CENTER","objectCode":"CC100","recipient":"crew",
                 "lines":[{"itemNo":"SKU-B","qty":1,"uom":"EA"}]}""")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.issue_no", is("MI000002")));
    }

    @Test
    void tagIdentitySeesEveryOwnerWithoutDisclosingIt() throws Exception {
        receive("SKU-EA", "5", "EA", "A-01-01", "LPN-ACME", null).andExpect(status().isCreated());
        post("/receipts", """
                {"ownerId":"BETA","itemNo":"SKU-B","qty":3,"uom":"EA","locationId":"A-01-02","lpnId":"LPN-BETA"}""")
                .andExpect(status().isCreated());
        RequestPostProcessor bea = beta("bea", TestTokens.ALL_ROLES);
        String acmeTag = "E2801160600002000000000C";
        call("POST", "/rfid/tags", anyOwner("operator1", TestTokens.ALL_ROLES), "t-1", """
                {"epc":"%s","ownerId":"ACME","itemNo":"SKU-EA"}""".formatted(acmeTag)).andExpect(status().isCreated());

        // ACME's LPN is refused, not taken for an LPN that does not exist yet
        call("POST", "/rfid/tags", bea, "t-2", """
                {"epc":"E2801160600002000000000D","lpnId":"LPN-ACME"}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("INV_LPN_OWNER_MISMATCH")))
                .andExpect(jsonPath("$.detail", containsString("another owner")));
        // ACME's active tag cannot be rebound to BETA's LPN, and BETA is not told what it is for
        call("POST", "/rfid/tags", bea, "t-3", """
                {"epc":"%s","lpnId":"LPN-BETA"}""".formatted(acmeTag))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INV_RFID_TAG_IN_USE")))
                .andExpect(jsonPath("$.detail", containsString("another owner")))
                .andExpect(jsonPath("$.detail", not(containsString("SKU-EA"))));
        // and BETA cannot read ACME's tag itself
        call("GET", "/rfid/tags/" + acmeTag, bea, null, null).andExpect(status().isNotFound());
    }

    @Test
    void aPickFaceOfAnotherOwnersItemIsTaken() throws Exception {
        asTenant(() -> refs.upsertLocation(new LocationUpserted(SITE, "P-01", "PICK", "SHELF", "0001", null, false, true, true,
                "ACTIVE", Instant.now(), "10", 1, "PICK")));
        call("PUT", "/replenishment-rules/P-01/ACME/SKU-EA", anyOwner("ada", Roles.SOLUTION_ADMIN), null,
                "{\"minQty\":1,\"maxQty\":20}").andExpect(status().isOk());
        call("POST", "/slotting/BETA/SKU-B/reslot", beta("bert", Roles.SOLUTION_ADMIN), null,
                "{\"toLocation\":\"P-01\",\"minQty\":1,\"maxQty\":10}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INV_RESLOT_LOCATION_TAKEN")));
    }
}
