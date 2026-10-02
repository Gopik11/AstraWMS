package com.astrawms.outbound.service;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Release policy of a site (ADR-0021): carrier cutoffs, ship-complete defaults and owner (3PL client) rules.
 * <ul>
 *   <li>An order's <b>cutoff</b> is its carrier's cutoff time (site time zone) on the planned goods-issue date; without
 *       a goods-issue date the next cutoff from now; without a carrier cutoff the planned goods issue itself. Waves,
 *       backorder recovery and pick priority are ordered by it.</li>
 *   <li><b>Ship complete</b>: the owner's rule if set, otherwise the site's. A ship-complete order is released only when
 *       every line is fully allocated; until then it waits (BACKORDERED) and holds no stock.</li>
 *   <li><b>Pick priority</b>: picks of an order whose cutoff is near rise above other picks (but stay below
 *       replenishment, which feeds them): +5 within four hours, +9 within one hour or late.</li>
 * </ul>
 */
@Component
public class ReleasePolicy {

    private final JdbcClient jdbc;
    private final Clock clock;

    public ReleasePolicy(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public ZoneId zone(String siteId) {
        String tz = jdbc.sql("select timezone from outbound_site_config where site_id = :site").param("site", siteId)
                .query(String.class).optional().orElse("UTC");
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            return ZoneId.of("UTC");
        }
    }

    public Optional<LocalTime> carrierCutoff(String siteId, String scac) {
        if (scac == null || scac.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("select cutoff_time from outbound_carrier_cutoff where site_id = :site and carrier_scac = :scac")
                .param("site", siteId).param("scac", scac).query(Time.class).optional().map(Time::toLocalTime);
    }

    /** The time the order must be ready to leave by (see class comment). */
    public Instant cutoffAt(String siteId, String scac, Instant plannedGi) {
        Optional<LocalTime> cut = carrierCutoff(siteId, scac);
        if (cut.isEmpty()) {
            return plannedGi;
        }
        ZoneId z = zone(siteId);
        Instant base = plannedGi != null ? plannedGi : clock.instant();
        LocalDate day = base.atZone(z).toLocalDate();
        Instant at = day.atTime(cut.get()).atZone(z).toInstant();
        if (plannedGi == null && at.isBefore(base)) {
            at = day.plusDays(1).atTime(cut.get()).atZone(z).toInstant();
        }
        return at;
    }

    /** Pick task priority of an order: the base priority, raised as its cutoff nears. */
    public int pickPriority(int base, Instant cutoffAt) {
        if (cutoffAt == null) {
            return base;
        }
        Duration left = Duration.between(clock.instant(), cutoffAt);
        if (left.compareTo(Duration.ofHours(1)) <= 0) {
            return base + 9;
        }
        return left.compareTo(Duration.ofHours(4)) <= 0 ? base + 5 : base;
    }

    /** Ship complete for an order of these owners: any owner rule that says so, else the site default. */
    public boolean shipComplete(String siteId, List<String> owners) {
        List<Boolean> rules = owners.isEmpty() ? List.of() : jdbc.sql("""
                        select ship_complete from outbound_owner_policy
                        where owner_id in (:owners) and ship_complete is not null""")
                .param("owners", owners).query(Boolean.class).list();
        if (!rules.isEmpty()) {
            return rules.contains(Boolean.TRUE);
        }
        return jdbc.sql("select coalesce((select ship_complete from outbound_site_config where site_id = :site), false)")
                .param("site", siteId).query(Boolean.class).single();
    }

    // ------------------------------------------------------------------------------------------ carrier cutoffs

    public List<Map<String, Object>> cutoffs(String siteId) {
        return jdbc.sql("""
                        select carrier_scac, to_char(cutoff_time, 'HH24:MI') as cutoff_time, updated_by, updated_at,
                               (select count(*) from outbound_order o where o.site_id = c.site_id
                                  and o.carrier_scac = c.carrier_scac and o.status in ('POOLED', 'RELEASED', 'BACKORDERED')) as open_orders
                        from outbound_carrier_cutoff c where c.site_id = :site order by cutoff_time, carrier_scac""")
                .param("site", siteId).query().listOfRows();
    }

    /** Sets a carrier's cutoff (HH:mm, site time) and re-times its open orders. */
    @Transactional
    public List<Map<String, Object>> putCutoff(String siteId, String scac, String time) {
        LocalTime t;
        try {
            t = LocalTime.parse(time == null ? "" : time.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("OUT_CUTOFF_INVALID", "cutoffTime must be HH:mm");
        }
        String carrier = scac.trim().toUpperCase();
        jdbc.sql("""
                        insert into outbound_carrier_cutoff (tenant_id, site_id, carrier_scac, cutoff_time, updated_by, updated_at)
                        values (:t, :site, :scac, :time, :user, :now)
                        on conflict (tenant_id, site_id, carrier_scac) do update set cutoff_time = excluded.cutoff_time,
                            updated_by = excluded.updated_by, updated_at = excluded.updated_at""")
                .param("t", TenantContext.tenantId()).param("site", siteId).param("scac", carrier)
                .param("time", Time.valueOf(t)).param("user", TenantContext.require().userId())
                .param("now", Timestamp.from(clock.instant())).update();
        retime(siteId, carrier);
        return cutoffs(siteId);
    }

    @Transactional
    public List<Map<String, Object>> deleteCutoff(String siteId, String scac) {
        String carrier = scac.trim().toUpperCase();
        jdbc.sql("delete from outbound_carrier_cutoff where site_id = :site and carrier_scac = :scac")
                .param("site", siteId).param("scac", carrier).update();
        retime(siteId, carrier);
        return cutoffs(siteId);
    }

    private void retime(String siteId, String scac) {
        record O(java.util.UUID id, Timestamp gi) {
        }
        for (O o : jdbc.sql("""
                        select id, planned_gi_utc from outbound_order where site_id = :site and carrier_scac = :scac
                          and status in ('POOLED', 'RELEASED', 'BACKORDERED', 'PICKED')""")
                .param("site", siteId).param("scac", scac)
                .query((rs, n) -> new O(rs.getObject(1, java.util.UUID.class), rs.getTimestamp(2))).list()) {
            Instant at = cutoffAt(siteId, scac, o.gi() == null ? null : o.gi().toInstant());
            jdbc.sql("update outbound_order set cutoff_at = :at where id = :id")
                    .param("at", at == null ? null : Timestamp.from(at)).param("id", o.id()).update();
        }
    }

    // ------------------------------------------------------------------------------------------ owner rules

    public List<Map<String, Object>> ownerPolicies() {
        return jdbc.sql("""
                        select owner_id, ship_complete, pack_list, label_template, updated_by, updated_at
                        from outbound_owner_policy order by owner_id""").query().listOfRows();
    }

    public Map<String, Object> ownerPolicy(String ownerId) {
        return jdbc.sql("""
                        select owner_id, ship_complete, pack_list, label_template from outbound_owner_policy
                        where owner_id = :owner""").param("owner", ownerId).query().listOfRows().stream().findFirst()
                .orElse(Map.of("owner_id", ownerId, "pack_list", false));
    }

    /** Sets an owner's rules; {@code shipComplete} null means "as the site". */
    @Transactional
    public Map<String, Object> putOwnerPolicy(String ownerId, Boolean shipComplete, Boolean packList, String labelTemplate) {
        String template = labelTemplate == null || labelTemplate.isBlank() ? null : labelTemplate.trim().toUpperCase();
        if (template != null && !List.of("STANDARD", "RETAIL", "MINIMAL").contains(template)) {
            throw ApiException.badRequest("OUT_OWNER_POLICY_INVALID", "labelTemplate must be STANDARD, RETAIL or MINIMAL");
        }
        jdbc.sql("""
                        insert into outbound_owner_policy (tenant_id, owner_id, ship_complete, pack_list, label_template,
                                                           updated_by, updated_at)
                        values (:t, :owner, :sc, :pl, :label, :user, :now)
                        on conflict (tenant_id, owner_id) do update set ship_complete = excluded.ship_complete,
                            pack_list = excluded.pack_list, label_template = excluded.label_template,
                            updated_by = excluded.updated_by, updated_at = excluded.updated_at""")
                .param("t", TenantContext.tenantId()).param("owner", ownerId.trim().toUpperCase()).param("sc", shipComplete)
                .param("pl", packList != null && packList).param("label", template)
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant())).update();
        return ownerPolicy(ownerId.trim().toUpperCase());
    }
}
