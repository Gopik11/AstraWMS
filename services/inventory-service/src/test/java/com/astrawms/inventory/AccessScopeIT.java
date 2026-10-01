package com.astrawms.inventory;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.contracts.MasterDataEvents.ItemUpserted;
import com.astrawms.common.security.Roles;
import com.astrawms.inventory.support.IntegrationTest;
import com.astrawms.test.TestTokens;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Attribute scopes on roles (§G.5.1, NFR-101): sites, owners (3PL clients) and approval value limits. */
class AccessScopeIT extends IntegrationTest {

    private static final String BALANCES = "/api/v1/sites/DC1/inventory/balances";

    @BeforeEach
    void ownersAndValuedItem() {
        asTenant(() -> refs.upsertItem(new ItemUpserted("BETA", "SKU-B", "EA", "ACTIVE", null, null, false,
                List.of(new ItemUpserted.Site(SITE, false, "NONE", "ACTIVE")), List.of(), Instant.now())));
        asTenant(() -> refs.upsertItem(new ItemUpserted(OWNER, "SKU-VAL", "EA", "ACTIVE", null, null, false,
                List.of(new ItemUpserted.Site(SITE, false, "NONE", "ACTIVE")), List.of(), Instant.now(),
                new BigDecimal("10"))));
    }

    private RequestPostProcessor user(TestTokens.Builder token) {
        return TestTokens.bearer(token.tenant(tenant).sign());
    }

    @Test
    void siteScopeAppliesToReadsAndWrites() throws Exception {
        mvc.perform(get(BALANCES).with(user(TestTokens.token().user("dc2").roles(Roles.SUPERVISOR).sites("DC2"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("SCOPE_SITE_DENIED")));
        mvc.perform(get(BALANCES).with(user(TestTokens.token().user("both").roles(Roles.SUPERVISOR).sites("DC1", "DC2"))))
                .andExpect(status().isOk());
    }

    @Test
    void missingScopeClaimsGrantNothing() throws Exception {
        mvc.perform(get(BALANCES).with(user(TestTokens.token().user("bare").roles(Roles.SUPERVISOR).sites((String[]) null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("SCOPE_SITE_DENIED")));
    }

    @Test
    void threePlClientSeesAndTouchesOnlyItsOwnStock() throws Exception {
        receive("SKU-EA", "5", "EA", "A-01-01", "LPN-ACME", null).andExpect(status().isCreated());
        post("/receipts", """
                {"ownerId":"BETA","itemNo":"SKU-B","qty":3,"uom":"EA","locationId":"A-01-02","lpnId":"LPN-BETA"}""")
                .andExpect(status().isCreated());
        RequestPostProcessor beta = user(TestTokens.token().user("beta-clerk").roles(Roles.RECEIVER).owners("BETA"));

        mvc.perform(get(BALANCES).with(beta))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[*].ownerId", everyItem(is("BETA"))));
        mvc.perform(get("/api/v1/sites/DC1/inventory/transactions").with(beta))
                .andExpect(jsonPath("$.items[*].ownerId", everyItem(is("BETA"))));
        mvc.perform(get("/api/v1/sites/DC1/inventory/lpns/LPN-ACME").with(beta))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/sites/DC1/inventory/items/ACME/SKU-EA/summary").with(beta))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("SCOPE_OWNER_DENIED")));
        postWith(beta, "/receipts", "beta-acme", """
                {"ownerId":"ACME","itemNo":"SKU-EA","qty":1,"uom":"EA","locationId":"A-01-01"}""", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("SCOPE_OWNER_DENIED")));
        postWith(beta, "/moves", "beta-move", """
                {"fromLocationId":"A-01-01","lpnId":"LPN-ACME","toLocationId":"A-01-02"}""", null)
                .andExpect(status().isForbidden());
        postWith(beta, "/receipts", "beta-own", """
                {"ownerId":"BETA","itemNo":"SKU-B","qty":1,"uom":"EA","locationId":"A-01-02","lpnId":"LPN-BETA"}""", null)
                .andExpect(status().isCreated());
    }

    @Test
    void approverValueLimitAndScope_G5() throws Exception {
        receive("SKU-VAL", "10", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        String adjust = """
                {"ownerId":"ACME","itemNo":"SKU-VAL","locationId":"A-01-01","qtyDelta":-2,"uom":"EA","reasonCode":"CC_VAR"}""";
        // value = 2 x 10.00 = 20.00
        postApproved("alice", "/adjustments", "lim-1", adjust, approver("bob", "15", "DC1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("APPROVAL_LIMIT_EXCEEDED")))
                .andExpect(jsonPath("$.value", is(20.00)));
        postApproved("alice", "/adjustments", "lim-2", adjust, approver("bob", "50", "DC2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("APPROVER_SCOPE_DENIED")));
        postApproved("alice", "/adjustments", "lim-3", adjust, approver("bob", "50", "DC1"))
                .andExpect(status().isCreated());
        postApproved("alice", "/adjustments", "lim-4", adjust, approver("carol", null, "DC1"))   // no limit claim
                .andExpect(status().isCreated());

        receive("SKU-EA", "5", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        postApproved("alice", "/adjustments", "lim-5", adjust.replace("SKU-VAL", "SKU-EA"), approver("bob", "50", "DC1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("APPROVAL_VALUE_UNKNOWN")));
    }

    private String approver(String user, String limit, String site) {
        return TestTokens.token().tenant(tenant).user(user).roles(Roles.INV_MANAGER).sites(site).approvalLimit(limit).sign();
    }
}
