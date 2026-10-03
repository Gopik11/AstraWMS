package com.astrawms.inventory.service;

import com.astrawms.common.barcode.Gs1;
import com.astrawms.common.contracts.IntegrationContracts.GoodsMovement;
import com.astrawms.common.security.AccessScope;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import com.astrawms.inventory.api.InventoryDtos.OperationResult;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Controlled material issue (ADR-0022): stock leaves the store for consumption by a cost centre, WBS element or
 * internal order, only on an approved request.
 * <pre>
 * REQUESTED ──approve──▶ APPROVED ──issue scans──▶ PARTIALLY_ISSUED ──▶ ISSUED
 *     └──reject──▶ REJECTED        └──cancel──▶ CANCELLED      └──close (rest not issued)──▶ CLOSED
 * </pre>
 * <ul>
 *   <li>A request names an active cost object, the recipient (person or department) and the items. If the cost object
 *       lists allowed requesters, only they may request issues to it. A request is approved by a supervisor or
 *       inventory manager other than the requester.</li>
 *   <li>The store keeper issues each line by scanning the bin and the item (item number, GTIN or GS1 label; a GS1
 *       lot fills the lot) and the quantity. Each scan is an inventory operation and an ERP goods issue to the cost
 *       object (SAP 201/221/261).</li>
 *   <li>Unused material comes back as a return against the line (SAP 202/222/262), up to what was issued.</li>
 * </ul>
 */
@Service
public class MaterialIssues {

    static final List<String> OBJECT_TYPES = List.of("COST_CENTER", "WBS", "ORDER");

    private final JdbcClient jdbc;
    private final InventoryCommandService commands;
    private final Clock clock;

    public MaterialIssues(JdbcClient jdbc, InventoryCommandService commands, Clock clock) {
        this.jdbc = jdbc;
        this.commands = commands;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------ cost objects

    public record CostObjectRequest(String description, String department, List<String> allowedRequesters,
                                    Boolean active) {
    }

    public List<Map<String, Object>> costObjects(String type, String q) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toUpperCase() + "%";
        return jdbc.sql("""
                        select object_type, code, description, department,
                               array_to_string(allowed_requesters, ',') as allowed_requesters, active, updated_by, updated_at
                        from cost_object
                        where (cast(:type as text) is null or object_type = :type)
                          and (cast(:q as text) is null or upper(code) like :q or upper(description) like :q
                               or upper(coalesce(department, '')) like :q)
                        order by object_type, code limit 500""")
                .param("type", upper(type)).param("q", like).query().listOfRows();
    }

    @Transactional
    public Map<String, Object> putCostObject(String type, String code, CostObjectRequest r) {
        String t = upper(type);
        if (t == null || !OBJECT_TYPES.contains(t) || r.description() == null || r.description().isBlank()) {
            throw ApiException.badRequest("INV_COST_OBJECT_INVALID", "type " + OBJECT_TYPES + " and a description are required");
        }
        jdbc.sql("""
                        insert into cost_object (tenant_id, object_type, code, description, department, allowed_requesters,
                                                 active, updated_by, updated_at)
                        values (:t, :type, :code, :desc, :dept, string_to_array(:req, ','), :active, :user, :now)
                        on conflict (tenant_id, object_type, code) do update set description = excluded.description,
                            department = excluded.department, allowed_requesters = excluded.allowed_requesters,
                            active = excluded.active, updated_by = excluded.updated_by, updated_at = excluded.updated_at""")
                .param("t", TenantContext.tenantId()).param("type", t).param("code", code.trim().toUpperCase())
                .param("desc", r.description().trim()).param("dept", r.department() == null ? null : r.department().trim())
                .param("req", r.allowedRequesters() == null ? "" : String.join(",", r.allowedRequesters().stream()
                        .map(String::trim).filter(s -> !s.isEmpty()).toList()))
                .param("active", r.active() == null || r.active())
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant())).update();
        return costObjects(t, code.trim().toUpperCase()).getFirst();
    }

    // ------------------------------------------------------------------------------------------ requests

    public record LineRequest(String itemNo, BigDecimal qty, String uom, String lotNo) {
    }

    public record CreateRequest(String ownerId, String objectType, String objectCode, String recipient, String note,
                                List<LineRequest> lines) {
    }

    @Transactional
    public Map<String, Object> create(String siteId, CreateRequest r) {
        String owner = upper(r.ownerId());
        String type = upper(r.objectType());
        String code = upper(r.objectCode());
        if (owner == null || type == null || code == null || r.recipient() == null || r.recipient().isBlank()
                || r.lines() == null || r.lines().isEmpty()) {
            throw ApiException.badRequest("INV_ISSUE_INVALID", "ownerId, objectType, objectCode, recipient and lines are required");
        }
        AccessScope.current().requireOwner(owner);
        String user = TenantContext.require().userId();
        record Obj(boolean active, List<String> requesters) {
        }
        Obj obj = jdbc.sql("""
                        select active, array_to_string(allowed_requesters, ',') from cost_object
                        where object_type = :type and code = :code""")
                .param("type", type).param("code", code)
                .query((rs, n) -> new Obj(rs.getBoolean(1), rs.getString(2) == null || rs.getString(2).isEmpty()
                        ? List.of() : List.of(rs.getString(2).split(","))))
                .optional().orElseThrow(() -> ApiException.unprocessable("INV_COST_OBJECT_UNKNOWN",
                        type + " " + code + " is not a known cost object"));
        if (!obj.active()) {
            throw ApiException.unprocessable("INV_COST_OBJECT_INACTIVE", type + " " + code + " is closed for postings");
        }
        if (!obj.requesters().isEmpty() && !obj.requesters().contains(user)) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "INV_REQUESTER_NOT_ALLOWED", user + " may not request issues to " + type + " " + code);
        }
        for (LineRequest l : r.lines()) {
            if (l.itemNo() == null || l.qty() == null || l.qty().signum() <= 0 || l.uom() == null) {
                throw ApiException.badRequest("INV_ISSUE_INVALID", "Each line needs itemNo, a positive qty and uom");
            }
            boolean known = jdbc.sql("select exists (select 1 from ref_item where owner_id = :o and item_no = :i and site_id = :s)")
                    .param("o", owner).param("i", l.itemNo().trim()).param("s", siteId).query(Boolean.class).single();
            if (!known) {
                throw ApiException.unprocessable("INV_ITEM_UNKNOWN", "Item " + l.itemNo() + " is not known at " + siteId);
            }
        }
        UUID id = UUID.randomUUID();
        long seq = jdbc.sql("select count(*) + 1 from material_issue where site_id = :site").param("site", siteId)
                .query(Long.class).single();
        String no = "MI%06d".formatted(seq);
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql("""
                        insert into material_issue (id, tenant_id, site_id, issue_no, owner_id, object_type, object_code,
                            recipient, note, status, requested_by, requested_at, updated_at)
                        values (:id, :t, :site, :no, :owner, :type, :code, :recipient, :note, 'REQUESTED', :user, :now, :now)""")
                .param("id", id).param("t", TenantContext.tenantId()).param("site", siteId).param("no", no)
                .param("owner", owner).param("type", type).param("code", code).param("recipient", r.recipient().trim())
                .param("note", r.note()).param("user", user).param("now", now).update();
        int lineNo = 0;
        for (LineRequest l : r.lines()) {
            lineNo += 10;
            jdbc.sql("""
                            insert into material_issue_line (issue_id, tenant_id, line_no, item_no, lot_no, qty_requested, uom)
                            values (:id, :t, :line, :item, :lot, :qty, :uom)""")
                    .param("id", id).param("t", TenantContext.tenantId()).param("line", lineNo)
                    .param("item", l.itemNo().trim()).param("lot", l.lotNo() == null || l.lotNo().isBlank() ? null : l.lotNo().trim())
                    .param("qty", l.qty()).param("uom", l.uom().trim().toUpperCase()).update();
        }
        return detail(siteId, no);
    }

    /** Approve or reject; the approver must not be the requester (segregation of duties). */
    @Transactional
    public Map<String, Object> decide(String siteId, String issueNo, boolean approve, String note) {
        Head h = lock(siteId, issueNo);
        requireStatus(h, "REQUESTED");
        String user = TenantContext.require().userId();
        if (user.equals(h.requestedBy())) {
            throw ApiException.unprocessable("INV_SELF_APPROVAL", "The requester cannot approve their own issue");
        }
        if (!approve && (note == null || note.isBlank())) {
            throw ApiException.badRequest("INV_REJECT_REASON_REQUIRED", "Say why the request is rejected");
        }
        jdbc.sql("""
                        update material_issue set status = :s, decided_by = :user, decided_at = :now, decision_note = :note,
                            updated_at = :now where id = :id""")
                .param("s", approve ? "APPROVED" : "REJECTED").param("user", user).param("note", note)
                .param("now", Timestamp.from(clock.instant())).param("id", h.id()).update();
        return detail(siteId, issueNo);
    }

    /** Cancel before anything was issued, or close a partly issued request (the rest will not be issued). */
    @Transactional
    public Map<String, Object> end(String siteId, String issueNo) {
        Head h = lock(siteId, issueNo);
        String target = switch (h.status()) {
            case "REQUESTED", "APPROVED" -> "CANCELLED";
            case "PARTIALLY_ISSUED" -> "CLOSED";
            default -> throw ApiException.unprocessable("INV_ISSUE_STATUS", "Issue " + issueNo + " is " + h.status());
        };
        jdbc.sql("update material_issue set status = :s, updated_at = :now where id = :id")
                .param("s", target).param("now", Timestamp.from(clock.instant())).param("id", h.id()).update();
        return detail(siteId, issueNo);
    }

    // ------------------------------------------------------------------------------------------ scans

    /** A store keeper's scan: bin (and LPN), item (number, GTIN or GS1 label), lot, quantity in the line's unit. */
    public record Scan(String locationId, String lpnId, String itemScan, String lotNo, BigDecimal qty,
                       List<String> serials) {
    }

    @Transactional
    public Map<String, Object> issue(String siteId, String issueNo, int lineNo, String idempotencyKey, Scan s) {
        Head h = lock(siteId, issueNo);
        if (scanDone("ISSUE", idempotencyKey)) {
            return detail(siteId, issueNo);                  // a retried scan: answered, not redone
        }
        if (!List.of("APPROVED", "PARTIALLY_ISSUED").contains(h.status())) {
            throw ApiException.unprocessable("INV_ISSUE_STATUS", "Issue " + issueNo + " is " + h.status()
                    + (h.status().equals("REQUESTED") ? "; it must be approved first" : ""));
        }
        Line l = line(h, lineNo);
        String lot = scannedLot(h, l, s);
        BigDecimal open = l.requested().subtract(l.issued());
        if (s.qty() == null || s.qty().signum() <= 0 || s.qty().compareTo(open) > 0) {
            throw ApiException.unprocessable("INV_ISSUE_QTY", "Issue between 0 and " + open.stripTrailingZeros().toPlainString()
                    + " " + l.uom() + " on line " + lineNo);
        }
        OperationResult op = commands.materialIssue(siteId, "MI-" + idempotencyKey, new InventoryCommandService.MaterialMove(
                issueNo, h.owner(), l.item(), lot, s.qty(), l.uom(), requireLocation(s), blankToNull(s.lpnId()),
                s.serials(), account(h)));
        if (!op.replayed()) {
            jdbc.sql("update material_issue_line set qty_issued = qty_issued + :q where issue_id = :id and line_no = :line")
                    .param("q", s.qty()).param("id", h.id()).param("line", lineNo).update();
            move(h, lineNo, "ISSUE", idempotencyKey, s, lot, op);
            boolean complete = jdbc.sql("""
                            select not exists (select 1 from material_issue_line where issue_id = :id
                                               and qty_issued < qty_requested)""")
                    .param("id", h.id()).query(Boolean.class).single();
            jdbc.sql("update material_issue set status = :s, updated_at = :now where id = :id")
                    .param("s", complete ? "ISSUED" : "PARTIALLY_ISSUED").param("now", Timestamp.from(clock.instant()))
                    .param("id", h.id()).update();
        }
        return detail(siteId, issueNo);
    }

    @Transactional
    public Map<String, Object> returnLine(String siteId, String issueNo, int lineNo, String idempotencyKey, Scan s) {
        Head h = lock(siteId, issueNo);
        if (scanDone("RETURN", idempotencyKey)) {
            return detail(siteId, issueNo);                  // a retried scan: answered, not redone
        }
        if (!List.of("PARTIALLY_ISSUED", "ISSUED", "CLOSED").contains(h.status())) {
            throw ApiException.unprocessable("INV_ISSUE_STATUS", "Nothing was issued on " + issueNo);
        }
        Line l = line(h, lineNo);
        String lot = scannedLot(h, l, s);
        BigDecimal returnable = l.issued().subtract(l.returned());
        if (s.qty() == null || s.qty().signum() <= 0 || s.qty().compareTo(returnable) > 0) {
            throw ApiException.unprocessable("INV_RETURN_QTY", "Return between 0 and "
                    + returnable.stripTrailingZeros().toPlainString() + " " + l.uom() + " on line " + lineNo);
        }
        OperationResult op = commands.materialReturn(siteId, "MR-" + idempotencyKey, new InventoryCommandService.MaterialMove(
                issueNo, h.owner(), l.item(), lot, s.qty(), l.uom(), requireLocation(s), blankToNull(s.lpnId()),
                s.serials(), account(h)));
        if (!op.replayed()) {
            jdbc.sql("update material_issue_line set qty_returned = qty_returned + :q where issue_id = :id and line_no = :line")
                    .param("q", s.qty()).param("id", h.id()).param("line", lineNo).update();
            move(h, lineNo, "RETURN", idempotencyKey, s, lot, op);
            jdbc.sql("update material_issue set updated_at = :now where id = :id")
                    .param("now", Timestamp.from(clock.instant())).param("id", h.id()).update();
        }
        return detail(siteId, issueNo);
    }

    /**
     * The scanned item must be the line's item; a GS1 lot (AI 10) fills the lot when none was keyed, and a line asking
     * for a lot gets that lot only.
     */
    private String scannedLot(Head h, Line l, Scan s) {
        if (s.itemScan() == null || s.itemScan().isBlank()) {
            throw ApiException.unprocessable("INV_ITEM_SCAN_REQUIRED", "Scan the item or its label");
        }
        Optional<Gs1.Data> gs1 = Gs1.parse(s.itemScan());
        String code = gs1.map(Gs1.Data::itemGtin).orElse(s.itemScan().trim());
        boolean matches = code.equalsIgnoreCase(l.item()) || jdbc.sql("""
                        select exists (select 1 from ref_item_uom where owner_id = :o and item_no = :i and gtin = :g)""")
                .param("o", h.owner()).param("i", l.item()).param("g", code.replaceFirst("^0+(?=.)", ""))
                .query(Boolean.class).single();
        if (!matches) {
            throw ApiException.unprocessable("INV_WRONG_ITEM", "Scanned " + s.itemScan().trim() + " is not item " + l.item());
        }
        String lot = blankToNull(s.lotNo()) != null ? s.lotNo().trim() : gs1.map(Gs1.Data::lot).orElse(null);
        if (l.lot() != null && lot != null && !l.lot().equals(lot)) {
            throw ApiException.unprocessable("INV_WRONG_LOT", "Line " + l.lineNo() + " is for lot " + l.lot() + ", not " + lot);
        }
        return lot != null ? lot : l.lot();
    }

    private boolean scanDone(String kind, String key) {
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_INVALID", "Header Idempotency-Key is required");
        }
        return jdbc.sql("select exists (select 1 from material_issue_move where kind = :k and scan_key = :key)")
                .param("k", kind).param("key", key).query(Boolean.class).single();
    }

    private void move(Head h, int lineNo, String kind, String key, Scan s, String lot, OperationResult op) {
        jdbc.sql("""
                        insert into material_issue_move (tenant_id, issue_id, line_no, kind, qty, lot_no, location_id, lpn_id,
                                                         operation_id, scan_key, wms_txn_id, moved_by, moved_at)
                        values (:t, :id, :line, :kind, :qty, :lot, :loc, :lpn, :op, :key, :txn, :user, :now)""")
                .param("key", key)
                .param("t", TenantContext.tenantId()).param("id", h.id()).param("line", lineNo).param("kind", kind)
                .param("qty", s.qty()).param("lot", lot).param("loc", s.locationId().trim().toUpperCase())
                .param("lpn", blankToNull(s.lpnId())).param("op", op.operationId())
                .param("txn", op.erpMovements().isEmpty() ? op.wmsTxnId() : op.erpMovements().getFirst().wmsTxnId())
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant())).update();
    }

    // ------------------------------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String siteId, String status, String q) {
        status = status == null || status.isBlank() ? null : status.trim().toUpperCase();   // "All statuses"
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toUpperCase() + "%";
        AccessScope scope = AccessScope.current();
        return jdbc.sql("""
                        select i.issue_no, i.owner_id, i.object_type, i.object_code, i.recipient, i.status, i.requested_by,
                               i.requested_at, i.decided_by, i.updated_at,
                               (select count(*) from material_issue_line l where l.issue_id = i.id) as lines
                        from material_issue i
                        where i.site_id = :site
                          and (cast(:status as text) is null or i.status = any(string_to_array(:status, ',')))
                          and (:ownersAll or i.owner_id in (:owners))
                          and (cast(:q as text) is null or upper(i.issue_no) like :q or upper(i.object_code) like :q
                               or upper(i.recipient) like :q or upper(i.requested_by) like :q
                               or exists (select 1 from material_issue_line l where l.issue_id = i.id and upper(l.item_no) like :q))
                        order by i.requested_at desc limit 500""")
                .param("site", siteId).param("status", status).param("q", like)
                .param("ownersAll", scope.ownersAll()).param("owners", scope.ownerList()).query().listOfRows();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String siteId, String issueNo) {
        Map<String, Object> head = jdbc.sql("""
                        select i.id, i.issue_no, i.owner_id, i.object_type, i.object_code, c.description as object_description,
                               c.department, i.recipient, i.note, i.status, i.requested_by, i.requested_at, i.decided_by,
                               i.decided_at, i.decision_note, i.updated_at
                        from material_issue i
                        left join cost_object c on c.object_type = i.object_type and c.code = i.object_code
                        where i.site_id = :site and i.issue_no = :no""")
                .param("site", siteId).param("no", issueNo).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> unknown(issueNo));
        AccessScope.current().requireOwner((String) head.get("owner_id"));
        Map<String, Object> out = new HashMap<>(head);
        out.put("lines", jdbc.sql("""
                        select line_no, item_no, lot_no, qty_requested, uom, qty_issued, qty_returned
                        from material_issue_line where issue_id = :id order by line_no""")
                .param("id", head.get("id")).query().listOfRows().stream().map(MaterialIssues::strip).toList());
        out.put("moves", jdbc.sql("""
                        select line_no, kind, qty, lot_no, location_id, lpn_id, wms_txn_id, moved_by, moved_at
                        from material_issue_move where issue_id = :id order by id""")
                .param("id", head.get("id")).query().listOfRows().stream().map(MaterialIssues::strip).toList());
        out.remove("id");
        return out;
    }

    // ------------------------------------------------------------------------------------------ helpers

    private record Head(UUID id, String status, String owner, String objectType, String objectCode, String recipient,
                        String requestedBy, String issueNo) {
    }

    private record Line(int lineNo, String item, String lot, BigDecimal requested, String uom, BigDecimal issued,
                        BigDecimal returned) {
    }

    private Head lock(String siteId, String issueNo) {
        Head h = jdbc.sql("""
                        select id, status, owner_id, object_type, object_code, recipient, requested_by, issue_no
                        from material_issue where site_id = :site and issue_no = :no for update""")
                .param("site", siteId).param("no", issueNo)
                .query((rs, n) -> new Head(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)))
                .optional().orElseThrow(() -> unknown(issueNo));
        AccessScope.current().requireOwner(h.owner());
        return h;
    }

    private Line line(Head h, int lineNo) {
        return jdbc.sql("""
                        select line_no, item_no, lot_no, qty_requested, uom, qty_issued, qty_returned
                        from material_issue_line where issue_id = :id and line_no = :line for update""")
                .param("id", h.id()).param("line", lineNo)
                .query((rs, n) -> new Line(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                        rs.getString(5), rs.getBigDecimal(6), rs.getBigDecimal(7)))
                .optional().orElseThrow(() -> ApiException.notFound("INV_ISSUE_LINE_UNKNOWN",
                        "Issue " + h.issueNo() + " has no line " + lineNo));
    }

    private static GoodsMovement.AccountAssignment account(Head h) {
        return new GoodsMovement.AccountAssignment(h.objectType(), h.objectCode(), h.recipient(), h.issueNo());
    }

    private static String requireLocation(Scan s) {
        if (s.locationId() == null || s.locationId().isBlank()) {
            throw ApiException.badRequest("INV_LOCATION_REQUIRED", "Scan the bin");
        }
        return s.locationId().trim().toUpperCase();
    }

    private static void requireStatus(Head h, String status) {
        if (!status.equals(h.status())) {
            throw ApiException.unprocessable("INV_ISSUE_STATUS", "Issue " + h.issueNo() + " is " + h.status());
        }
    }

    private static ApiException unknown(String issueNo) {
        return ApiException.notFound("INV_ISSUE_UNKNOWN", "No material issue " + issueNo);
    }

    private static Map<String, Object> strip(Map<String, Object> row) {
        Map<String, Object> out = new HashMap<>(row);
        out.replaceAll((k, v) -> v instanceof BigDecimal b
                ? (b.stripTrailingZeros().scale() < 0 ? b.stripTrailingZeros().setScale(0) : b.stripTrailingZeros()) : v);
        return out;
    }

    private static String upper(String v) {
        return v == null || v.isBlank() ? null : v.trim().toUpperCase();
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
