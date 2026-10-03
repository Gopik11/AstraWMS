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
