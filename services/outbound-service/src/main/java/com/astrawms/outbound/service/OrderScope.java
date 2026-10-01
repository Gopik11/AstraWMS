package com.astrawms.outbound.service;

import com.astrawms.common.security.AccessScope;
import com.astrawms.common.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Owner scope of outbound orders (§G.5.1): a user scoped to some owners sees and works only orders whose lines all
 * belong to those owners (404 otherwise, so their existence is not disclosed). Waves only take such orders.
 */
@Component
public class OrderScope {

    /** SQL predicate on an order aliased {@code o}; bind {@code :scopeOwners} with {@link AccessScope#ownerList()}. */
    static final String ALL_LINES_IN_SCOPE = """
            not exists (select 1 from outbound_line l where l.order_id = o.id and l.owner_id not in (:scopeOwners))""";

    private final JdbcClient jdbc;

    public OrderScope(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void require(String siteId, String erpDocNo) {
        AccessScope scope = AccessScope.current();
        if (scope.ownersAll()) {
            return;
        }
        boolean visible = jdbc.sql("select exists (select 1 from outbound_order o where o.site_id = :site "
                        + "and o.erp_doc_no = :doc and " + ALL_LINES_IN_SCOPE + ")")
                .param("site", siteId).param("doc", erpDocNo).param("scopeOwners", scope.ownerList())
                .query(Boolean.class).single();
        if (!visible) {
            throw ApiException.notFound("OUT_ORDER_UNKNOWN", "No outbound order " + erpDocNo);
        }
    }

    public List<Map<String, Object>> filter(String siteId, List<Map<String, Object>> rows) {
        AccessScope scope = AccessScope.current();
        if (scope.ownersAll()) {
            return rows;
        }
        Set<String> visible = Set.copyOf(jdbc.sql("select o.erp_doc_no from outbound_order o where o.site_id = :site and "
                        + ALL_LINES_IN_SCOPE)
                .param("site", siteId).param("scopeOwners", scope.ownerList()).query(String.class).list());
        return rows.stream().filter(r -> visible.contains((String) r.get("erp_doc_no"))).toList();
    }
}
