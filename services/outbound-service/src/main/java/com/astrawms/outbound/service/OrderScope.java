package com.astrawms.outbound.service;

import com.astrawms.common.security.AccessScope;
import com.astrawms.common.tenancy.OwnerVisibility;
import com.astrawms.common.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owner scope of outbound orders (§G.5.1): a user scoped to some owners sees and works only orders whose lines all
 * belong to those owners (404 otherwise, so their existence is not disclosed). Waves only take such orders.
 *
 * <p>The same rule covers what holds orders (ADR-0030 C): a carton is worked only when its order is in scope; a wave or
 * a load only when every order on it is (403 when it also holds orders the user may work, 404 when it holds none).
 * Otherwise an operation would act on another owner's orders, or, with owner RLS on the lines, act on part of them.
 *
 * <p>Every check sees every owner's lines ({@link OwnerVisibility#allOwners}): with RLS a user cannot see the lines of
 * another owner, so "no line of another owner" would always hold. Checks run in a read-only transaction of their own
 * when the caller has none.
 */
@Component
public class OrderScope {

    /** SQL predicate on an order aliased {@code o}; bind {@code :scopeOwners} with {@link AccessScope#ownerList()}. */
    static final String ALL_LINES_IN_SCOPE = """
            not exists (select 1 from outbound_line l where l.order_id = o.id and l.owner_id not in (:scopeOwners))""";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public OrderScope(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    private <T> T acrossOwners(Supplier<T> check) {
        // all-owners: the document rule asks whether any line belongs to another owner, so it must see those lines
        return tx.execute(s -> OwnerVisibility.allOwners(jdbc, check));
    }

    public void require(String siteId, String erpDocNo) {
        AccessScope scope = AccessScope.current();
        if (scope.ownersAll()) {
            return;
        }
        boolean visible = acrossOwners(() -> jdbc.sql("select exists (select 1 from outbound_order o where o.site_id = :site "
                        + "and o.erp_doc_no = :doc and " + ALL_LINES_IN_SCOPE + ")")
                .param("site", siteId).param("doc", erpDocNo).param("scopeOwners", scope.ownerList())
                .query(Boolean.class).single());
        if (!visible) {
            throw ApiException.notFound("OUT_ORDER_UNKNOWN", "No outbound order " + erpDocNo);
        }
    }

    public List<Map<String, Object>> filter(String siteId, List<Map<String, Object>> rows) {
        AccessScope scope = AccessScope.current();
        if (scope.ownersAll()) {
            return rows;
        }
        Set<String> visible = Set.copyOf(acrossOwners(() -> jdbc.sql(
                        "select o.erp_doc_no from outbound_order o where o.site_id = :site and " + ALL_LINES_IN_SCOPE)
                .param("site", siteId).param("scopeOwners", scope.ownerList()).query(String.class).list()));
        return rows.stream().filter(r -> visible.contains((String) r.get("erp_doc_no"))).toList();
    }

    /** A carton is worked only when its order is in scope (404 otherwise, as for the order). */
    public void requireCarton(String siteId, String sscc) {
        AccessScope scope = AccessScope.current();
        if (scope.ownersAll()) {
            return;
        }
        boolean visible = acrossOwners(() -> jdbc.sql("""
                        select exists (select 1 from carton c join outbound_order o on o.id = c.order_id
                                       where c.site_id = :site and c.sscc = :sscc and\s""" + ALL_LINES_IN_SCOPE + ")")
                .param("site", siteId).param("sscc", sscc).param("scopeOwners", scope.ownerList())
                .query(Boolean.class).single());
        if (!visible) {
            throw ApiException.notFound("OUT_CARTON_UNKNOWN", "No carton " + sscc);
        }
    }

    /** A wave is worked only when every order on it is in scope. */
    public void requireWave(String siteId, String waveNo) {
        requireContainer("outbound_wave", "wave_id", "wave_no", siteId, waveNo, "OUT_WAVE", "Wave");
    }

    /** A load is worked only when every order on it is in scope. */
    public void requireLoad(String siteId, String loadNo) {
        requireContainer("shipment_load", "load_id", "load_no", siteId, loadNo, "OUT_LOAD", "Load");
    }

    public <T> List<T> filterWaves(String siteId, List<T> rows, Function<T, String> waveNo) {
        return filterContainers("outbound_wave", "wave_id", "wave_no", siteId, rows, waveNo);
    }

    public <T> List<T> filterLoads(String siteId, List<T> rows, Function<T, String> loadNo) {
        return filterContainers("shipment_load", "load_id", "load_no", siteId, rows, loadNo);
    }

    private record Mix(long inScope, long other) {
    }

    private void requireContainer(String table, String link, String numberColumn, String siteId, String number,
                                  String code, String what) {
        AccessScope scope = AccessScope.current();
        if (scope.ownersAll()) {
            return;
        }
        Mix mix = acrossOwners(() -> jdbc.sql("select count(*) filter (where " + ALL_LINES_IN_SCOPE + "), "
                        + "count(*) filter (where not " + ALL_LINES_IN_SCOPE + ") "
                        + "from outbound_order o join " + table + " k on k.id = o." + link
                        + " where k.site_id = :site and k." + numberColumn + " = :no")
                .param("site", siteId).param("no", number).param("scopeOwners", scope.ownerList())
                .query((rs, n) -> new Mix(rs.getLong(1), rs.getLong(2))).single());
        if (mix.other() == 0) {
            return;
        }
        if (mix.inScope() == 0) {
            throw ApiException.notFound(code + "_UNKNOWN", "No " + what.toLowerCase() + " " + number);
        }
        throw new ApiException(HttpStatus.FORBIDDEN, code + "_OTHER_OWNERS", what + " " + number
                + " also holds orders of owners you are not authorised for: it needs a user authorised for all of them",
                Map.of());
    }

    private <T> List<T> filterContainers(String table, String link, String numberColumn, String siteId, List<T> rows,
                                         Function<T, String> number) {
        AccessScope scope = AccessScope.current();
        if (scope.ownersAll()) {
            return rows;
        }
        Set<String> hidden = Set.copyOf(acrossOwners(() -> jdbc.sql("select distinct k." + numberColumn
                        + " from outbound_order o join " + table + " k on k.id = o." + link
                        + " where k.site_id = :site and not " + ALL_LINES_IN_SCOPE)
                .param("site", siteId).param("scopeOwners", scope.ownerList()).query(String.class).list()));
        return rows.stream().filter(r -> !hidden.contains(number.apply(r))).toList();
    }
}
