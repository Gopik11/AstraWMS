package com.astrawms.task.api;

import com.astrawms.common.security.AccessScope;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Site network view, task half (ADR-0024): open work, exceptions and tasks left assigned too long, per site and per
 * aisle of one site (the digital twin colours an aisle by its aged exceptions).
 */
@RestController
public class NetworkController {

    /** Assigned longer than this is stale (the overview's tile limit and its "Unassign" action). */
    static final Duration STALE = Duration.ofMinutes(30);
    /** The aisle of a location: its id up to the second dash (A-01-10 is aisle A-01); otherwise the location itself. */
    private static final String AISLE = "coalesce(substring(%1$s from '^[^-]+-[^-]+'), %1$s)";

    private final JdbcClient jdbc;
    private final Clock clock;

    public NetworkController(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/api/v1/network/tasks")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> sites() {
        AccessScope scope = AccessScope.current();
        return jdbc.sql("""
                        select site_id,
                               count(*) filter (where status in ('RELEASED', 'ASSIGNED')) as tasks_open,
                               count(*) filter (where status = 'EXCEPTION') as exceptions,
                               count(*) filter (where status = 'ASSIGNED' and assigned_at < :stale) as stale_assigned,
                               min(updated_at) filter (where status = 'EXCEPTION') as oldest_exception_at
                        from task where status in ('RELEASED', 'ASSIGNED', 'EXCEPTION') and (:sitesAll or site_id in (:sites))
                        group by site_id order by site_id""")
                .param("stale", Timestamp.from(clock.instant().minus(STALE)))
                .param("sitesAll", scope.sitesAll()).param("sites", scope.siteList())
                .query().listOfRows();
    }

    /** Open work per aisle: a task counts at its source location (and a putaway also at its target). */
    @GetMapping("/api/v1/sites/{siteId}/tasks/aisles")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> aisles(@PathVariable String siteId) {
        return jdbc.sql("""
                        select aisle,
                               count(*) filter (where status in ('RELEASED', 'ASSIGNED')) as tasks_open,
                               count(*) filter (where status = 'EXCEPTION') as exceptions,
                               count(*) filter (where status = 'ASSIGNED' and assigned_at < :stale) as stale_assigned,
                               min(updated_at) filter (where status = 'EXCEPTION') as oldest_exception_at
                        from (
                            select %s as aisle, status, assigned_at, updated_at from task
                            where site_id = :site and status in ('RELEASED', 'ASSIGNED', 'EXCEPTION') and from_location <> ''
                            union all
                            select %s, status, assigned_at, updated_at from task
                            where site_id = :site and status in ('RELEASED', 'ASSIGNED', 'EXCEPTION')
                              and task_type = 'PUTAWAY' and target_location is not null
                        ) t group by aisle order by aisle""".formatted(AISLE.formatted("from_location"),
                        AISLE.formatted("target_location")))
                .param("site", siteId).param("stale", Timestamp.from(clock.instant().minus(STALE)))
                .query().listOfRows();
    }
}
