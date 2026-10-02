package com.astrawms.inventory.service;

import com.astrawms.common.contracts.InventoryContracts.PickFaceChanged;
import com.astrawms.common.contracts.InventoryContracts.ReplenRequested;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Min/max replenishment (§7): when the free stock of an item at a forward location (one with a rule) falls to its
 * minimum, stock up to the maximum is reserved in reserve locations in rotation order (FEFO, RPL-001) and an RF task
 * moves it. Free stock is on hand minus what open allocations hold plus replenishments under way, so an allocation that
 * would empty the face triggers it before the pick (demand replenishment, ADR-0019). Evaluated after every inventory
 * operation that takes stock out of a location, after allocations, and on request.
 */
@Component
public class Replenishments {

    public record Rule(String locationId, String ownerId, String itemNo, BigDecimal minQty, BigDecimal maxQty,
                       boolean active) {
    }

    private final JdbcClient jdbc;
    private final AllocationRepository allocations;
    private final ReferenceRepository refs;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final int priority;
    private final com.astrawms.inventory.events.InventoryEvents.Topics topics;

    public Replenishments(JdbcClient jdbc, AllocationRepository allocations, ReferenceRepository refs, OutboxWriter outbox,
                          Clock clock, @Value("${astra.inventory.replenishment.priority:70}") int priority,
                          com.astrawms.inventory.events.InventoryEvents.Topics topics) {
        this.topics = topics;
        this.jdbc = jdbc;
        this.allocations = allocations;
        this.refs = refs;
        this.outbox = outbox;
        this.clock = clock;
        this.priority = priority;
    }

    // ------------------------------------------------------------------ rules

    @Transactional
    public Rule putRule(String siteId, String locationId, String ownerId, String itemNo, BigDecimal min, BigDecimal max,
                        boolean active) {
        if (min == null || max == null || min.signum() < 0 || max.compareTo(min) <= 0) {
            throw ApiException.badRequest("INV_REPLEN_RULE_INVALID", "Require 0 ≤ minQty < maxQty (base unit)");
        }
        refs.item(ownerId, itemNo, siteId).orElseThrow(() -> ApiException.unprocessable("INV_ITEM_UNKNOWN",
                "Item " + itemNo + " of owner " + ownerId + " is not known at site " + siteId));
        refs.location(siteId, locationId).orElseThrow(() ->
                ApiException.unprocessable("INV_LOCATION_UNKNOWN", "Location " + locationId + " does not exist"));
        jdbc.sql("""
                        insert into replen_rule (tenant_id, site_id, location_id, owner_id, item_no, min_qty, max_qty, active,
                                                 updated_by, updated_at)
                        values (:t, :site, :loc, :owner, :item, :min, :max, :active, :user, :now)
                        on conflict (tenant_id, site_id, location_id, owner_id, item_no) do update set
                            min_qty = excluded.min_qty, max_qty = excluded.max_qty, active = excluded.active,
                            updated_by = excluded.updated_by, updated_at = excluded.updated_at""")
                .param("t", TenantContext.tenantId()).param("site", siteId).param("loc", locationId)
                .param("owner", ownerId).param("item", itemNo).param("min", min).param("max", max)
                .param("active", active).param("user", TenantContext.require().userId())
                .param("now", Timestamp.from(clock.instant())).update();
        // The task service directs putaway to the item's pick faces (ADR-0019).
        outbox.append(new OutboxWriter.Message(topics.inventoryEvents(), PickFaceChanged.TYPE, PickFaceChanged.VERSION,
                null, siteId, ownerId, siteId + ":" + locationId,
                new PickFaceChanged(locationId, ownerId, itemNo, min, max, active, clock.instant())));
        if (active) {
            evaluate(siteId, locationId, ownerId, itemNo, "MIN_MAX");
        }
        return new Rule(locationId, ownerId, itemNo, min, max, active);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> rules(String siteId) {
        return jdbc.sql("""
                        select r.location_id, r.owner_id, r.item_no, r.min_qty, r.max_qty, r.active,
                               coalesce((select sum(b.qty) from inventory_balance b where b.site_id = r.site_id
                                         and b.location_id = r.location_id and b.owner_id = r.owner_id
                                         and b.item_no = r.item_no and b.stock_status = 'AVAILABLE'), 0) as on_hand,
                               coalesce((select sum(p.qty) from replenishment p where p.site_id = r.site_id
                                         and p.location_id = r.location_id and p.owner_id = r.owner_id
                                         and p.item_no = r.item_no and p.status = 'OPEN'), 0) as incoming
                        from replen_rule r where r.site_id = :site order by r.location_id, r.owner_id, r.item_no""")
                .param("site", siteId).query().listOfRows();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String siteId, String status) {
        return jdbc.sql("""
                        select id, location_id, owner_id, item_no, lot_no, qty, source_location, source_lpn, trigger, status,
                               created_at, completed_at
                        from replenishment where site_id = :site and (cast(:status as text) is null or status = :status)
                        order by created_at desc limit 500""")
                .param("site", siteId).param("status", status).query().listOfRows();
    }

    // ------------------------------------------------------------------ trigger

    /** After an operation took stock out of {@code locations}: replenish the forward locations among them. */
    void onStockDecreased(String siteId, List<String[]> locationOwnerItem) {
        for (String[] loi : locationOwnerItem) {
            evaluate(siteId, loi[0], loi[1], loi[2], "MIN_MAX");
        }
    }

    /** Whether the item has an active pick face at the site. */
    public boolean hasPickFace(String siteId, String ownerId, String itemNo) {
        return jdbc.sql("""
                        select exists (select 1 from replen_rule where site_id = :site and owner_id = :owner
                          and item_no = :item and active)""")
                .param("site", siteId).param("owner", ownerId).param("item", itemNo).query(Boolean.class).single();
    }

    /** After an allocation of the item: replenish every face of it whose free stock fell to its minimum. */
    void onDemand(String siteId, String ownerId, String itemNo) {
        jdbc.sql("select location_id from replen_rule where site_id = :site and owner_id = :owner and item_no = :item and active")
                .param("site", siteId).param("owner", ownerId).param("item", itemNo).query(String.class).list()
                .forEach(loc -> evaluate(siteId, loc, ownerId, itemNo, "MIN_MAX"));
    }

    /** Evaluates every active rule of the site (top-off run); returns the replenishments created. */
    @Transactional
    public int evaluateAll(String siteId) {
        List<String[]> keys = jdbc.sql("select location_id, owner_id, item_no from replen_rule where site_id = :site and active")
                .param("site", siteId)
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}).list();
        int created = 0;
        for (String[] k : keys) {
            created += evaluate(siteId, k[0], k[1], k[2], "MANUAL");
        }
        return created;
    }

    private int evaluate(String siteId, String locationId, String ownerId, String itemNo, String trigger) {
        record R(BigDecimal min, BigDecimal max) {
        }
        R rule = jdbc.sql("""
                        select min_qty, max_qty from replen_rule where site_id = :site and location_id = :loc
                          and owner_id = :owner and item_no = :item and active""")
                .param("site", siteId).param("loc", locationId).param("owner", ownerId).param("item", itemNo)
                .query((rs, n) -> new R(rs.getBigDecimal(1), rs.getBigDecimal(2))).optional().orElse(null);
        if (rule == null) {
            return 0;
        }
        BigDecimal onHand = sum("""
                select coalesce(sum(qty - allocated_qty), 0) from inventory_balance where site_id = :site
                  and location_id = :loc and owner_id = :owner and item_no = :item and stock_status = 'AVAILABLE'""",
                siteId, locationId, ownerId, itemNo);
        BigDecimal incoming = sum("""
                select coalesce(sum(qty), 0) from replenishment where site_id = :site and location_id = :loc
                  and owner_id = :owner and item_no = :item and status = 'OPEN'""", siteId, locationId, ownerId, itemNo);
        BigDecimal projected = onHand.add(incoming);
        if (projected.compareTo(rule.min()) > 0) {
            return 0;
        }
        BigDecimal wanted = rule.max().subtract(projected);
        // Never from staging, the target itself or other forward locations (they are replenished, not sources).
        List<String> excluded = new ArrayList<>(jdbc.sql("""
                        select distinct location_id from replen_rule where site_id = :site and owner_id = :owner
                          and item_no = :item and active""")
                .param("site", siteId).param("owner", ownerId).param("item", itemNo).query(String.class).list());
        excluded.add(locationId);
        int created = 0;
        for (AllocationRepository.Candidate c : allocations.candidates(siteId, ownerId, itemNo, null, null, true, excluded)) {
            if (wanted.signum() <= 0) {
                break;
            }
            if (c.face()) {
                continue;   // other pick faces are replenished, not sources
            }
            BigDecimal take = c.free().min(wanted);
            allocations.reserve(c.key(), take);
            UUID allocationId = UUID.randomUUID();
            UUID id = UUID.randomUUID();
            allocations.insert(allocationId, c.key(), "REPL-" + id, "REPL", take, clock.instant());
            jdbc.sql("""
                            insert into replenishment (id, tenant_id, site_id, location_id, owner_id, item_no, lot_no, qty,
                                                       source_location, source_lpn, allocation_id, trigger, status, created_at)
                            values (:id, :t, :site, :loc, :owner, :item, :lot, :qty, :src, :srcLpn, :alloc, :trigger, 'OPEN', :now)""")
                    .param("id", id).param("t", TenantContext.tenantId()).param("site", siteId).param("loc", locationId)
                    .param("owner", ownerId).param("item", itemNo).param("lot", c.key().lotNo()).param("qty", take)
                    .param("src", c.key().locationId()).param("srcLpn", c.key().lpnId()).param("alloc", allocationId)
                    .param("trigger", trigger).param("now", Timestamp.from(clock.instant())).update();
            String baseUom = refs.item(ownerId, itemNo, siteId).map(i -> i.baseUom()).orElse(null);
            outbox.append(new OutboxWriter.Message(OutboundContracts.TOPIC_TASK_REQUESTS, ReplenRequested.TYPE,
                    ReplenRequested.VERSION, null, siteId, ownerId, siteId + ":" + locationId,
                    new ReplenRequested(id, ownerId, itemNo, c.key().lotNo(), Quantities.normalize(take), baseUom,
                            c.key().locationId(), c.key().lpnId(), locationId, priority)));
            wanted = wanted.subtract(take);
            created++;
        }
        return created;
    }

    private BigDecimal sum(String sql, String siteId, String locationId, String ownerId, String itemNo) {
        return jdbc.sql(sql).param("site", siteId).param("loc", locationId).param("owner", ownerId).param("item", itemNo)
                .query(BigDecimal.class).single();
    }
}
