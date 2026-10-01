package com.astrawms.inbound.expectation;

import com.astrawms.common.contracts.IntegrationContracts;
import com.astrawms.common.contracts.IntegrationContracts.ApplicationAck;
import com.astrawms.common.contracts.IntegrationContracts.ReceiptExpectation;
import com.astrawms.common.messaging.EventEnvelope;
import com.astrawms.common.messaging.OutboxWriter;
import com.astrawms.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies ERP receipt expectations (IF-IB-001) using the change-handling matrix of ISD IF-IB-001 §6.1 and answers
 * every document with an {@link ApplicationAck} (the adapter turns it into SAP ALEAUD 53/51).
 */
@Service
public class ExpectationService {

    private static final Logger log = LoggerFactory.getLogger(ExpectationService.class);

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final Clock clock;

    public ExpectationService(JdbcClient jdbc, OutboxWriter outbox, Clock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.clock = clock;
    }

    private record Current(UUID id, String status, long revision) {
    }

    private record Decision(String result, String reasonCode, String reasonText) {
        static Decision applied() {
            return new Decision("APPLIED", null, null);
        }

        static Decision rejected(String code, String text) {
            return new Decision("REJECTED", code, text);
        }
    }

    @Transactional
    public void apply(EventEnvelope envelope, ReceiptExpectation e) {
        String site = envelope.siteId();
        Optional<Current> current = jdbc.sql("""
                        select id, status, revision from receipt_expectation
                        where site_id = :site and erp_doc_no = :doc for update""")
                .param("site", site).param("doc", e.erpDocNo())
                .query((rs, n) -> new Current(rs.getObject("id", UUID.class), rs.getString("status"), rs.getLong("revision")))
                .optional();

        Decision decision;
        UUID id;
        if (current.isEmpty()) {
            if ("DELETE".equals(e.action())) {
                ack(envelope, e, Decision.applied());   // nothing to delete; idempotent
                return;
            }
            id = UUID.randomUUID();
            insertHeader(id, site, envelope.sourceSystem(), e);
            replaceLines(id, site, e);
            decision = Decision.applied();
        } else {
            Current c = current.get();
            id = c.id();
            if (e.revision() <= c.revision()) {
                log(id, e, new Decision("STALE", "STALE_REVISION", "Revision " + e.revision() + " <= " + c.revision()));
                ack(envelope, e, Decision.applied());
                return;
            }
            decision = switch (c.status()) {
                case "NOT_STARTED" -> {
                    if ("DELETE".equals(e.action())) {
                        setStatus(id, "CANCELLED", e.revision());
                    } else {
                        updateHeader(id, e);
                        replaceLines(id, site, e);
                    }
                    yield Decision.applied();
                }
                case "IN_PROGRESS" -> "DELETE".equals(e.action())
                        ? Decision.rejected("RECEIPT_IN_PROGRESS", "Receipt has started; delivery cannot be deleted")
                        : changeInProgress(id, site, e);
                default -> Decision.rejected("EXPECTATION_CLOSED",
                        "Expectation is " + c.status() + "; correct in ERP via return or adjustment");
            };
        }
        log(id, e, decision);
        ack(envelope, e, decision);
    }

    /** IN_PROGRESS: increases and new lines apply; a line may not drop below what was already received. */
    private Decision changeInProgress(UUID id, String site, ReceiptExpectation e) {
        Map<String, BigDecimal> received = new HashMap<>();
        jdbc.sql("select erp_line_ref, qty_received from receipt_expectation_line where expectation_id = :id")
                .param("id", id)
                .query((rs, n) -> received.put(rs.getString(1), rs.getBigDecimal(2))).list();
        Map<String, BigDecimal> requested = new HashMap<>();
        e.lines().forEach(l -> requested.put(l.erpLineRef(), l.qtyExpected()));
        for (Map.Entry<String, BigDecimal> r : received.entrySet()) {
            BigDecimal newQty = requested.getOrDefault(r.getKey(), BigDecimal.ZERO);
            if (newQty.compareTo(r.getValue()) < 0) {
                return Decision.rejected("QTY_BELOW_RECEIVED", "Line " + r.getKey() + ": new quantity "
                        + newQty.toPlainString() + " is below the received " + r.getValue().stripTrailingZeros().toPlainString());
            }
        }
        updateHeader(id, e);
        for (ReceiptExpectation.Line l : e.lines()) {
            upsertLine(id, l, received.containsKey(l.erpLineRef()));
        }
        // Lines absent from the new version with nothing received are removed.
        received.forEach((ref, qty) -> {
            if (!requested.containsKey(ref)) {
                jdbc.sql("delete from receipt_expectation_line where expectation_id = :id and erp_line_ref = :ref")
                        .param("id", id).param("ref", ref).update();
            }
        });
        return Decision.applied();
    }

    // ------------------------------------------------------------------ persistence

    private void insertHeader(UUID id, String site, String sourceSystem, ReceiptExpectation e) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql("""
                        insert into receipt_expectation (id, tenant_id, site_id, erp_doc_no, erp_doc_type, revision,
                            source_system, vendor_id, ship_from_gln, supplying_site_id, carrier_scac,
                            expected_arrival_utc, external_ref, bill_of_lading, container_no, seal_no, status,
                            source_changed_at, created_at, updated_at)
                        values (:id, :tenant, :site, :doc, :type, :rev, :source, :vendor, :gln, :supplying, :scac,
                                :arrival, :ext, :bol, :container, :seal, 'NOT_STARTED', :changed, :now, :now)""")
                .param("id", id).param("tenant", TenantContext.tenantId()).param("site", site)
                .param("doc", e.erpDocNo()).param("type", e.erpDocType()).param("rev", e.revision())
                .param("source", sourceSystem).param("vendor", e.vendorId()).param("gln", e.shipFromGln())
                .param("supplying", e.supplyingSiteId()).param("scac", e.carrierScac())
                .param("arrival", Timestamp.from(e.expectedArrivalUtc())).param("ext", e.externalRef())
                .param("bol", e.billOfLading()).param("container", e.containerNo()).param("seal", e.sealNo())
                .param("changed", Timestamp.from(e.sourceChangedAt())).param("now", now)
                .update();
    }

    private void updateHeader(UUID id, ReceiptExpectation e) {
        jdbc.sql("""
                        update receipt_expectation set revision = :rev, vendor_id = :vendor, carrier_scac = :scac,
                            expected_arrival_utc = :arrival, external_ref = :ext, bill_of_lading = :bol,
                            container_no = :container, seal_no = :seal, source_changed_at = :changed, updated_at = :now
                        where id = :id""")
                .param("id", id).param("rev", e.revision()).param("vendor", e.vendorId()).param("scac", e.carrierScac())
                .param("arrival", Timestamp.from(e.expectedArrivalUtc())).param("ext", e.externalRef())
                .param("bol", e.billOfLading()).param("container", e.containerNo()).param("seal", e.sealNo())
                .param("changed", Timestamp.from(e.sourceChangedAt())).param("now", Timestamp.from(clock.instant()))
                .update();
    }

    private void setStatus(UUID id, String status, long revision) {
        jdbc.sql("update receipt_expectation set status = :status, revision = :rev, updated_at = :now where id = :id")
                .param("id", id).param("status", status).param("rev", revision)
                .param("now", Timestamp.from(clock.instant())).update();
    }

    private void replaceLines(UUID id, String site, ReceiptExpectation e) {
        jdbc.sql("delete from expected_hu where expectation_id = :id").param("id", id).update();
        jdbc.sql("delete from receipt_expectation_line where expectation_id = :id").param("id", id).update();
        for (ReceiptExpectation.Line l : e.lines()) {
            upsertLine(id, l, false);
        }
        for (ReceiptExpectation.HandlingUnit hu : e.handlingUnits() == null ? List.<ReceiptExpectation.HandlingUnit>of() : e.handlingUnits()) {
            boolean duplicate = jdbc.sql("""
                            select exists (select 1 from expected_hu h join receipt_expectation x on x.id = h.expectation_id
                                           where h.site_id = :site and h.sscc = :sscc and h.expectation_id <> :id
                                             and x.status in ('NOT_STARTED', 'IN_PROGRESS'))""")
                    .param("site", site).param("sscc", hu.sscc()).param("id", id).query(Boolean.class).single();
            if (duplicate) {
                // IB001-E05: ignore this HU; its lines are received line by line. Logged for vendor compliance.
                log.warn("Duplicate SSCC {} on expectation {} ignored", hu.sscc(), e.erpDocNo());
                continue;
            }
            for (ReceiptExpectation.HuContent c : hu.contents()) {
                jdbc.sql("""
                                insert into expected_hu (expectation_id, tenant_id, site_id, sscc, erp_line_ref, qty, uom, lot_no)
                                values (:id, :tenant, :site, :sscc, :ref, :qty, :uom, :lot)""")
                        .param("id", id).param("tenant", TenantContext.tenantId()).param("site", site)
                        .param("sscc", hu.sscc()).param("ref", c.erpLineRef()).param("qty", c.qty())
                        .param("uom", c.uom()).param("lot", c.lotNo()).update();
            }
        }
    }

    private void upsertLine(UUID id, ReceiptExpectation.Line l, boolean exists) {
        String sql = exists ? """
                update receipt_expectation_line set qty_expected = :qty, lot_no = :lot, vendor_lot_no = :vlot,
                    over_tolerance_pct = :over, under_tolerance_pct = :under
                where expectation_id = :id and erp_line_ref = :ref""" : """
                insert into receipt_expectation_line (expectation_id, tenant_id, erp_line_ref, owner_id, item_no,
                    qty_expected, uom, lot_no, vendor_lot_no, po_no, po_line, stock_type_target, over_tolerance_pct,
                    under_tolerance_pct)
                values (:id, :tenant, :ref, :owner, :item, :qty, :uom, :lot, :vlot, :po, :poLine, :stock, :over, :under)
                on conflict (expectation_id, erp_line_ref) do update set qty_expected = excluded.qty_expected""";
        var stmt = jdbc.sql(sql)
                .param("id", id).param("ref", l.erpLineRef()).param("qty", l.qtyExpected())
                .param("lot", l.lotNo()).param("vlot", l.vendorLotNo())
                .param("over", l.overTolerancePct() == null ? BigDecimal.ZERO : l.overTolerancePct())
                .param("under", l.underTolerancePct() == null ? BigDecimal.valueOf(100) : l.underTolerancePct());
        if (!exists) {
            stmt = stmt.param("tenant", TenantContext.tenantId()).param("owner", l.ownerId()).param("item", l.itemNo())
                    .param("uom", l.uom())
                    .param("po", l.poRef() == null ? null : l.poRef().poNo())
                    .param("poLine", l.poRef() == null ? null : l.poRef().poLine())
                    .param("stock", l.stockTypeTarget() == null ? "AVAILABLE" : l.stockTypeTarget());
        }
        stmt.update();
    }

    private void log(UUID id, ReceiptExpectation e, Decision d) {
        jdbc.sql("""
                        insert into expectation_change_log (tenant_id, expectation_id, revision, action, result,
                                                            reason_code, reason_text, logged_at)
                        values (:tenant, :id, :rev, :action, :result, :code, :text, :now)""")
                .param("tenant", TenantContext.tenantId()).param("id", id).param("rev", e.revision())
                .param("action", e.action()).param("result", d.result()).param("code", d.reasonCode())
                .param("text", d.reasonText()).param("now", Timestamp.from(clock.instant())).update();
    }

    private void ack(EventEnvelope envelope, ReceiptExpectation e, Decision d) {
        boolean rejected = "REJECTED".equals(d.result());
        if (rejected) {
            log.info("Expectation {} revision {} rejected: {}", e.erpDocNo(), e.revision(), d.reasonCode());
        }
        outbox.append(new OutboxWriter.Message(IntegrationContracts.TOPIC_APPLICATION_ACKS, ApplicationAck.TYPE,
                ApplicationAck.VERSION, envelope.sourceSystem(), envelope.siteId(), envelope.ownerId(),
                envelope.siteId() + ":" + e.erpDocNo(),
                new ApplicationAck(envelope.sourceSystem(), envelope.messageId().toString(), e.sourceIdocOrEventId(),
                        e.erpDocNo(), rejected ? ApplicationAck.REJECTED : ApplicationAck.ACCEPTED,
                        d.reasonCode(), d.reasonText())));
    }
}
