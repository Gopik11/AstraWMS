package com.astrawms.inbound.receiving;

import com.astrawms.common.security.AccessScope;
import com.astrawms.common.web.ApiException;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Owner scope of receipts (§G.5.1): a user scoped to some owners sees and works only expectations whose lines all
 * belong to those owners. Others look like they do not exist (404), so their existence is not disclosed.
 */
@Component
public class ReceiptScope {

    private static final String ALL_LINES_IN_SCOPE = """
            not exists (select 1 from receipt_expectation_line l
                        where l.expectation_id = e.id and l.owner_id not in (:owners))""";

    private final JdbcClient jdbc;

    public ReceiptScope(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void require(String siteId, String erpDocNo) {
        AccessScope scope = AccessScope.current();
        if (scope.ownersAll()) {
            return;
        }
        boolean visible = jdbc.sql("select exists (select 1 from receipt_expectation e where e.site_id = :site "
                        + "and e.erp_doc_no = :doc and " + ALL_LINES_IN_SCOPE + ")")
                .param("site", siteId).param("doc", erpDocNo).param("owners", scope.ownerList())
                .query(Boolean.class).single();
        if (!visible) {
            throw ApiException.notFound("INB_EXPECTATION_UNKNOWN", "No expectation " + erpDocNo);
        }
    }

    public <T> List<T> filter(String siteId, List<T> rows, Function<T, String> erpDocNo) {
        AccessScope scope = AccessScope.current();
        if (scope.ownersAll()) {
            return rows;
        }
        Set<String> visible = Set.copyOf(jdbc.sql("select e.erp_doc_no from receipt_expectation e where e.site_id = :site and "
                        + ALL_LINES_IN_SCOPE)
                .param("site", siteId).param("owners", scope.ownerList()).query(String.class).list());
        return rows.stream().filter(r -> visible.contains(erpDocNo.apply(r))).toList();
    }
}
