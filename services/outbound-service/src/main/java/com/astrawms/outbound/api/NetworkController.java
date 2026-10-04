package com.astrawms.outbound.api;

import com.astrawms.common.security.AccessScope;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Site network view, outbound half (ADR-0024): per site of the user's scope the open orders and transfers, the short
 * lines, the goods issues the ERP refused and the orders due within two hours.
 */
@RestController
public class NetworkController {

    private static final String OPEN = "o.status not in ('SHIPPED', 'CONFIRMED', 'CANCELLED', 'SHIP_ERROR')";

    private final JdbcClient jdbc;
    private final Clock clock;

    public NetworkController(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Management view (ADR-0025) over the last {@code days} days per site: fill rate (units shipped / units ordered on
     * orders shipped, and lines shipped complete / lines) and transfer on-time (shipped by the planned ship time).
     */
    @GetMapping("/api/v1/network/outbound/metrics")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> metrics(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "30") int days) {
        AccessScope scope = AccessScope.current();
        return jdbc.sql("""
                        select o.site_id,
                               count(distinct o.id) filter (where o.transfer_to_site is null) as orders_shipped,
                               sum(l.qty_picked) filter (where o.transfer_to_site is null) as units_shipped,
                               sum(l.qty_requested_base) filter (where o.transfer_to_site is null) as units_ordered,
                               count(*) filter (where o.transfer_to_site is null) as lines,
                               count(*) filter (where o.transfer_to_site is null and l.qty_picked >= l.qty_requested_base) as lines_complete,
                               count(distinct o.id) filter (where o.transfer_to_site is not null) as transfers_shipped,
                               count(distinct o.id) filter (where o.transfer_to_site is not null
                                   and (coalesce(o.planned_gi_utc, o.cutoff_at) is null
                                        or o.shipped_at <= coalesce(o.planned_gi_utc, o.cutoff_at))) as transfers_on_time
                        from outbound_order o join outbound_line l on l.order_id = o.id
                        where o.status in ('SHIPPED', 'CONFIRMED') and o.shipped_at >= :since
                          and (:sitesAll or o.site_id in (:sites))
                        group by o.site_id order by o.site_id""")
                .param("since", Timestamp.from(clock.instant().minus(Duration.ofDays(Math.max(1, Math.min(days, 366))))))
                .param("sitesAll", scope.sitesAll()).param("sites", scope.siteList())
                .query().listOfRows();
    }

    /** One item across sites (ADR-0025): packed (in cartons, not yet shipped), open demand and short quantity. */
    @GetMapping("/api/v1/network/outbound/items/{itemNo}")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> item(@org.springframework.web.bind.annotation.PathVariable String itemNo) {
        AccessScope scope = AccessScope.current();
        return jdbc.sql("""
                        select o.site_id,
                               coalesce(sum(coalesce(l.qty_requested_base, l.qty_requested) - l.qty_picked) filter (where %1$s), 0) as open_demand,
                               coalesce(sum(l.qty_short - l.qty_short_closed) filter (where %1$s), 0) as short,
                               coalesce((select sum(ci.qty) from carton_item ci join carton c on c.id = ci.carton_id
                                         join outbound_order x on x.id = c.order_id
                                         where ci.item_no = :item and x.site_id = o.site_id
                                           and x.status not in ('SHIPPED', 'CONFIRMED', 'CANCELLED')), 0) as packed
                        from outbound_order o join outbound_line l on l.order_id = o.id
                        where l.item_no = :item and (:sitesAll or o.site_id in (:sites))
                        group by o.site_id order by o.site_id""".formatted(OPEN))
                .param("item", itemNo).param("sitesAll", scope.sitesAll()).param("sites", scope.siteList())
                .query().listOfRows();
    }

    @GetMapping("/api/v1/network/outbound")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> sites() {
        AccessScope scope = AccessScope.current();
        return jdbc.sql("""
                        select o.site_id,
                               count(*) filter (where %1$s and o.transfer_to_site is null) as orders_open,
                               count(*) filter (where %1$s and o.transfer_to_site is not null) as transfers_open,
                               count(*) filter (where o.status = 'SHIP_ERROR') as ship_errors,
                               count(*) filter (where %1$s and coalesce(o.cutoff_at, o.planned_gi_utc) < :soon) as due_soon,
                               coalesce(sum((select count(*) from outbound_line l where l.order_id = o.id and l.qty_short > 0))
                                   filter (where %1$s), 0) as lines_short
                        from outbound_order o
                        where (:sitesAll or o.site_id in (:sites))
                        group by o.site_id order by o.site_id""".formatted(OPEN))
                .param("soon", Timestamp.from(clock.instant().plus(Duration.ofHours(2))))
                .param("sitesAll", scope.sitesAll()).param("sites", scope.siteList())
                .query().listOfRows();
    }
}
