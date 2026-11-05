package com.astrawms.inbound.returns;

import com.astrawms.common.security.AccessScope;
import com.astrawms.common.tenancy.OwnerVisibility;
import com.astrawms.common.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owner scope of customer returns (§G.5.1, ADR-0030 C): a user scoped to some owners sees and works only returns whose
 * expected lines and received units all belong to those owners; others look like they do not exist (404). Units count
 * too, because a blind return has units and no expected lines.
 *
 * <p>The check sees every owner's lines and units ({@link OwnerVisibility#allOwners}): with owner RLS a user cannot see
 * another owner's rows, so "none of another owner" would always hold.
 */
@Component
public class ReturnScope {

    /** SQL predicate on a return aliased {@code r}; bind {@code :owners} with {@link AccessScope#ownerList()}. */
    static final String ALL_IN_SCOPE = """
            not exists (select 1 from return_line l where l.return_id = r.id and l.owner_id not in (:owners))
            and not exists (select 1 from return_unit u where u.return_id = r.id and u.owner_id not in (:owners))""";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public ReturnScope(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public void require(String siteId, String rmaNo) {
        AccessScope scope = AccessScope.current();
        if (scope.ownersAll()) {
            return;
        }
        // all-owners: the return rule asks whether any line or unit belongs to another owner
        boolean visible = tx.execute(s -> OwnerVisibility.allOwners(jdbc, () -> jdbc.sql(
                        "select exists (select 1 from return_order r where r.site_id = :site and r.rma_no = :rma and "
                                + ALL_IN_SCOPE + ")")
                .param("site", siteId).param("rma", rmaNo).param("owners", scope.ownerList())
                .query(Boolean.class).single()));
        if (!visible) {
            throw ApiException.notFound("RET_UNKNOWN", "No return " + rmaNo);
        }
    }
}
