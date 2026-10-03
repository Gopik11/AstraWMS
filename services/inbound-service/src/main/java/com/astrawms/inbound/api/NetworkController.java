package com.astrawms.inbound.api;

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
 * Site network view, inbound half (ADR-0024): per site of the user's scope the receipts not started, the transfers on
 * their way in (shipped by another site, not yet received), the ERP postings that failed and the late appointments.
 */
@RestController
public class NetworkController {

    private final JdbcClient jdbc;
    private final Clock clock;

    public NetworkController(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/api/v1/network/inbound")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> sites() {
        AccessScope scope = AccessScope.current();
        return jdbc.sql("""
                        select site_id,
                               sum(not_started) as receipts_not_started,
                               sum(in_transit) as transfers_in_transit,
                               sum(failed) as postings_failed,
                               sum(late) as appointments_late,
                               min(oldest_failed) as oldest_failed_at
                        from (
                            select site_id,
                                   count(*) filter (where status = 'NOT_STARTED' and supplying_site_id is null) as not_started,
                                   count(*) filter (where status in ('NOT_STARTED', 'IN_PROGRESS') and supplying_site_id is not null) as in_transit,
                                   count(*) filter (where status = 'POSTING_FAILED') as failed,
                                   0 as late,
                                   min(updated_at) filter (where status = 'POSTING_FAILED') as oldest_failed
                            from receipt_expectation where (:sitesAll or site_id in (:sites)) group by site_id
                            union all
                            select site_id, 0, 0, count(*), 0, min(updated_at)
                            from return_order where status = 'POSTING_FAILED' and (:sitesAll or site_id in (:sites))
                            group by site_id
                            union all
                            select site_id, 0, 0, 0, count(*), null
                            from dock_appointment where status = 'SCHEDULED' and scheduled_start < :late
                              and (:sitesAll or site_id in (:sites))
                            group by site_id
                        ) x group by site_id order by site_id""")
                .param("sitesAll", scope.sitesAll()).param("sites", scope.siteList())
                .param("late", Timestamp.from(clock.instant().minus(Duration.ofMinutes(15))))
                .query().listOfRows();
    }
}
