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
 * Outbound order lifecycle (scope §3–5): RELEASED (allocated, picks requested) or BACKORDERED (nothing allocable)
 * → PICKED → SHIPPED (ShipmentConfirmation sent) → CONFIRMED (ERP goods issue posted) or SHIP_ERROR.
 * Allocation happens on receipt of the order (hard allocation, §3.3); shortfalls are reported, not backordered in
 * WMS (OUT-EX-01: the ERP decides on the remainder from the confirmed quantities).
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

    public OutboundService(JdbcClient jdbc, InventoryClient inventory, OutboxWriter outbox, JsonMapper json, Clock clock,
                           @Value("${astra.outbound.staging-location:STAGE-OUT}") String stagingLocation,
                           @Value("${astra.outbound.pick-priority:60}") int pickPriority) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
        this.stagingLocation = stagingLocation;
        this.pickPriority = pickPriority;
    }

    private record Order(UUID id, String siteId, String erpDocNo, String status, long revision, String pickLpn,
                         String shipmentTxnId) {
    }

    // =====================================================================================================
    // IF-OB-001: orders from the ERP
    // =====================================================================================================

    @Transactional
    public void onOrder(EventEnvelope envelope, OutboundOrder o) {
        String site = envelope.siteId();
        Optional<Order> existing = lockOrder(site, o.erpDocNo());
        if (existing.isPresent()) {
            Order cur = existing.get();
            if (o.revision() <= cur.revision()) {
                ack(envelope, o, null, null);                       // stale or duplicate: acknowledged, ignored
                return;
            }
            if ("CANCEL".equals(o.action())) {
                cancel(envelope, o, cur);
            } else {
                // IF-OB-002 §6.1: orders are released to picking on receipt, so changes are rejected (ERP re-plans).
                ack(envelope, o, "RELEASED_TO_PICK", "Order is " + cur.status() + "; create a new delivery for changes");
            }
            return;
        }
        if ("CANCEL".equals(o.action())) {
            ack(envelope, o, null, null);
            return;
        }
        create(envelope, o);
        ack(envelope, o, null, null);
    }

    private void create(EventEnvelope envelope, OutboundOrder o) {
        String site = envelope.siteId();
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        String pickLpn = "PK-" + o.erpDocNo();
        jdbc.sql("""
                        insert into outbound_order (id, tenant_id, site_id, erp_doc_no, order_type, revision, source_system,
                            ship_to, carrier_scac, planned_gi_utc, status, staging_location, pick_lpn, created_at, updated_at)
                        values (:id, :t, :site, :doc, :type, :rev, :src, cast(:shipTo as jsonb), :scac, :gi, 'RELEASED',
                                :staging, :pickLpn, :now, :now)""")
                .param("id", id).param("t", TenantContext.tenantId()).param("site", site).param("doc", o.erpDocNo())
                .param("type", o.orderType()).param("rev", o.revision()).param("src", envelope.sourceSystem())
                .param("shipTo", o.shipTo() == null ? null : json.writeValueAsString(o.shipTo()))
                .param("scac", o.carrierScac())
                .param("gi", o.plannedGoodsIssueUtc() == null ? null : Timestamp.from(o.plannedGoodsIssueUtc()))
                .param("staging", stagingLocation).param("pickLpn", pickLpn).param("now", now).update();
        BigDecimal totalAllocated = BigDecimal.ZERO;
        for (OutboundOrder.Line l : o.lines()) {
            InventoryClient.AllocateResult a = inventory.allocate(site, "OUT-" + o.erpDocNo() + "-" + o.revision() + "-" + l.erpLineRef(),
                    o.erpDocNo(), l.erpLineRef(), l.ownerId(), l.itemNo(), l.qtyRequested(), l.uom(), l.lotNo());
            jdbc.sql("""
                            insert into outbound_line (order_id, tenant_id, erp_line_ref, owner_id, item_no, qty_requested,
                                uom, lot_no, base_uom, qty_requested_base, qty_allocated, qty_short)
                            values (:o, :t, :ref, :owner, :item, :qty, :uom, :lot, :baseUom, :reqBase, :alloc, :short)""")
                    .param("o", id).param("t", TenantContext.tenantId()).param("ref", l.erpLineRef())
                    .param("owner", l.ownerId()).param("item", l.itemNo()).param("qty", l.qtyRequested())
                    .param("uom", l.uom()).param("lot", l.lotNo()).param("baseUom", a.baseUom())
                    .param("reqBase", a.requestedQty()).param("alloc", a.allocatedQty()).param("short", a.shortQty())
                    .update();
            for (InventoryClient.Allocation al : a.allocations()) {
                jdbc.sql("""
                                insert into outbound_allocation (allocation_id, tenant_id, order_id, erp_line_ref, location_id,
                                    lpn_id, lot_no, qty, status)
                                values (:a, :t, :o, :ref, :loc, :lpn, :lot, :qty, 'OPEN')""")
                        .param("a", al.id()).param("t", TenantContext.tenantId()).param("o", id)
                        .param("ref", l.erpLineRef()).param("loc", al.locationId()).param("lpn", al.lpnId())
                        .param("lot", al.lotNo()).param("qty", al.qty()).update();
                outbox.append(new OutboxWriter.Message(OutboundContracts.TOPIC_TASK_REQUESTS,
                        OutboundContracts.PickRequested.TYPE, OutboundContracts.PickRequested.VERSION, null, site,
                        l.ownerId(), site + ":" + o.erpDocNo(),
                        new OutboundContracts.PickRequested(al.id(), o.erpDocNo(), l.erpLineRef(), l.ownerId(),
                                l.itemNo(), al.lotNo(), al.qty(), a.baseUom(), al.locationId(), al.lpnId(),
                                stagingLocation, pickLpn, pickPriority)));
            }
            totalAllocated = totalAllocated.add(a.allocatedQty());
        }
        if (totalAllocated.signum() == 0) {
            setStatus(id, "BACKORDERED");
        }
    }

    private void cancel(EventEnvelope envelope, OutboundOrder o, Order cur) {
        if (!"RELEASED".equals(cur.status()) && !"BACKORDERED".equals(cur.status())) {
            ack(envelope, o, cur.status(), "Order is " + cur.status() + "; it can no longer be cancelled");
            return;
        }
        BigDecimal picked = jdbc.sql("select coalesce(sum(qty_picked), 0) from outbound_line where order_id = :o")
                .param("o", cur.id()).query(BigDecimal.class).single();
        if (picked.signum() > 0) {
            ack(envelope, o, "PICK_STARTED", "Picked stock must be returned before the order can be cancelled");
            return;
        }
        inventory.release(cur.siteId(), "OUT-REL-" + o.erpDocNo() + "-" + o.revision(), o.erpDocNo());
        for (UUID allocation : jdbc.sql("select allocation_id from outbound_allocation where order_id = :o and status = 'OPEN'")
                .param("o", cur.id()).query(UUID.class).list()) {
            outbox.append(new OutboxWriter.Message(OutboundContracts.TOPIC_TASK_REQUESTS,
                    OutboundContracts.PickCancelled.TYPE, OutboundContracts.PickCancelled.VERSION, null, cur.siteId(), null,
                    cur.siteId() + ":" + o.erpDocNo(), new OutboundContracts.PickCancelled(allocation, o.erpDocNo())));
        }
        jdbc.sql("update outbound_allocation set status = 'CANCELLED' where order_id = :o and status = 'OPEN'")
                .param("o", cur.id()).update();
        jdbc.sql("update outbound_order set status = 'CANCELLED', revision = :rev, updated_at = :now where id = :id")
                .param("rev", o.revision()).param("now", Timestamp.from(clock.instant())).param("id", cur.id()).update();
        ack(envelope, o, null, null);
    }

    private void ack(EventEnvelope envelope, OutboundOrder o, String rejectCode, String rejectText) {
        outbox.append(new OutboxWriter.Message(IntegrationContracts.TOPIC_APPLICATION_ACKS, ApplicationAck.TYPE,
                ApplicationAck.VERSION, envelope.sourceSystem(), envelope.siteId(), envelope.ownerId(),
                envelope.siteId() + ":" + o.erpDocNo(),
                new ApplicationAck(envelope.sourceSystem(), envelope.messageId().toString(), o.sourceIdocOrEventId(),
                        o.erpDocNo(), rejectCode == null ? ApplicationAck.ACCEPTED : ApplicationAck.REJECTED,
                        rejectCode, rejectText)));
    }

    // =====================================================================================================
    // Pick progress
    // =====================================================================================================

    @Transactional
    public void onTaskCompleted(TaskCompleted t) {
        if (!"PICK".equals(t.taskType()) || t.allocationId() == null) {
            return;
        }
        Optional<UUID> orderId = jdbc.sql("""
                        update outbound_allocation set qty_picked = :picked, qty_short = :short, status = 'DONE'
                        where allocation_id = :a and status = 'OPEN' returning order_id""")
                .param("picked", t.qtyPicked()).param("short", t.qtyShort()).param("a", t.allocationId())
                .query(UUID.class).optional();
        if (orderId.isEmpty()) {
            return;   // duplicate or cancelled
        }
        jdbc.sql("""
                        update outbound_line set qty_picked = qty_picked + :picked, qty_short = qty_short + :short
                        where order_id = :o and erp_line_ref = :line""")
                .param("picked", t.qtyPicked()).param("short", t.qtyShort()).param("o", orderId.get())
                .param("line", t.orderLineRef()).update();
        boolean open = jdbc.sql("select exists (select 1 from outbound_allocation where order_id = :o and status = 'OPEN')")
                .param("o", orderId.get()).query(Boolean.class).single();
        if (!open) {
            jdbc.sql("update outbound_order set status = 'PICKED', updated_at = :now where id = :id and status = 'RELEASED'")
                    .param("now", Timestamp.from(clock.instant())).param("id", orderId.get()).update();
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
        List<InventoryClient.IssuedLine> issued = inventory.issue(siteId, "OUT-SHIP-" + erpDocNo, erpDocNo);
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
        List<InventoryClient.IssuedLine> issued = inventory.issue(siteId, "OUT-SHIP-" + erpDocNo, erpDocNo);
        jdbc.sql("update outbound_order set status = 'SHIPPED', erp_error_class = null, erp_error_text = null, updated_at = :now where id = :id")
                .param("now", Timestamp.from(clock.instant())).param("id", o.id()).update();
        publishConfirmation(siteId, o.id(), erpDocNo, o.shipmentTxnId(), shippedAt, issued);
        return detail(siteId, erpDocNo);
    }

    private void publishConfirmation(String siteId, UUID orderId, String erpDocNo, String txn, Instant shippedAt,
                                     List<InventoryClient.IssuedLine> issued) {
        Map<String, InventoryClient.IssuedLine> byLine = new HashMap<>();
        issued.forEach(l -> byLine.put(l.orderLineRef(), l));
        record Line(String ref, String item, BigDecimal requestedBase, BigDecimal allocated, String baseUom) {
        }
        List<Line> lines = jdbc.sql("""
                        select erp_line_ref, item_no, qty_requested_base, qty_allocated, base_uom
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
                shortReason = l.allocated().compareTo(l.requestedBase()) < 0 ? "NO_STOCK" : "SHORT_PICK";
            }
            out.add(new ShipmentConfirmation.Line(l.ref(), l.item(), strip(qty), l.baseUom(),
                    shipped == null ? List.of() : shipped.lots().stream()
                            .map(x -> new ShipmentConfirmation.LotSplit(x.lotNo(), x.qty())).toList(),
                    shipped == null || shipped.serials().isEmpty() ? null : shipped.serials(), shortReason));
        }
        record Header(String scac, String tracking, String bol) {
        }
        Header h = jdbc.sql("select carrier_scac, tracking_no, bill_of_lading from outbound_order where id = :id")
                .param("id", orderId).query((rs, n) -> new Header(rs.getString(1), rs.getString(2), rs.getString(3))).single();
        outbox.append(new OutboxWriter.Message(OutboundContracts.TOPIC_SHIPMENT_CONFIRMATIONS, ShipmentConfirmation.TYPE,
                ShipmentConfirmation.VERSION, "ERP", siteId, null, siteId + ":" + erpDocNo,
                new ShipmentConfirmation(txn, erpDocNo, shippedAt, h.scac(), h.tracking(), h.bol(), out)));
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
    // Queries and helpers
    // =====================================================================================================

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String siteId, String status) {
        return jdbc.sql("""
                        select erp_doc_no, order_type, status, carrier_scac, planned_gi_utc, shipment_txn_id, erp_document
                        from outbound_order where site_id = :site and (cast(:status as text) is null or status = :status)
                        order by planned_gi_utc nulls last, erp_doc_no limit 500""")
                .param("site", siteId).param("status", status).query().listOfRows();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String siteId, String erpDocNo) {
        Map<String, Object> header = jdbc.sql("""
                        select id, erp_doc_no, order_type, revision, status, carrier_scac, staging_location, pick_lpn,
                               shipment_txn_id, tracking_no, erp_document, erp_error_class, erp_error_text
                        from outbound_order where site_id = :site and erp_doc_no = :doc""")
                .param("site", siteId).param("doc", erpDocNo).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> unknown(erpDocNo));
        Map<String, Object> result = new HashMap<>(header);
        result.put("lines", jdbc.sql("""
                        select erp_line_ref, item_no, qty_requested, uom, base_uom, qty_requested_base, qty_allocated,
                               qty_picked, qty_short
                        from outbound_line where order_id = :o order by erp_line_ref""")
                .param("o", header.get("id")).query().listOfRows().stream().map(OutboundService::stripRow).toList());
        result.put("allocations", jdbc.sql("""
                        select allocation_id, erp_line_ref, location_id, lpn_id, lot_no, qty, qty_picked, qty_short, status
                        from outbound_allocation where order_id = :o order by erp_line_ref""")
                .param("o", header.get("id")).query().listOfRows().stream().map(OutboundService::stripRow).toList());
        return result;
    }

    private Optional<Order> lockOrder(String siteId, String erpDocNo) {
        return jdbc.sql("""
                        select id, site_id, erp_doc_no, status, revision, pick_lpn, shipment_txn_id from outbound_order
                        where site_id = :site and erp_doc_no = :doc for update""")
                .param("site", siteId).param("doc", erpDocNo)
                .query((rs, n) -> new Order(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getLong(5), rs.getString(6), rs.getString(7)))
                .optional();
    }

    private void setStatus(UUID id, String status) {
        jdbc.sql("update outbound_order set status = :s, updated_at = :now where id = :id")
                .param("s", status).param("now", Timestamp.from(clock.instant())).param("id", id).update();
    }

    private static ApiException unknown(String erpDocNo) {
        return ApiException.notFound("OUT_ORDER_UNKNOWN", "No outbound order " + erpDocNo);
    }

    private static Map<String, Object> stripRow(Map<String, Object> row) {
        Map<String, Object> out = new HashMap<>(row);
        out.replaceAll((k, v) -> v instanceof BigDecimal b ? strip(b) : v);
        return out;
    }

    private static BigDecimal strip(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }
}
