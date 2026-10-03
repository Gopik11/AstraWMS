package com.astrawms.inventory.service;

import com.astrawms.common.security.AccessScope;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Full physical inventory (ADR-0022), built on the blind RF counts of ADR-0014:
 * <pre>
 * PLANNED ──start──▶ COUNTING ──post──▶ POSTED
 *     └──────────────────┴──cancel──▶ CANCELLED
 * </pre>
 * <ul>
 *   <li><b>Start</b> opens a count (and an RF count task) for every active location in scope — the whole site, or the
 *       chosen zones — and, with {@code freeze}, freezes them: no stock moves in or out and nothing is allocated from
 *       them until the inventory is posted or cancelled.</li>
 *   <li>Counting follows the count rules (blind, recount by another user when counts disagree), but no count posts on
 *       its own: every difference waits for review.</li>
 *   <li><b>Post</b> (inventory manager or supervisor, who counted none of it) needs every count finished; it posts all
 *       differences as inventory adjustments with reason PI_DIFF — to SAP as 701/702 — and lifts the freeze.</li>
 * </ul>
 */
@Service
public class PhysicalInventories {

    private final JdbcClient jdbc;
    private final CountRequests requests;
    private final CountService counts;
    private final Clock clock;

    public PhysicalInventories(JdbcClient jdbc, CountRequests requests, CountService counts, Clock clock) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.counts = counts;
        this.clock = clock;
    }

    public record CreateRequest(List<String> zones, Boolean freeze, String note) {
    }

    @Transactional
    public Map<String, Object> create(String siteId, CreateRequest r) {
        List<String> zones = r.zones() == null ? List.of()
                : r.zones().stream().map(z -> z.trim().toUpperCase()).filter(z -> !z.isEmpty()).distinct().toList();
        boolean open = jdbc.sql("select exists (select 1 from physical_inventory where site_id = :site and status in ('PLANNED', 'COUNTING'))")
                .param("site", siteId).query(Boolean.class).single();
        if (open) {
            throw ApiException.conflict("INV_PI_OPEN", "Site " + siteId + " has a physical inventory in progress");
        }
        long seq = jdbc.sql("select count(*) + 1 from physical_inventory where site_id = :site").param("site", siteId)
                .query(Long.class).single();
        String no = "PI%05d".formatted(seq);
        jdbc.sql("""
                        insert into physical_inventory (id, tenant_id, site_id, pi_no, zones, freeze_stock, status, note,
                                                        created_by, created_at)
                        values (:id, :t, :site, :no, string_to_array(:zones, ','), :freeze, 'PLANNED', :note, :user, :now)""")
                .param("id", UUID.randomUUID()).param("t", TenantContext.tenantId()).param("site", siteId).param("no", no)
                .param("zones", String.join(",", zones)).param("freeze", r.freeze() == null || r.freeze())
                .param("note", r.note()).param("user", TenantContext.require().userId())
                .param("now", Timestamp.from(clock.instant())).update();
        return detail(siteId, no);
    }

    @Transactional
    public Map<String, Object> start(String siteId, String piNo) {
        Head h = lock(siteId, piNo);
        if (!"PLANNED".equals(h.status())) {
            throw ApiException.unprocessable("INV_PI_STATUS", "Physical inventory " + piNo + " is " + h.status());
        }
        List<String> locations = jdbc.sql("""
                        select location_id from ref_location
                        where site_id = :site and status = 'ACTIVE'
                          and (cardinality(string_to_array(:zones, ',')) = 0 or zone_id = any(string_to_array(:zones, ',')))
                        order by pick_seq nulls last, location_id""")
                .param("site", siteId).param("zones", String.join(",", h.zones())).query(String.class).list();
        if (locations.isEmpty()) {
            throw ApiException.unprocessable("INV_PI_EMPTY", "No active locations in scope");
        }
        for (String loc : locations) {
            UUID count = requests.open(siteId, loc, "PHYSICAL", "Physical inventory " + piNo);
            jdbc.sql("update stock_count set pi_id = :pi where id = :id").param("pi", h.id()).param("id", count).update();
            if (h.freeze()) {
                jdbc.sql("""
                                insert into location_freeze (tenant_id, site_id, location_id, pi_id) values (:t, :site, :loc, :pi)
                                on conflict do nothing""")
                        .param("t", TenantContext.tenantId()).param("site", siteId).param("loc", loc).param("pi", h.id())
                        .update();
            }
        }
        jdbc.sql("update physical_inventory set status = 'COUNTING', started_by = :user, started_at = :now where id = :id")
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant()))
                .param("id", h.id()).update();
        return detail(siteId, piNo);
    }

    @Transactional
    public Map<String, Object> post(String siteId, String piNo) {
        Head h = lock(siteId, piNo);
        if (!"COUNTING".equals(h.status())) {
            throw ApiException.unprocessable("INV_PI_STATUS", "Physical inventory " + piNo + " is " + h.status());
        }
        int unfinished = jdbc.sql("select count(*) from stock_count where pi_id = :pi and status in ('OPEN', 'RECOUNT')")
                .param("pi", h.id()).query(Integer.class).single();
        if (unfinished > 0) {
            throw ApiException.unprocessable("INV_PI_NOT_COUNTED", unfinished + " location(s) are not counted yet");
        }
        String user = TenantContext.require().userId();
        boolean counted = jdbc.sql("""
                        select exists (select 1 from stock_count_result r join stock_count c on c.id = r.count_id
                                       where c.pi_id = :pi and r.counted_by = :user)""")
                .param("pi", h.id()).param("user", user).query(Boolean.class).single();
        if (counted) {
            throw ApiException.unprocessable("INV_SELF_APPROVAL", "A counter cannot post the physical inventory (INV-008)");
        }
        // Lift the freeze first: the postings are movements at the counted locations.
        jdbc.sql("delete from location_freeze where pi_id = :pi").param("pi", h.id()).update();
        for (UUID count : jdbc.sql("select id from stock_count where pi_id = :pi and status = 'PENDING_APPROVAL' order by location_id")
                .param("pi", h.id()).query(UUID.class).list()) {
            counts.approveForPhysicalInventory(siteId, count);
        }
        jdbc.sql("update physical_inventory set status = 'POSTED', posted_by = :user, posted_at = :now where id = :id")
                .param("user", user).param("now", Timestamp.from(clock.instant())).param("id", h.id()).update();
        return detail(siteId, piNo);
    }

    @Transactional
    public Map<String, Object> cancel(String siteId, String piNo) {
        Head h = lock(siteId, piNo);
        if (!List.of("PLANNED", "COUNTING").contains(h.status())) {
            throw ApiException.unprocessable("INV_PI_STATUS", "Physical inventory " + piNo + " is " + h.status());
        }
        jdbc.sql("""
                        update stock_count set status = 'REJECTED', note = 'Physical inventory cancelled', updated_at = :now
                        where pi_id = :pi and status in ('OPEN', 'RECOUNT', 'PENDING_APPROVAL')""")
                .param("now", Timestamp.from(clock.instant())).param("pi", h.id()).update();
        jdbc.sql("delete from location_freeze where pi_id = :pi").param("pi", h.id()).update();
        jdbc.sql("update physical_inventory set status = 'CANCELLED' where id = :id").param("id", h.id()).update();
        return detail(siteId, piNo);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String siteId) {
        return jdbc.sql("""
                        select p.pi_no, p.status, array_to_string(p.zones, ',') as zones, p.freeze_stock as freeze, p.created_by, p.created_at,
                               p.started_at, p.posted_by, p.posted_at,
                               (select count(*) from stock_count c where c.pi_id = p.id) as locations,
                               (select count(*) from stock_count c where c.pi_id = p.id and c.status not in ('OPEN', 'RECOUNT')) as counted
                        from physical_inventory p where p.site_id = :site order by p.created_at desc limit 100""")
                .param("site", siteId).query().listOfRows();
    }

    /** Header, progress by count status, and every location with a difference (system vs counted, value). */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(String siteId, String piNo) {
        Map<String, Object> head = jdbc.sql("""
                        select id, pi_no, status, array_to_string(zones, ',') as zones, freeze_stock as freeze, note, created_by, created_at,
                               started_by, started_at, posted_by, posted_at
                        from physical_inventory where site_id = :site and pi_no = :no""")
                .param("site", siteId).param("no", piNo).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> unknown(piNo));
        Map<String, Object> out = new HashMap<>(head);
        UUID id = (UUID) head.get("id");
        Map<String, Object> progress = new HashMap<>();
        jdbc.sql("select status, count(*) from stock_count where pi_id = :pi group by status").param("pi", id)
                .query((rs, n) -> progress.put(rs.getString(1), rs.getInt(2))).list();
        out.put("progress", progress);
        out.put("frozenLocations", jdbc.sql("select count(*) from location_freeze where pi_id = :pi").param("pi", id)
                .query(Integer.class).single());
        AccessScope scope = AccessScope.current();
        out.put("differences", jdbc.sql("""
                        select c.location_id, c.status, c.id as count_id, v.owner_id, v.item_no, v.lot_no, v.lpn_id,
                               v.system_qty, v.counted_qty, v.counted_qty - v.system_qty as difference,
                               round((v.counted_qty - v.system_qty) * v.unit_cost, 2) as value
                        from stock_count c join stock_count_variance v on v.count_id = c.id
                        where c.pi_id = :pi and (:ownersAll or v.owner_id in (:owners))
                        order by c.location_id, v.item_no""")
                .param("pi", id).param("ownersAll", scope.ownersAll()).param("owners", scope.ownerList())
                .query().listOfRows().stream().map(PhysicalInventories::strip).toList());
        BigDecimal net = jdbc.sql("""
                        select coalesce(sum((v.counted_qty - v.system_qty) * coalesce(v.unit_cost, 0)), 0)
                        from stock_count c join stock_count_variance v on v.count_id = c.id where c.pi_id = :pi""")
                .param("pi", id).query(BigDecimal.class).single();
        out.put("netValue", net.setScale(2, java.math.RoundingMode.HALF_UP));
        out.remove("id");
        return out;
    }

    private record Head(UUID id, String status, List<String> zones, boolean freeze) {
    }

    private Head lock(String siteId, String piNo) {
        return jdbc.sql("""
                        select id, status, array_to_string(zones, ','), freeze_stock from physical_inventory
                        where site_id = :site and pi_no = :no for update""")
                .param("site", siteId).param("no", piNo)
                .query((rs, n) -> new Head(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3) == null || rs.getString(3).isEmpty() ? List.of() : List.of(rs.getString(3).split(",")),
                        rs.getBoolean(4)))
                .optional().orElseThrow(() -> unknown(piNo));
    }

    private static ApiException unknown(String piNo) {
        return ApiException.notFound("INV_PI_UNKNOWN", "No physical inventory " + piNo);
    }

    private static Map<String, Object> strip(Map<String, Object> row) {
        Map<String, Object> out = new HashMap<>(row);
        out.replaceAll((k, v) -> v instanceof BigDecimal b
                ? (b.stripTrailingZeros().scale() < 0 ? b.stripTrailingZeros().setScale(0) : b.stripTrailingZeros()) : v);
        return out;
    }
}
