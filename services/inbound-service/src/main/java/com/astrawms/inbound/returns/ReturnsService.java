package com.astrawms.inbound.returns;

import com.astrawms.common.contracts.IntegrationContracts;
import com.astrawms.common.contracts.IntegrationContracts.ApplicationAck;
import com.astrawms.common.contracts.IntegrationContracts.ErpPostingResult;
import com.astrawms.common.contracts.ReturnsContracts;
import com.astrawms.common.contracts.ReturnsContracts.ReturnConfirmation;
import com.astrawms.common.contracts.ReturnsContracts.ReturnExpectation;
import com.astrawms.common.ids.WmsTxnId;
import com.astrawms.common.messaging.EventEnvelope;
import com.astrawms.common.messaging.OutboxWriter;
import com.astrawms.common.security.AccessScope;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import com.astrawms.inbound.inventory.InventoryClient;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer returns (§8):
 * <pre>
 * RMA from the ERP (EXPECTED) or blind return ──receive & grade units──▶ IN_PROGRESS ──close──▶ CLOSED
 *     ──ERP result──▶ CONFIRMED / POSTING_FAILED (repost)
 * </pre>
 * Each unit is graded (A–E) and given a disposition (suggested by the disposition table, overridable within the
 * rules); it goes into inventory at the returns location with the stock status of its disposition.
 */
@Service
public class ReturnsService {

    /** Disposition → WMS stock status at receipt. */
    static final Map<String, String> STOCK_STATUS = Map.of(
            "RESTOCK", "AVAILABLE", "QUARANTINE", "QI", "REFURBISH", "BLOCKED", "RTV", "BLOCKED",
            "LIQUIDATE", "BLOCKED", "SCRAP", "DAMAGED");

    public record ReceiveUnit(String erpLineRef, String ownerId, String itemNo, BigDecimal qty, String uom, String lotNo,
                              List<String> serials, String conditionGrade, String returnReasonActual, String disposition,
                              String locationId, String lpnId, boolean override) {
    }

    private record Header(UUID id, String siteId, String rmaNo, String type, String status, long revision,
                          String receiptTxnId, String dispositionTxnId) {
    }

    private record Line(String ref, String owner, String item, BigDecimal expected, String uom, List<String> serials) {
    }

    private final JdbcClient jdbc;
    private final InventoryClient inventory;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final com.astrawms.inbound.receiving.ReceivingWork work;

    public ReturnsService(JdbcClient jdbc, InventoryClient inventory, OutboxWriter outbox, Clock clock,
                          com.astrawms.inbound.receiving.ReceivingWork work) {
        this.work = work;
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Disposition table (§8.3, first cut): recalls and unexpected serials always go to quarantine; otherwise by
     * condition grade A/B restock, C refurbish, D return to vendor, E quarantine.
     */
    static String suggest(String returnType, String grade, boolean serialFlag) {
        if ("RECALL".equals(returnType) || serialFlag) {
            return "QUARANTINE";
        }
        return switch (grade) {
            case "A", "B" -> "RESTOCK";
            case "C" -> "REFURBISH";
            case "D" -> "RTV";
            default -> "QUARANTINE";
        };
    }

    // ------------------------------------------------------------------ IF-RET-001

    @Transactional
    public void apply(EventEnvelope envelope, ReturnExpectation e) {
        String site = envelope.siteId();
        Optional<Header> current = header(site, e.rmaNo(), true);
        if (current.isEmpty()) {
            if ("CANCEL".equals(e.action())) {
                ack(envelope, e, null, null);
                return;
            }
            UUID id = UUID.randomUUID();
            Timestamp now = Timestamp.from(clock.instant());
            jdbc.sql("""
                            insert into return_order (id, tenant_id, site_id, rma_no, return_type, revision, source_system,
                                campaign_id, customer_id, customer_name, expected_arrival_utc, status, created_at, updated_at)
                            values (:id, :t, :site, :rma, :type, :rev, :src, :campaign, :cust, :name, :eta, 'EXPECTED', :now, :now)""")
                    .param("id", id).param("t", TenantContext.tenantId()).param("site", site).param("rma", e.rmaNo())
                    .param("type", e.returnType() == null ? "CUSTOMER" : e.returnType()).param("rev", e.revision())
                    .param("src", envelope.sourceSystem()).param("campaign", e.campaignId())
                    .param("cust", e.customer() == null ? null : e.customer().partnerId())
                    .param("name", e.customer() == null ? null : e.customer().name())
                    .param("eta", e.expectedArrivalUtc() == null ? null : Timestamp.from(e.expectedArrivalUtc()))
                    .param("now", now).update();
            insertLines(id, e.lines());
            ack(envelope, e, null, null);
            work.rmaReceivable(site, id);          // ADR-0019: an RF receiving task for the RMA
            return;
        }
        Header h = current.get();
        if (e.revision() <= h.revision()) {
            ack(envelope, e, null, null);
            return;
        }
        if (!"EXPECTED".equals(h.status())) {
            ack(envelope, e, "RETURN_IN_PROGRESS", "Return " + e.rmaNo() + " is " + h.status() + " in the warehouse");
            return;
        }
        if ("CANCEL".equals(e.action())) {
            jdbc.sql("update return_order set status = 'CANCELLED', revision = :rev, updated_at = :now where id = :id")
                    .param("rev", e.revision()).param("now", Timestamp.from(clock.instant())).param("id", h.id()).update();
            work.ended(site, com.astrawms.common.contracts.ReceivingContracts.KIND_RMA, e.rmaNo(), "CANCELLED");
        } else {
            jdbc.sql("delete from return_line where return_id = :id").param("id", h.id()).update();
            insertLines(h.id(), e.lines());
            jdbc.sql("update return_order set revision = :rev, updated_at = :now where id = :id")
                    .param("rev", e.revision()).param("now", Timestamp.from(clock.instant())).param("id", h.id()).update();
            work.rmaReceivable(site, h.id());
        }
        ack(envelope, e, null, null);
    }

    private void insertLines(UUID id, List<ReturnExpectation.Line> lines) {
        for (ReturnExpectation.Line l : lines) {
            jdbc.sql("""
                            insert into return_line (return_id, tenant_id, erp_line_ref, owner_id, item_no, qty_expected, uom,
                                return_reason, expected_serials, inspection_required)
                            values (:id, :t, :ref, :owner, :item, :qty, :uom, :reason, cast(:serials as text[]), :insp)""")
                    .param("id", id).param("t", TenantContext.tenantId()).param("ref", l.erpLineRef())
                    .param("owner", l.ownerId()).param("item", l.itemNo()).param("qty", l.qtyExpected()).param("uom", l.uom())
                    .param("reason", l.returnReason()).param("serials", pgArray(l.expectedSerials()))
                    .param("insp", l.inspectionRequired()).update();
        }
    }

    private void ack(EventEnvelope envelope, ReturnExpectation e, String rejectCode, String rejectText) {
        outbox.append(new OutboxWriter.Message(IntegrationContracts.TOPIC_APPLICATION_ACKS, ApplicationAck.TYPE,
                ApplicationAck.VERSION, envelope.sourceSystem(), envelope.siteId(), envelope.ownerId(),
                envelope.siteId() + ":" + e.rmaNo(),
                new ApplicationAck(envelope.sourceSystem(), envelope.messageId().toString(), e.sourceIdocOrEventId(),
                        e.rmaNo(), rejectCode == null ? ApplicationAck.ACCEPTED : ApplicationAck.REJECTED, rejectCode, rejectText)));
    }

    // ------------------------------------------------------------------ blind returns (no RMA)

    @Transactional
    public Map<String, Object> createBlind(String siteId, String customerName) {
        long n = jdbc.sql("select nextval('blind_return_seq')").query(Long.class).single();
        String rma = "BLIND-%06d".formatted(n);
        Timestamp now = Timestamp.from(clock.instant());
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into return_order (id, tenant_id, site_id, rma_no, return_type, revision, source_system,
                            customer_name, status, created_at, updated_at)
                        values (:id, :t, :site, :rma, 'BLIND', 0, 'ASTRAWMS', :name, 'IN_PROGRESS', :now, :now)""")
                .param("id", id).param("t", TenantContext.tenantId()).param("site", siteId).param("rma", rma)
                .param("name", customerName).param("now", now).update();
        work.rmaReceivable(siteId, id);
        return detail(siteId, rma);
    }

    // ------------------------------------------------------------------ receive and grade (RET-001/002, RET-EX-02/03)

    @Transactional
    public Map<String, Object> receive(String siteId, String rmaNo, String idempotencyKey, ReceiveUnit r) {
        Header h = header(siteId, rmaNo, true).orElseThrow(() -> unknown(rmaNo));
        Optional<Map<String, Object>> replay = jdbc.sql("select * from return_unit where return_id = :r and idempotency_key = :k")
                .param("r", h.id()).param("k", idempotencyKey).query().listOfRows().stream().findFirst();
        if (replay.isPresent()) {
            return unitView(replay.get());
        }
        if (!List.of("EXPECTED", "IN_PROGRESS").contains(h.status())) {
            throw ApiException.conflict("RET_NOT_OPEN", "Return " + rmaNo + " is " + h.status());
        }
        if (r.itemNo() == null || r.itemNo().isBlank() || r.qty() == null || r.qty().signum() <= 0 || r.uom() == null
                || r.locationId() == null || r.locationId().isBlank()) {
            throw ApiException.badRequest("RET_UNIT_INVALID", "itemNo, qty > 0, uom and locationId are required");
        }
        String grade = r.conditionGrade() == null ? "" : r.conditionGrade().trim().toUpperCase();
        if (!List.of("A", "B", "C", "D", "E").contains(grade)) {
            throw ApiException.badRequest("RET_GRADE_INVALID", "conditionGrade must be A, B, C, D or E");
        }
        List<Line> lines = lines(h.id());
        Line line = lines.stream().filter(l -> l.ref().equals(r.erpLineRef())).findFirst()
                .or(() -> lines.stream().filter(l -> l.item().equals(r.itemNo().trim())).findFirst())
                .orElse(null);
        boolean wrongItem = !lines.isEmpty() && (line == null || !line.item().equals(r.itemNo().trim()));   // RET-EX-02
        String owner = line != null ? line.owner() : r.ownerId();
        if (owner == null || owner.isBlank()) {
            throw ApiException.badRequest("RET_OWNER_REQUIRED", "ownerId is required for items not on the RMA");
        }
        AccessScope.current().requireOwner(owner);
        boolean overRma = false;
        if (line != null && !wrongItem) {
            BigDecimal received = jdbc.sql("""
                            select coalesce(sum(qty), 0) from return_unit where return_id = :r and erp_line_ref = :ref
                              and not wrong_item""")
                    .param("r", h.id()).param("ref", line.ref()).query(BigDecimal.class).single();
            if (received.add(r.qty()).compareTo(line.expected()) > 0) {                                       // RET-002
                if (!r.override() || !hasRole("SUPERVISOR")) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "RET_QTY_OVER_RMA",
                            "Receiving " + r.qty().stripTrailingZeros().toPlainString() + " would exceed the RMA quantity of "
                                    + line.expected().stripTrailingZeros().toPlainString() + "; a supervisor override is required",
                            Map.of("received", received, "expected", line.expected()));
                }
                overRma = true;
            }
        }
        List<String> serials = r.serials() == null ? List.of() : r.serials().stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        boolean serialFlag = line != null && !line.serials().isEmpty()                                       // RET-001
                && serials.stream().anyMatch(s -> !line.serials().contains(s));
        String disposition = r.disposition() == null || r.disposition().isBlank()
                ? suggest(h.type(), grade, serialFlag) : r.disposition().trim().toUpperCase();
        if (!STOCK_STATUS.containsKey(disposition)) {
            throw ApiException.badRequest("RET_DISPOSITION_INVALID", "Unknown disposition " + disposition);
        }
        if (("RECALL".equals(h.type()) || serialFlag) && !"QUARANTINE".equals(disposition)) {               // RET-EX-03
            disposition = "QUARANTINE";
        }
        String status = STOCK_STATUS.get(disposition);
        // Every unit is put on an LPN, so it gets the same putaway task as a vendor receipt (ADR-0019): restocked
        // units go to storage, the others to QC. Units without a scanned LPN get one per unit.
        String lpn = blankToNull(r.lpnId());
        if (lpn == null) {
            int n = jdbc.sql("select count(*) from return_unit where return_id = :r").param("r", h.id())
                    .query(Integer.class).single();
            lpn = "R" + rmaNo + "-" + (n + 1);
        }
        InventoryClient.ReceiveResult op = inventory.receive(siteId, "RET-" + idempotencyKey,
                new InventoryClient.ReceiveCommand(owner, r.itemNo().trim(), blankToNull(r.lotNo()), null, r.qty(), r.uom(),
                        lpn, r.locationId().trim(), status, "RMA " + rmaNo, serials));
        UUID unitId = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql("""
                        insert into return_unit (id, tenant_id, return_id, idempotency_key, erp_line_ref, owner_id, item_no, qty,
                            uom, lot_no, serials, condition_grade, return_reason_actual, disposition, stock_status, wrong_item,
                            serial_flag, over_rma, location_id, lpn_id, inventory_operation_id, received_by, received_at)
                        values (:id, :t, :r, :k, :ref, :owner, :item, :qty, :uom, :lot, cast(:serials as text[]), :grade, :reason,
                                :disp, :status, :wrong, :sflag, :over, :loc, :lpn, :op, :user, :now)""")
                .param("id", unitId).param("t", TenantContext.tenantId()).param("r", h.id()).param("k", idempotencyKey)
                .param("ref", line == null || wrongItem ? null : line.ref()).param("owner", owner).param("item", r.itemNo().trim())
                .param("qty", r.qty()).param("uom", r.uom()).param("lot", blankToNull(r.lotNo())).param("serials", pgArray(serials))
                .param("grade", grade).param("reason", blankToNull(r.returnReasonActual())).param("disp", disposition)
                .param("status", status).param("wrong", wrongItem).param("sflag", serialFlag).param("over", overRma)
                .param("loc", r.locationId().trim()).param("lpn", lpn).param("op", op.operationId())
                .param("user", TenantContext.require().userId()).param("now", now).update();
        jdbc.sql("update return_order set status = 'IN_PROGRESS', updated_at = :now where id = :id and status = 'EXPECTED'")
                .param("now", now).param("id", h.id()).update();
        return unitView(jdbc.sql("select * from return_unit where id = :id").param("id", unitId).query().singleRow());
    }

    // ------------------------------------------------------------------ IF-RET-002

    @Transactional
    public Map<String, Object> close(String siteId, String rmaNo) {
        Header h = header(siteId, rmaNo, true).orElseThrow(() -> unknown(rmaNo));
        if (!"IN_PROGRESS".equals(h.status())) {
            throw ApiException.conflict("RET_NOT_CLOSABLE", "Return " + rmaNo + " is " + h.status() + "; receive units first");
        }
        String receiptTxn = WmsTxnId.next(clock);
        String dispositionTxn = WmsTxnId.next(clock);
        Instant now = clock.instant();
        jdbc.sql("""
                        update return_order set status = 'CLOSED', receipt_txn_id = :r, disposition_txn_id = :d, closed_at = :now,
                            updated_at = :now where id = :id""")
                .param("r", receiptTxn).param("d", dispositionTxn).param("now", Timestamp.from(now)).param("id", h.id()).update();
        publish(h, receiptTxn, dispositionTxn, now);
        work.ended(siteId, com.astrawms.common.contracts.ReceivingContracts.KIND_RMA, rmaNo, "CLOSED");
        return detail(siteId, rmaNo);
    }

    @Transactional
    public Map<String, Object> repost(String siteId, String rmaNo) {
        Header h = header(siteId, rmaNo, true).orElseThrow(() -> unknown(rmaNo));
        if (!"POSTING_FAILED".equals(h.status())) {
            throw ApiException.conflict("RET_NOT_REPOSTABLE", "Only POSTING_FAILED returns can be reposted; status is " + h.status());
        }
        Instant closedAt = jdbc.sql("select closed_at from return_order where id = :id").param("id", h.id())
                .query(Timestamp.class).single().toInstant();
        jdbc.sql("update return_order set status = 'CLOSED', erp_error_class = null, erp_error_text = null, updated_at = :now where id = :id")
                .param("now", Timestamp.from(clock.instant())).param("id", h.id()).update();
        publish(h, h.receiptTxnId(), h.dispositionTxnId(), closedAt);
        return detail(siteId, rmaNo);
    }

    private void publish(Header h, String receiptTxn, String dispositionTxn, Instant at) {
        record Key(String ref, String item, String uom, String lot, String grade, String reason, String disposition, boolean wrong) {
        }
        Map<Key, BigDecimal> qty = new LinkedHashMap<>();
        Map<Key, List<String>> serials = new HashMap<>();
        jdbc.sql("""
                        select erp_line_ref, item_no, uom, lot_no, condition_grade, return_reason_actual, disposition, wrong_item,
                               qty, serials from return_unit where return_id = :r order by received_at""")
                .param("r", h.id())
                .query((rs, n) -> {
                    Key k = new Key(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getString(6), rs.getString(7), rs.getBoolean(8));
                    qty.merge(k, rs.getBigDecimal(9), BigDecimal::add);
                    Array a = rs.getArray(10);
                    serials.computeIfAbsent(k, x -> new ArrayList<>()).addAll(a == null ? List.of() : Arrays.asList((String[]) a.getArray()));
                    return k;
                })
                .list();
        List<ReturnConfirmation.Line> lines = qty.entrySet().stream().map(e -> {
            Key k = e.getKey();
            List<String> sn = serials.getOrDefault(k, List.of());
            return new ReturnConfirmation.Line(k.ref(), k.item(), e.getValue().stripTrailingZeros(), k.uom(), k.lot(),
                    sn.isEmpty() ? null : sn, k.grade(), k.reason(), k.disposition(), k.wrong());
        }).toList();
        outbox.append(new OutboxWriter.Message(ReturnsContracts.TOPIC_RETURN_CONFIRMATIONS, ReturnConfirmation.TYPE,
                ReturnConfirmation.VERSION, "ERP", h.siteId(), null, h.siteId() + ":" + h.rmaNo(),
                new ReturnConfirmation(receiptTxn, dispositionTxn, "BLIND".equals(h.type()) ? null : h.rmaNo(), at, lines)));
    }

    /** Terminal ERP result (INT-011), keyed by the receipt transaction ID. */
    @Transactional
    public void onPostingResult(ErpPostingResult result) {
        if (result.success()) {
            jdbc.sql("""
                            update return_order set status = 'CONFIRMED', erp_document = :doc, erp_error_class = null,
                                erp_error_text = null, updated_at = :now
                            where receipt_txn_id = :txn and status in ('CLOSED', 'POSTING_FAILED')""")
                    .param("doc", result.erpDocument()).param("txn", result.wmsTxnId())
                    .param("now", Timestamp.from(clock.instant())).update();
        } else {
            jdbc.sql("""
                            update return_order set status = 'POSTING_FAILED', erp_error_class = :cls, erp_error_text = :text,
                                updated_at = :now where receipt_txn_id = :txn and status = 'CLOSED'""")
                    .param("cls", result.errorClass())
                    .param("text", Objects.toString(result.erpMessageId(), "") + " " + Objects.toString(result.erpMessageText(), ""))
                    .param("txn", result.wmsTxnId()).param("now", Timestamp.from(clock.instant())).update();
        }
    }

    // ------------------------------------------------------------------ queries (owner scope: all lines/units in scope)

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String siteId, String status) {
        return list(siteId, status, null);
    }

    /** {@code q} matches the RMA number, customer or an item on the RMA (case-insensitive). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String siteId, String status, String q) {
        AccessScope scope = AccessScope.current();
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toUpperCase() + "%";
        return jdbc.sql("""
                        select r.rma_no, r.return_type, r.status, r.customer_name, r.expected_arrival_utc, r.erp_document,
                               r.erp_error_text, r.created_at, r.updated_at,
                               (select count(*) from return_line l where l.return_id = r.id) as lines,
                               (select coalesce(sum(u.qty), 0) from return_unit u where u.return_id = r.id) as units
                        from return_order r
                        where r.site_id = :site and (cast(:status as text) is null or r.status = :status)
                          and (:all or not exists (select 1 from return_line l where l.return_id = r.id and l.owner_id not in (:owners)))
                          and (cast(:q as text) is null or upper(r.rma_no) like :q or upper(coalesce(r.customer_name, '')) like :q
                               or exists (select 1 from return_line l where l.return_id = r.id and upper(l.item_no) like :q)
                               or exists (select 1 from return_unit u where u.return_id = r.id and upper(coalesce(u.lpn_id, '')) like :q))
                        order by r.created_at desc limit 500""")
                .param("site", siteId).param("status", status).param("all", scope.ownersAll()).param("owners", scope.ownerList())
                .param("q", like)
                .query().listOfRows();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String siteId, String rmaNo) {
        Map<String, Object> h = jdbc.sql("""
                        select id, rma_no, return_type, status, campaign_id, customer_id, customer_name, expected_arrival_utc,
                               receipt_txn_id, disposition_txn_id, erp_document, erp_error_class, erp_error_text, created_at, closed_at
                        from return_order where site_id = :site and rma_no = :rma""")
                .param("site", siteId).param("rma", rmaNo).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> unknown(rmaNo));
        List<Map<String, Object>> lines = jdbc.sql("""
                        select erp_line_ref, owner_id, item_no, qty_expected, uom, return_reason, expected_serials::text as expected_serials,
                               inspection_required,
                               (select coalesce(sum(u.qty), 0) from return_unit u where u.return_id = l.return_id
                                  and u.erp_line_ref = l.erp_line_ref) as qty_received
                        from return_line l where l.return_id = :id order by erp_line_ref""")
                .param("id", h.get("id")).query().listOfRows();
        AccessScope scope = AccessScope.current();
        if (lines.stream().anyMatch(l -> !scope.allowsOwner(String.valueOf(l.get("owner_id"))))) {
            throw unknown(rmaNo);
        }
        Map<String, Object> out = new HashMap<>(h);
        out.put("lines", lines);
        out.put("units", jdbc.sql("select * from return_unit where return_id = :id order by received_at")
                .param("id", h.get("id")).query().listOfRows().stream().map(ReturnsService::unitView).toList());
        return out;
    }

    private static Map<String, Object> unitView(Map<String, Object> row) {
        Map<String, Object> out = new HashMap<>();
        for (String k : List.of("id", "erp_line_ref", "owner_id", "item_no", "qty", "uom", "lot_no", "condition_grade",
                "return_reason_actual", "disposition", "stock_status", "wrong_item", "serial_flag", "over_rma", "location_id",
                "lpn_id", "received_by", "received_at")) {
            out.put(k, row.get(k));
        }
        Object serials = row.get("serials");
        try {
            out.put("serials", serials instanceof Array a ? Arrays.asList((Object[]) a.getArray()) : serials);
        } catch (java.sql.SQLException e) {
            out.put("serials", List.of());
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private Optional<Header> header(String siteId, String rmaNo, boolean lock) {
        return jdbc.sql("""
                        select id, site_id, rma_no, return_type, status, revision, receipt_txn_id, disposition_txn_id
                        from return_order where site_id = :site and rma_no = :rma""" + (lock ? " for update" : ""))
                .param("site", siteId).param("rma", rmaNo)
                .query((rs, n) -> new Header(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getLong(6), rs.getString(7), rs.getString(8)))
                .optional();
    }

    private List<Line> lines(UUID returnId) {
        return jdbc.sql("select erp_line_ref, owner_id, item_no, qty_expected, uom, expected_serials from return_line where return_id = :r order by erp_line_ref")
                .param("r", returnId)
                .query((rs, n) -> {
                    Array a = rs.getArray(6);
                    return new Line(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getString(5),
                            a == null ? List.of() : Arrays.asList((String[]) a.getArray()));
                })
                .list();
    }

    private static String pgArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        return "{" + String.join(",", values.stream().map(v -> "\"" + v.replace("\\", "").replace("\"", "") + "\"").toList()) + "}";
    }

    private static boolean hasRole(String role) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> ("ROLE_" + role).equals(a.getAuthority()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ApiException unknown(String rmaNo) {
        return ApiException.notFound("RET_UNKNOWN", "No return " + rmaNo);
    }
}
