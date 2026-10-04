package com.astrawms.task.service;

import com.astrawms.common.contracts.InventoryContracts.InventoryChanged;
import com.astrawms.common.contracts.OutboundContracts;
import com.astrawms.common.messaging.OutboxWriter;
import com.astrawms.common.security.AccessScope;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import com.astrawms.task.api.TaskDtos.Content;
import com.astrawms.task.api.TaskDtos.TaskView;
import com.astrawms.task.inventory.InventoryClient;
import com.astrawms.task.projection.Projections;
import com.astrawms.task.projection.Projections.Stock;
import com.astrawms.task.putaway.PutawayEngine;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Putaway tasks: created when an LPN arrives at an inbound staging location, directed by {@link PutawayEngine},
 * executed on RF (get next → scan LPN → scan location check digit → confirm), with re-planning on exceptions.
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private static final Set<String> OPEN = Set.of("RELEASED", "ASSIGNED", "EXCEPTION");
    private static final Set<String> REPLAN_REASONS = Set.of("LOCATION_BLOCKED", "LOCATION_OCCUPIED");

    private final JdbcClient jdbc;
    private final Projections projections;
    private final PutawayEngine engine;
    private final InventoryClient inventory;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final com.astrawms.task.inbound.InboundClient inbound;
    private final tools.jackson.databind.json.JsonMapper json;
    private final Labor labor;

    /**
     * Which task types each RF role works (ADR-0019); SUPERVISOR works all. A user with several roles gets the union.
     */
    static final Map<String, List<String>> TASK_TYPES_BY_ROLE = Map.of(
            "RECEIVER", List.of("RECEIVE", "PUTAWAY", "RETURN", "REPLEN", "MOVE"),
            "PICKER", List.of("PICK", "RETURN", "REPLEN", "COUNT", "MOVE"),
            "INV_ANALYST", List.of("COUNT", "REPLEN", "MOVE"),
            "SUPERVISOR", List.of("RECEIVE", "PUTAWAY", "PICK", "RETURN", "REPLEN", "COUNT", "MOVE"));

    public TaskService(JdbcClient jdbc, Projections projections, PutawayEngine engine, InventoryClient inventory,
                       OutboxWriter outbox, Clock clock, com.astrawms.task.inbound.InboundClient inbound,
                       tools.jackson.databind.json.JsonMapper json, Labor labor,
                       com.astrawms.task.labels.LabelClient labels) {
        this.labels = labels;
        this.labor = labor;
        this.inbound = inbound;
        this.json = json;
        this.jdbc = jdbc;
        this.projections = projections;
        this.engine = engine;
        this.inventory = inventory;
        this.outbox = outbox;
        this.clock = clock;
    }

    private final com.astrawms.task.labels.LabelClient labels;

    /**
     * A label printed by AstraWMS is active only after its verification scan (ADR-0025): a PRINTED label is refused
     * until verified, a VOID one always. Barcodes AstraWMS never printed (vendor SSCCs) pass.
     */
    private void requireActiveLabel(String siteId, String type, String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return;
        }
        String status = labels.status(siteId, type, barcode.trim()).orElse("VERIFIED");
        if ("VOID".equals(status)) {
            throw ApiException.unprocessable("TSK_LABEL_VOID", type.toLowerCase() + " label " + barcode
                    + " is void: take it off and use another one");
        }
        if ("PRINTED".equals(status)) {
            throw ApiException.unprocessable("TSK_LABEL_NOT_VERIFIED", type.toLowerCase() + " label " + barcode
                    + " is printed but not verified: scan it once in Verify label, then go on");
        }
    }

    // =====================================================================================================
    // RECEIVE tasks (ADR-0019)
    // =====================================================================================================

    /**
     * One RF receiving task per vendor delivery or RMA. A change of the document while the task is open refreshes
     * what is expected; a redelivered request is harmless.
     */
    @Transactional
    public void onReceiveRequested(String siteId, com.astrawms.common.contracts.ReceivingContracts.ReceiveRequested r) {
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        boolean created = jdbc.sql("""
                        insert into task (id, tenant_id, site_id, task_type, status, priority, owner_id, lpn_id,
                                          from_location, strategy, receive_kind, doc_no, order_ref, partner,
                                          expected_lines, created_at, updated_at)
                        values (:id, :t, :site, 'RECEIVE', 'RELEASED', :prio, :owner, '', '', :kind, :kind, :doc, :doc,
                                :partner, cast(:lines as jsonb), :now, :now)
                        on conflict (tenant_id, site_id, receive_kind, doc_no)
                            where task_type = 'RECEIVE' and status in ('RELEASED', 'ASSIGNED', 'EXCEPTION')
                        do update set expected_lines = excluded.expected_lines, partner = excluded.partner,
                                      updated_at = excluded.updated_at
                        returning (xmax = 0)""")
                .param("id", id).param("t", TenantContext.tenantId()).param("site", siteId).param("prio", r.priority())
                .param("owner", r.ownerId() == null ? "" : r.ownerId()).param("kind", r.kind()).param("doc", r.docNo())
                .param("partner", r.partner()).param("lines", json.writeValueAsString(r.lines()))
                .param("now", Timestamp.from(now))
                .query(Boolean.class).single();
        if (created) {
            event(id, "CREATED", "Receive " + r.kind() + " " + r.docNo() + " (" + r.lines().size() + " line(s))");
        }
    }

    /** The document was closed or cancelled elsewhere: its open receiving task is cancelled; a close sweeps the dock. */
    @Transactional
    public void onReceiveEnded(String siteId, com.astrawms.common.contracts.ReceivingContracts.ReceiveEnded e) {
        if ("CLOSED".equals(e.reason())) {
            sweepDock(siteId);
        }
        jdbc.sql("""
                        update task set status = 'CANCELLED', exception_reason = :reason, updated_at = :now
                        where site_id = :site and task_type = 'RECEIVE' and receive_kind = :kind and doc_no = :doc
                          and status in ('RELEASED', 'ASSIGNED', 'EXCEPTION')""")
                .param("reason", e.reason()).param("now", Timestamp.from(clock.instant())).param("site", siteId)
                .param("kind", e.kind()).param("doc", e.docNo()).update();
    }

    public record SweepResult(int putawaysCreated, int lpnsCreated, List<String> failed) {
    }

    /**
     * Dock sweep (ADR-0020): everything still at inbound staging (dock, door, receiving, returns) gets a putaway. LPNs
     * without an open putaway task get one; loose stock is first put on a generated LPN at the same location, and the
     * arrival of that LPN creates its task. Runs when a receipt or return is closed (all stock on the dock is then older
     * than the close) and on request.
     */
    @Transactional
    public SweepResult sweepDock(String siteId) {
        List<String> staging = jdbc.sql("select location_id, location_type, zone_type from ref_location where site_id = :site")
                .param("site", siteId)
                .query((rs, n) -> new Projections.Location(siteId, rs.getString(1), null, rs.getString(2), null, false,
                        true, true, "ACTIVE", null, null, rs.getString(3)))
                .list().stream().filter(PutawayEngine::inboundStaging).map(Projections.Location::locationId).toList();
        int tasks = 0;
        int lpns = 0;
        List<String> failed = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Stock s : projections.stockAtLocations(siteId, staging)) {
            if (!s.lpnId().isEmpty()) {
                if (seen.add(s.lpnId()) && findOpenByLpn(siteId, s.lpnId()).isEmpty()) {
                    createPutaway(siteId, s.ownerId(), s.lpnId(), s.locationId(), UUID.randomUUID());
                    tasks++;
                }
                continue;
            }
            String lpn = "DK" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
            try {
                inventory.moveQuantity(siteId, "SWEEP-" + lpn, s.ownerId(), s.itemNo(), s.lotNo(), s.status(), s.qty(),
                        s.locationId(), null, s.locationId(), lpn);
                lpns++;
            } catch (ApiException e) {
                // stale projection or a concurrent move: the next sweep tries again
                failed.add(s.itemNo() + "@" + s.locationId() + ": " + e.code());
                log.warn("Dock sweep could not palletise {} of {} at {}: {}", s.qty(), s.itemNo(), s.locationId(), e.getMessage());
            }
        }
        return new SweepResult(tasks, lpns, failed);
    }

    /** One RF receiving scan. {@code scanId} is generated by the device per scan and makes a retried scan harmless. */
    public record ReceiveScan(String scanId, String docNo, String itemNo, String ownerId, java.math.BigDecimal qty,
                              String uom, String lotNo, java.time.LocalDate expiryDate, List<String> serials,
                              String lpnId, String locationId, String checkDigit, String conditionGrade,
                              String disposition, String returnReason, String overrideReason, String damageReason,
                              String damageNote, String photo) {
        public ReceiveScan(String scanId, String docNo, String itemNo, String ownerId, java.math.BigDecimal qty,
                           String uom, String lotNo, java.time.LocalDate expiryDate, List<String> serials,
                           String lpnId, String locationId, String checkDigit, String conditionGrade,
                           String disposition, String returnReason, String overrideReason) {
            this(scanId, docNo, itemNo, ownerId, qty, uom, lotNo, expiryDate, serials, lpnId, locationId, checkDigit,
                    conditionGrade, disposition, returnReason, overrideReason, null, null, null);
        }
    }

    public record ReceiveScanResult(TaskView task, tools.jackson.databind.JsonNode result) {
    }

    private record Receiving(String status, String type, String assignedTo, String kind, String docNo) {
    }

    private Receiving lockReceiving(String siteId, UUID taskId) {
        Receiving t = jdbc.sql("""
                        select status, task_type, assigned_to, receive_kind, doc_no from task
                        where site_id = :site and id = :id for update""")
                .param("site", siteId).param("id", taskId)
                .query((rs, n) -> new Receiving(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)))
                .optional().orElseThrow(() -> ApiException.notFound("TSK_UNKNOWN", "Task " + taskId + " not found"));
        if (!"RECEIVE".equals(t.type())) {
            throw ApiException.unprocessable("TSK_WRONG_TYPE", "Task " + taskId + " is a " + t.type() + " task");
        }
        String user = TenantContext.require().userId();
        if (!"ASSIGNED".equals(t.status()) || !user.equals(t.assignedTo())) {
            throw ApiException.conflict("TSK_NOT_ASSIGNED", "Task is " + t.status() + " and not assigned to " + user);
        }
        return t;
    }

    /**
     * RF receive: the operator scans the document, the item, the quantity (with lot, expiry and serials when the
     * item needs them), the LPN, and the check digit of the dock, receiving or returns location the goods are put
     * down at. The inbound service records the receipt (tolerances, lot rules, return grading); the putaway task
     * follows from the stock arriving at the dock.
     */
    @Transactional
    public ReceiveScanResult confirmReceive(String siteId, UUID taskId, ReceiveScan scan) {
        Receiving t = lockReceiving(siteId, taskId);
        ReceiveScan s = withGs1(siteId, taskId, scan);
        if (s.docNo() == null || !t.docNo().equals(s.docNo().trim())) {
            throw ApiException.unprocessable("TSK_WRONG_DOCUMENT",
                    "Scanned document " + s.docNo() + " but the task is for " + t.docNo());
        }
        Projections.Location loc = projections.location(siteId, s.locationId()).orElseThrow(() ->
                ApiException.unprocessable("TSK_LOCATION_UNKNOWN", "Location " + s.locationId() + " is not known"));
        if (loc.checkDigit() == null || s.checkDigit() == null || !loc.checkDigit().equals(s.checkDigit().trim())) {
            throw ApiException.unprocessable("TSK_CHECK_DIGIT_MISMATCH", "Check digit does not match location " + s.locationId());
        }
        requireActiveLabel(siteId, "LPN", s.lpnId());
        if (!PutawayEngine.inboundStaging(loc)) {
            throw ApiException.unprocessable("TSK_LOCATION_NOT_ALLOWED",
                    s.locationId() + " is not a dock, receiving or returns location");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("itemNo", s.itemNo());
        body.put("qty", s.qty());
        body.put("uom", s.uom());
        body.put("lotNo", s.lotNo());
        body.put("serials", s.serials() == null ? List.of() : s.serials());
        body.put("lpnId", s.lpnId());
        body.put("locationId", s.locationId());
        tools.jackson.databind.JsonNode result;
        String key = "RF-" + s.scanId();
        if ("ASN".equals(t.kind())) {
            body.put("expiryDate", s.expiryDate() == null ? null : s.expiryDate().toString());
            body.put("overrideReason", s.overrideReason());
            body.put("damageReason", s.damageReason());
            body.put("damageNote", s.damageNote());
            body.put("photo", s.photo());
            result = inbound.receiveAsnItem(siteId, t.docNo(), key, body);
        } else {
            body.put("ownerId", s.ownerId());
            body.put("conditionGrade", s.conditionGrade());
            body.put("disposition", s.disposition());
            body.put("returnReasonActual", s.returnReason());
            result = inbound.receiveReturnUnit(siteId, t.docNo(), key, body);
        }
        jdbc.sql("update task set scans = scans + 1, updated_at = :now where id = :id")
                .param("now", Timestamp.from(clock.instant())).param("id", taskId).update();
        event(taskId, "SCANNED", s.qty().stripTrailingZeros().toPlainString() + " " + s.uom() + " " + s.itemNo()
                + (s.lpnId() == null || s.lpnId().isBlank() ? "" : " LPN " + s.lpnId()) + " at " + s.locationId());
        return new ReceiveScanResult(view(siteId, taskId), result);
    }

    /**
     * ADR-0022: the item field may hold a GS1 element string (or a bare GTIN). The GTIN becomes the item (among the
     * items of the document when several share it); lot, expiry, serial, count (AI 37/30) and SSCC (as the LPN) fill
     * what the operator left empty. What the operator typed wins.
     */
    private ReceiveScan withGs1(String siteId, UUID taskId, ReceiveScan s) {
        if (s.itemNo() == null) {
            return s;
        }
        java.util.Optional<com.astrawms.common.barcode.Gs1.Data> gs1 = com.astrawms.common.barcode.Gs1.parse(s.itemNo());
        String gtin = gs1.map(com.astrawms.common.barcode.Gs1.Data::itemGtin)
                .orElse(s.itemNo().trim().matches("\\d{8}|\\d{12,14}") ? s.itemNo().trim() : null);
        if (gtin == null) {
            return s;
        }
        List<String[]> items = projections.itemsByGtin(gtin);
        String expected = jdbc.sql("select coalesce(expected_lines::text, '') from task where id = :id").param("id", taskId)
                .query(String.class).single();
        List<String[]> onDoc = items.stream().filter(i -> expected.contains("\"" + i[1] + "\"")).toList();
        List<String[]> candidates = onDoc.isEmpty() ? items : onDoc;
        if (candidates.isEmpty()) {
            if (gs1.isEmpty()) {
                return s;                                   // digits that are no known GTIN: maybe an item number
            }
            throw ApiException.unprocessable("TSK_GTIN_UNKNOWN", "GTIN " + gtin + " is not a unit of any item");
        }
        if (candidates.size() > 1) {
            throw ApiException.unprocessable("TSK_GTIN_AMBIGUOUS", "GTIN " + gtin + " belongs to several items; scan the item number");
        }
        com.astrawms.common.barcode.Gs1.Data d = gs1.orElse(null);
        // ADR-0027: every SGTIN tag carries a serial, also on items that are not serial-tracked, so a tag's serial is
        // never taken as the unit's serial here; the RF client sends the serials of serial-tracked items explicitly.
        boolean rfidTag = com.astrawms.common.rfid.Epc.parse(s.itemNo()).isPresent();
        List<String> serials = s.serials() != null && !s.serials().isEmpty() ? s.serials()
                : d != null && d.serial() != null && !rfidTag ? List.of(d.serial()) : s.serials();
        return new ReceiveScan(s.scanId(), s.docNo(), candidates.getFirst()[1],
                s.ownerId() == null || s.ownerId().isBlank() ? candidates.getFirst()[0] : s.ownerId(),
                s.qty() != null ? s.qty() : d == null ? null : d.count(), s.uom(),
                blank(s.lotNo()) && d != null ? d.lot() : s.lotNo(),
                s.expiryDate() == null && d != null ? d.expiry() : s.expiryDate(), serials,
                blank(s.lpnId()) && d != null ? d.sscc() : s.lpnId(), s.locationId(), s.checkDigit(), s.conditionGrade(),
                s.disposition(), s.returnReason(), s.overrideReason());
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    /** The SSCC of an LPN scan given as a GS1-128 pallet label {@code (00)...} or an SSCC RFID tag (ADR-0027); else null. */
    static String ssccOf(String lpnScan) {
        return com.astrawms.common.barcode.Gs1.parse(lpnScan).map(com.astrawms.common.barcode.Gs1.Data::sscc).orElse(null);
    }

    /**
     * RF finish: closes the receipt (the ERP confirmation is sent) and completes the task. Lines received short need a
     * reason ({@code shortReasons}: erpLineRef → reason); a return is closed as received.
     */
    @Transactional
    public ReceiveScanResult closeReceive(String siteId, UUID taskId, Map<String, String> shortReasons) {
        Receiving t = lockReceiving(siteId, taskId);
        tools.jackson.databind.JsonNode result = "ASN".equals(t.kind())
                ? inbound.closeAsn(siteId, t.docNo(), shortReasons)
                : inbound.closeReturn(siteId, t.docNo());
        jdbc.sql("update task set status = 'COMPLETED', completed_at = :now, updated_at = :now where id = :id")
                .param("now", Timestamp.from(clock.instant())).param("id", taskId).update();
        event(taskId, "COMPLETED", t.kind() + " " + t.docNo() + " closed");
        return new ReceiveScanResult(view(siteId, taskId), result);
    }

    /** RF: hand a started task back to the queue (shift end, wrong device); it keeps everything already confirmed. */
    @Transactional
    public TaskView release(String siteId, UUID taskId) {
        Task t = lockTask(siteId, taskId);
        String user = TenantContext.require().userId();
        if (!"ASSIGNED".equals(t.status()) || !user.equals(t.assignedTo())) {
            throw ApiException.conflict("TSK_NOT_ASSIGNED", "Task is " + t.status() + " and not assigned to " + user);
        }
        jdbc.sql("update task set status = 'RELEASED', assigned_to = null, assigned_at = null, updated_at = :now where id = :id")
                .param("now", Timestamp.from(clock.instant())).param("id", taskId).update();
        event(taskId, "RELEASED", "handed back by " + user);
        return view(siteId, taskId);
    }

    /**
     * Supervisor unassign (ADR-0024): a task left assigned to an operator who walked away goes back to the queue,
     * with the reason on the task history. Nothing has moved yet for an assigned task, so this is always safe.
     */
    @Transactional
    public TaskView unassign(String siteId, UUID taskId, String reason) {
        Task t = lockTask(siteId, taskId);
        boolean conflict = "EXCEPTION".equals(t.status()) && SYNC_CONFLICT.equals(jdbc.sql(
                "select exception_reason from task where id = :id").param("id", taskId).query(String.class).single());
        if (!"ASSIGNED".equals(t.status()) && !conflict) {
            throw ApiException.conflict("TSK_NOT_ASSIGNED", "Task is " + t.status() + ", not assigned");
        }
        jdbc.sql("""
                        update task set status = 'RELEASED', assigned_to = null, assigned_at = null, exception_reason = null,
                                        pick_group = null, pick_mode = null, updated_at = :now where id = :id""")
                .param("now", Timestamp.from(clock.instant())).param("id", taskId).update();
        event(taskId, "UNASSIGNED", "taken from " + t.assignedTo() + " by " + TenantContext.require().userId()
                + (reason == null || reason.isBlank() ? "" : ": " + reason.trim()));
        return view(siteId, taskId);
    }

    static final String SYNC_CONFLICT = "SYNC_CONFLICT";

    /**
     * Offline replay refused (ADR-0024): a confirmation made on a device without network was refused when it was sent
     * (the stock or the task changed meanwhile). The server's state stands; the task, if still open, becomes an
     * exception for the supervisor, who checks the location and puts it back in the queue (unassign).
     */
    @Transactional
    public TaskView syncConflict(String siteId, UUID taskId, String detail) {
        Task t = lockTask(siteId, taskId);
        String text = detail == null || detail.isBlank() ? "refused when sent from the device" : detail.trim();
        if (OPEN.contains(t.status())) {
            jdbc.sql("""
                            update task set status = 'EXCEPTION', exception_reason = :r, assigned_to = null, assigned_at = null,
                                            updated_at = :now where id = :id""")
                    .param("r", SYNC_CONFLICT).param("now", Timestamp.from(clock.instant())).param("id", taskId).update();
        }
        event(taskId, SYNC_CONFLICT, text.length() > 500 ? text.substring(0, 500) : text);
        return view(siteId, taskId);
    }

    /** Unassigns every task of {@code type} (all types when null) assigned longer than {@code minutes} ago. */
    @Transactional
    public List<TaskView> unassignStale(String siteId, String type, int minutes) {
        List<UUID> ids = jdbc.sql("""
                        select id from task where site_id = :site and status = 'ASSIGNED'
                          and (cast(:type as text) is null or task_type = :type) and assigned_at < :before
                        order by assigned_at""")
                .param("site", siteId).param("type", type == null || type.isBlank() ? null : type.trim().toUpperCase())
                .param("before", Timestamp.from(clock.instant().minus(Duration.ofMinutes(minutes))))
                .query(UUID.class).list();
        return ids.stream().map(id -> unassign(siteId, id, "assigned over " + minutes + " min")).toList();
    }

    // =====================================================================================================
    // PICK tasks (scope §4)
    // =====================================================================================================

    /** Creates the pick task for an allocation; a redelivered request is ignored (one task per allocation). */
    @Transactional
    public void onPickRequested(String siteId, OutboundContracts.PickRequested p) {
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                        insert into task (id, tenant_id, site_id, task_type, status, priority, owner_id, lpn_id,
                                          from_location, target_location, strategy, allocation_id, order_ref,
                                          order_line_ref, item_no, lot_no, qty, uom, to_lpn, created_at, updated_at)
                        values (:id, :t, :site, 'PICK', 'RELEASED', :prio, :owner, :lpn, :from, :to, 'ALLOCATION',
                                :alloc, :order, :line, :item, :lot, :qty, :uom, :toLpn, :now, :now)
                        on conflict (tenant_id, allocation_id) where task_type = 'PICK' do nothing""")
                .param("id", id).param("t", TenantContext.tenantId()).param("site", siteId).param("prio", p.priority())
                .param("owner", p.ownerId()).param("lpn", p.fromLpn() == null ? "" : p.fromLpn())
                .param("from", p.fromLocation()).param("to", p.toLocation()).param("alloc", p.allocationId())
                .param("order", p.orderRef()).param("line", p.orderLineRef()).param("item", p.itemNo())
                .param("lot", p.lotNo()).param("qty", p.qty()).param("uom", p.uom()).param("toLpn", p.toLpn())
                .param("now", Timestamp.from(now)).update();
        if (inserted == 1) {
            event(id, "CREATED", "Pick " + p.qty().toPlainString() + " " + p.itemNo() + " for " + p.orderRef());
        }
    }

    @Transactional
    public void onPickCancelled(String siteId, OutboundContracts.PickCancelled c) {
        jdbc.sql("""
                        update task set status = 'CANCELLED', exception_reason = 'ORDER_CANCELLED', updated_at = :now
                        where site_id = :site and allocation_id = :alloc and task_type = 'PICK'
                          and status in ('RELEASED', 'ASSIGNED', 'EXCEPTION')""")
                .param("now", Timestamp.from(clock.instant())).param("site", siteId).param("alloc", c.allocationId())
                .update();
    }

    public static final List<String> SHORT_REASONS = List.of("NOT_FOUND", "DAMAGED", "QTY_LESS", "WRONG_ITEM", "OTHER");
    public static final List<String> SHORT_ACTIONS = List.of("REALLOCATE", "BACKORDER", "SHIP_SHORT");

    /**
     * RF pick: the scanned check digit must belong to the source location and the scanned item (item number or
     * GTIN) must be the task's item (ADR-0020: a location-only pick is rejected). Picking less than requested is a
     * short pick: it needs a reason and says what happens to the missing quantity (reallocate elsewhere, wait for
     * stock via backorder recovery, or ship short); the remainder is released at the source (PCK-003).
     * Idempotent via the inventory key {@code TSK-<taskId>}.
     */
    @Transactional
    public TaskView confirmPick(String siteId, UUID taskId, String checkDigit, String itemScan, java.math.BigDecimal qty,
                                List<String> serials, String shortReason, String shortAction) {
        record Pick(String status, String type, String assignedTo, String from, String to, String toLpn,
                    UUID allocation, String order, String line, java.math.BigDecimal requested, String owner, String item) {
        }
        Pick p = jdbc.sql("""
                        select status, task_type, assigned_to, from_location, target_location, to_lpn, allocation_id,
                               order_ref, order_line_ref, qty, owner_id, item_no
                        from task where site_id = :site and id = :id for update""")
                .param("site", siteId).param("id", taskId)
                .query((rs, n) -> new Pick(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8), rs.getString(9),
                        rs.getBigDecimal(10), rs.getString(11), rs.getString(12)))
                .optional().orElseThrow(() -> ApiException.notFound("TSK_UNKNOWN", "Task " + taskId + " not found"));
        if (!"PICK".equals(p.type())) {
            throw ApiException.unprocessable("TSK_WRONG_TYPE", "Task " + taskId + " is a " + p.type() + " task");
        }
        if ("COMPLETED".equals(p.status())) {
            return view(siteId, taskId);
        }
        String user = TenantContext.require().userId();
        if (!"ASSIGNED".equals(p.status()) || !user.equals(p.assignedTo())) {
            throw ApiException.conflict("TSK_NOT_ASSIGNED", "Task is " + p.status() + " and not assigned to " + user);
        }
        Projections.Location source = projections.location(siteId, p.from()).orElseThrow(() ->
                ApiException.unprocessable("TSK_LOCATION_UNKNOWN", "Location " + p.from() + " is not known"));
        if (source.checkDigit() == null || !source.checkDigit().equals(checkDigit.trim())) {
            throw ApiException.unprocessable("TSK_CHECK_DIGIT_MISMATCH", "Check digit does not match location " + p.from());
        }
        if (itemScan == null || itemScan.isBlank()) {
            throw ApiException.unprocessable("TSK_ITEM_SCAN_REQUIRED", "Scan the item or its GTIN before confirming");
        }
        if (!projections.scanMatchesItem(p.owner(), p.item(), itemScan)) {
            throw ApiException.unprocessable("TSK_WRONG_ITEM", "Scanned " + itemScan.trim() + " is not item " + p.item());
        }
        // ADR-0022: a GS1 scan that carries a lot must be the task's lot.
        String scannedLot = com.astrawms.common.barcode.Gs1.parse(itemScan).map(com.astrawms.common.barcode.Gs1.Data::lot)
                .orElse(null);
        String taskLot = jdbc.sql("select coalesce(lot_no, '') from task where id = :id").param("id", taskId)
                .query(String.class).single();
        if (scannedLot != null && !taskLot.isEmpty() && !taskLot.equals(scannedLot)) {
            throw ApiException.unprocessable("TSK_WRONG_LOT", "Scanned lot " + scannedLot + " but the task is for lot " + taskLot);
        }
        if (qty.compareTo(p.requested()) > 0) {
            throw ApiException.unprocessable("TSK_PICK_QTY_EXCEEDS", "Requested " + p.requested().stripTrailingZeros().toPlainString());
        }
        boolean shortPick = qty.compareTo(p.requested()) < 0;
        String reason = null;
        String action = null;
        if (shortPick) {
            reason = shortReason == null || shortReason.isBlank() ? null : shortReason.trim().toUpperCase();
            if (reason == null || !SHORT_REASONS.contains(reason)) {
                throw ApiException.unprocessable("TSK_SHORT_REASON_REQUIRED",
                        "A short pick needs a reason: one of " + SHORT_REASONS);
            }
            action = shortAction == null || shortAction.isBlank() ? "REALLOCATE" : shortAction.trim().toUpperCase();
            if (!SHORT_ACTIONS.contains(action)) {
                throw ApiException.unprocessable("TSK_SHORT_ACTION_INVALID", "Short action must be one of " + SHORT_ACTIONS);
            }
        }
        UUID operation = inventory.pick(siteId, "TSK-" + taskId, p.allocation(), qty, p.to(), p.toLpn(), serials, shortPick);
        Instant now = clock.instant();
        jdbc.sql("""
                        update task set status = 'COMPLETED', qty_picked = :qty, confirmed_location = target_location,
                            inventory_operation_id = :op, exception_reason = :short, short_reason = :reason,
                            short_action = :action, completed_at = :now, updated_at = :now
                        where id = :id""")
                .param("qty", qty).param("op", operation).param("short", shortPick ? "SHORT_PICK" : null)
                .param("reason", reason).param("action", action)
                .param("now", Timestamp.from(now)).param("id", taskId).update();
        event(taskId, "COMPLETED", "picked " + qty.toPlainString() + (shortPick ? " (short: " + reason + ", " + action + ")" : ""));
        outbox.append(new OutboxWriter.Message(OutboundContracts.TOPIC_TASK_EVENTS, OutboundContracts.TaskCompleted.TYPE,
                OutboundContracts.TaskCompleted.VERSION, null, siteId, null, siteId + ":" + p.order(),
                new OutboundContracts.TaskCompleted(taskId, "PICK", p.allocation(), p.order(), p.line(), qty,
                        p.requested().subtract(qty), user, now, reason, action)));
        return view(siteId, taskId);
    }

    // =====================================================================================================
    // REPLEN tasks (§7 replenishment)
    // =====================================================================================================

    @Transactional
    public void onReplenRequested(String siteId, com.astrawms.common.contracts.InventoryContracts.ReplenRequested r) {
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                        insert into task (id, tenant_id, site_id, task_type, status, priority, owner_id, lpn_id,
                                          from_location, target_location, strategy, replenishment_id, item_no, lot_no,
                                          qty, uom, created_at, updated_at)
                        values (:id, :t, :site, 'REPLEN', 'RELEASED', :prio, :owner, :lpn, :from, :to, 'MIN_MAX', :repl,
                                :item, :lot, :qty, :uom, :now, :now)
                        on conflict (tenant_id, replenishment_id) where task_type = 'REPLEN' do nothing""")
                .param("id", id).param("t", TenantContext.tenantId()).param("site", siteId).param("prio", r.priority())
                .param("owner", r.ownerId()).param("lpn", r.fromLpn() == null ? "" : r.fromLpn())
                .param("from", r.fromLocation()).param("to", r.toLocation()).param("repl", r.replenishmentId())
                .param("item", r.itemNo()).param("lot", r.lotNo()).param("qty", r.qty()).param("uom", r.uom())
                .param("now", Timestamp.from(now)).update();
        if (inserted == 1) {
            event(id, "CREATED", "Replenish " + r.qty().toPlainString() + " " + r.itemNo() + " to " + r.toLocation());
        }
    }

    /** RF replenishment: take the stock from reserve, drop it at the forward location, scan that location's check digit. */
    @Transactional
    public TaskView confirmReplenishment(String siteId, UUID taskId, String checkDigit) {
        record Rep(String status, String type, String assignedTo, String to, UUID replenishment) {
        }
        Rep r = jdbc.sql("""
                        select status, task_type, assigned_to, target_location, replenishment_id from task
                        where site_id = :site and id = :id for update""")
                .param("site", siteId).param("id", taskId)
                .query((rs, n) -> new Rep(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, UUID.class)))
                .optional().orElseThrow(() -> ApiException.notFound("TSK_UNKNOWN", "Task " + taskId + " not found"));
        if (!"REPLEN".equals(r.type())) {
            throw ApiException.unprocessable("TSK_WRONG_TYPE", "Task " + taskId + " is a " + r.type() + " task");
        }
        if ("COMPLETED".equals(r.status())) {
            return view(siteId, taskId);
        }
        String user = TenantContext.require().userId();
        if (!"ASSIGNED".equals(r.status()) || !user.equals(r.assignedTo())) {
            throw ApiException.conflict("TSK_NOT_ASSIGNED", "Task is " + r.status() + " and not assigned to " + user);
        }
        Projections.Location target = projections.location(siteId, r.to()).orElseThrow(() ->
                ApiException.unprocessable("TSK_LOCATION_UNKNOWN", "Location " + r.to() + " is not known"));
        if (target.checkDigit() == null || !target.checkDigit().equals(checkDigit.trim())) {
            throw ApiException.unprocessable("TSK_CHECK_DIGIT_MISMATCH", "Check digit does not match location " + r.to());
        }
        UUID operation = inventory.confirmReplenishment(siteId, "TSK-" + taskId, r.replenishment());
        Instant now = clock.instant();
        jdbc.sql("""
                        update task set status = 'COMPLETED', qty_picked = qty, confirmed_location = target_location,
                            inventory_operation_id = :op, completed_at = :now, updated_at = :now where id = :id""")
                .param("op", operation).param("now", Timestamp.from(now)).param("id", taskId).update();
        event(taskId, "COMPLETED", "replenished " + r.to());
        return view(siteId, taskId);
    }

    // =====================================================================================================
    // MOVE tasks (ADR-0021: reslot)
    // =====================================================================================================

    @Transactional
    public void onMoveRequested(String siteId, com.astrawms.common.contracts.InventoryContracts.MoveRequested m) {
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                        insert into task (id, tenant_id, site_id, task_type, status, priority, owner_id, lpn_id,
                                          from_location, target_location, strategy, move_id, item_no, lot_no, qty, uom,
                                          created_at, updated_at)
                        values (:id, :t, :site, 'MOVE', 'RELEASED', :prio, :owner, :lpn, :from, :to, :reason, :move,
                                :item, :lot, :qty, :uom, :now, :now)
                        on conflict (tenant_id, move_id) where task_type = 'MOVE' do nothing""")
                .param("id", id).param("t", TenantContext.tenantId()).param("site", siteId).param("prio", m.priority())
                .param("owner", m.ownerId()).param("lpn", m.lpnId() == null ? "" : m.lpnId())
                .param("from", m.fromLocation()).param("to", m.toLocation()).param("reason", m.reason())
                .param("move", m.moveId()).param("item", m.itemNo()).param("lot", m.lotNo()).param("qty", m.qty())
                .param("uom", m.uom()).param("now", Timestamp.from(now)).update();
        if (inserted == 1) {
            event(id, "CREATED", m.reason() + ": move " + m.qty().toPlainString() + " " + m.itemNo() + " "
                    + m.fromLocation() + " → " + m.toLocation());
        }
    }

    /** RF move: take the stock at the source, drop it at the target and scan the target's check digit. */
    @Transactional
    public TaskView confirmMove(String siteId, UUID taskId, String checkDigit) {
        record Mv(String status, String type, String assignedTo, String owner, String lpn, String from, String to,
                  String item, String lot, java.math.BigDecimal qty) {
        }
        Mv m = jdbc.sql("""
                        select status, task_type, assigned_to, owner_id, lpn_id, from_location, target_location, item_no,
                               lot_no, qty
                        from task where site_id = :site and id = :id for update""")
                .param("site", siteId).param("id", taskId)
                .query((rs, n) -> new Mv(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getBigDecimal(10)))
                .optional().orElseThrow(() -> ApiException.notFound("TSK_UNKNOWN", "Task " + taskId + " not found"));
        if (!"MOVE".equals(m.type())) {
            throw ApiException.unprocessable("TSK_WRONG_TYPE", "Task " + taskId + " is a " + m.type() + " task");
        }
        if ("COMPLETED".equals(m.status())) {
            return view(siteId, taskId);
        }
        String user = TenantContext.require().userId();
        if (!"ASSIGNED".equals(m.status()) || !user.equals(m.assignedTo())) {
            throw ApiException.conflict("TSK_NOT_ASSIGNED", "Task is " + m.status() + " and not assigned to " + user);
        }
        Projections.Location target = projections.location(siteId, m.to()).orElseThrow(() ->
                ApiException.unprocessable("TSK_LOCATION_UNKNOWN", "Location " + m.to() + " is not known"));
        if (target.checkDigit() == null || checkDigit == null || !target.checkDigit().equals(checkDigit.trim())) {
            throw ApiException.unprocessable("TSK_CHECK_DIGIT_MISMATCH", "Check digit does not match location " + m.to());
        }
        UUID operation = inventory.moveQuantity(siteId, "TSK-" + taskId, m.owner(), m.item(), m.lot(), "AVAILABLE",
                m.qty(), m.from(), m.lpn(), m.to(), null);
        jdbc.sql("""
                        update task set status = 'COMPLETED', qty_picked = qty, confirmed_location = target_location,
                            inventory_operation_id = :op, completed_at = :now, updated_at = :now where id = :id""")
                .param("op", operation).param("now", Timestamp.from(clock.instant())).param("id", taskId).update();
        event(taskId, "COMPLETED", "moved to " + m.to());
        return view(siteId, taskId);
    }

    // =====================================================================================================
    // COUNT tasks (§6.3 cycle counting)
    // =====================================================================================================

    /** Creates the count task of a count sequence; recounts are offered only to users who have not counted yet. */
    @Transactional
    public void onCountRequested(String siteId, com.astrawms.common.contracts.InventoryContracts.CountRequested c) {
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                        insert into task (id, tenant_id, site_id, task_type, status, priority, owner_id, lpn_id,
                                          from_location, target_location, strategy, count_id, count_sequence,
                                          excluded_users, created_at, updated_at)
                        values (:id, :t, :site, 'COUNT', 'RELEASED', :prio, '', '', :loc, :loc, :strategy, :count, :seq,
                                cast(:excluded as text[]), :now, :now)
                        on conflict (tenant_id, count_id, count_sequence) where task_type = 'COUNT' do nothing""")
                .param("id", id).param("t", TenantContext.tenantId()).param("site", siteId).param("prio", c.priority())
                .param("loc", c.locationId()).param("strategy", (c.sequence() > 1 ? "RECOUNT " : "COUNT ") + c.trigger())
                .param("count", c.countId()).param("seq", c.sequence())
                .param("excluded", "{" + String.join(",", c.excludedUsers().stream().map(u -> '"' + u.replace("\"", "") + '"').toList()) + "}")
                .param("now", Timestamp.from(now)).update();
        if (inserted == 1) {
            event(id, "CREATED", "Count " + c.sequence() + " of " + c.locationId() + " (" + c.trigger() + ")");
        }
    }

    /**
     * RF count: the counter scans the location's check digit and enters what is there, blind. The result goes to
     * the inventory service, which decides on tolerance, recount or approval. Idempotent via {@code TSK-<taskId>}.
     */
    @Transactional
    public TaskView confirmCount(String siteId, UUID taskId, String checkDigit, List<?> lines) {
        record Cnt(String status, String type, String assignedTo, String location, UUID countId) {
        }
        Cnt c = jdbc.sql("""
                        select status, task_type, assigned_to, from_location, count_id from task
                        where site_id = :site and id = :id for update""")
                .param("site", siteId).param("id", taskId)
                .query((rs, n) -> new Cnt(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, UUID.class)))
                .optional().orElseThrow(() -> ApiException.notFound("TSK_UNKNOWN", "Task " + taskId + " not found"));
        if (!"COUNT".equals(c.type())) {
            throw ApiException.unprocessable("TSK_WRONG_TYPE", "Task " + taskId + " is a " + c.type() + " task");
        }
        if ("COMPLETED".equals(c.status())) {
            return view(siteId, taskId);
        }
        String user = TenantContext.require().userId();
        if (!"ASSIGNED".equals(c.status()) || !user.equals(c.assignedTo())) {
            throw ApiException.conflict("TSK_NOT_ASSIGNED", "Task is " + c.status() + " and not assigned to " + user);
        }
        Projections.Location location = projections.location(siteId, c.location()).orElseThrow(() ->
                ApiException.unprocessable("TSK_LOCATION_UNKNOWN", "Location " + c.location() + " is not known"));
        if (location.checkDigit() == null || !location.checkDigit().equals(checkDigit.trim())) {
            throw ApiException.unprocessable("TSK_CHECK_DIGIT_MISMATCH", "Check digit does not match location " + c.location());
        }
        String result = inventory.submitCount(siteId, "TSK-" + taskId, c.countId(), lines);
        Instant now = clock.instant();
        jdbc.sql("""
                        update task set status = 'COMPLETED', confirmed_location = from_location, completed_at = :now,
                            updated_at = :now where id = :id""")
                .param("now", Timestamp.from(now)).param("id", taskId).update();
        event(taskId, "COMPLETED", "counted " + lines.size() + " line(s); count is now " + result);
        return view(siteId, taskId);
    }

    // =====================================================================================================
    // RETURN tasks (OUT-EX-02 reverse pick)
    // =====================================================================================================

    /** Creates the return task for a picked allocation of a cancelled order; redeliveries are ignored. */
    @Transactional
    public void onReturnRequested(String siteId, OutboundContracts.ReturnRequested r) {
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                        insert into task (id, tenant_id, site_id, task_type, status, priority, owner_id, lpn_id,
                                          from_location, target_location, strategy, allocation_id, order_ref,
                                          order_line_ref, item_no, lot_no, qty, uom, to_lpn, created_at, updated_at)
                        values (:id, :t, :site, 'RETURN', 'RELEASED', :prio, :owner, :lpn, :from, :to, 'REVERSE_PICK',
                                :alloc, :order, :line, :item, :lot, :qty, :uom, :toLpn, :now, :now)
                        on conflict (tenant_id, allocation_id) where task_type = 'RETURN' do nothing""")
                .param("id", id).param("t", TenantContext.tenantId()).param("site", siteId).param("prio", r.priority())
                .param("owner", r.ownerId()).param("lpn", r.fromLpn() == null ? "" : r.fromLpn())
                .param("from", r.fromLocation()).param("to", r.toLocation()).param("alloc", r.allocationId())
                .param("order", r.orderRef()).param("line", r.orderLineRef()).param("item", r.itemNo())
                .param("lot", r.lotNo()).param("qty", r.qty()).param("uom", r.uom())
                .param("toLpn", r.toLpn() == null ? "" : r.toLpn())
                .param("now", Timestamp.from(now)).update();
        if (inserted == 1) {
            event(id, "CREATED", "Return " + r.qty().toPlainString() + " " + r.itemNo() + " of cancelled " + r.orderRef());
        }
    }

    /**
     * RF return: the operator takes the stock from outbound staging and scans the check digit of the location it is
     * put back to. The whole picked quantity is returned. Idempotent via the inventory key {@code TSK-<taskId>}.
     */
    @Transactional
    public TaskView confirmReturn(String siteId, UUID taskId, String checkDigit) {
        record Ret(String status, String type, String assignedTo, String to, String toLpn, UUID allocation,
                   String order, String line, java.math.BigDecimal qty) {
        }
        Ret r = jdbc.sql("""
                        select status, task_type, assigned_to, target_location, to_lpn, allocation_id, order_ref,
                               order_line_ref, qty
                        from task where site_id = :site and id = :id for update""")
                .param("site", siteId).param("id", taskId)
                .query((rs, n) -> new Ret(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8),
                        rs.getBigDecimal(9)))
                .optional().orElseThrow(() -> ApiException.notFound("TSK_UNKNOWN", "Task " + taskId + " not found"));
        if (!"RETURN".equals(r.type())) {
            throw ApiException.unprocessable("TSK_WRONG_TYPE", "Task " + taskId + " is a " + r.type() + " task");
        }
        if ("COMPLETED".equals(r.status())) {
            return view(siteId, taskId);
        }
        String user = TenantContext.require().userId();
        if (!"ASSIGNED".equals(r.status()) || !user.equals(r.assignedTo())) {
            throw ApiException.conflict("TSK_NOT_ASSIGNED", "Task is " + r.status() + " and not assigned to " + user);
        }
        Projections.Location target = projections.location(siteId, r.to()).orElseThrow(() ->
                ApiException.unprocessable("TSK_LOCATION_UNKNOWN", "Location " + r.to() + " is not known"));
        if (target.checkDigit() == null || !target.checkDigit().equals(checkDigit.trim())) {
            throw ApiException.unprocessable("TSK_CHECK_DIGIT_MISMATCH", "Check digit does not match location " + r.to());
        }
        UUID operation = inventory.returnToStock(siteId, "TSK-" + taskId, r.allocation(), r.to(),
                r.toLpn() == null || r.toLpn().isEmpty() ? null : r.toLpn());
        Instant now = clock.instant();
        jdbc.sql("""
                        update task set status = 'COMPLETED', qty_picked = qty, confirmed_location = target_location,
                            inventory_operation_id = :op, completed_at = :now, updated_at = :now
                        where id = :id""")
                .param("op", operation).param("now", Timestamp.from(now)).param("id", taskId).update();
        event(taskId, "COMPLETED", "returned to " + r.to());
        outbox.append(new OutboxWriter.Message(OutboundContracts.TOPIC_TASK_EVENTS, OutboundContracts.TaskCompleted.TYPE,
                OutboundContracts.TaskCompleted.VERSION, null, siteId, null, siteId + ":" + r.order(),
                new OutboundContracts.TaskCompleted(taskId, "RETURN", r.allocation(), r.order(), r.line(), r.qty(),
                        java.math.BigDecimal.ZERO, user, now)));
        return view(siteId, taskId);
    }

    private record Task(UUID id, String siteId, String status, String ownerId, String lpnId, String fromLocation,
                        String targetLocation, String assignedTo, List<String> excluded, UUID inventoryOperationId,
                        String type) {
    }

    // =====================================================================================================
    // Event-driven creation and cancellation
    // =====================================================================================================

    /**
     * Reacts to inventory changes after the projection was updated: LPNs arriving at staging get a putaway task;
     * open tasks whose LPN left its source by other means are cancelled.
     */
    @Transactional
    public void onInventoryChanged(String siteId, InventoryChanged e) {
        for (InventoryChanged.Line l : e.lines()) {
            String lpn = l.lpnId() == null ? "" : l.lpnId();
            if (lpn.isEmpty()) {
                continue;   // putaway is LPN-based; loose dock stock is handled by the dock check (§1.2)
            }
            if (("RECEIPT".equals(l.txnType()) || "MOVE_IN".equals(l.txnType())) && isStaging(siteId, l.locationId())) {
                createPutaway(siteId, e.ownerId(), lpn, l.locationId(), e.operationId());
            }
            if ("MOVE_OUT".equals(l.txnType())) {
                cancelIfMovedElsewhere(siteId, lpn, l.locationId(), e.operationId());
            }
        }
    }

    private boolean isStaging(String siteId, String locationId) {
        return projections.location(siteId, locationId).map(PutawayEngine::inboundStaging).orElse(false);
    }

    private void createPutaway(String siteId, String ownerId, String lpn, String from, UUID sourceOperation) {
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                        insert into task (id, tenant_id, site_id, task_type, status, owner_id, lpn_id, from_location,
                                          source_operation_id, created_at, updated_at)
                        values (:id, :t, :site, 'PUTAWAY', 'EXCEPTION', :owner, :lpn, :from, :src, :now, :now)
                        on conflict (tenant_id, site_id, lpn_id)
                            where task_type = 'PUTAWAY' and status in ('RELEASED', 'ASSIGNED', 'EXCEPTION')
                        do nothing""")
                .param("id", id).param("t", TenantContext.tenantId()).param("site", siteId).param("owner", ownerId)
                .param("lpn", lpn).param("from", from).param("src", sourceOperation).param("now", Timestamp.from(now))
                .update();
        if (inserted == 1) {
            event(id, "CREATED", "LPN " + lpn + " at " + from);
            plan(lockTask(siteId, id));   // a second item of the same LPN re-plans the existing task below
        } else {
            findOpenByLpn(siteId, lpn).filter(t -> !"ASSIGNED".equals(t.status())).ifPresent(this::plan);
        }
    }

    private void cancelIfMovedElsewhere(String siteId, String lpn, String fromLocation, UUID operationId) {
        findOpenByLpn(siteId, lpn)
                .filter(t -> t.fromLocation().equals(fromLocation) && !operationId.equals(t.inventoryOperationId()))
                .ifPresent(t -> {
                    setStatus(t.id(), "CANCELLED", "LPN_MOVED_OUTSIDE_TASK");
                    event(t.id(), "CANCELLED", "LPN moved by operation " + operationId);
                });
    }

    // =====================================================================================================
    // Planning
    // =====================================================================================================

    /** Plans (or re-plans) the target; serialised per site so two tasks never reserve the same slot. */
    private void plan(Task t) {
        jdbc.sql("select 1 from pg_advisory_xact_lock(hashtext(:k))")
                .param("k", TenantContext.tenantId() + "|" + t.siteId() + "|putaway").query(Integer.class).single();
        List<Stock> contents = projections.lpnContents(t.siteId(), t.lpnId(), t.fromLocation());
        Optional<PutawayEngine.Plan> plan = engine.plan(t.siteId(), contents, t.excluded(), reservations(t.siteId(), t.id()));
        if (plan.isPresent()) {
            jdbc.sql("""
                            update task set status = 'RELEASED', target_location = :target, suggested_location = :target,
                                strategy = :strategy, exception_reason = null, assigned_to = null, updated_at = :now
                            where id = :id""")
                    .param("target", plan.get().locationId()).param("strategy", plan.get().strategy())
                    .param("now", Timestamp.from(clock.instant())).param("id", t.id()).update();
            event(t.id(), "PLANNED", plan.get().strategy() + " → " + plan.get().locationId());
        } else {
            String reason = contents.isEmpty() ? "LPN_CONTENTS_UNKNOWN" : "NO_LOCATION";   // PUT-EX-01
            jdbc.sql("""
                            update task set status = 'EXCEPTION', target_location = null, suggested_location = null,
                                strategy = null, exception_reason = :reason, assigned_to = null, updated_at = :now
                            where id = :id""")
                    .param("reason", reason).param("now", Timestamp.from(clock.instant())).param("id", t.id()).update();
            event(t.id(), "EXCEPTION", reason);
            log.warn("Putaway task {} for LPN {} has no target: {}", t.id(), t.lpnId(), reason);
        }
    }

    private Map<String, Integer> reservations(String siteId, UUID excludeTask) {
        Map<String, Integer> map = new HashMap<>();
        jdbc.sql("""
                        select target_location, count(*) from task
                        where site_id = :site and status in ('RELEASED', 'ASSIGNED') and target_location is not null
                          and id <> :id
                        group by target_location""")
                .param("site", siteId).param("id", excludeTask)
                .query((rs, n) -> map.put(rs.getString(1), rs.getInt(2))).list();
        return map;
    }

    // =====================================================================================================
    // RF execution
    // =====================================================================================================

    /** Returns the operator's current task, or assigns the highest-priority released task (oldest first). */
    @Transactional
    public Optional<TaskView> next(String siteId) {
        String user = TenantContext.require().userId();
        Optional<UUID> current = jdbc.sql("""
                        select id from task where site_id = :site and status = 'ASSIGNED' and assigned_to = :user
                        order by assigned_at limit 1""")
                .param("site", siteId).param("user", user).query(UUID.class).optional();
        if (current.isPresent()) {
            return Optional.of(view(siteId, current.get()));
        }
        // Only work in the operator's owner and zone scope (§G.5.1); a task's zones are those of its from/to location.
        // Only the task types of the operator's roles (ADR-0019). Within a priority, work follows the travel path,
        // so replenishments and picks of the same area interleave.
        Optional<UUID> next = claimable(siteId, user, 1).stream().findFirst();
        next.ifPresent(id -> assign(id, user));
        return next.map(id -> view(siteId, id));
    }

    /**
     * Offline work (ADR-0023): the operator's assigned tasks plus up to {@code count} more, chosen exactly as
     * {@link #next} would, all assigned to them, so a handheld can work through them without network and send the
     * confirmations when it is back online.
     */
    @Transactional
    public List<TaskView> claimBatch(String siteId, int count) {
        String user = TenantContext.require().userId();
        int wanted = Math.max(1, Math.min(count, 25));
        List<UUID> held = jdbc.sql("""
                        select id from task where site_id = :site and status = 'ASSIGNED' and assigned_to = :user
                        order by assigned_at""")
                .param("site", siteId).param("user", user).query(UUID.class).list();
        List<UUID> all = new ArrayList<>(held);
        if (held.size() < wanted) {
            for (UUID id : claimable(siteId, user, wanted - held.size())) {
                assign(id, user);
                all.add(id);
            }
        }
        return all.stream().map(id -> view(siteId, id)).toList();
    }

    // ------------------------------------------------------------------ batch and cluster picking (ADR-0025)

    /**
     * Takes a group of picks for one trip, chosen exactly as "next" chooses (role, zone, owner, skill, equipment,
     * priority, pick path):
     * <ul>
     *   <li>CLUSTER: every released pick of up to {@code size} orders, one tote per order;</li>
     *   <li>BATCH: the released picks of the best task's item from the same bin for several orders (up to
     *       {@code size} picks), picked together and sorted to the orders at staging.</li>
     * </ul>
     * Each pick is still confirmed on its own with the normal pick API. A group the operator already holds is
     * returned again (resume).
     */
    @Transactional
    public Map<String, Object> pickGroup(String siteId, String mode, int size) {
        String m = mode == null ? "CLUSTER" : mode.trim().toUpperCase();
        if (!List.of("CLUSTER", "BATCH").contains(m)) {
            throw ApiException.badRequest("TSK_PICK_MODE_INVALID", "mode is CLUSTER or BATCH");
        }
        String user = TenantContext.require().userId();
        if (!taskTypesOfCurrentUser().contains("PICK")) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "TSK_NOT_A_PICKER", "Your roles do not include picking");
        }
        List<UUID> held = jdbc.sql("""
                        select id from task where site_id = :site and status = 'ASSIGNED' and assigned_to = :user
                          and task_type = 'PICK' and pick_group is not null order by assigned_at""")
                .param("site", siteId).param("user", user).query(UUID.class).list();
        if (!held.isEmpty()) {
            return group(siteId, held);
        }
        int limit = Math.max(1, Math.min(size, 25));
        record Candidate(UUID id, String order, String owner, String item, String lot, String from) {
        }
        List<UUID> ids = claimable(siteId, user, 200);
        List<Candidate> picks = new ArrayList<>();
        for (UUID id : ids) {
            jdbc.sql("""
                            select id, order_ref, owner_id, item_no, coalesce(lot_no, ''), from_location from task
                            where id = :id and task_type = 'PICK'""")
                    .param("id", id)
                    .query((rs, n) -> picks.add(new Candidate(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6)))).list();
        }
        if (picks.isEmpty()) {
            return Map.of("mode", m, "tasks", List.of(), "orders", List.of());
        }
        List<UUID> chosen = new ArrayList<>();
        if ("CLUSTER".equals(m)) {
            java.util.LinkedHashSet<String> orders = new java.util.LinkedHashSet<>();
            for (Candidate c : picks) {
                if (orders.size() < limit || orders.contains(c.order())) {
                    orders.add(c.order());
                    chosen.add(c.id());
                }
            }
        } else {
            Candidate first = picks.getFirst();
            for (Candidate c : picks) {
                if (chosen.size() < limit && c.owner().equals(first.owner()) && c.item().equals(first.item())
                        && c.lot().equals(first.lot()) && c.from().equals(first.from())) {
                    chosen.add(c.id());
                }
            }
        }
        UUID group = UUID.randomUUID();
        for (UUID id : chosen) {
            assign(id, user);
            jdbc.sql("update task set pick_group = :g, pick_mode = :m where id = :id")
                    .param("g", group).param("m", m).param("id", id).update();
        }
        return group(siteId, chosen);
    }

    private Map<String, Object> group(String siteId, List<UUID> ids) {
        List<TaskView> tasks = ids.stream().map(id -> view(siteId, id)).toList();
        Map<String, Object> first = jdbc.sql("select pick_group, pick_mode from task where id = :id").param("id", ids.getFirst())
                .query().singleRow();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("groupId", first.get("pick_group"));
        out.put("mode", first.get("pick_mode"));
        out.put("orders", tasks.stream().map(TaskView::orderRef).distinct().toList());
        out.put("totalQty", tasks.stream().map(TaskView::qty).filter(java.util.Objects::nonNull)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
        out.put("tasks", tasks);
        return out;
    }

    private void assign(UUID id, String user) {
        jdbc.sql("update task set status = 'ASSIGNED', assigned_to = :user, assigned_at = :now, updated_at = :now where id = :id")
                .param("user", user).param("now", Timestamp.from(clock.instant())).param("id", id).update();
        event(id, "ASSIGNED", null);
    }

    /** Released tasks this operator may take, best first; locked so concurrent claims never share one. */
    private List<UUID> claimable(String siteId, String user, int limit) {
        List<String> types = taskTypesOfCurrentUser();
        if (types.isEmpty()) {
            return List.of();
        }
        AccessScope scope = AccessScope.current();
        // ADR-0021: the skill a task type requires and the equipment its zones require (labor policy).
        String[] profile = labor.profileOfCurrentUser();
        return jdbc.sql("""
                        select t.id from task t
                        left join ref_location f on f.site_id = t.site_id and f.location_id = t.from_location
                        where t.site_id = :site and t.status = 'RELEASED' and t.task_type in (:types)
                          and (:ownersAll or t.owner_id in (:owners) or t.owner_id = '')
                          and not (:user = any(t.excluded_users))
                          and (:zonesAll or t.task_type = 'RECEIVE' or exists (select 1 from ref_location l where l.site_id = t.site_id
                                 and l.location_id in (t.from_location, t.target_location) and l.zone_id in (:zones)))
                          and not exists (select 1 from task_standard s where s.site_id = t.site_id
                                 and s.task_type = t.task_type and s.required_skill is not null
                                 and not (s.required_skill = any(string_to_array(:skills, ','))))
                          and not exists (select 1 from ref_location l join zone_equipment z
                                 on z.site_id = l.site_id and z.zone_id = l.zone_id
                                 where l.site_id = t.site_id and l.location_id in (t.from_location, t.target_location)
                                   and not (z.equipment = any(string_to_array(:equipment, ','))))
                          and (t.automation_manual or not exists (select 1 from ref_location l join automation_zone a
                                 on a.site_id = l.site_id and a.zone_id = l.zone_id and a.enabled
                                 where l.site_id = t.site_id and l.location_id = t.from_location))
                        order by t.priority desc, f.pick_seq nulls last, t.from_location, t.created_at
                        limit :limit for update of t skip locked""")
                .param("equipment", profile[0]).param("skills", profile[1]).param("limit", limit)
                .param("site", siteId).param("user", user).param("types", types)
                .param("ownersAll", scope.ownersAll()).param("owners", scope.ownerList())
                .param("zonesAll", scope.zonesAll()).param("zones", scope.zoneList())
                .query(UUID.class).list();
    }

    /**
     * Confirms a putaway (PUT-002): the scanned LPN must be the task's; the scanned location must carry the right
     * check digit; a location other than the target is accepted only if it passes the engine's hard constraints.
     * Idempotent: confirming a completed task returns it unchanged.
     */
    public static final List<String> OVERRIDE_REASONS = List.of("LOCATION_FULL", "LOCATION_BLOCKED", "LOCATION_DAMAGED",
            "CLOSER_LOCATION", "CONSOLIDATE", "OTHER");

    @Transactional
    public TaskView confirm(String siteId, UUID taskId, String lpnId, String locationId, String checkDigit,
                            String overrideReason) {
        Task t = lockTask(siteId, taskId);
        if (!"PUTAWAY".equals(t.type())) {
            throw ApiException.unprocessable("TSK_WRONG_TYPE", "Task " + taskId + " is a " + t.type() + " task");
        }
        if ("COMPLETED".equals(t.status())) {
            return view(siteId, taskId);
        }
        String user = TenantContext.require().userId();
        if (!"ASSIGNED".equals(t.status()) || !user.equals(t.assignedTo())) {
            throw ApiException.conflict("TSK_NOT_ASSIGNED", "Task is " + t.status() + " and not assigned to " + user);
        }
        if (!t.lpnId().equals(lpnId) && !t.lpnId().equals(ssccOf(lpnId))) {
            throw ApiException.unprocessable("TSK_WRONG_LPN", "Scanned LPN " + lpnId + " but the task is for " + t.lpnId());   // PUT-EX-05
        }
        Projections.Location loc = projections.location(siteId, locationId).orElseThrow(() ->
                ApiException.unprocessable("TSK_LOCATION_UNKNOWN", "Location " + locationId + " is not known"));
        if (loc.checkDigit() == null || !loc.checkDigit().equals(checkDigit.trim())) {
            throw ApiException.unprocessable("TSK_CHECK_DIGIT_MISMATCH", "Check digit does not match location " + locationId);
        }
        requireActiveLabel(siteId, "LOCATION", locationId);
        String strategy = null;
        String reason = null;
        if (!locationId.equals(t.targetLocation())) {
            List<Stock> contents = projections.lpnContents(siteId, t.lpnId(), t.fromLocation());
            int reservedThere = reservations(siteId, t.id()).getOrDefault(locationId, 0);
            Optional<PutawayEngine.Rejection> rejection = engine.validate(siteId, contents, locationId, reservedThere);
            if (rejection.isPresent()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, rejection.get().code(), rejection.get().reason());
            }
            // ADR-0021: an override says why, so the task explains itself like the engine's own choice does.
            reason = overrideReason == null || overrideReason.isBlank() ? null : overrideReason.trim().toUpperCase();
            if (reason == null || !OVERRIDE_REASONS.contains(reason)) {
                throw ApiException.unprocessable("TSK_OVERRIDE_REASON_REQUIRED",
                        "Putting away to " + locationId + " instead of " + t.targetLocation() + " needs a reason: one of "
                                + OVERRIDE_REASONS);
            }
            strategy = "OVERRIDE";
        }
        UUID operation = inventory.moveLpn(siteId, "TSK-" + taskId, t.lpnId(), t.fromLocation(), locationId);
        jdbc.sql("""
                        update task set status = 'COMPLETED', confirmed_location = :loc, target_location = :loc,
                            inventory_operation_id = :op, strategy = coalesce(:strategy, strategy), completed_at = :now,
                            override_reason = :reason, updated_at = :now
                        where id = :id""")
                .param("loc", locationId).param("op", operation).param("strategy", strategy).param("reason", reason)
                .param("now", Timestamp.from(clock.instant())).param("id", taskId).update();
        event(taskId, "COMPLETED", (strategy != null ? "override (" + reason + ") of " + t.targetLocation() + " → " : "at ")
                + locationId);
        return view(siteId, taskId);
    }

    /** RF exception at the target: the location is excluded and the task re-planned (PUT-EX-02). */
    @Transactional
    public TaskView reportException(String siteId, UUID taskId, String reason, String detail) {
        Task t = lockTask(siteId, taskId);
        if (!OPEN.contains(t.status())) {
            throw ApiException.conflict("TSK_NOT_OPEN", "Task is " + t.status());
        }
        if (!"PUTAWAY".equals(t.type())) {
            throw ApiException.unprocessable("TSK_WRONG_TYPE", "Report shorts on pick tasks by confirming a lower quantity");
        }
        event(taskId, "EXCEPTION_REPORTED", reason + (detail == null ? "" : ": " + detail));
        if (REPLAN_REASONS.contains(reason)) {
            if (t.targetLocation() != null) {
                jdbc.sql("update task set excluded_locations = array_append(excluded_locations, :loc) where id = :id")
                        .param("loc", t.targetLocation()).param("id", taskId).update();
            }
            plan(lockTask(siteId, taskId));
        } else if ("LPN_NOT_FOUND".equals(reason)) {
            jdbc.sql("update task set status = 'EXCEPTION', exception_reason = :r, assigned_to = null, updated_at = :now where id = :id")
                    .param("r", reason).param("now", Timestamp.from(clock.instant())).param("id", taskId).update();
        } else {
            throw ApiException.unprocessable("TSK_REASON_UNKNOWN", "Unknown exception reason " + reason);
        }
        return view(siteId, taskId);
    }

    /** Supervisor: re-plan an EXCEPTION task after the cause was fixed (e.g. capacity freed, master data added). */
    @Transactional
    public TaskView replan(String siteId, UUID taskId) {
        Task t = lockTask(siteId, taskId);
        if (!OPEN.contains(t.status())) {
            throw ApiException.conflict("TSK_NOT_OPEN", "Task is " + t.status());
        }
        if (!"PUTAWAY".equals(t.type())) {
            throw ApiException.unprocessable("TSK_WRONG_TYPE", "Only putaway tasks are planned by the task service");
        }
        plan(t);
        return view(siteId, taskId);
    }

    // =====================================================================================================
    // Queries and helpers
    // =====================================================================================================

    @Transactional(readOnly = true)
    public List<TaskView> list(String siteId, String status) {
        return list(siteId, status, null, null);
    }

    /**
     * Tasks of a site, newest work first. {@code q} matches the document (delivery, order, RMA), item, LPN, location
     * or partner, case-insensitively; {@code type} filters the task type.
     */
    @Transactional(readOnly = true)
    public List<TaskView> list(String siteId, String status, String q, String type) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toUpperCase() + "%";
        List<UUID> ids = jdbc.sql("""
                        select id from task where site_id = :site and (cast(:status as text) is null or status = :status)
                          and (cast(:type as text) is null or task_type = :type)
                          and (:ownersAll or owner_id in (:owners) or owner_id = '')
                          and (cast(:q as text) is null or upper(coalesce(doc_no, '')) like :q
                               or upper(coalesce(order_ref, '')) like :q or upper(coalesce(item_no, '')) like :q
                               or upper(lpn_id) like :q or upper(coalesce(to_lpn, '')) like :q or upper(from_location) like :q
                               or upper(coalesce(target_location, '')) like :q or upper(coalesce(partner, '')) like :q
                               or upper(coalesce(expected_lines::text, '')) like :q)
                        order by case when status in ('RELEASED', 'ASSIGNED', 'EXCEPTION') then 0 else 1 end,
                                 priority desc, created_at desc limit 500""")
                .param("site", siteId).param("status", status).param("type", type).param("q", like)
                .param("ownersAll", AccessScope.current().ownersAll()).param("owners", AccessScope.current().ownerList())
                .query(UUID.class).list();
        List<TaskView> views = new ArrayList<>();
        ids.forEach(id -> views.add(view(siteId, id)));
        return views;
    }

    @Transactional(readOnly = true)
    public TaskView get(String siteId, UUID taskId) {
        return view(siteId, taskId);
    }

    private TaskView view(String siteId, UUID id) {
        TaskView base = jdbc.sql("""
                        select id, task_type, status, priority, owner_id, lpn_id, from_location, target_location, strategy,
                               exception_reason, assigned_to, confirmed_location, inventory_operation_id, created_at,
                               completed_at, allocation_id, order_ref, order_line_ref, item_no, lot_no, qty, uom, to_lpn,
                               qty_picked, count_id, count_sequence, suggested_location, receive_kind, doc_no, partner,
                               expected_lines::text, scans, override_reason, short_reason, short_action, assigned_at
                        from task where site_id = :site and id = :id""")
                .param("site", siteId).param("id", id)
                .query((rs, n) -> new TaskView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getString(11), rs.getString(12), rs.getObject(13, UUID.class), List.of(),
                        rs.getTimestamp(14).toInstant(), rs.getTimestamp(15) == null ? null : rs.getTimestamp(15).toInstant(),
                        rs.getObject(16, UUID.class), rs.getString(17), rs.getString(18), rs.getString(19), rs.getString(20),
                        strip(rs.getBigDecimal(21)), rs.getString(22), rs.getString(23), strip(rs.getBigDecimal(24)),
                        rs.getObject(25, UUID.class), (Integer) rs.getObject(26), rs.getString(27), rs.getString(28),
                        rs.getString(29), rs.getString(30),
                        rs.getString(31) == null ? null : json.readTree(rs.getString(31)), rs.getInt(32), rs.getString(33),
                        rs.getString(34), rs.getString(35),
                        rs.getTimestamp(36) == null ? null : rs.getTimestamp(36).toInstant()))
                .optional()
                .orElseThrow(() -> ApiException.notFound("TSK_UNKNOWN", "Task " + id + " not found"));
        String where = base.status().equals("COMPLETED") ? base.confirmedLocation() : base.fromLocation();
        // Only putaways show what is on the LPN; counts must stay blind (INV-003).
        List<Content> contents = !"PUTAWAY".equals(base.taskType()) ? List.of() : projections.lpnContents(siteId, base.lpnId(), where).stream()
                .map(s -> new Content(s.ownerId(), s.itemNo(), s.lotNo(), s.qty().stripTrailingZeros()))
                .toList();
        return new TaskView(base.id(), base.taskType(), base.status(), base.priority(), base.ownerId(), base.lpnId(),
                base.fromLocation(), base.targetLocation(), base.strategy(), base.exceptionReason(), base.assignedTo(),
                base.confirmedLocation(), base.inventoryOperationId(), contents, base.createdAt(), base.completedAt(),
                base.allocationId(), base.orderRef(), base.orderLineRef(), base.itemNo(), base.lotNo(), base.qty(),
                base.uom(), base.toLpn(), base.qtyPicked(), base.countId(), base.countSequence(),
                base.suggestedLocation(), base.receiveKind(), base.docNo(), base.partner(), base.expectedLines(),
                base.scans(), base.overrideReason(), base.shortReason(), base.shortAction(), base.assignedAt());
    }

    private static java.math.BigDecimal strip(java.math.BigDecimal v) {
        if (v == null) {
            return null;
        }
        java.math.BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    private Task lockTask(String siteId, UUID id) {
        return jdbc.sql(TASK + " where site_id = :site and id = :id for update")
                .param("site", siteId).param("id", id).query(TaskService::task).optional()
                .orElseThrow(() -> ApiException.notFound("TSK_UNKNOWN", "Task " + id + " not found"));
    }

    private Optional<Task> findOpenByLpn(String siteId, String lpn) {
        return jdbc.sql(TASK + " where site_id = :site and lpn_id = :lpn and task_type = 'PUTAWAY'"
                        + " and status in ('RELEASED', 'ASSIGNED', 'EXCEPTION') for update")
                .param("site", siteId).param("lpn", lpn).query(TaskService::task).optional();
    }

    private static final String TASK = """
            select id, site_id, status, owner_id, lpn_id, from_location, target_location, assigned_to,
                   excluded_locations, inventory_operation_id, task_type from task""";

    private static Task task(ResultSet rs, int n) throws SQLException {
        Array excluded = rs.getArray(9);
        return new Task(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8),
                excluded == null ? List.of() : List.of((String[]) excluded.getArray()), rs.getObject(10, UUID.class),
                rs.getString(11));
    }

    /** Task types the current user works on RF, from their roles. */
    static List<String> taskTypesOfCurrentUser() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return List.of();
        }
        java.util.Set<String> types = new java.util.LinkedHashSet<>();
        auth.getAuthorities().forEach(a -> {
            String role = a.getAuthority().startsWith("ROLE_") ? a.getAuthority().substring(5) : a.getAuthority();
            types.addAll(TASK_TYPES_BY_ROLE.getOrDefault(role, List.of()));
        });
        return List.copyOf(types);
    }

    private void setStatus(UUID id, String status, String reason) {
        jdbc.sql("update task set status = :s, exception_reason = :r, updated_at = :now where id = :id")
                .param("s", status).param("r", reason).param("now", Timestamp.from(clock.instant())).param("id", id).update();
    }

    private void event(UUID taskId, String event, String detail) {
        jdbc.sql("""
                        insert into task_event (tenant_id, task_id, event, detail, user_id, at)
                        values (:t, :task, :event, :detail, :user, :now)""")
                .param("t", TenantContext.tenantId()).param("task", taskId).param("event", event).param("detail", detail)
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant())).update();
    }
}
