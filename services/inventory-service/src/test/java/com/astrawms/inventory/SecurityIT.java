package com.astrawms.inventory;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.security.Roles;
import com.astrawms.inventory.support.IntegrationTest;
import com.astrawms.test.TestTokens;
import java.time.Instant;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** ADR-0010: who may call the API, for which tenant, with which roles (NFR-100, NFR-101, NFR-103). */
class SecurityIT extends IntegrationTest {

    private static final String BALANCES = "/api/v1/sites/DC1/inventory/balances";
    private static final String RECEIPT = """
            {"ownerId":"ACME","itemNo":"SKU-EA","qty":1,"uom":"EA","locationId":"A-01-01"}""";

    @Nested
    class Authentication {

        @Test
        void healthProbeIsAnonymous() throws Exception {
            mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        }

        @Test
        void missingTokenIs401WithBearerChallenge() throws Exception {
            mvc.perform(get(BALANCES))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", containsString("Bearer")))
                    .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")));
        }

        @Test
        void forgedExpiredForeignIssuerAndWrongAudienceTokensAreRejected() throws Exception {
            String[] bad = {
                    TestTokens.token().tenant(tenant).roles(Roles.SUPERVISOR).forged().sign(),
                    TestTokens.token().tenant(tenant).roles(Roles.SUPERVISOR)
                            .issuedAt(Instant.now().minusSeconds(900)).expiresAt(Instant.now().minusSeconds(300)).sign(),
                    TestTokens.token().tenant(tenant).roles(Roles.SUPERVISOR).issuer("https://evil.example/realms/x").sign(),
                    TestTokens.token().tenant(tenant).roles(Roles.SUPERVISOR).audience("some-other-api").sign(),
                    "not-a-jwt"};
            for (String token : bad) {
                mvc.perform(get(BALANCES).with(TestTokens.bearer(token)))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code", is("TOKEN_INVALID")));
            }
        }
    }

    @Nested
    class TenantBinding {

        @Test
        void tenantComesFromTheTokenNotFromHeaders() throws Exception {
            receive("SKU-EA", "5", "EA", "A-01-01", null, null).andExpect(status().isCreated());
            String victim = tenant;
            // An intruder with a valid token for their own tenant names the victim tenant in the header.
            mvc.perform(get(BALANCES).with(TestTokens.as("t-intruder", "mallory", Roles.SUPERVISOR))
                            .header("X-Tenant-Id", victim))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("TENANT_MISMATCH")));
            // Without the header they only see their own (empty) tenant.
            mvc.perform(get(BALANCES).with(TestTokens.as("t-intruder", "mallory", Roles.SUPERVISOR)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(0)));
        }

        @Test
        void userTokenWithoutTenantClaimIsRejected() throws Exception {
            mvc.perform(get(BALANCES).with(TestTokens.bearer(TestTokens.token().roles(Roles.SUPERVISOR).sign())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("TENANT_CLAIM_MISSING")));
        }

        @Test
        void spoofedUserHeaderIsIgnoredForUserTokens() throws Exception {
            RequestPostProcessor spoofing = request -> {
                TestTokens.as(tenant, "alice", Roles.RECEIVER).postProcessRequest(request);
                request.addHeader("X-User-Id", "the-boss");
                return request;
            };
            postWith(spoofing, "/receipts", "spoof-1", RECEIPT, null).andExpect(status().isCreated());
            getJson("/transactions?itemNo=SKU-EA").andExpect(jsonPath("$.items[0].userId", is("alice")));
        }

        @Test
        void serviceAccountActsForTheNamedTenantAndUser() throws Exception {
            RequestPostProcessor inboundService = request -> {
                TestTokens.service("astra-inbound").postProcessRequest(request);
                request.addHeader("X-Tenant-Id", tenant);
                request.addHeader("X-User-Id", "receiver7");
                return request;
            };
            postWith(inboundService, "/receipts", "svc-1", RECEIPT, null).andExpect(status().isCreated());
            getJson("/transactions?itemNo=SKU-EA").andExpect(jsonPath("$.items[0].userId", is("receiver7")));

            postWith(TestTokens.service("astra-inbound"), "/receipts", "svc-2", RECEIPT, null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("TENANT_MISSING")));
        }
    }

    @Nested
    class RoleChecks {

        @Test
        void writesRequireAnAuthorisedRole_G5() throws Exception {
            String adjust = """
                    {"ownerId":"ACME","itemNo":"SKU-EA","locationId":"A-01-01","qtyDelta":1,"uom":"EA","reasonCode":"CC_TOL"}""";
            postWith(TestTokens.as(tenant, "pete", Roles.PICKER), "/adjustments", "r-1", adjust, null)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("FORBIDDEN")));
            postWith(TestTokens.as(tenant, "pete", Roles.PICKER), "/receipts", "r-2", RECEIPT, null)
                    .andExpect(status().isForbidden());
            postWith(TestTokens.as(tenant, "ann", Roles.INV_ANALYST), "/adjustments", "r-3", adjust, null)
                    .andExpect(status().isCreated());
            // ERP middleware may not touch stock directly.
            postWith(TestTokens.as(tenant, "sap", Roles.ERP_INTEGRATION), "/adjustments", "r-4", adjust, null)
                    .andExpect(status().isForbidden());
        }

        @Test
        void anyTenantUserMayRead() throws Exception {
            mvc.perform(get(BALANCES).with(TestTokens.as(tenant, "pete", Roles.PICKER))).andExpect(status().isOk());
        }

        @Test
        void releaseFromQualityInspectionNeedsQaManagerAndApprover_G5() throws Exception {
            receive("SKU-EA", "4", "EA", "A-01-01", "LPN-QA", null).andExpect(status().isCreated());
            String change = """
                    {"ownerId":"ACME","itemNo":"SKU-EA","locationId":"A-01-01","lpnId":"LPN-QA","fromStatus":"%s",
                     "toStatus":"%s","qty":4,"uom":"EA","reasonCode":"%s"}""";
            post("/status-changes", change.formatted("AVAILABLE", "QI", "QA_HOLD")).andExpect(status().isCreated());

            String release = change.formatted("QI", "AVAILABLE", "QA_REL");
            postWith(TestTokens.as(tenant, "ivan", Roles.INV_MANAGER), "/status-changes", "qa-1", release,
                    approvalToken("sue", Roles.SUPERVISOR))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("INV_QA_RELEASE_REQUIRED")));
            postWith(TestTokens.as(tenant, "quinn", Roles.QA_MANAGER), "/status-changes", "qa-2", release,
                    approvalToken("sue", Roles.SUPERVISOR))
                    .andExpect(status().isCreated());
        }
    }
}
