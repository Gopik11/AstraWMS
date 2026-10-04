package com.astrawms.inventory.service;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Risk-based cycle counting (ADR-0025). A location's class is the fastest velocity class (A, B, C) of the items it
 * holds (set on the item's slotting, or computed from pick history). It is due when its last finished count is older
 * than the class interval (site setting; A 30, B 90, C 180 days by default) — half that interval when its last count
 * had a variance, so a location that was wrong is checked again sooner. Never counted is due now.
 */
@Service
public class CyclePlan {

    private final JdbcClient jdbc;
    private final Slotting slotting;
    private final CountRequests requests;
    private final Clock clock;

    public CyclePlan(JdbcClient jdbc, Slotting slotting, CountRequests requests, Clock clock) {
        this.jdbc = jdbc;
        this.slotting = slotting;
        this.requests = requests;
        this.clock = clock;
    }

    public record Frequency(int aDays, int bDays, int cDays) {
        int days(String velocity) {
            return switch (velocity) {
                case "A" -> aDays;
                case "B" -> bDays;
                default -> cDays;
            };
        }
    }

    @Transactional(readOnly = true)
    public Frequency frequency(String siteId) {
        return jdbc.sql("select a_days, b_days, c_days from count_frequency where site_id = :site").param("site", siteId)
                .query((rs, n) -> new Frequency(rs.getInt(1), rs.getInt(2), rs.getInt(3))).optional()
                .orElse(new Frequency(30, 90, 180));
    }

    @Transactional
    public Frequency putFrequency(String siteId, Frequency f) {
        if (f.aDays() <= 0 || f.bDays() <= 0 || f.cDays() <= 0) {
            throw ApiException.badRequest("INV_COUNT_FREQUENCY_INVALID", "Days per class must be positive");
        }
        jdbc.sql("""
                        insert into count_frequency (tenant_id, site_id, a_days, b_days, c_days, updated_by, updated_at)
                        values (:t, :site, :a, :b, :c, :user, :now)
                        on conflict (tenant_id, site_id) do update set a_days = excluded.a_days, b_days = excluded.b_days,
                            c_days = excluded.c_days, updated_by = excluded.updated_by, updated_at = excluded.updated_at""")
                .param("t", TenantContext.tenantId()).param("site", siteId).param("a", f.aDays()).param("b", f.bDays())
                .param("c", f.cDays()).param("user", TenantContext.require().userId())
                .param("now", Timestamp.from(clock.instant())).update();
        return frequency(siteId);
    }

    /** Locations with stock, their class, last count, interval and whether (and why) they are due; due first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> plan(String siteId) {
        Frequency f = frequency(siteId);
        Map<String, String> velocity = new HashMap<>();
        for (Map<String, Object> r : slotting.analysis(siteId)) {
            velocity.put(r.get("owner_id") + "\u0001" + r.get("item_no"), String.valueOf(r.getOrDefault("velocity_class", "C")));
        }
        record Loc(String location, List<String> items) {
        }
        Map<String, List<String>> items = new LinkedHashMap<>();
        jdbc.sql("""
                        select distinct b.location_id, b.owner_id, b.item_no from inventory_balance b
                        join ref_location l on l.site_id = b.site_id and l.location_id = b.location_id
                        where b.site_id = :site and b.qty > 0
                          and l.location_type not in ('STAGING_OUT', 'DOOR', 'DOCK', 'STAGING', 'STAGING_IN')
                        order by 1""")
                .param("site", siteId)
                .query((rs, n) -> items.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                        .add(rs.getString(2) + "\u0001" + rs.getString(3))).list();
        record Last(Instant at, boolean variance) {
        }
        Map<String, Last> last = new HashMap<>();
        jdbc.sql("""
                        select distinct on (c.location_id) c.location_id, coalesce(c.decided_at, c.updated_at),
                               exists (select 1 from stock_count_variance v where v.count_id = c.id
                                       and v.counted_qty <> v.system_qty)
                        from stock_count c where c.site_id = :site and c.status in ('ADJUSTED', 'CLOSED')
                        order by c.location_id, coalesce(c.decided_at, c.updated_at) desc""")
                .param("site", siteId)
                .query((rs, n) -> last.put(rs.getString(1), new Last(rs.getTimestamp(2).toInstant(), rs.getBoolean(3)))).list();
        java.util.Set<String> open = new java.util.HashSet<>(jdbc.sql("""
                        select location_id from stock_count where site_id = :site and status in ('OPEN', 'RECOUNT', 'PENDING_APPROVAL')""")
                .param("site", siteId).query(String.class).list());
        Instant now = clock.instant();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : items.entrySet()) {
            String cls = e.getValue().stream().map(i -> velocity.getOrDefault(i, "C")).min(String::compareTo).orElse("C");
            Last l = last.get(e.getKey());
            int interval = f.days(cls);
            if (l != null && l.variance()) {
                interval = Math.max(1, interval / 2);
            }
            Instant dueAt = l == null ? now : l.at().plus(Duration.ofDays(interval));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("location_id", e.getKey());
            r.put("velocity_class", cls);
            r.put("items", e.getValue().size());
            r.put("last_counted_at", l == null ? null : l.at());
            r.put("last_variance", l != null && l.variance());
            r.put("interval_days", interval);
            r.put("due_at", dueAt);
            r.put("count_open", open.contains(e.getKey()));
            r.put("due", !open.contains(e.getKey()) && !dueAt.isAfter(now));
            r.put("reason", l == null ? "Never counted" : (l.variance() ? "Variance last time: every " + interval
                    + " days instead of " + f.days(cls) : "Class " + cls + ": every " + interval + " days"));
            out.add(r);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> !Boolean.TRUE.equals(m.get("due")))
                .thenComparing(m -> (String) m.get("velocity_class")).thenComparing(m -> (Instant) m.get("due_at")));
        return out;
    }

    /** Opens a cycle count (blind, on RF) for up to {@code limit} due locations, most at risk first. */
    @Transactional
    public Map<String, Object> openDue(String siteId, int limit, String note) {
        List<UUID> opened = new ArrayList<>();
        List<String> locations = new ArrayList<>();
        for (Map<String, Object> r : plan(siteId)) {
            if (opened.size() >= limit || !Boolean.TRUE.equals(r.get("due"))) {
                continue;
            }
            String loc = (String) r.get("location_id");
            opened.add(requests.open(siteId, loc, "CYCLE", note == null ? "Cycle count: " + r.get("reason") : note));
            locations.add(loc);
        }
        return Map.of("opened", opened.size(), "locations", locations);
    }
}
