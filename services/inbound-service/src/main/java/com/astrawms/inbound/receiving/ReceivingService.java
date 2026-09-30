package com.astrawms.inbound.receiving;

import com.astrawms.common.contracts.IntegrationContracts;
import com.astrawms.common.contracts.IntegrationContracts.ErpPostingResult;
import com.astrawms.common.contracts.IntegrationContracts.ReceiptConfirmation;
import com.astrawms.common.ids.WmsTxnId;
import com.astrawms.common.messaging.OutboxWriter;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import com.astrawms.inbound.api.InboundDtos.CloseRequest;
import com.astrawms.inbound.api.InboundDtos.ExpectationDetail;
import com.astrawms.inbound.api.InboundDtos.ExpectationSummary;
import com.astrawms.inbound.api.InboundDtos.HuDetail;
import com.astrawms.inbound.api.InboundDtos.LineDetail;
import com.astrawms.inbound.api.InboundDtos.LineProgress;
import com.astrawms.inbound.api.InboundDtos.ReceiveLineRequest;
import com.astrawms.inbound.api.InboundDtos.ReceiveResult;
import com.astrawms.inbound.api.InboundDtos.ReceiveSsccRequest;
import com.astrawms.inbound.inventory.InventoryClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * RF receiving against expectations, receipt close and ERP confirmation (scope §1, IF-IB-002).
 *
 * <p>Each receive action is one DB transaction that calls inventory-service synchronously with an idempotency key
 * derived from the RF action's key (ADR-0005). If anything fails after inventory succeeded, the RF client retries
 * with the same key: inventory replays its result and this service records the receipt exactly once.
 */
@Service
public class ReceivingService {

    private static final Logger log = LoggerFactory.getLogger(ReceivingService.class);
    private static final Set<String> OPEN = Set.of("NOT_STARTED", "IN_PROGRESS");
    private static final Set<String> SHORT_REASONS = Set.of("SHORT_VENDOR", "DAMAGED", "REFUSED", "IN_TRANSIT");
    private static final int MAX_KEY = 80;

    private final JdbcClient jdbc;
    private final InventoryClient inventory;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final Clock clock;

    public ReceivingService(JdbcClient jdbc, InventoryClient inventory, OutboxWriter outbox, JsonMapper json,
                            Clock clock) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    // =====================================================================================================
    // Receiving
    // =====================================================================================================

    private record Header(UUID id, String siteId, String erpDocNo, String status, String vendorId,
                          String confirmationTxnId) {
    }

    private record Line(String erpLineRef, String ownerId, String itemNo, BigDecimal qtyExpected, BigDecimal qtyReceived,
                        String uom, String lotNo, String stockTypeTarget, BigDecimal overTolerancePct) {
        BigDecimal maxQty() {
            return qtyExpected.multiply(BigDecimal.ONE.add(overTolerancePct.movePointLeft(2)))
                    .setScale(3, RoundingMode.DOWN);
        }
    }

    @Transactional
    public ReceiveResult receiveLine(String siteId, String erpDocNo, String lineRef, String idempotencyKey,
                                     ReceiveLineRequest r) {
        checkKey(idempotencyKey);
        String hash = hash("LINE|" + siteId + "|" + erpDocNo + "|" + lineRef + "|" + json.writeValueAsString(r));
        Optional<ReceiveResult> replay = replayOrReserve(idempotencyKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Header h = lockOpenExpectation(siteId, erpDocNo);
        Line line = line(h.id(), lineRef);
        if (!line.uom().equals(r.uom())) {
            throw ApiException.unprocessable("INB_UOM_MISMATCH",
                    "Line " + lineRef + " is expected in " + line.uom() + "; received in " + r.uom());
        }
        String lot = blankToNull(r.lotNo());
        if (line.lotNo() != null && !line.lotNo().equals(lot) && blankToNull(r.overrideReason()) == null) {
            throw ApiException.unprocessable("INB_LOT_MISMATCH", "ASN lot is " + line.lotNo() + "; scanned " + lot
                    + ". Accepting a different lot needs an overrideReason (INB-EX-08)");
        }
        checkTolerance(line, r.qty(), r.overrideReason(), r.approvedBy());

        InventoryClient.ReceiveResult inv = inventory.receive(siteId, "INB-" + idempotencyKey,
                new InventoryClient.ReceiveCommand(line.ownerId(), line.itemNo(), lot, r.expiryDate(), r.qty(),
                        r.uom(), blankToNull(r.lpnId()), r.locationId(), line.stockTypeTarget(),
                        erpDocNo + "/" + lineRef));
        recordTxn(h, line, idempotencyKey, r.qty(), r.uom(), lot, r.vendorLotNo(), r.expiryDate(), r.lpnId(),
                r.locationId(), inv.operationId(), r.overrideReason(), r.approvedBy());
        ReceiveResult result = new ReceiveResult(erpDocNo, progress(h.id()), List.of(inv.operationId()),
                blankToNull(r.lpnId()), false);
        saveResponse(idempotencyKey, result);
        return result;
    }

    /** Whole-pallet receipt by SSCC from the ASN: one scan, LPN = SSCC (INB-011). */
    @Transactional
    public ReceiveResult receiveSscc(String siteId, String erpDocNo, String sscc, String idempotencyKey,
                                     ReceiveSsccRequest r) {
        checkKey(idempotencyKey);
        String hash = hash("SSCC|" + siteId + "|" + erpDocNo + "|" + sscc + "|" + r.locationId());
        Optional<ReceiveResult> replay = replayOrReserve(idempotencyKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Header h = lockOpenExpectation(siteId, erpDocNo);
        record Content(String lineRef, BigDecimal qty, String uom, String lotNo, boolean received) {
        }
        List<Content> contents = jdbc.sql("""
                        select erp_line_ref, qty, uom, lot_no, received from expected_hu
                        where expectation_id = :id and sscc = :sscc order by erp_line_ref""")
                .param("id", h.id()).param("sscc", sscc)
                .query((rs, n) -> new Content(rs.getString(1), rs.getBigDecimal(2), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5)))
                .list();
        if (contents.isEmpty()) {
            throw ApiException.unprocessable("INB_SSCC_UNKNOWN",
                    "SSCC " + sscc + " is not on delivery " + erpDocNo + "; receive by line (INB-EX-07)");
        }
        if (contents.stream().anyMatch(Content::received)) {
            throw ApiException.conflict("INB_SSCC_ALREADY_RECEIVED", "SSCC " + sscc + " was already received");
        }
        List<UUID> operations = new ArrayList<>();
        for (Content c : contents) {
            Line line = line(h.id(), c.lineRef());
            checkTolerance(line, c.qty(), null, null);
            String key = idempotencyKey + "#" + c.lineRef();
            InventoryClient.ReceiveResult inv = inventory.receive(siteId, "INB-" + key,
                    new InventoryClient.ReceiveCommand(line.ownerId(), line.itemNo(), c.lotNo(), null, c.qty(), c.uom(),
                            sscc, r.locationId(), line.stockTypeTarget(), erpDocNo + "/" + c.lineRef()));
            recordTxn(h, line, key, c.qty(), c.uom(), c.lotNo(), null, null, sscc, r.locationId(),
                    inv.operationId(), null, null);
            operations.add(inv.operationId());
        }
        jdbc.sql("update expected_hu set received = true where expectation_id = :id and sscc = :sscc")
                .param("id", h.id()).param("sscc", sscc).update();
        ReceiveResult result = new ReceiveResult(erpDocNo, progress(h.id()), operations, sscc, false);
        saveResponse(idempotencyKey, result);
        return result;
    }

    // =====================================================================================================
    // Close and ERP confirmation
    // =====================================================================================================

    /** Closes the receipt and sends ReceiptConfirmation (IF-IB-002). Idempotent once closed. */
    @Transactional
    public ExpectationSummary close(String siteId, String erpDocNo, CloseRequest r) {
        Header h = lockHeader(siteId, erpDocNo);
        if (!OPEN.contains(h.status())) {
            if ("CANCELLED".equals(h.status())) {
                throw ApiException.unprocessable("INB_EXPECTATION_CANCELLED", "Delivery " + erpDocNo + " was cancelled");
            }
            return summary(siteId, erpDocNo);   // already closed: idempotent
        }
        Map<String, String> reasons = r == null || r.shortReasons() == null ? Map.of() : r.shortReasons();
        List<Line> lines = lines(h.id());
        List<String> missing = new ArrayList<>();
        for (Line l : lines) {
            if (l.qtyReceived().compareTo(l.qtyExpected()) < 0) {
                String reason = reasons.get(l.erpLineRef());
                if (reason == null) {
                    missing.add(l.erpLineRef());
                } else if (!SHORT_REASONS.contains(reason)) {
                    throw ApiException.unprocessable("INB_SHORT_REASON_INVALID",
                            "Short reason " + reason + " is not one of " + SHORT_REASONS);
                } else {
                    jdbc.sql("update receipt_expectation_line set short_reason = :r where expectation_id = :id and erp_line_ref = :ref")
                            .param("r", reason).param("id", h.id()).param("ref", l.erpLineRef()).update();
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INB_SHORT_REASON_REQUIRED",
                    "Lines received short need a reason (INB-003)", Map.of("lines", missing));
        }
        String txnId = WmsTxnId.next(clock);
        Instant now = clock.instant();
        jdbc.sql("""
                        update receipt_expectation set status = 'CLOSED', closed_at = :now, closed_by = :user,
                            confirmation_txn_id = :txn, updated_at = :now where id = :id""")
                .param("now", Timestamp.from(now)).param("user", TenantContext.require().userId())
                .param("txn", txnId).param("id", h.id()).update();
        publishConfirmation(h, txnId, now);
        return summary(siteId, erpDocNo);
    }

    /** Re-sends the confirmation with the <em>same</em> wmsTxnId after the ERP-side cause was fixed (INT-014). */
    @Transactional
    public ExpectationSummary repost(String siteId, String erpDocNo) {
        Header h = lockHeader(siteId, erpDocNo);
        if (!"POSTING_FAILED".equals(h.status())) {
            throw ApiException.conflict("INB_NOT_REPOSTABLE", "Only POSTING_FAILED receipts can be reposted; status is " + h.status());
        }
        Instant closedAt = jdbc.sql("select closed_at from receipt_expectation where id = :id").param("id", h.id())
                .query(Timestamp.class).single().toInstant();
        jdbc.sql("""
                        update receipt_expectation set status = 'CLOSED', erp_error_class = null, erp_error_text = null,
                            updated_at = :now where id = :id""")
                .param("now", Timestamp.from(clock.instant())).param("id", h.id()).update();
        publishConfirmation(h, h.confirmationTxnId(), closedAt);
        return summary(siteId, erpDocNo);
    }

    /** Terminal ERP result for a confirmation (INT-011). Duplicate results are ignored. */
    @Transactional
    public void onPostingResult(ErpPostingResult result) {
        int updated = result.success()
                ? jdbc.sql("""
                        update receipt_expectation set status = 'CONFIRMED', erp_document = :doc, erp_error_class = null,
                            erp_error_text = null, updated_at = :now
                        where confirmation_txn_id = :txn and status in ('CLOSED', 'POSTING_FAILED')""")
                .param("doc", result.erpDocument()).param("txn", result.wmsTxnId())
                .param("now", Timestamp.from(clock.instant())).update()
                : jdbc.sql("""
                        update receipt_expectation set status = 'POSTING_FAILED', erp_error_class = :cls,
                            erp_error_text = :text, updated_at = :now
                        where confirmation_txn_id = :txn and status = 'CLOSED'""")
                .param("cls", result.errorClass())
                .param("text", Objects.toString(result.erpMessageId(), "") + " " + Objects.toString(result.erpMessageText(), ""))
                .param("txn", result.wmsTxnId()).param("now", Timestamp.from(clock.instant())).update();
        if (updated == 0) {
            log.debug("Posting result for {} did not change any receipt (duplicate or unknown)", result.wmsTxnId());
        }
    }

    private void publishConfirmation(Header h, String txnId, Instant completedAt) {
        List<Line> lines = lines(h.id());
        record Txn(String lineRef, String lotNo, String vendorLotNo, LocalDate expiry, BigDecimal qty, String lpn,
                   String status) {
        }
        List<Txn> txns = jdbc.sql("""
                        select erp_line_ref, lot_no, vendor_lot_no, expiry_date, qty, lpn_id, stock_status
                        from receipt_txn where expectation_id = :id and qty > 0 order by received_at""")
                .param("id", h.id())
                .query((rs, n) -> {
                    Date expiry = rs.getDate("expiry_date");
                    return new Txn(rs.getString(1), rs.getString(2), rs.getString(3),
                            expiry == null ? null : expiry.toLocalDate(), rs.getBigDecimal(5), rs.getString(6),
                            rs.getString(7));
                })
                .list();
        Map<String, String> shortReasons = new LinkedHashMap<>();
        jdbc.sql("select erp_line_ref, short_reason from receipt_expectation_line where expectation_id = :id")
                .param("id", h.id()).query((rs, n) -> shortReasons.put(rs.getString(1), rs.getString(2))).list();

        List<ReceiptConfirmation.Line> confirmationLines = new ArrayList<>();
        for (Line l : lines) {
            Map<String, ReceiptConfirmation.LotSplit> splits = new LinkedHashMap<>();
            for (Txn t : txns) {
                if (t.lineRef().equals(l.erpLineRef()) && t.lotNo() != null) {
                    splits.merge(t.lotNo(), new ReceiptConfirmation.LotSplit(t.lotNo(), t.vendorLotNo(), t.qty(), t.expiry()),
                            (a, b) -> new ReceiptConfirmation.LotSplit(a.lotNo(), a.vendorLotNo(), a.qty().add(b.qty()),
                                    a.expiryDate() != null ? a.expiryDate() : b.expiryDate()));
                }
            }
            int cmp = l.qtyReceived().compareTo(l.qtyExpected());
            String reason = cmp < 0 ? shortReasons.get(l.erpLineRef()) : cmp > 0 ? "OVER_ACCEPTED" : null;
            confirmationLines.add(new ReceiptConfirmation.Line(l.erpLineRef(), l.itemNo(), strip(l.qtyReceived()),
                    l.uom(), new ArrayList<>(splits.values()), l.stockTypeTarget(), reason));
        }
        Map<String, List<ReceiptConfirmation.HuContent>> hus = new LinkedHashMap<>();
        for (Txn t : txns) {
            if (t.lpn() != null) {
                hus.computeIfAbsent(t.lpn(), k -> new ArrayList<>())
                        .add(new ReceiptConfirmation.HuContent(t.lineRef(), t.lotNo(), t.qty()));
            }
        }
        List<ReceiptConfirmation.HandlingUnit> handlingUnits = hus.entrySet().stream()
                .map(e -> new ReceiptConfirmation.HandlingUnit(e.getKey(), null, e.getValue())).toList();

        outbox.append(new OutboxWriter.Message(IntegrationContracts.TOPIC_RECEIPT_CONFIRMATIONS,
                ReceiptConfirmation.TYPE, ReceiptConfirmation.VERSION, "ERP", h.siteId(), null,
                h.siteId() + ":" + h.erpDocNo(),
                new ReceiptConfirmation(txnId, h.erpDocNo(), false, h.vendorId(), completedAt, true,
                        confirmationLines, handlingUnits)));
    }

    // =====================================================================================================
    // Queries
    // =====================================================================================================

    @Transactional(readOnly = true)
    public List<ExpectationSummary> list(String siteId, String status) {
        return jdbc.sql(SUMMARY + " where e.site_id = :site and (cast(:status as text) is null or e.status = :status)"
                        + " order by e.expected_arrival_utc, e.erp_doc_no limit 500")
                .param("site", siteId).param("status", status).query(ReceivingService::summary).list();
    }

    @Transactional(readOnly = true)
    public ExpectationSummary summary(String siteId, String erpDocNo) {
        return jdbc.sql(SUMMARY + " where e.site_id = :site and e.erp_doc_no = :doc")
                .param("site", siteId).param("doc", erpDocNo).query(ReceivingService::summary).optional()
                .orElseThrow(() -> ApiException.notFound("INB_EXPECTATION_UNKNOWN", "No expectation " + erpDocNo));
    }

    @Transactional(readOnly = true)
    public ExpectationDetail detail(String siteId, String erpDocNo) {
        ExpectationSummary header = summary(siteId, erpDocNo);
        List<LineDetail> lines = jdbc.sql("""
                        select erp_line_ref, owner_id, item_no, qty_expected, qty_received, uom, lot_no,
                               stock_type_target, over_tolerance_pct, short_reason
                        from receipt_expectation_line where expectation_id = :id order by erp_line_ref""")
                .param("id", header.id())
                .query((rs, n) -> new LineDetail(rs.getString(1), rs.getString(2), rs.getString(3),
                        strip(rs.getBigDecimal(4)), strip(rs.getBigDecimal(5)), rs.getString(6), rs.getString(7),
                        rs.getString(8), strip(rs.getBigDecimal(9)), rs.getString(10)))
                .list();
        List<HuDetail> hus = jdbc.sql("""
                        select sscc, erp_line_ref, qty, lot_no, received from expected_hu
                        where expectation_id = :id order by sscc, erp_line_ref""")
                .param("id", header.id())
                .query((rs, n) -> new HuDetail(rs.getString(1), rs.getString(2), strip(rs.getBigDecimal(3)),
                        rs.getString(4), rs.getBoolean(5)))
                .list();
        return new ExpectationDetail(header, lines, hus);
    }

    private static final String SUMMARY = """
            select e.id, e.erp_doc_no, e.erp_doc_type, e.revision, e.vendor_id, e.expected_arrival_utc, e.status,
                   (select count(*) from receipt_expectation_line l where l.expectation_id = e.id) as line_count,
                   e.confirmation_txn_id, e.erp_document, e.erp_error_class, e.erp_error_text
            from receipt_expectation e""";

    private static ExpectationSummary summary(ResultSet rs, int n) throws SQLException {
        return new ExpectationSummary(rs.getObject("id", UUID.class), rs.getString("erp_doc_no"),
                rs.getString("erp_doc_type"), rs.getLong("revision"), rs.getString("vendor_id"),
                rs.getTimestamp("expected_arrival_utc").toInstant(), rs.getString("status"), rs.getInt("line_count"),
                rs.getString("confirmation_txn_id"), rs.getString("erp_document"), rs.getString("erp_error_class"),
                rs.getString("erp_error_text"));
    }

    // =====================================================================================================
    // Helpers
    // =====================================================================================================

    /** INB-001/002: cumulative receipts stay within the over-tolerance unless a supervisor approves the override. */
    private static void checkTolerance(Line line, BigDecimal qty, String overrideReason, String approvedBy) {
        BigDecimal total = line.qtyReceived().add(qty);
        if (total.compareTo(line.maxQty()) <= 0) {
            return;
        }
        if (blankToNull(overrideReason) == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INB_OVER_TOLERANCE",
                    "Receiving " + qty.toPlainString() + " would exceed the allowed " + strip(line.maxQty()).toPlainString()
                            + " for line " + line.erpLineRef() + " (INB-002)",
                    Map.of("maxQty", strip(line.maxQty()), "receivedQty", strip(line.qtyReceived())));
        }
        String user = TenantContext.require().userId();
        if (blankToNull(approvedBy) == null || approvedBy.equals(user)) {
            throw ApiException.unprocessable("INB_OVERRIDE_APPROVAL_REQUIRED",
                    "Over-receipt beyond tolerance needs approvedBy from another user (INB-EX-02)");
        }
    }

    private Header lockHeader(String siteId, String erpDocNo) {
        return jdbc.sql("""
                        select id, site_id, erp_doc_no, status, vendor_id, confirmation_txn_id from receipt_expectation
                        where site_id = :site and erp_doc_no = :doc for update""")
                .param("site", siteId).param("doc", erpDocNo)
                .query((rs, n) -> new Header(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)))
                .optional()
                .orElseThrow(() -> ApiException.notFound("INB_EXPECTATION_UNKNOWN",
                        "No expectation for delivery " + erpDocNo + " at site " + siteId));
    }

    private Header lockOpenExpectation(String siteId, String erpDocNo) {
        Header h = lockHeader(siteId, erpDocNo);
        if (!OPEN.contains(h.status())) {
            throw ApiException.unprocessable("INB_EXPECTATION_CLOSED", "Delivery " + erpDocNo + " is " + h.status());
        }
        if ("NOT_STARTED".equals(h.status())) {
            jdbc.sql("update receipt_expectation set status = 'IN_PROGRESS', updated_at = :now where id = :id")
                    .param("now", Timestamp.from(clock.instant())).param("id", h.id()).update();
        }
        return h;
    }

    private Line line(UUID expectationId, String lineRef) {
        return lines(expectationId).stream().filter(l -> l.erpLineRef().equals(lineRef)).findFirst()
                .orElseThrow(() -> ApiException.unprocessable("INB_LINE_UNKNOWN", "Line " + lineRef + " is not expected"));
    }

    private List<Line> lines(UUID expectationId) {
        return jdbc.sql("""
                        select erp_line_ref, owner_id, item_no, qty_expected, qty_received, uom, lot_no,
                               stock_type_target, over_tolerance_pct
                        from receipt_expectation_line where expectation_id = :id order by erp_line_ref""")
                .param("id", expectationId)
                .query((rs, n) -> new Line(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getBigDecimal(9)))
                .list();
    }

    private List<LineProgress> progress(UUID expectationId) {
        return lines(expectationId).stream().map(l -> new LineProgress(l.erpLineRef(), l.itemNo(),
                strip(l.qtyExpected()), strip(l.qtyReceived()),
                strip(l.qtyExpected().subtract(l.qtyReceived()).max(BigDecimal.ZERO)), l.uom())).toList();
    }

    private void recordTxn(Header h, Line line, String key, BigDecimal qty, String uom, String lot,
                           String vendorLot, LocalDate expiry, String lpn, String location, UUID inventoryOperation,
                           String overrideReason, String approvedBy) {
        TenantContext.Scope scope = TenantContext.require();
        jdbc.sql("""
                        insert into receipt_txn (id, tenant_id, expectation_id, erp_line_ref, idempotency_key,
                            qty, uom, lot_no, vendor_lot_no, expiry_date, lpn_id, location_id,
                            stock_status, inventory_operation_id, override_reason, approved_by, received_by, received_at)
                        values (:id, :tenant, :exp, :ref, :key, :qty, :uom, :lot, :vlot, :expiry, :lpn, :loc,
                                :status, :op, :override, :approvedBy, :user, :now)""")
                .param("id", UUID.randomUUID()).param("tenant", scope.tenantId()).param("exp", h.id())
                .param("ref", line.erpLineRef()).param("key", key).param("qty", qty)
                .param("uom", uom).param("lot", lot).param("vlot", blankToNull(vendorLot))
                .param("expiry", expiry == null ? null : Date.valueOf(expiry)).param("lpn", blankToNull(lpn))
                .param("loc", location).param("status", line.stockTypeTarget()).param("op", inventoryOperation)
                .param("override", blankToNull(overrideReason)).param("approvedBy", blankToNull(approvedBy))
                .param("user", scope.userId()).param("now", Timestamp.from(clock.instant()))
                .update();
        jdbc.sql("""
                        update receipt_expectation_line set qty_received = qty_received + :qty
                        where expectation_id = :id and erp_line_ref = :ref""")
                .param("qty", qty).param("id", h.id()).param("ref", line.erpLineRef()).update();
    }

    /**
     * Returns the stored result for a repeated RF action, or reserves the key for this request. A concurrent request
     * with the same key blocks on the primary key until the first commits, then replays its result.
     */
    private Optional<ReceiveResult> replayOrReserve(String key, String hash) {
        Optional<ReceiveResult> stored = storedResult(key, hash);
        if (stored.isPresent()) {
            return stored;
        }
        int reserved = jdbc.sql("""
                        insert into receive_request (tenant_id, idempotency_key, request_hash, created_at)
                        values (:tenant, :key, :hash, :now) on conflict (tenant_id, idempotency_key) do nothing""")
                .param("tenant", TenantContext.tenantId()).param("key", key).param("hash", hash)
                .param("now", Timestamp.from(clock.instant())).update();
        return reserved == 1 ? Optional.empty() : storedResult(key, hash);
    }

    private Optional<ReceiveResult> storedResult(String key, String hash) {
        record Stored(String hash, String response) {
        }
        Optional<Stored> stored = jdbc.sql("select request_hash, response::text from receive_request where idempotency_key = :key")
                .param("key", key).query((rs, n) -> new Stored(rs.getString(1), rs.getString(2))).optional();
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        if (!stored.get().hash().equals(hash)) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was already used for a different request");
        }
        if (stored.get().response() == null) {
            throw ApiException.conflict("OPERATION_IN_PROGRESS", "The original request is still being processed");
        }
        return Optional.of(json.readValue(stored.get().response(), ReceiveResult.class).asReplay());
    }

    private void saveResponse(String key, ReceiveResult result) {
        jdbc.sql("update receive_request set response = cast(:r as jsonb) where idempotency_key = :key")
                .param("r", json.writeValueAsString(result)).param("key", key).update();
    }

    private static void checkKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key is required (1-" + MAX_KEY + " characters)");
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static BigDecimal strip(BigDecimal v) {
        if (v == null) {
            return null;
        }
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }
}
