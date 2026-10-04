package com.astrawms.outbound.service;

import com.astrawms.common.contracts.IntegrationContracts;
import com.astrawms.common.contracts.IntegrationContracts.ApplicationAck;
import com.astrawms.common.contracts.IntegrationContracts.ErpPostingResult;
import com.astrawms.common.contracts.OutboundContracts;
import com.astrawms.common.contracts.OutboundContracts.OutboundOrder;
import com.astrawms.common.contracts.OutboundContracts.ShipmentConfirmation;
import com.astrawms.common.contracts.OutboundContracts.TaskCompleted;
import com.astrawms.common.ids.WmsTxnId;
import com.astrawms.common.messaging.EventEnvelope;
import com.astrawms.common.messaging.OutboxWriter;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import com.astrawms.outbound.inventory.InventoryClient;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbound order lifecycle (scope §3–5, ADR-0009, ADR-0011):
 * <pre>
 * POOLED (wave mode) ──wave release──┐
 *                                    ├─▶ RELEASED (allocated, picks requested) or BACKORDERED (nothing allocable)
 * (waveless mode: on receipt) ───────┘      → PICKED → SHIPPED → CONFIRMED or SHIP_ERROR
 * cancel: POOLED/BACKORDERED → CANCELLED; after release → CANCEL_REQUESTED (picked stock returning) → CANCELLED
 * </pre>
 * Shortfalls are reported to the ERP (OUT-EX-01); a short pick is first re-allocated from other locations (PCK-003).
 */
@Service
public class OutboundService {

    private final JdbcClient jdbc;
    private final InventoryClient inventory;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final Clock clock;
    private final String stagingLocation;
    private final int pickPriority;
    private final int returnPriority;
    private final com.astrawms.outbound.packing.PackingService packing;
    private final ReleasePolicy policy;

    public OutboundService(JdbcClient jdbc, InventoryClient inventory, OutboxWriter outbox, JsonMapper json, Clock clock,
                           com.astrawms.outbound.packing.PackingService packing, ReleasePolicy policy,
                           @Value("${astra.outbound.staging-location:STAGE-OUT}") String stagingLocation,
                           @Value("${astra.outbound.pick-priority:60}") int pickPriority,
                           @Value("${astra.outbound.return-priority:70}") int returnPriority) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
        this.stagingLocation = stagingLocation;
        this.pickPriority = pickPriority;
        this.returnPriority = returnPriority;
        this.packing = packing;
        this.policy = policy;
    }

    record Order(UUID id, String siteId, String erpDocNo, String status, long revision, String pickLpn,
                 String shipmentTxnId, boolean shipComplete, Instant cutoffAt) {
    }

    /** Where the ERP acknowledgement of an order message goes (IF-OB-002); kept while a cancellation is pending. */
    record AckTarget(String sourceSystem, String siteId, String ownerId, String messageId, String sourceIdocOrEventId,
                     String erpDocNo) {

        static AckTarget of(EventEnvelope envelope, OutboundOrder o) {
            return new AckTarget(envelope.sourceSystem(), envelope.siteId(), envelope.ownerId(),
                    envelope.messageId().toString(), o.sourceIdocOrEventId(), o.erpDocNo());
        }
    }

    // =====================================================================================================
    // IF-OB-001: orders from the ERP
    // =====================================================================================================

    @Transactional
    public void onOrder(EventEnvelope envelope, OutboundOrder o) {
        String site = envelope.siteId();
        AckTarget ack = AckTarget.of(envelope, o);
        Optional<Order> existing = lockOrder(site, o.erpDocNo());
        if (existing.isPresent()) {
            Order cur = existing.get();
            if (o.revision() <= cur.revision()) {
                ack(ack, null, null);                       // stale or duplicate: acknowledged, ignored
                return;
            }
            if ("CANCEL".equals(o.action())) {
                cancel(ack, o, cur);
            } else if ("POOLED".equals(cur.status())) {
                replacePooled(envelope, o, cur);              // OUT-002: changes accepted until release
                ack(ack, null, null);
            } else {
                // IF-OB-002 §6.1: after release, changes are rejected (the ERP re-plans with a new delivery).
                ack(ack, "RELEASED_TO_PICK", "Order is " + cur.status() + "; create a new delivery for changes");
            }
            return;
        }
        if ("CANCEL".equals(o.action())) {
            ack(ack, null, null);
            return;
        }
        create(site, o, envelope.sourceSystem(), null, null);
        ack(ack, null, null);
    }

    /** Stores a new order and, in waveless mode, releases it (allocation and picks). */
    private UUID create(String site, OutboundOrder o, String sourceSystem, String transferTo, String note) {
        return create(site, o, sourceSystem, transferTo, note, null);
    }

    /**
     * {@code attempt}: part of the allocation idempotency keys. A strict transfer that is refused leaves its number
     * free for the next transfer; its own attempt keeps the next one's allocations from replaying the refused ones.
     */
    private UUID create(String site, OutboundOrder o, String sourceSystem, String transferTo, String note, String attempt) {
        boolean pooled = "WAVE".equals(releaseMode(site));
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        Instant cutoff = policy.cutoffAt(site, o.carrierScac(), o.plannedGoodsIssueUtc());
        boolean shipComplete = policy.shipComplete(site,
                o.lines().stream().map(OutboundOrder.Line::ownerId).distinct().toList());
        jdbc.sql("""
                        insert into outbound_order (id, tenant_id, site_id, erp_doc_no, order_type, revision, source_system,
                            ship_to, carrier_scac, planned_gi_utc, status, staging_location, pick_lpn, created_at, updated_at,
                            cutoff_at, ship_complete, transfer_to_site, note)
                        values (:id, :t, :site, :doc, :type, :rev, :src, cast(:shipTo as jsonb), :scac, :gi, :status,
                                :staging, :pickLpn, :now, :now, :cutoff, :sc, :transferTo, :note)""")
                .param("transferTo", transferTo).param("note", note)
                .param("cutoff", cutoff == null ? null : Timestamp.from(cutoff)).param("sc", shipComplete)
                .param("id", id).param("t", TenantContext.tenantId()).param("site", site).param("doc", o.erpDocNo())
                .param("type", o.orderType()).param("rev", o.revision()).param("src", sourceSystem)
                .param("shipTo", o.shipTo() == null ? null : json.writeValueAsString(o.shipTo()))
                .param("scac", o.carrierScac())
                .param("gi", o.plannedGoodsIssueUtc() == null ? null : Timestamp.from(o.plannedGoodsIssueUtc()))
                .param("status", pooled ? "POOLED" : "RELEASED")
                .param("staging", stagingLocation).param("pickLpn", "PK-" + o.erpDocNo()).param("now", now).update();
        insertLines(id, o.lines());
        if (!pooled) {
            allocateAndRelease(lockOrder(id), attempt);
        }
        return id;
    }

    // =====================================================================================================
    // Transfers between sites started in the WMS (ADR-0023)
    // =====================================================================================================

    public record TransferLine(String ownerId, String itemNo, java.math.BigDecimal qty, String uom, String lotNo) {
    }

    /**
     * {@code priority} (default 50) and {@code criticality} (default NORMAL) rank it in the short-stock queue.
     * {@code strict} (an accepted replenishment, ADR-0025): refused unless the source has the quantity free (on hand,
     * less allocated, less what open orders and transfers still wait for) and no open transfer to the same store
     * already carries the item.
     */
    public record TransferRequest(String toSiteId, String carrierScac, Instant plannedShipUtc, String note,
                                  List<TransferLine> lines, Integer priority, String criticality, java.math.BigDecimal distanceKm,
                                  Boolean strict) {
        public TransferRequest(String toSiteId, String carrierScac, Instant plannedShipUtc, String note, List<TransferLine> lines) {
            this(toSiteId, carrierScac, plannedShipUtc, note, lines, null, null, null, null);
        }
    }

    /**
     * A strict transfer is allocated in full or not at all (ADR-0025, review 2): free stock the site policy cannot
     * allocate now (a reserve pallet the transfer does not cover while the pick face waits for its replenishment) is
     * not a reservation. Anything allocated is released and the transfer is not created; the refusal says why.
     */
    private void requireAllocated(String siteId, UUID orderId, String transferNo) {
        List<Map<String, Object>> shorts = jdbc.sql("""
                        select erp_line_ref, item_no, qty_short, short_reason, short_detail from outbound_line
                        where order_id = :o and qty_short > 0 order by erp_line_ref """)
                .param("o", orderId).query().listOfRows();
        if (shorts.isEmpty()) {
            return;
        }
        inventory.release(siteId, "OUT-STRICT-" + orderId, transferNo);
        throw ApiException.conflict("OUT_NOT_ALLOCABLE", "Nothing was reserved: " + shorts.stream()
                .map(l -> l.get("item_no") + " short " + strip((BigDecimal) l.get("qty_short")).toPlainString()
                        + (l.get("short_detail") == null ? "" : " (" + l.get("short_detail") + ")"))
                .collect(java.util.stream.Collectors.joining("; "))
                + ". Accept again when the stock can be allocated, e.g. after the pick-face replenishment is confirmed on RF.");
    }

    /** The source's free quantity for a strict transfer, and who holds the rest when it is not enough. */
    private void requireFree(String siteId, String toSite, String owner, String item, BigDecimal qty) {
        List<String> open = jdbc.sql("""
                        select distinct o.erp_doc_no from outbound_order o join outbound_line l on l.order_id = o.id
                        where o.site_id = :site and o.transfer_to_site = :to and l.owner_id = :owner and l.item_no = :item
                          and o.status not in ('SHIPPED', 'CONFIRMED', 'CANCELLED', 'SHIP_ERROR')""")
                .param("site", siteId).param("to", toSite).param("owner", owner).param("item", item).query(String.class).list();
        if (!open.isEmpty()) {
            throw ApiException.conflict("OUT_TRANSFER_EXISTS", "Open transfer " + String.join(", ", open) + " already brings "
                    + item + " from " + siteId + " to " + toSite + "; it is counted before recommending again");
        }
        InventoryClient.Availability a = inventory.availability(siteId, owner, item);
        BigDecimal waiting = jdbc.sql("""
                        select coalesce(sum(l.qty_short - l.qty_short_closed), 0) from outbound_order o
                        join outbound_line l on l.order_id = o.id
                        where o.site_id = :site and l.owner_id = :owner and l.item_no = :item
                          and o.status not in ('SHIPPED', 'CONFIRMED', 'CANCELLED', 'SHIP_ERROR')""")
                .param("site", siteId).param("owner", owner).param("item", item).query(BigDecimal.class).single();
        BigDecimal free = a.free().subtract(waiting);
        if (free.compareTo(qty) >= 0) {
            return;
        }
        List<String> holders = jdbc.sql("""
                        select o.erp_doc_no || coalesce(' (to ' || o.transfer_to_site || ')', '') || ': '
                               || case when l.qty_allocated > 0 then trim(to_char(l.qty_allocated, 'FM999999990.###')) || ' allocated' else '' end
                               || case when l.qty_allocated > 0 and l.qty_short - l.qty_short_closed > 0 then ', ' else '' end
                               || case when l.qty_short - l.qty_short_closed > 0
                                       then trim(to_char(l.qty_short - l.qty_short_closed, 'FM999999990.###')) || ' waiting' else '' end
                        from outbound_order o join outbound_line l on l.order_id = o.id
                        where o.site_id = :site and l.owner_id = :owner and l.item_no = :item
                          and o.status not in ('SHIPPED', 'CONFIRMED', 'CANCELLED', 'SHIP_ERROR')
                          and (l.qty_allocated > 0 or l.qty_short - l.qty_short_closed > 0)
                        order by l.qty_allocated + l.qty_short desc limit 8""")
                .param("site", siteId).param("owner", owner).param("item", item).query(String.class).list();
        throw ApiException.conflict("OUT_SOURCE_SHORT", siteId + " has " + strip(free.max(BigDecimal.ZERO)).toPlainString() + " of " + item
                + " free (on hand " + strip(a.onHand()).toPlainString() + ", allocated " + strip(a.allocated()).toPlainString() + ", waiting on open orders and transfers "
                + strip(waiting).toPlainString() + "), not " + strip(qty).toPlainString() + (holders.isEmpty() ? "" : ". Held by: " + String.join("; ", holders)));
    }

    /**
     * A transfer to another site (main warehouse to a store, store to store) without an SAP stock transport order:
     * an outbound order of type TRANSFER, released, picked, packed and shipped like any other. At shipment the
     * receiving site gets an expected receipt (WMS_TRANSFER) with what left, lot by lot, and SAP gets a 303 stock
     * transfer to the receiving plant; the receipt there posts 305.
     */
    @Transactional
    public Map<String, Object> createTransfer(String siteId, TransferRequest r) {
        String to = r.toSiteId() == null ? null : r.toSiteId().trim().toUpperCase();
        if (to == null || to.isEmpty() || to.equals(siteId)) {
            throw ApiException.badRequest("OUT_TRANSFER_INVALID", "toSiteId must be another site");
        }
        if (r.lines() == null || r.lines().isEmpty()) {
            throw ApiException.badRequest("OUT_TRANSFER_INVALID", "A transfer needs lines");
        }
        long seq = jdbc.sql("select count(*) + 1 from outbound_order where site_id = :site and transfer_to_site is not null")
                .param("site", siteId).query(Long.class).single();
        String no = "TR-" + siteId + "-" + "%06d".formatted(seq);
        List<OutboundOrder.Line> lines = new ArrayList<>();
        int n = 0;
        for (TransferLine l : r.lines()) {
            if (l.ownerId() == null || l.itemNo() == null || l.qty() == null || l.qty().signum() <= 0 || l.uom() == null) {
                throw ApiException.badRequest("OUT_TRANSFER_INVALID", "Each line needs ownerId, itemNo, a positive qty and uom");
            }
            com.astrawms.common.security.AccessScope.current().requireOwner(l.ownerId().trim().toUpperCase());
            if (Boolean.TRUE.equals(r.strict())) {
                requireFree(siteId, to, l.ownerId().trim().toUpperCase(), l.itemNo().trim(), l.qty());
            }
            n += 10;
            lines.add(new OutboundOrder.Line("%06d".formatted(n), l.ownerId().trim().toUpperCase(), l.itemNo().trim(),
                    l.qty(), l.uom().trim().toUpperCase(), l.lotNo() == null || l.lotNo().isBlank() ? null : l.lotNo().trim()));
        }
        OutboundOrder order = new OutboundOrder(no, "TRANSFER", "CREATE", 1, no,
                new OutboundOrder.ShipTo(to, "Site " + to, null, null),
                r.carrierScac() == null || r.carrierScac().isBlank() ? null : r.carrierScac().trim().toUpperCase(),
                r.plannedShipUtc(), lines, clock.instant());
        String criticality = r.criticality() == null || r.criticality().isBlank() ? "NORMAL" : r.criticality().trim().toUpperCase();
        if (!List.of("LOW", "NORMAL", "HIGH", "CRITICAL").contains(criticality)) {
            throw ApiException.badRequest("OUT_TRANSFER_INVALID", "criticality is LOW, NORMAL, HIGH or CRITICAL");
        }
        String attempt = Boolean.TRUE.equals(r.strict()) ? "S" + UUID.randomUUID().toString().substring(0, 8) : null;
        UUID id = create(siteId, order, "ASTRAWMS", to, r.note(), attempt);
        if (Boolean.TRUE.equals(r.strict())) {
            if ("POOLED".equals(lockOrder(id).status())) {
                allocateAndRelease(lockOrder(id), attempt);   // a reservation is allocated now, wave release or not
            }
            requireAllocated(siteId, id, no);
        }
        jdbc.sql("update outbound_order set priority = :p, criticality = :c, distance_km = :d where id = :id")
                .param("p", r.priority() == null ? 50 : Math.max(0, Math.min(r.priority(), 100))).param("c", criticality)
                .param("d", r.distanceKm()).param("id", id).update();
        return detail(siteId, no);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> transfers(String siteId, String direction) {
        boolean incoming = "IN".equalsIgnoreCase(direction);
        return jdbc.sql("""
                        select o.erp_doc_no, o.site_id as from_site, o.transfer_to_site as to_site, o.status, o.carrier_scac,
                               o.planned_gi_utc, o.shipped_at, o.erp_document, o.created_at, o.note,
                               (select count(*) from outbound_line l where l.order_id = o.id) as lines,
                               (select coalesce(sum(l.qty_picked), 0) from outbound_line l where l.order_id = o.id) as qty_picked,
                               (select coalesce(sum(coalesce(l.qty_requested_base, l.qty_requested)), 0) from outbound_line l where l.order_id = o.id) as qty_requested,
                               (select coalesce(sum(l.qty_allocated), 0) from outbound_line l where l.order_id = o.id) as qty_allocated,
                               (select coalesce(sum(l.qty_short - l.qty_short_closed), 0) from outbound_line l where l.order_id = o.id) as qty_short
                        from outbound_order o
                        where o.transfer_to_site is not null
                          and (case when :incoming then o.transfer_to_site = :site else o.site_id = :site end)
                        order by o.created_at desc limit 300""")
                .param("incoming", incoming).param("site", siteId).query().listOfRows();
    }

    /** The receiving site's expected receipt for a shipped transfer: what left, per line and lot. */
    private void publishTransferExpectation(String siteId, UUID orderId, String erpDocNo, Instant shippedAt,
                                            List<InventoryClient.IssuedLine> issued) {
        record T(String to, String scac) {
        }
        T t = jdbc.sql("select transfer_to_site, carrier_scac from outbound_order where id = :id").param("id", orderId)
                .query((rs, n) -> new T(rs.getString(1), rs.getString(2))).single();
        if (t.to() == null) {
            return;
        }
        Map<String, String> owners = new HashMap<>();
        jdbc.sql("select erp_line_ref, owner_id from outbound_line where order_id = :o").param("o", orderId)
                .query((rs, n) -> owners.put(rs.getString(1), rs.getString(2))).list();
        List<IntegrationContracts.ReceiptExpectation.Line> lines = new ArrayList<>();
        for (InventoryClient.IssuedLine l : issued) {
            if (l.qty().signum() <= 0) {
                continue;
            }
            List<InventoryClient.LotQty> lots = l.lots() == null || l.lots().isEmpty()
                    ? List.of(new InventoryClient.LotQty(null, l.qty())) : l.lots();
            int k = 0;
            for (InventoryClient.LotQty lot : lots) {
                String ref = lots.size() == 1 ? l.orderLineRef() : l.orderLineRef() + "-" + (++k);
                lines.add(new IntegrationContracts.ReceiptExpectation.Line(ref, owners.get(l.orderLineRef()), l.itemNo(),
                        strip(lot.qty()), l.uom(), lot.lotNo() == null || lot.lotNo().isEmpty() ? null : lot.lotNo(), null,
                        null, "AVAILABLE", null, null));
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        String owner = lines.getFirst().ownerId();
        outbox.append(new OutboxWriter.Message(IntegrationContracts.TOPIC_RECEIPT_EXPECTATIONS,
                IntegrationContracts.ReceiptExpectation.TYPE, IntegrationContracts.ReceiptExpectation.VERSION, "ASTRAWMS",
                t.to(), owner, t.to() + ":" + erpDocNo,
                new IntegrationContracts.ReceiptExpectation(erpDocNo, "WMS_TRANSFER", "CREATE", 1, erpDocNo, null, null,
                        siteId, t.scac(), shippedAt, null, null, null, null, lines, List.of(), shippedAt)));
    }

    private void replacePooled(EventEnvelope envelope, OutboundOrder o, Order cur) {
        jdbc.sql("delete from outbound_line where order_id = :o").param("o", cur.id()).update();
        insertLines(cur.id(), o.lines());
        jdbc.sql("""
                        update outbound_order set revision = :rev, order_type = :type, ship_to = cast(:shipTo as jsonb),
                            carrier_scac = :scac, planned_gi_utc = :gi, source_system = :src, updated_at = :now,
                            cutoff_at = :cutoff
                        where id = :id""")
                .param("cutoff", Optional.ofNullable(policy.cutoffAt(envelope.siteId(), o.carrierScac(),
                        o.plannedGoodsIssueUtc())).map(Timestamp::from).orElse(null))
                .param("rev", o.revision()).param("type", o.orderType())
                .param("shipTo", o.shipTo() == null ? null : json.writeValueAsString(o.shipTo()))
                .param("scac", o.carrierScac())
                .param("gi", o.plannedGoodsIssueUtc() == null ? null : Timestamp.from(o.plannedGoodsIssueUtc()))
                .param("src", envelope.sourceSystem()).param("now", Timestamp.from(clock.instant()))
                .param("id", cur.id()).update();
    }

    private void insertLines(UUID orderId, List<OutboundOrder.Line> lines) {
        for (OutboundOrder.Line l : lines) {
            jdbc.sql("""
                            insert into outbound_line (order_id, tenant_id, erp_line_ref, owner_id, item_no, qty_requested,
                                uom, lot_no)
                            values (:o, :t, :ref, :owner, :item, :qty, :uom, :lot)""")
                    .param("o", orderId).param("t", TenantContext.tenantId()).param("ref", l.erpLineRef())
                    .param("owner", l.ownerId()).param("item", l.itemNo()).param("qty", l.qtyRequested())
                    .param("uom", l.uom()).param("lot", l.lotNo()).update();
        }
    }

    /**
     * Hard-allocates every line (§3.4) and requests one pick task per allocation. The order becomes RELEASED, or
     * BACKORDERED when nothing could be allocated. Inventory keys include the revision, so a retry allocates once.
     * A ship-complete order (ADR-0021) that cannot be fully allocated gives its stock back and waits BACKORDERED; each
     * later attempt passes its own {@code attempt} so that inventory allocates afresh.
     */
    void allocateAndRelease(Order order, String attempt) {
        record Line(String ref, String owner, String item, BigDecimal qty, String uom, String lot) {
        }
        List<Line> lines = jdbc.sql("""
                        select erp_line_ref, owner_id, item_no, qty_requested, uom, lot_no from outbound_line
                        where order_id = :o order by erp_line_ref""")
                .param("o", order.id())
                .query((rs, n) -> new Line(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                        rs.getString(5), rs.getString(6)))
                .list();
        BigDecimal totalAllocated = BigDecimal.ZERO;
        Map<String, InventoryClient.AllocateResult> results = new java.util.LinkedHashMap<>();
        for (Line l : lines) {
            results.put(l.ref(), inventory.allocate(order.siteId(),
                    "OUT-" + order.erpDocNo() + "-" + order.revision() + "-" + l.ref() + (attempt == null ? "" : "-" + attempt),
                    order.erpDocNo(), l.ref(), l.owner(), l.item(), l.qty(), l.uom(), l.lot(), List.of()));
        }
        String firstShort = results.entrySet().stream().filter(e -> e.getValue().shortQty().signum() > 0)
                .map(Map.Entry::getKey).findFirst().orElse(null);
        if (order.shipComplete() && firstShort != null) {
            holdShipComplete(order, results, firstShort, attempt);
            return;
        }
        for (Line l : lines) {
            InventoryClient.AllocateResult a = results.get(l.ref());
            jdbc.sql("""
                            update outbound_line set base_uom = :baseUom, qty_requested_base = :reqBase,
                                qty_allocated = :alloc, qty_short = :short, short_reason = :reason, short_detail = :detail,
                                allocation_rule = :rule
                            where order_id = :o and erp_line_ref = :ref""")
                    .param("baseUom", a.baseUom()).param("reqBase", a.requestedQty()).param("alloc", a.allocatedQty())
                    .param("short", a.shortQty()).param("reason", a.shortReason()).param("detail", a.shortDetail())
                    .param("rule", a.rule() == null ? null : a.rule() + " · at release")
                    .param("o", order.id()).param("ref", l.ref()).update();
            requestPicks(order, l.ref(), l.owner(), l.item(), a, null);
            totalAllocated = totalAllocated.add(a.allocatedQty());
        }
        setStatus(order.id(), totalAllocated.signum() == 0 ? "BACKORDERED" : "RELEASED");
    }

    /** Ship complete but short: everything allocated goes back, and every line says why the order waits. */
    private void holdShipComplete(Order order, Map<String, InventoryClient.AllocateResult> results, String firstShort,
                                  String attempt) {
        if (results.values().stream().anyMatch(a -> a.allocatedQty().signum() > 0)) {
            inventory.release(order.siteId(), "OUT-SC-" + order.erpDocNo() + "-" + order.revision()
                    + (attempt == null ? "" : "-" + attempt), order.erpDocNo());
        }
        results.forEach((ref, a) -> {
            boolean isShort = a.shortQty().signum() > 0;
            jdbc.sql("""
                            update outbound_line set base_uom = :baseUom, qty_requested_base = :reqBase, qty_allocated = 0,
                                qty_short = :reqBase, short_reason = :reason, short_detail = :detail
                            where order_id = :o and erp_line_ref = :ref""")
                    .param("baseUom", a.baseUom()).param("reqBase", a.requestedQty())
                    .param("reason", isShort ? a.shortReason() : "SHIP_COMPLETE")
                    .param("detail", isShort ? a.shortDetail()
                            : "In stock, held: the order ships complete and line " + firstShort + " is short")
                    .param("o", order.id()).param("ref", ref).update();
        });
        setStatus(order.id(), "BACKORDERED");
    }

    private void requestPicks(Order order, String lineRef, String owner, String item, InventoryClient.AllocateResult a,
                              UUID replaces) {
        for (InventoryClient.Allocation al : a.allocations()) {
            jdbc.sql("""
                            insert into outbound_allocation (allocation_id, tenant_id, order_id, erp_line_ref, location_id,
                                lpn_id, lot_no, qty, status, replaces)
                            values (:a, :t, :o, :ref, :loc, :lpn, :lot, :qty, 'OPEN', :replaces)""")
                    .param("a", al.id()).param("t", TenantContext.tenantId()).param("o", order.id())
                    .param("ref", lineRef).param("loc", al.locationId()).param("lpn", al.lpnId())
                    .param("lot", al.lotNo()).param("qty", al.qty()).param("replaces", replaces).update();
            outbox.append(new OutboxWriter.Message(OutboundContracts.TOPIC_TASK_REQUESTS,
                    OutboundContracts.PickRequested.TYPE, OutboundContracts.PickRequested.VERSION, null, order.siteId(),
                    owner, order.siteId() + ":" + order.erpDocNo(),
                    new OutboundContracts.PickRequested(al.id(), order.erpDocNo(), lineRef, owner, item, al.lotNo(),
                            al.qty(), a.baseUom(), al.locationId(), al.lpnId(), stagingLocation, order.pickLpn(),
                            policy.pickPriority(pickPriority, order.cutoffAt()))));
        }
    }

    // =====================================================================================================
    // Backorder recovery (ADR-0019)
    // =====================================================================================================

    private static final List<String> AUTO_RECOVERY = List.of("BACKORDERED", "RELEASED");
    private static final List<String> MANUAL_RECOVERY = List.of("BACKORDERED", "RELEASED", "PICKED");

    record ShortLine(UUID orderId, String erpDocNo, String ref, String owner, String item, BigDecimal shortQty,
                     String baseUom, String lot) {
    }

    /**
     * Stock of an item became available (receipt into storage, putaway, return restock, positive adjustment,
     * replenishment): its short lines on BACKORDERED and RELEASED orders are allocated again, the earliest planned
     * goods issue first, then the highest order priority. A recovered order is RELEASED with pick tasks. Picked orders
     * are left alone (they may be packed); a supervisor recovers them with {@link #reallocateShorts}.
     */
    /** The inventory movement that made stock available, kept in the recovery log (ADR-0021). */
    public record StockArrival(String txnType, String locationId, String lpnId, UUID operationId) {
    }

    @Transactional
    public void onStockAvailable(String siteId, String ownerId, String itemNo, StockArrival arrival) {
        java.util.Set<UUID> retried = new java.util.HashSet<>();
        for (ShortLine s : shortLines(siteId, ownerId, itemNo, null, AUTO_RECOVERY, false)) {
            if (retried.contains(s.orderId())) {
                continue;
            }
            Order o = lockOrder(s.orderId());
            if (o.shipComplete() && "BACKORDERED".equals(o.status())) {
                retried.add(o.id());       // the whole order or nothing; a failed attempt holds no stock
                retryShipComplete(o, "AUTOMATIC", arrival, "R" + arrival.operationId().toString().substring(0, 8));
                continue;
            }
            BigDecimal got = recover(s, AUTO_RECOVERY, "OUT-REC-" + arrival.operationId() + "-" + s.erpDocNo() + "-" + s.ref(),
                    "AUTOMATIC", arrival);
            if (got.compareTo(s.shortQty()) < 0) {
                break;   // the new stock is used up
            }
        }
    }

    /** Supervisor "Reallocate shorts": tries to allocate every short line of the order now. */
    @Transactional
    public Map<String, Object> reallocateShorts(String siteId, String erpDocNo) {
        Order o = lockOrder(siteId, erpDocNo).orElseThrow(() -> unknown(erpDocNo));
        if (!MANUAL_RECOVERY.contains(o.status())) {
            throw ApiException.unprocessable("OUT_NOT_REALLOCATABLE",
                    "Order " + erpDocNo + " is " + o.status() + "; only BACKORDERED, RELEASED or PICKED orders are reallocated");
        }
        boolean loaded = jdbc.sql("select load_id is not null from outbound_order where id = :id").param("id", o.id())
                .query(Boolean.class).single();
        if (loaded) {
            throw ApiException.unprocessable("OUT_ORDER_LOADED", "Order " + erpDocNo + " is on a load; unload it first");
        }
        String attempt = UUID.randomUUID().toString().substring(0, 8);
        BigDecimal recovered = BigDecimal.ZERO;
        if (o.shipComplete() && "BACKORDERED".equals(o.status())) {
            recovered = retryShipComplete(o, "MANUAL", null, "M" + attempt);
            Map<String, Object> result = new HashMap<>(detail(siteId, erpDocNo));
            result.put("recoveredQty", strip(recovered));
            return result;
        }
        for (ShortLine s : shortLines(siteId, null, null, o.id(), MANUAL_RECOVERY, true)) {
            recovered = recovered.add(recover(s, MANUAL_RECOVERY, "OUT-RS-" + attempt + "-" + erpDocNo + "-" + s.ref(),
                    "MANUAL", null));
        }
        Map<String, Object> result = new HashMap<>(detail(siteId, erpDocNo));
        result.put("recoveredQty", strip(recovered));
        return result;
    }

    /**
     * Supervisor "close shorts": what is still short ships short. Shorts waiting for stock (BACKORDER) stop waiting; an
     * order with nothing left to pick and something picked becomes PICKED.
     */
    @Transactional
    public Map<String, Object> closeShorts(String siteId, String erpDocNo) {
        Order o = lockOrder(siteId, erpDocNo).orElseThrow(() -> unknown(erpDocNo));
        if (!MANUAL_RECOVERY.contains(o.status())) {
            throw ApiException.unprocessable("OUT_NOT_REALLOCATABLE", "Order " + erpDocNo + " is " + o.status());
        }
        jdbc.sql("update outbound_line set qty_short_closed = qty_short, short_hold = false where order_id = :o")
                .param("o", o.id()).update();
        boolean open = jdbc.sql("select exists (select 1 from outbound_allocation where order_id = :o and status = 'OPEN')")
                .param("o", o.id()).query(Boolean.class).single();
        boolean picked = jdbc.sql("select coalesce(sum(qty_picked), 0) > 0 from outbound_line where order_id = :o")
                .param("o", o.id()).query(Boolean.class).single();
        if (!open && picked && !"PICKED".equals(o.status())) {
            setStatus(o.id(), "PICKED");
        }
        return detail(siteId, erpDocNo);
    }

    /**
     * Short lines in recovery order. Automatic recovery takes only the open short ({@code qty_short - qty_short_closed});
     * a supervisor's reallocation ({@code includeClosed}) also reopens shorts that were going to ship short.
     */
    private List<ShortLine> shortLines(String siteId, String ownerId, String itemNo, UUID orderId, List<String> statuses,
                                       boolean includeClosed) {
        String qty = includeClosed ? "l.qty_short" : "l.qty_short - l.qty_short_closed";
        return jdbc.sql("""
                        select o.id, o.erp_doc_no, l.erp_line_ref, l.owner_id, l.item_no,\s""" + qty + """
                        , l.base_uom, l.lot_no
                        from outbound_line l join outbound_order o on o.id = l.order_id
                        where o.site_id = :site and o.status in (:statuses) and\s""" + qty + """
                         > 0 and l.base_uom is not null
                          and (cast(:owner as text) is null or l.owner_id = :owner)
                          and (cast(:item as text) is null or l.item_no = :item)
                          and (cast(:order as uuid) is null or o.id = :order)
                        order by o.priority desc, coalesce(o.cutoff_at, o.planned_gi_utc) nulls last,
                                 case o.criticality when 'CRITICAL' then 0 when 'HIGH' then 1 when 'NORMAL' then 2 else 3 end,
                                 o.distance_km nulls last, o.created_at, l.erp_line_ref""")
                .param("site", siteId).param("statuses", statuses).param("owner", ownerId).param("item", itemNo)
                .param("order", orderId)
                .query((rs, n) -> new ShortLine(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getBigDecimal(6), rs.getString(7), rs.getString(8)))
                .list();
    }

    /**
     * Where the order stood in the short-stock queue (ADR-0025): priority, then promised date, then criticality, then
     * distance, then age — not request time alone.
     */
    private String queuePosition(Order order) {
        Map<String, Object> o = jdbc.sql("""
                        select priority, coalesce(cutoff_at, planned_gi_utc) as promised, criticality, distance_km
                        from outbound_order where id = :id""").param("id", order.id()).query().singleRow();
        return "priority " + o.get("priority") + ", promised " + (o.get("promised") == null ? "—" : o.get("promised"))
                + ", criticality " + o.get("criticality")
                + (o.get("distance_km") == null ? "" : ", distance " + o.get("distance_km") + " km");
    }

    /** A waiting ship-complete order is allocated whole again; returns the quantity allocated (0 if still short). */
    private BigDecimal retryShipComplete(Order o, String trigger, StockArrival arrival, String attempt) {
        allocateAndRelease(o, attempt);
        if (!"RELEASED".equals(lockOrder(o.id()).status())) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> l : jdbc.sql("select erp_line_ref, qty_allocated from outbound_line where order_id = :o")
                .param("o", o.id()).query().listOfRows()) {
            BigDecimal qty = (BigDecimal) l.get("qty_allocated");
            logRecovery(o.id(), (String) l.get("erp_line_ref"), qty, trigger, arrival);
            total = total.add(qty);
        }
        return total;
    }

    private BigDecimal recover(ShortLine s, List<String> statuses, String key, String trigger, StockArrival arrival) {
        Order order = lockOrder(s.orderId());
        if (!statuses.contains(order.status())) {
            return BigDecimal.ZERO;
        }
        InventoryClient.AllocateResult a = inventory.allocate(order.siteId(), key, order.erpDocNo(), s.ref(), s.owner(),
                s.item(), s.shortQty(), s.baseUom(), s.lot(), List.of());
        if (a.allocatedQty().signum() == 0) {
            if (a.shortReason() != null) {          // still short: keep the latest explanation
                jdbc.sql("update outbound_line set short_reason = :r, short_detail = :d where order_id = :o and erp_line_ref = :ref")
                        .param("r", a.shortReason()).param("d", a.shortDetail()).param("o", order.id()).param("ref", s.ref())
                        .update();
            }
            return BigDecimal.ZERO;
        }
        requestPicks(order, s.ref(), s.owner(), s.item(), a, null);
        jdbc.sql("""
                        update outbound_line set qty_allocated = qty_allocated + :got, qty_short = qty_short - :got,
                            qty_short_pick = least(qty_short_pick, qty_short - :got),
                            qty_short_closed = least(qty_short_closed, qty_short - :got),
                            short_hold = short_hold and qty_short - :got > qty_short_closed,
                            short_reason = case when qty_short - :got > 0 then :reason end,
                            short_detail = case when qty_short - :got > 0 then :detail end,
                            allocation_rule = :rule
                        where order_id = :o and erp_line_ref = :ref""")
                .param("got", a.allocatedQty()).param("reason", a.shortReason()).param("detail", a.shortDetail())
                .param("rule", (a.rule() == null ? "" : a.rule() + " · ") + "recovered (" + trigger.toLowerCase()
                        + ") by the queue: " + queuePosition(order))
                .param("o", order.id()).param("ref", s.ref()).update();
        logRecovery(order.id(), s.ref(), a.allocatedQty(), trigger, arrival);
        if (!"RELEASED".equals(order.status())) {
            setStatus(order.id(), "RELEASED");
        }
        return a.allocatedQty();
    }

    /** Which stock freed which order (ADR-0021): the recovery log shown on the order. */
    private void logRecovery(UUID orderId, String ref, BigDecimal qty, String trigger, StockArrival arrival) {
        jdbc.sql("""
                        insert into outbound_recovery (tenant_id, order_id, erp_line_ref, qty, trigger, txn_type, location_id,
                                                       lpn_id, operation_id, recovered_by, recovered_at)
                        values (:t, :o, :ref, :qty, :trigger, :txn, :loc, :lpn, :op, :user, :now)""")
                .param("t", TenantContext.tenantId()).param("o", orderId).param("ref", ref)
                .param("qty", qty).param("trigger", trigger)
                .param("txn", arrival == null ? null : arrival.txnType())
                .param("loc", arrival == null ? null : arrival.locationId())
                .param("lpn", arrival == null || arrival.lpnId() == null || arrival.lpnId().isEmpty() ? null : arrival.lpnId())
                .param("op", arrival == null ? null : arrival.operationId())
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant())).update();
    }

    // =====================================================================================================
    // Cancellation (OUT-EX-02)
    // =====================================================================================================

    /**
     * Before release the order is simply cancelled. After release, open picks are cancelled and stock that was
     * already picked is returned to stock by reverse-pick tasks; the ERP gets its acknowledgement only when all of it
     * is back in stock.
     */
    private void cancel(AckTarget ack, OutboundOrder o, Order cur) {
        switch (cur.status()) {
            case "POOLED" -> {
                jdbc.sql("update outbound_order set status = 'CANCELLED', wave_id = null, revision = :rev, updated_at = :now where id = :id")
                        .param("rev", o.revision()).param("now", Timestamp.from(clock.instant())).param("id", cur.id()).update();
                ack(ack, null, null);
                return;
            }
            case "RELEASED", "BACKORDERED", "PICKED" -> {
                // continue below
            }
            default -> {
                ack(ack, cur.status(), "Order is " + cur.status() + "; it can no longer be cancelled");
                return;
            }
        }
        // Inventory serialises this with picks: afterwards each allocation is either released or PICKED there.
        inventory.release(cur.siteId(), "OUT-REL-" + o.erpDocNo() + "-" + o.revision(), o.erpDocNo());
        for (UUID allocation : jdbc.sql("select allocation_id from outbound_allocation where order_id = :o and status = 'OPEN'")
                .param("o", cur.id()).query(UUID.class).list()) {
            outbox.append(new OutboxWriter.Message(OutboundContracts.TOPIC_TASK_REQUESTS,
                    OutboundContracts.PickCancelled.TYPE, OutboundContracts.PickCancelled.VERSION, null, cur.siteId(), null,
                    cur.siteId() + ":" + o.erpDocNo(), new OutboundContracts.PickCancelled(allocation, o.erpDocNo())));
        }
        jdbc.sql("update outbound_allocation set status = 'CANCELLED' where order_id = :o and status = 'OPEN'")
                .param("o", cur.id()).update();

        int returning = 0;
        for (InventoryClient.InventoryAllocation a : inventory.allocations(cur.siteId(), o.erpDocNo())) {
            if (!"PICKED".equals(a.status())) {
                continue;
            }
            jdbc.sql("update outbound_allocation set status = 'RETURNING' where allocation_id = :a")
                    .param("a", a.id()).update();
            String baseUom = jdbc.sql("select base_uom from outbound_line where order_id = :o and erp_line_ref = :ref")
                    .param("o", cur.id()).param("ref", a.orderLineRef()).query(String.class).optional().orElse(null);
            outbox.append(new OutboxWriter.Message(OutboundContracts.TOPIC_TASK_REQUESTS,
                    OutboundContracts.ReturnRequested.TYPE, OutboundContracts.ReturnRequested.VERSION, null, cur.siteId(),
                    a.ownerId(), cur.siteId() + ":" + o.erpDocNo(),
                    new OutboundContracts.ReturnRequested(a.id(), o.erpDocNo(), a.orderLineRef(), a.ownerId(),
                            a.itemNo(), a.lotNo(), a.qtyPicked(), baseUom, a.pickedLocation(), a.pickedLpn(),
                            a.locationId(), a.lpnId(), returnPriority)));
            returning++;
        }
        if (returning == 0) {
            jdbc.sql("update outbound_order set status = 'CANCELLED', revision = :rev, updated_at = :now where id = :id")
                    .param("rev", o.revision()).param("now", Timestamp.from(clock.instant())).param("id", cur.id()).update();
            ack(ack, null, null);
        } else {
            jdbc.sql("""
                            update outbound_order set status = 'CANCEL_REQUESTED', revision = :rev,
                                pending_cancel_ack = cast(:ack as jsonb), updated_at = :now where id = :id""")
                    .param("rev", o.revision()).param("ack", json.writeValueAsString(ack))
                    .param("now", Timestamp.from(clock.instant())).param("id", cur.id()).update();
        }
    }

    private void ack(AckTarget t, String rejectCode, String rejectText) {
        outbox.append(new OutboxWriter.Message(IntegrationContracts.TOPIC_APPLICATION_ACKS, ApplicationAck.TYPE,
                ApplicationAck.VERSION, t.sourceSystem(), t.siteId(), t.ownerId(), t.siteId() + ":" + t.erpDocNo(),
                new ApplicationAck(t.sourceSystem(), t.messageId(), t.sourceIdocOrEventId(), t.erpDocNo(),
                        rejectCode == null ? ApplicationAck.ACCEPTED : ApplicationAck.REJECTED, rejectCode, rejectText)));
    }

    // =====================================================================================================
    // Pick and return progress
    // =====================================================================================================

    @Transactional
    public void onTaskCompleted(TaskCompleted t) {
        if (t.allocationId() == null) {
            return;
        }
        if ("RETURN".equals(t.taskType())) {
            onReturned(t);
        } else if ("PICK".equals(t.taskType())) {
            onPicked(t);
        }
    }

    private void onPicked(TaskCompleted t) {
        Optional<UUID> orderId = jdbc.sql("""
                        update outbound_allocation set qty_picked = :picked, qty_short = :short, status = 'DONE',
                            short_reason = :reason, short_action = :action
                        where allocation_id = :a and status = 'OPEN' returning order_id""")
                .param("picked", t.qtyPicked()).param("short", t.qtyShort()).param("a", t.allocationId())
                .param("reason", t.shortReason()).param("action", t.shortAction())
                .query(UUID.class).optional();
        if (orderId.isEmpty()) {
            return;   // duplicate, or the order was cancelled (inventory decided whether stock must be returned)
        }
        Order order = lockOrder(orderId.get());
        jdbc.sql("update outbound_line set qty_picked = qty_picked + :picked where order_id = :o and erp_line_ref = :line")
                .param("picked", t.qtyPicked()).param("o", order.id()).param("line", t.orderLineRef()).update();
        if (t.qtyShort().signum() > 0) {
            String action = t.shortAction() == null ? "REALLOCATE" : t.shortAction();
            if (order.shipComplete() && "SHIP_SHORT".equals(action)) {
                action = "BACKORDER";       // a ship-complete order never ships short from the floor (ADR-0021)
            }
            switch (action) {
                case "BACKORDER" -> jdbc.sql("""
                                update outbound_line set qty_allocated = qty_allocated - :short, qty_short = qty_short + :short,
                                    short_hold = true
                                where order_id = :o and erp_line_ref = :line""")
                        .param("short", t.qtyShort()).param("o", order.id()).param("line", t.orderLineRef()).update();
                case "SHIP_SHORT" -> jdbc.sql("""
                                update outbound_line set qty_allocated = qty_allocated - :short, qty_short = qty_short + :short,
                                    qty_short_pick = qty_short_pick + :short, qty_short_closed = qty_short_closed + :short
                                where order_id = :o and erp_line_ref = :line""")
                        .param("short", t.qtyShort()).param("o", order.id()).param("line", t.orderLineRef()).update();
                default -> reallocateShort(order, t);
            }
        }
        // PICKED once nothing is left to pick and no short waits for stock (a BACKORDER short keeps it RELEASED).
        boolean open = jdbc.sql("""
                        select exists (select 1 from outbound_allocation where order_id = :o and status = 'OPEN')
                            or exists (select 1 from outbound_line where order_id = :o and short_hold
                                       and qty_short > qty_short_closed)""")
                .param("o", order.id()).query(Boolean.class).single();
        if (!open) {
            jdbc.sql("update outbound_order set status = 'PICKED', updated_at = :now where id = :id and status = 'RELEASED'")
                    .param("now", Timestamp.from(clock.instant())).param("id", order.id()).update();
        }
    }

    /**
     * PCK-003 (c): the short-picked quantity is allocated again, avoiding every location of this line where a pick
     * came up short. What cannot be re-allocated is reported as SHORT_PICK in the shipment confirmation (PCK-003 d).
     */
    private void reallocateShort(Order order, TaskCompleted t) {
        record Line(String owner, String item, String lot, String baseUom) {
        }
        Line l = jdbc.sql("""
                        select owner_id, item_no, lot_no, base_uom from outbound_line
                        where order_id = :o and erp_line_ref = :line""")
                .param("o", order.id()).param("line", t.orderLineRef())
                .query((rs, n) -> new Line(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).single();
        InventoryClient.AllocateResult a = new InventoryClient.AllocateResult(l.baseUom(), t.qtyShort(), BigDecimal.ZERO,
                t.qtyShort(), List.of());
        if ("RELEASED".equals(order.status())) {
            List<String> shortLocations = jdbc.sql("""
                            select distinct location_id from outbound_allocation
                            where order_id = :o and erp_line_ref = :line and qty_short > 0""")
                    .param("o", order.id()).param("line", t.orderLineRef()).query(String.class).list();
            a = inventory.allocate(order.siteId(), "OUT-RA-" + t.allocationId(), order.erpDocNo(), t.orderLineRef(),
                    l.owner(), l.item(), t.qtyShort(), l.baseUom(), l.lot(), shortLocations);
            requestPicks(order, t.orderLineRef(), l.owner(), l.item(), a, t.allocationId());
        }
        if (order.shipComplete()) {       // what cannot be re-allocated waits for stock instead of shipping short
            jdbc.sql("""
                            update outbound_line set qty_allocated = qty_allocated - :short + :realloc,
                                qty_short = qty_short + :unrecovered, short_hold = short_hold or :unrecovered > 0
                            where order_id = :o and erp_line_ref = :line""")
                    .param("short", t.qtyShort()).param("realloc", a.allocatedQty()).param("unrecovered", a.shortQty())
                    .param("o", order.id()).param("line", t.orderLineRef()).update();
            return;
        }
        jdbc.sql("""
                        update outbound_line set qty_allocated = qty_allocated - :short + :realloc,
                            qty_short = qty_short + :unrecovered, qty_short_pick = qty_short_pick + :unrecovered,
                            qty_short_closed = qty_short_closed + :unrecovered
                        where order_id = :o and erp_line_ref = :line""")
                .param("short", t.qtyShort()).param("realloc", a.allocatedQty()).param("unrecovered", a.shortQty())
                .param("o", order.id()).param("line", t.orderLineRef()).update();
    }

    private void onReturned(TaskCompleted t) {
        Optional<UUID> orderId = jdbc.sql("""
                        update outbound_allocation set status = 'RETURNED'
                        where allocation_id = :a and status = 'RETURNING' returning order_id""")
                .param("a", t.allocationId()).query(UUID.class).optional();
        if (orderId.isEmpty()) {
            return;
        }
        Order order = lockOrder(orderId.get());
        boolean pending = jdbc.sql("select exists (select 1 from outbound_allocation where order_id = :o and status = 'RETURNING')")
                .param("o", order.id()).query(Boolean.class).single();
        if (pending || !"CANCEL_REQUESTED".equals(order.status())) {
            return;
        }
        String ackJson = jdbc.sql("select pending_cancel_ack::text from outbound_order where id = :id")
                .param("id", order.id()).query(String.class).single();
        jdbc.sql("update outbound_order set status = 'CANCELLED', pending_cancel_ack = null, updated_at = :now where id = :id")
                .param("now", Timestamp.from(clock.instant())).param("id", order.id()).update();
        if (ackJson != null) {
            ack(json.readValue(ackJson, AckTarget.class), null, null);
        }
    }

    // =====================================================================================================
    // Shipping and ERP confirmation (IF-OB-003, SHP-005)
    // =====================================================================================================

    public record ShipRequest(String carrierScac, String trackingNo, String billOfLading) {
    }

    /** Issues the picked stock and sends the shipment confirmation. Idempotent once shipped. */
    @Transactional
    public Map<String, Object> ship(String siteId, String erpDocNo, ShipRequest r) {
        Order o = lockOrder(siteId, erpDocNo).orElseThrow(() -> unknown(erpDocNo));
        if (List.of("SHIPPED", "CONFIRMED", "SHIP_ERROR").contains(o.status())) {
            return detail(siteId, erpDocNo);
        }
        if (!"PICKED".equals(o.status())) {
            throw ApiException.unprocessable("OUT_NOT_PICKED", "Order " + erpDocNo + " is " + o.status() + "; it must be PICKED");
        }
        packing.requirePacked(o.id(), siteId, erpDocNo);                     // SHP-002
        List<InventoryClient.IssuedLine> issued = inventory.issue(siteId, "OUT-SHIP-" + erpDocNo, erpDocNo, transferTo(o.id()));
        Instant now = clock.instant();
        String txn = WmsTxnId.next(clock);
        jdbc.sql("""
                        update outbound_order set status = 'SHIPPED', shipment_txn_id = :txn, shipped_at = :now,
                            carrier_scac = coalesce(:scac, carrier_scac), tracking_no = :tracking, bill_of_lading = :bol,
                            updated_at = :now where id = :id""")
                .param("txn", txn).param("now", Timestamp.from(now)).param("scac", r == null ? null : r.carrierScac())
                .param("tracking", r == null ? null : r.trackingNo()).param("bol", r == null ? null : r.billOfLading())
                .param("id", o.id()).update();
        publishConfirmation(siteId, o.id(), erpDocNo, txn, now, issued);
        publishTransferExpectation(siteId, o.id(), erpDocNo, now, issued);
        return detail(siteId, erpDocNo);
    }

    /** Re-sends the confirmation with the same wmsTxnId after the ERP-side cause was fixed (INT-014). */
    @Transactional
    public Map<String, Object> repost(String siteId, String erpDocNo) {
        Order o = lockOrder(siteId, erpDocNo).orElseThrow(() -> unknown(erpDocNo));
        if (!"SHIP_ERROR".equals(o.status())) {
            throw ApiException.conflict("OUT_NOT_REPOSTABLE", "Only SHIP_ERROR orders can be reposted; status is " + o.status());
        }
        Instant shippedAt = jdbc.sql("select shipped_at from outbound_order where id = :id").param("id", o.id())
                .query(Timestamp.class).single().toInstant();
        // The issue is replayed by its idempotency key, returning the same lines without a second stock effect.
        List<InventoryClient.IssuedLine> issued = inventory.issue(siteId, "OUT-SHIP-" + erpDocNo, erpDocNo, transferTo(o.id()));
        jdbc.sql("update outbound_order set status = 'SHIPPED', erp_error_class = null, erp_error_text = null, updated_at = :now where id = :id")
                .param("now", Timestamp.from(clock.instant())).param("id", o.id()).update();
        publishConfirmation(siteId, o.id(), erpDocNo, o.shipmentTxnId(), shippedAt, issued);
        return detail(siteId, erpDocNo);
    }

    /** The receiving site of a transfer order, null for a customer order. */
    private String transferTo(UUID orderId) {
        return jdbc.sql("select transfer_to_site from outbound_order where id = :id").param("id", orderId)
                .query(String.class).list().stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private void publishConfirmation(String siteId, UUID orderId, String erpDocNo, String txn, Instant shippedAt,
                                     List<InventoryClient.IssuedLine> issued) {
        Map<String, InventoryClient.IssuedLine> byLine = new HashMap<>();
        issued.forEach(l -> byLine.put(l.orderLineRef(), l));
        record Line(String ref, String item, BigDecimal requestedBase, BigDecimal shortPick, String baseUom) {
        }
        List<Line> lines = jdbc.sql("""
                        select erp_line_ref, item_no, qty_requested_base, qty_short_pick, base_uom
                        from outbound_line where order_id = :o order by erp_line_ref""")
                .param("o", orderId)
                .query((rs, n) -> new Line(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                        rs.getString(5)))
                .list();
        List<ShipmentConfirmation.Line> out = new ArrayList<>();
        for (Line l : lines) {
            InventoryClient.IssuedLine shipped = byLine.get(l.ref());
            BigDecimal qty = shipped == null ? BigDecimal.ZERO : shipped.qty();
            String shortReason = null;
            if (qty.compareTo(l.requestedBase()) < 0) {
                shortReason = l.shortPick().signum() > 0 ? "SHORT_PICK" : "NO_STOCK";
            }
            out.add(new ShipmentConfirmation.Line(l.ref(), l.item(), strip(qty), l.baseUom(),
                    shipped == null ? List.of() : shipped.lots().stream()
                            .map(x -> new ShipmentConfirmation.LotSplit(x.lotNo(), x.qty())).toList(),
                    shipped == null || shipped.serials().isEmpty() ? null : shipped.serials(), shortReason));
        }
        record Header(String scac, String tracking, String bol, String transferTo) {
        }
        Header h = jdbc.sql("select carrier_scac, tracking_no, bill_of_lading, transfer_to_site from outbound_order where id = :id")
                .param("id", orderId).query((rs, n) -> new Header(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4))).single();
        outbox.append(new OutboxWriter.Message(OutboundContracts.TOPIC_SHIPMENT_CONFIRMATIONS, ShipmentConfirmation.TYPE,
                ShipmentConfirmation.VERSION, "ERP", siteId, null, siteId + ":" + erpDocNo,
                new ShipmentConfirmation(txn, erpDocNo, shippedAt, h.scac(), h.tracking(), h.bol(), out, h.transferTo())));
    }

    /** Terminal ERP result (INT-011). A failed goods issue leaves the order physically shipped (SHP-005). */
    @Transactional
    public void onPostingResult(ErpPostingResult r) {
        if (r.success()) {
            jdbc.sql("""
                            update outbound_order set status = 'CONFIRMED', erp_document = :doc, erp_error_class = null,
                                erp_error_text = null, updated_at = :now
                            where shipment_txn_id = :txn and status in ('SHIPPED', 'SHIP_ERROR')""")
                    .param("doc", r.erpDocument()).param("txn", r.wmsTxnId()).param("now", Timestamp.from(clock.instant()))
                    .update();
        } else {
            jdbc.sql("""
                            update outbound_order set status = 'SHIP_ERROR', erp_error_class = :cls, erp_error_text = :text,
                                updated_at = :now
                            where shipment_txn_id = :txn and status = 'SHIPPED'""")
                    .param("cls", r.errorClass())
                    .param("text", (r.erpMessageId() == null ? "" : r.erpMessageId() + " ") + r.erpMessageText())
                    .param("txn", r.wmsTxnId()).param("now", Timestamp.from(clock.instant())).update();
        }
    }

    // =====================================================================================================
    // Site release mode (§C.4)
    // =====================================================================================================

    /** WAVELESS (default): orders are allocated and released on receipt. WAVE: they wait in the pool. */
    public String releaseMode(String siteId) {
        return jdbc.sql("select release_mode from outbound_site_config where site_id = :site")
                .param("site", siteId).query(String.class).optional().orElse("WAVELESS");
    }

    /**
     * Site outbound settings: release mode, whether orders must be fully packed before loading/shipping, the site's
     * time zone (carrier cutoffs are local times) and whether orders ship complete unless their owner says otherwise.
     */
    public Map<String, Object> siteConfig(String siteId) {
        Map<String, Object> row = jdbc.sql("""
                        select pack_required, timezone, ship_complete from outbound_site_config where site_id = :site""")
                .param("site", siteId).query().listOfRows().stream().findFirst()
                .orElse(Map.of("pack_required", false, "timezone", "UTC", "ship_complete", false));
        return Map.of("siteId", siteId, "releaseMode", releaseMode(siteId), "packRequired", row.get("pack_required"),
                "timezone", row.get("timezone"), "shipComplete", row.get("ship_complete"));
    }

    /** Changes the given settings; {@code null} keeps the current value. */
    @Transactional
    public Map<String, Object> setSiteConfig(String siteId, String mode, Boolean packRequired, String timezone,
                                             Boolean shipComplete) {
        String newMode = mode == null ? releaseMode(siteId) : mode;
        if (!List.of("WAVE", "WAVELESS").contains(newMode)) {
            throw ApiException.badRequest("OUT_RELEASE_MODE_INVALID", "releaseMode must be WAVE or WAVELESS");
        }
        Map<String, Object> cur = siteConfig(siteId);
        boolean pack = packRequired != null ? packRequired : (Boolean) cur.get("packRequired");
        boolean complete = shipComplete != null ? shipComplete : (Boolean) cur.get("shipComplete");
        String tz = timezone == null || timezone.isBlank() ? (String) cur.get("timezone") : timezone.trim();
        try {
            java.time.ZoneId.of(tz);
        } catch (java.time.DateTimeException e) {
            throw ApiException.badRequest("OUT_TIMEZONE_INVALID", "Unknown time zone " + tz);
        }
        jdbc.sql("""
                        insert into outbound_site_config (tenant_id, site_id, release_mode, pack_required, timezone,
                                                          ship_complete, updated_by, updated_at)
                        values (:t, :site, :mode, :pack, :tz, :sc, :user, :now)
                        on conflict (tenant_id, site_id) do update set release_mode = excluded.release_mode,
                            pack_required = excluded.pack_required, timezone = excluded.timezone,
                            ship_complete = excluded.ship_complete, updated_by = excluded.updated_by,
                            updated_at = excluded.updated_at""")
                .param("tz", tz).param("sc", complete)
                .param("t", TenantContext.tenantId()).param("site", siteId).param("mode", newMode).param("pack", pack)
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant())).update();
        return siteConfig(siteId);
    }

    /**
     * A supervisor's release policy for one order (ADR-0021): its priority (0–100, higher first in waves, recovery
     * and picking) and whether it ships complete. Ship complete can change only before picks are requested.
     */
    @Transactional
    public Map<String, Object> setOrderPolicy(String siteId, String erpDocNo, Integer priority, Boolean shipComplete) {
        return setOrderPolicy(siteId, erpDocNo, priority, shipComplete, null, null);
    }

    /** Priority, ship complete, and (ADR-0025) criticality and distance, which rank the order in the short-stock queue. */
    @Transactional
    public Map<String, Object> setOrderPolicy(String siteId, String erpDocNo, Integer priority, Boolean shipComplete,
                                              String criticality, BigDecimal distanceKm) {
        if (criticality != null && !List.of("LOW", "NORMAL", "HIGH", "CRITICAL").contains(criticality.trim().toUpperCase())) {
            throw ApiException.badRequest("OUT_CRITICALITY_INVALID", "criticality is LOW, NORMAL, HIGH or CRITICAL");
        }
        Order o = lockOrder(siteId, erpDocNo).orElseThrow(() -> unknown(erpDocNo));
        if (!List.of("POOLED", "BACKORDERED", "RELEASED").contains(o.status())) {
            throw ApiException.unprocessable("OUT_ORDER_POLICY_LOCKED", "Order " + erpDocNo + " is " + o.status());
        }
        if (priority != null && (priority < 0 || priority > 100)) {
            throw ApiException.badRequest("OUT_PRIORITY_INVALID", "priority must be 0–100");
        }
        if (shipComplete != null && shipComplete != o.shipComplete() && "RELEASED".equals(o.status())) {
            throw ApiException.unprocessable("OUT_ORDER_POLICY_LOCKED",
                    "Order " + erpDocNo + " is already released to picking; ship complete can no longer change");
        }
        jdbc.sql("""
                        update outbound_order set priority = coalesce(:p, priority),
                            ship_complete = coalesce(:sc, ship_complete), criticality = coalesce(:c, criticality),
                            distance_km = coalesce(:d, distance_km), updated_at = :now where id = :id""")
                .param("p", priority).param("sc", shipComplete).param("now", Timestamp.from(clock.instant()))
                .param("c", criticality == null ? null : criticality.trim().toUpperCase()).param("d", distanceKm)
                .param("id", o.id()).update();
        return detail(siteId, erpDocNo);
    }

    // =====================================================================================================
    // Queries and helpers
    // =====================================================================================================

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String siteId, String status) {
        return list(siteId, status, null);
    }

    /**
     * Orders of a site; {@code q} matches the delivery, the ship-to customer (ID or name) or an item of the order,
     * case-insensitively. {@code lines_short} counts the lines with quantity still unallocated.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String siteId, String status, String q) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toUpperCase() + "%";
        return jdbc.sql("""
                        select o.erp_doc_no, o.order_type, o.status, o.carrier_scac, o.planned_gi_utc, o.shipment_txn_id,
                               o.erp_document, o.priority, o.ship_to ->> 'name' as ship_to_name, o.updated_at,
                               o.cutoff_at, o.ship_complete,
                               (select count(*) from outbound_line l where l.order_id = o.id and l.qty_short > 0) as lines_short
                        from outbound_order o where o.site_id = :site and (cast(:status as text) is null or o.status = :status)
                          and (cast(:q as text) is null or upper(o.erp_doc_no) like :q
                               or upper(coalesce(o.ship_to ->> 'name', '')) like :q
                               or upper(coalesce(o.ship_to ->> 'partnerId', '')) like :q
                               or upper(coalesce(o.pick_lpn, '')) like :q
                               or exists (select 1 from outbound_line l where l.order_id = o.id and upper(l.item_no) like :q)
                               or exists (select 1 from outbound_allocation a where a.order_id = o.id
                                          and upper(coalesce(a.lpn_id, '')) like :q)
                               or exists (select 1 from carton c where c.order_id = o.id and c.sscc like :q))
                        order by o.planned_gi_utc nulls last, o.erp_doc_no limit 500""")
                .param("site", siteId).param("status", status).param("q", like).query().listOfRows();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String siteId, String erpDocNo) {
        Map<String, Object> header = jdbc.sql("""
                        select o.id, o.erp_doc_no, o.order_type, o.revision, o.status, o.carrier_scac, o.staging_location,
                               o.pick_lpn, o.shipment_txn_id, o.tracking_no, o.erp_document, o.erp_error_class,
                               o.erp_error_text, w.wave_no, o.priority, o.criticality, o.distance_km, o.planned_gi_utc,
                               o.ship_to ->> 'name' as ship_to_name,
                               o.load_id is not null as loaded, o.cutoff_at, o.ship_complete, o.transfer_to_site, o.note
                        from outbound_order o left join outbound_wave w on w.id = o.wave_id
                        where o.site_id = :site and o.erp_doc_no = :doc""")
                .param("site", siteId).param("doc", erpDocNo).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> unknown(erpDocNo));
        Map<String, Object> result = new HashMap<>(header);
        result.put("lines", jdbc.sql("""
                        select erp_line_ref, item_no, qty_requested, uom, base_uom, qty_requested_base, qty_allocated,
                               qty_picked, qty_short, qty_short_pick, qty_short_closed, short_hold, short_reason, short_detail,
                               allocation_rule
                        from outbound_line where order_id = :o order by erp_line_ref""")
                .param("o", header.get("id")).query().listOfRows().stream().map(OutboundService::stripRow).toList());
        result.put("allocations", jdbc.sql("""
                        select allocation_id, erp_line_ref, location_id, lpn_id, lot_no, qty, qty_picked, qty_short,
                               status, replaces, short_reason, short_action
                        from outbound_allocation where order_id = :o order by erp_line_ref""")
                .param("o", header.get("id")).query().listOfRows().stream().map(OutboundService::stripRow).toList());
        result.put("recoveries", jdbc.sql("""
                        select erp_line_ref, qty, trigger, txn_type, location_id, lpn_id, operation_id, recovered_by, recovered_at
                        from outbound_recovery where order_id = :o order by id""")
                .param("o", header.get("id")).query().listOfRows().stream().map(OutboundService::stripRow).toList());
        return result;
    }

    Optional<Order> lockOrder(String siteId, String erpDocNo) {
        return jdbc.sql("""
                        select id, site_id, erp_doc_no, status, revision, pick_lpn, shipment_txn_id, ship_complete,
                               cutoff_at
                        from outbound_order where site_id = :site and erp_doc_no = :doc for update""")
                .param("site", siteId).param("doc", erpDocNo)
                .query(OutboundService::mapOrder)
                .optional();
    }

    Order lockOrder(UUID id) {
        return jdbc.sql("""
                        select id, site_id, erp_doc_no, status, revision, pick_lpn, shipment_txn_id, ship_complete,
                               cutoff_at
                        from outbound_order where id = :id for update""")
                .param("id", id).query(OutboundService::mapOrder).single();
    }

    private static Order mapOrder(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Order(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getLong(5), rs.getString(6), rs.getString(7), rs.getBoolean(8),
                rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant());
    }

    private void setStatus(UUID id, String status) {
        jdbc.sql("update outbound_order set status = :s, updated_at = :now where id = :id")
                .param("s", status).param("now", Timestamp.from(clock.instant())).param("id", id).update();
    }

    private static ApiException unknown(String erpDocNo) {
        return ApiException.notFound("OUT_ORDER_UNKNOWN", "No outbound order " + erpDocNo);
    }

    static Map<String, Object> stripRow(Map<String, Object> row) {
        Map<String, Object> out = new HashMap<>(row);
        out.replaceAll((k, v) -> v instanceof BigDecimal b ? strip(b) : v);
        return out;
    }

    private static BigDecimal strip(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }
}
