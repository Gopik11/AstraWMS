package com.astrawms.inventory.service;

import com.astrawms.common.contracts.InventoryContracts.MoveRequested;
import com.astrawms.common.contracts.InventoryContracts.SlottingChanged;
import com.astrawms.common.contracts.OutboundContracts;
import com.astrawms.common.messaging.OutboxWriter;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import com.astrawms.inventory.domain.Quantities;
import com.astrawms.inventory.persistence.AllocationRepository;
import com.astrawms.inventory.reference.ReferenceRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slotting (ADR-0021): the item–location master and velocity-based slot suggestions.
 * <ul>
 *   <li>An item's <b>pick face</b> is its min/max rule ({@link Replenishments}); its <b>reserve zone</b>, units per pallet
 *       and velocity class are kept here and sent to the task service, which directs putaway with them.</li>
 *   <li><b>Velocity</b> is computed from 30 days of picks (pick transactions per item, Pareto: items making the first 80 %
 *       of picks are A, the next 15 % B, the rest C); a class set by hand wins.</li>
 *   <li><b>Golden zone</b>: pick-zone locations in travel-path order. An A item whose face is further down the path than
 *       a free golden slot gets that slot suggested; a C item occupying one of the first slots gets a slower one.</li>
 *   <li><b>Reslot</b> moves the face: the rule moves to the new location and the free stock left at the old face
 *       becomes RF MOVE tasks to the new one.</li>
 * </ul>
 */
@Component
public class Slotting {

    private static final Duration HISTORY = Duration.ofDays(30);

    private final JdbcClient jdbc;
    private final Replenishments replenishments;
    private final ReferenceRepository refs;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final com.astrawms.inventory.events.InventoryEvents.Topics topics;
    private final int movePriority;

    public Slotting(JdbcClient jdbc, Replenishments replenishments, ReferenceRepository refs, OutboxWriter outbox,
                    Clock clock, com.astrawms.inventory.events.InventoryEvents.Topics topics,
                    @Value("${astra.inventory.reslot.priority:45}") int movePriority) {
        this.jdbc = jdbc;
        this.replenishments = replenishments;
        this.refs = refs;
        this.outbox = outbox;
        this.clock = clock;
        this.topics = topics;
        this.movePriority = movePriority;
    }

    // ------------------------------------------------------------------ master

    @Transactional
    public Map<String, Object> put(String siteId, String ownerId, String itemNo, String reserveZone,
                                   BigDecimal unitsPerPallet, String velocityClass) {
        refs.item(ownerId, itemNo, siteId).orElseThrow(() -> ApiException.unprocessable("INV_ITEM_UNKNOWN",
                "Item " + itemNo + " of owner " + ownerId + " is not known at site " + siteId));
        String velocity = velocityClass == null || velocityClass.isBlank() ? null : velocityClass.trim().toUpperCase();
        if (velocity != null && !List.of("A", "B", "C").contains(velocity)) {
            throw ApiException.badRequest("INV_SLOTTING_INVALID", "velocityClass must be A, B, C or empty (computed)");
        }
        if (unitsPerPallet != null && unitsPerPallet.signum() <= 0) {
            throw ApiException.badRequest("INV_SLOTTING_INVALID", "unitsPerPallet must be positive");
        }
        String zone = reserveZone == null || reserveZone.isBlank() ? null : reserveZone.trim().toUpperCase();
        Instant now = clock.instant();
        jdbc.sql("""
                        insert into item_slotting (tenant_id, site_id, owner_id, item_no, reserve_zone, units_per_pallet,
                                                   velocity_class, updated_by, updated_at)
                        values (:t, :site, :owner, :item, :zone, :upp, :vel, :user, :now)
                        on conflict (tenant_id, site_id, owner_id, item_no) do update set reserve_zone = excluded.reserve_zone,
                            units_per_pallet = excluded.units_per_pallet, velocity_class = excluded.velocity_class,
                            updated_by = excluded.updated_by, updated_at = excluded.updated_at""")
                .param("t", TenantContext.tenantId()).param("site", siteId).param("owner", ownerId).param("item", itemNo)
                .param("zone", zone).param("upp", unitsPerPallet).param("vel", velocity)
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(now)).update();
        publish(siteId, ownerId, itemNo, zone, unitsPerPallet, velocity == null ? computedClass(siteId, ownerId, itemNo) : velocity);
        return row(siteId, ownerId, itemNo);
    }

    private void publish(String siteId, String ownerId, String itemNo, String zone, BigDecimal upp, String velocity) {
        outbox.append(new OutboxWriter.Message(topics.inventoryEvents(), SlottingChanged.TYPE, SlottingChanged.VERSION, null,
                siteId, ownerId, siteId + ":" + ownerId + ":" + itemNo,
                new SlottingChanged(ownerId, itemNo, zone, upp, velocity, clock.instant())));
    }

    // ------------------------------------------------------------------ analysis

    /** Every item with stock, a face or a slotting record at the site, with velocity and a slot suggestion. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> analysis(String siteId) {
        Map<String, Long> picks = pickCounts(siteId);
        Map<String, String> computed = abc(picks);
        List<Map<String, Object>> rows = jdbc.sql("""
                        with items as (
                            select owner_id, item_no from inventory_balance where site_id = :site and qty > 0
                            union select owner_id, item_no from replen_rule where site_id = :site and active
                            union select owner_id, item_no from item_slotting where site_id = :site)
                        select i.owner_id, i.item_no, s.reserve_zone, s.units_per_pallet, s.velocity_class as velocity_set,
                               r.location_id as pick_face, r.min_qty, r.max_qty, l.pick_seq as face_seq,
                               (select coalesce(sum(b.qty), 0) from inventory_balance b where b.site_id = :site
                                  and b.owner_id = i.owner_id and b.item_no = i.item_no) as on_hand,
                               (select coalesce(sum(-t.qty_delta), 0) from inventory_txn t where t.site_id = :site
                                  and t.owner_id = i.owner_id and t.item_no = i.item_no and t.txn_type = 'PICK_OUT'
                                  and t.occurred_at >= :since) as units_picked
                        from items i
                        left join item_slotting s on s.site_id = :site and s.owner_id = i.owner_id and s.item_no = i.item_no
                        left join replen_rule r on r.site_id = :site and r.owner_id = i.owner_id and r.item_no = i.item_no and r.active
                        left join ref_location l on l.site_id = :site and l.location_id = r.location_id
                        order by i.owner_id, i.item_no""")
                .param("site", siteId).param("since", Timestamp.from(clock.instant().minus(HISTORY)))
                .query().listOfRows();
        List<Slot> golden = freeGoldenSlots(siteId);
        List<Map<String, Object>> faces = jdbc.sql("""
                        select r.location_id, l.pick_seq from replen_rule r
                        join ref_location l on l.site_id = r.site_id and l.location_id = r.location_id
                        where r.site_id = :site and r.active and l.pick_seq is not null order by l.pick_seq""")
                .param("site", siteId).query().listOfRows();
        long aItems = rows.stream().filter(r -> "A".equals(effective(r, computed))).count();
        int goldenSlotsUsed = 0;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            String key = r.get("owner_id") + "|" + r.get("item_no");
            String velocity = effective(r, computed);
            m.put("picks_30d", picks.getOrDefault(key, 0L));
            m.put("velocity_class", velocity);
            m.put("velocity_source", r.get("velocity_set") == null ? "COMPUTED" : "SET");
            m.put("units_picked", Quantities.normalize((BigDecimal) r.get("units_picked")));
            m.put("on_hand", Quantities.normalize((BigDecimal) r.get("on_hand")));
            Integer seq = (Integer) r.get("face_seq");
            String suggestion = null;
            String why = null;
            if ("A".equals(velocity) && r.get("pick_face") == null && goldenSlotsUsed < golden.size()) {
                Slot s = golden.get(goldenSlotsUsed++);
                suggestion = s.location();
                why = "Fast mover without a pick face: give it the free golden slot " + s.location();
            } else if ("A".equals(velocity) && seq != null && goldenSlotsUsed < golden.size()
                       && golden.get(goldenSlotsUsed).seq() < seq) {
                Slot s = golden.get(goldenSlotsUsed++);
                suggestion = s.location();
                why = "Fast mover at path position " + seq + "; " + s.location() + " is closer (" + s.seq() + ")";
            } else if ("C".equals(velocity) && seq != null && rank(faces, (String) r.get("pick_face")) < aItems
                       && !golden.isEmpty()) {
                Slot s = golden.getLast();
                suggestion = s.location();
                why = "Slow mover in one of the first " + aItems + " pick slots; free it for a fast mover";
            }
            m.put("suggested_face", suggestion);
            m.put("suggestion_reason", why);
            out.add(m);
        }
        return out;
    }

    private static String effective(Map<String, Object> r, Map<String, String> computed) {
        Object set = r.get("velocity_set");
        return set != null ? set.toString() : computed.getOrDefault(r.get("owner_id") + "|" + r.get("item_no"), "C");
    }

    private static int rank(List<Map<String, Object>> faces, String location) {
        for (int i = 0; i < faces.size(); i++) {
            if (faces.get(i).get("location_id").equals(location)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private record Slot(String location, int seq) {
    }

    /** Pick-zone locations without a face and without stock, in travel-path order. */
    private List<Slot> freeGoldenSlots(String siteId) {
        return jdbc.sql("""
                        select l.location_id, l.pick_seq from ref_location l
                        where l.site_id = :site and l.status = 'ACTIVE' and coalesce(l.zone_type, '') in (:pick)
                          and l.pick_seq is not null
                          and not exists (select 1 from replen_rule r where r.site_id = l.site_id and r.location_id = l.location_id and r.active)
                          and not exists (select 1 from inventory_balance b where b.site_id = l.site_id and b.location_id = l.location_id and b.qty > 0)
                        order by l.pick_seq, l.location_id""")
                .param("site", siteId).param("pick", AllocationRepository.PICK_ZONES)
                .query((rs, n) -> new Slot(rs.getString(1), rs.getInt(2))).list();
    }

    private Map<String, Long> pickCounts(String siteId) {
        Map<String, Long> out = new HashMap<>();
        jdbc.sql("""
                        select owner_id, item_no, count(distinct operation_id) from inventory_txn
                        where site_id = :site and txn_type = 'PICK_OUT' and occurred_at >= :since group by owner_id, item_no""")
                .param("site", siteId).param("since", Timestamp.from(clock.instant().minus(HISTORY)))
                .query((rs, n) -> out.put(rs.getString(1) + "|" + rs.getString(2), rs.getLong(3))).list();
        return out;
    }

    /** Pareto classes by pick count: the items making the first 80 % of picks are A, the next 15 % B, the rest C. */
    static Map<String, String> abc(Map<String, Long> picks) {
        long total = picks.values().stream().mapToLong(Long::longValue).sum();
        Map<String, String> out = new HashMap<>();
        long running = 0;
        for (Map.Entry<String, Long> e : picks.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).toList()) {
            double before = total == 0 ? 1 : (double) running / total;
            out.put(e.getKey(), before < 0.80 ? "A" : before < 0.95 ? "B" : "C");
            running += e.getValue();
        }
        return out;
    }

    private String computedClass(String siteId, String ownerId, String itemNo) {
        return abc(pickCounts(siteId)).getOrDefault(ownerId + "|" + itemNo, "C");
    }

    private Map<String, Object> row(String siteId, String ownerId, String itemNo) {
        return analysis(siteId).stream().filter(r -> ownerId.equals(r.get("owner_id")) && itemNo.equals(r.get("item_no")))
                .findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ reslot

    /**
     * Moves the item's pick face to {@code toLocation}: the min/max rule moves (same min/max unless given), and every
     * unallocated balance left at the old face becomes an RF MOVE task to the new face. Allocated stock stays for its
     * picks. Returns the moves requested.
     */
    @Transactional
    public Map<String, Object> reslot(String siteId, String ownerId, String itemNo, String toLocation, BigDecimal min,
                                      BigDecimal max) {
        String to = toLocation == null ? "" : toLocation.trim().toUpperCase();
        refs.location(siteId, to).orElseThrow(() -> ApiException.unprocessable("INV_LOCATION_UNKNOWN", "Location " + to + " does not exist"));
        record Rule(String location, BigDecimal min, BigDecimal max) {
        }
        List<Rule> old = jdbc.sql("""
                        select location_id, min_qty, max_qty from replen_rule
                        where site_id = :site and owner_id = :owner and item_no = :item and active""")
                .param("site", siteId).param("owner", ownerId).param("item", itemNo)
                .query((rs, n) -> new Rule(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3))).list();
        if (old.stream().anyMatch(r -> r.location().equals(to))) {
            throw ApiException.unprocessable("INV_RESLOT_SAME_LOCATION", to + " is already the pick face of " + itemNo);
        }
        boolean taken = jdbc.sql("""
                        select exists (select 1 from replen_rule where site_id = :site and location_id = :loc and active
                                         and (owner_id, item_no) <> (:owner, :item))""")
                .param("site", siteId).param("loc", to).param("owner", ownerId).param("item", itemNo)
                .query(Boolean.class).single();
        if (taken) {
            throw ApiException.conflict("INV_RESLOT_LOCATION_TAKEN", to + " is the pick face of another item");
        }
        BigDecimal newMin = min != null ? min : old.isEmpty() ? null : old.getFirst().min();
        BigDecimal newMax = max != null ? max : old.isEmpty() ? null : old.getFirst().max();
        if (newMin == null || newMax == null) {
            throw ApiException.badRequest("INV_RESLOT_MINMAX_REQUIRED", itemNo + " has no pick face yet: give minQty and maxQty");
        }
        for (Rule r : old) {
            replenishments.putRule(siteId, r.location(), ownerId, itemNo, r.min(), r.max(), false);
        }
        replenishments.putRule(siteId, to, ownerId, itemNo, newMin, newMax, true);
        String baseUom = refs.item(ownerId, itemNo, siteId).map(i -> i.baseUom()).orElse(null);
        List<Map<String, Object>> moves = new ArrayList<>();
        for (Rule r : old) {
            for (Map<String, Object> b : jdbc.sql("""
                            select lot_no, lpn_id, qty - allocated_qty as free from inventory_balance
                            where site_id = :site and location_id = :loc and owner_id = :owner and item_no = :item
                              and stock_status = 'AVAILABLE' and qty > allocated_qty""")
                    .param("site", siteId).param("loc", r.location()).param("owner", ownerId).param("item", itemNo)
                    .query().listOfRows()) {
                UUID id = UUID.randomUUID();
                BigDecimal qty = Quantities.normalize((BigDecimal) b.get("free"));
                outbox.append(new OutboxWriter.Message(OutboundContracts.TOPIC_TASK_REQUESTS, MoveRequested.TYPE,
                        MoveRequested.VERSION, null, siteId, ownerId, siteId + ":" + r.location(),
                        new MoveRequested(id, ownerId, itemNo, (String) b.get("lot_no"), (String) b.get("lpn_id"), qty,
                                baseUom, r.location(), to, "RESLOT", movePriority)));
                moves.add(Map.of("moveId", id, "from", r.location(), "to", to, "qty", qty, "lpnId", b.get("lpn_id")));
            }
        }
        return Map.of("ownerId", ownerId, "itemNo", itemNo, "pickFace", to, "moves", moves);
    }
}
