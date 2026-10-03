package com.astrawms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.security.Roles;
import com.astrawms.inventory.support.IntegrationTest;
import com.astrawms.test.TestTokens;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Controlled material issue to cost objects with approval, scan issue, return and SAP account assignment (ADR-0022). */
class MaterialIssueIT extends IntegrationTest {

    private ResultActions call(String method, String path, RequestPostProcessor who, String key, String json) throws Exception {
        var r = MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method),
                        "/api/v1/sites/" + SITE + "/inventory" + path)
                .with(who).contentType(MediaType.APPLICATION_JSON).content(json == null ? "" : json);
        if (key != null) {
            r.header("Idempotency-Key", key);
        }
        return mvc.perform(r);
    }

    private RequestPostProcessor as(String user, String... roles) {
        return TestTokens.as(tenant, user, roles);
    }

    @Test
    void approvedIssueIsScannedOutToTheCostCentreAndReturnsReverseIt() throws Exception {
        var admin = as("ada", Roles.SOLUTION_ADMIN);
        call("PUT", "/cost-objects/cost_center/cc100", admin, null, "{\"description\":\"Maintenance\",\"department\":\"MAINT\"}")
                .andExpect(jsonPath("$.code", is("CC100")));
        call("PUT", "/cost-objects/WBS/P-1", admin, null, "{\"description\":\"Project One\",\"allowedRequesters\":[\"eve\"]}")
                .andExpect(status().isOk());
        receive("SKU-EA", "20", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        asTenant(() -> jdbc.sql("update ref_item_uom set gtin = '4012345678901' where item_no = 'SKU-EA' and uom = 'CS'").update());

        var rita = as("rita", Roles.RECEIVER);
        call("POST", "/material-issues", rita, null, """
                {"ownerId":"ACME","objectType":"WBS","objectCode":"P-1","recipient":"Site crew","lines":[{"itemNo":"SKU-EA","qty":1,"uom":"EA"}]}""")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("INV_REQUESTER_NOT_ALLOWED")));
        call("POST", "/material-issues", rita, null, """
                {"ownerId":"ACME","objectType":"COST_CENTER","objectCode":"CC100","recipient":"J. Doe, maintenance",
                 "lines":[{"itemNo":"SKU-EA","qty":5,"uom":"EA"}]}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.issue_no", is("MI000001"))).andExpect(jsonPath("$.status", is("REQUESTED")))
                .andExpect(jsonPath("$.object_description", is("Maintenance")));
        String scan = "{\"locationId\":\"A-01-01\",\"itemScan\":\"%s\",\"qty\":%s}";
        call("POST", "/material-issues/MI000001/lines/10/issue", rita, "k0", scan.formatted("SKU-EA", "1"))
                .andExpect(jsonPath("$.code", is("INV_ISSUE_STATUS")));                       // not approved yet
        call("POST", "/material-issues/MI000001/approve", as("rita", Roles.SUPERVISOR), null, "{}")
                .andExpect(jsonPath("$.code", is("INV_SELF_APPROVAL")));
        call("POST", "/material-issues/MI000001/approve", as("sue", Roles.SUPERVISOR), null, "{}")
                .andExpect(jsonPath("$.status", is("APPROVED"))).andExpect(jsonPath("$.decided_by", is("sue")));

        call("POST", "/material-issues/MI000001/lines/10/issue", rita, "k1", scan.formatted("SKU-LOT", "1"))
                .andExpect(jsonPath("$.code", is("INV_WRONG_ITEM")));
        call("POST", "/material-issues/MI000001/lines/10/issue", rita, "k1", scan.formatted("SKU-EA", "6"))
                .andExpect(jsonPath("$.code", is("INV_ISSUE_QTY")));
        // A GS1 label of the item's case GTIN identifies it.
        call("POST", "/material-issues/MI000001/lines/10/issue", rita, "k2", scan.formatted("(01)04012345678901", "3"))
                .andExpect(jsonPath("$.status", is("PARTIALLY_ISSUED")))
                .andExpect(jsonPath("$.lines[0].qty_issued", is(3)));
        call("POST", "/material-issues/MI000001/lines/10/issue", rita, "k2", scan.formatted("(01)04012345678901", "3"))
                .andExpect(jsonPath("$.lines[0].qty_issued", is(3)));                        // a retried scan counts once
        assertThat(onHand("SKU-EA", "A-01-01", "AVAILABLE")).isEqualByComparingTo("17");
        call("POST", "/material-issues/MI000001/lines/10/issue", rita, "k3", scan.formatted("SKU-EA", "2"))
                .andExpect(jsonPath("$.status", is("ISSUED")));

        call("POST", "/material-issues/MI000001/lines/10/return", rita, "r1", scan.formatted("SKU-EA", "6"))
                .andExpect(jsonPath("$.code", is("INV_RETURN_QTY")));
        call("POST", "/material-issues/MI000001/lines/10/return", rita, "r2", scan.formatted("SKU-EA", "1"))
                .andExpect(jsonPath("$.lines[0].qty_returned", is(1)))
                .andExpect(jsonPath("$.moves.length()", is(3)));
        assertThat(onHand("SKU-EA", "A-01-01", "AVAILABLE")).isEqualByComparingTo("16");

        List<String> movements = outboxEnvelopes("GoodsMovement");
        assertThat(movements).hasSize(3);
        assertThat(movements.getFirst()).contains("\"movementType\":\"ISSUE_COST_CENTER\"", "\"objectType\":\"COST_CENTER\"",
                "\"code\":\"CC100\"", "\"issueNo\":\"MI000001\"");
        assertThat(movements.getLast()).contains("\"movementType\":\"RETURN_COST_CENTER\"");
        call("GET", "/material-issues?q=cc100", rita, null, null).andExpect(jsonPath("$[0].status", is("ISSUED")));
    }
}
