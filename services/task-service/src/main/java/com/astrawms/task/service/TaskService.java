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

    /**
     * Which task types each RF role works (ADR-0019); SUPERVISOR works all. A user with several roles gets the union.
     */
    static final Map<String, List<String>> TASK_TYPES_BY_ROLE = Map.of(
            "RECEIVER", List.of("RECEIVE", "PUTAWAY", "RETURN", "REPLEN"),
            "PICKER", List.of("PICK", "RETURN", "REPLEN", "COUNT"),
            "INV_ANALYST", List.of("COUNT", "REPLEN"),
            "SUPERVISOR", List.of("RECEIVE", "PUTAWAY", "PICK", "RETURN", "REPLEN", "COUNT"));

    public TaskService(JdbcClient jdbc, Projections projections, PutawayEngine engine, InventoryClient inventory,
                       OutboxWriter outbox, Clock clock, com.astrawms.task.inbound.InboundClient inbound,
                       tools.jackson.databind.json.JsonMapper json) {
        this.inbound = inbound;
        this.json = json;
        this.jdbc = jdbc;
        this.projections = projections;
        this.engine = engine;
        this.inventory = inventory;
        this.outbox = outbox;
        this.clock = clock;
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

    /** The document was closed or cancelled elsewhere: its open receiving task is cancelled. */
    @Transactional
    public void onReceiveEnded(String siteId, com.astrawms.common.contracts.ReceivingContracts.ReceiveEnded e) {
        jdbc.sql("""
                        update task set status = 'CANCELLED', exception_reason = :reason, updated_at = :now
                        where site_id = :site and task_type = 'RECEIVE' and receive_kind = :kind and doc_no = :doc
                          and status in ('RELEASED', 'ASSIGNED', 'EXCEPTION')""")
                .param("reason", e.reason()).param("now", Timestamp.from(clock.instant())).param("site", siteId)
                .param("kind", e.kind()).param("doc", e.docNo()).update();
    }

    /** One RF receiving scan. {@code scanId} is generated by the device per scan and makes a retried scan harmless. */
    public record ReceiveScan(String scanId, String docNo, String itemNo, String ownerId, java.math.BigDecimal qty,
                              String uom, String lotNo, java.time.LocalDate expiryDate, List<String> serials,
                              String lpnId, String locationId, String checkDigit, String conditionGrade,
                              String disposition, String returnReason, String overrideReason) {
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
    public ReceiveScanResult confirmReceive(String siteId, UUID taskId, ReceiveScan s) {
        Receiving t = lockReceiving(siteId, taskId);
        if (s.docNo() == null || !t.docNo().equals(s.docNo().trim())) {
            throw ApiException.unprocessable("TSK_WRONG_DOCUMENT",
                    "Scanned document " + s.docNo() + " but the task is for " + t.docNo());
        }
        Projections.Location loc = projections.location(siteId, s.locationId()).orElseThrow(() ->
                ApiException.unprocessable("TSK_LOCATION_UNKNOWN", "Location " + s.locationId() + " is not known"));
        if (loc.checkDigit() == null || s.checkDigit() == null || !loc.checkDigit().equals(s.checkDigit().trim())) {
            throw ApiException.unprocessable("TSK_CHECK_DIGIT_MISMATCH", "Check digit does not match location " + s.locationId());
        }
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

    /**
     * RF pick: the scanned check digit must belong to the source location; picking less than requested is a short
     * pick that releases the remainder (PCK-003). Idempotent via the inventory key {@code TSK-<taskId>}.
     */
    @Transactional
    public TaskView confirmPick(String siteId, UUID taskId, String checkDigit, java.math.BigDecimal qty,
                                List<String> serials) {
        record Pick(String status, String type, String assignedTo, String from, String to, String toLpn,
                    UUID allocation, String order, String line, java.math.BigDecimal requested) {
        }
        Pick p = jdbc.sql("""
                        select status, task_type, assigned_to, from_location, target_location, to_lpn, allocation_id,
                               order_ref, order_line_ref, qty
                        from task where site_id = :site and id = :id for update""")
                .param("site", siteId).param("id", taskId)
                .query((rs, n) -> new Pick(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8), rs.getString(9),
                        rs.getBigDecimal(10)))
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
        if (qty.compareTo(p.requested()) > 0) {
            throw ApiException.unprocessable("TSK_PICK_QTY_EXCEEDS", "Requested " + p.requested().stripTrailingZeros().toPlainString());
        }
        boolean shortPick = qty.compareTo(p.requested()) < 0;
        UUID operation = inventory.pick(siteId, "TSK-" + taskId, p.allocation(), qty, p.to(), p.toLpn(), serials, shortPick);
        Instant now = clock.instant();
        jdbc.sql("""
                        update task set status = 'COMPLETED', qty_picked = :qty, confirmed_location = target_location,
                            inventory_operation_id = :op, exception_reason = :short, completed_at = :now, updated_at = :now
                        where id = :id""")
                .param("qty", qty).param("op", operation).param("short", shortPick ? "SHORT_PICK" : null)
                .param("now", Timestamp.from(now)).param("id", taskId).update();
        event(taskId, "COMPLETED", "picked " + qty.toPlainString() + (shortPick ? " (short)" : ""));
        outbox.append(new OutboxWriter.Message(OutboundContracts.TOPIC_TASK_EVENTS, OutboundContracts.TaskCompleted.TYPE,
                OutboundContracts.TaskCompleted.VERSION, null, siteId, null, siteId + ":" + p.order(),
                new OutboundContracts.TaskCompleted(taskId, "PICK", p.allocation(), p.order(), p.line(), qty,
                        p.requested().subtract(qty), user, now)));
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
        List<String> types = taskTypesOfCurrentUser();
        if (types.isEmpty()) {
            return Optional.empty();
        }
        AccessScope scope = AccessScope.current();
        Optional<UUID> next = jdbc.sql("""
                        select t.id from task t
                        left join ref_location f on f.site_id = t.site_id and f.location_id = t.from_location
                        where t.site_id = :site and t.status = 'RELEASED' and t.task_type in (:types)
                          and (:ownersAll or t.owner_id in (:owners) or t.owner_id = '')
                          and not (:user = any(t.excluded_users))
                          and (:zonesAll or t.task_type = 'RECEIVE' or exists (select 1 from ref_location l where l.site_id = t.site_id
                                 and l.location_id in (t.from_location, t.target_location) and l.zone_id in (:zones)))
                        order by t.priority desc, f.pick_seq nulls last, t.from_location, t.created_at
                        limit 1 for update of t skip locked""")
                .param("site", siteId).param("user", user).param("types", types)
                .param("ownersAll", scope.ownersAll()).param("owners", scope.ownerList())
                .param("zonesAll", scope.zonesAll()).param("zones", scope.zoneList())
                .query(UUID.class).optional();
        next.ifPresent(id -> {
            jdbc.sql("update task set status = 'ASSIGNED', assigned_to = :user, assigned_at = :now, updated_at = :now where id = :id")
                    .param("user", user).param("now", Timestamp.from(clock.instant())).param("id", id).update();
            event(id, "ASSIGNED", null);
        });
        return next.map(id -> view(siteId, id));
    }

    /**
     * Confirms a putaway (PUT-002): the scanned LPN must be the task's; the scanned location must carry the right
     * check digit; a location other than the target is accepted only if it passes the engine's hard constraints.
     * Idempotent: confirming a completed task returns it unchanged.
     */
    @Transactional
    public TaskView confirm(String siteId, UUID taskId, String lpnId, String locationId, String checkDigit) {
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
        if (!t.lpnId().equals(lpnId)) {
            throw ApiException.unprocessable("TSK_WRONG_LPN", "Scanned LPN " + lpnId + " but the task is for " + t.lpnId());   // PUT-EX-05
        }
        Projections.Location loc = projections.location(siteId, locationId).orElseThrow(() ->
                ApiException.unprocessable("TSK_LOCATION_UNKNOWN", "Location " + locationId + " is not known"));
        if (loc.checkDigit() == null || !loc.checkDigit().equals(checkDigit.trim())) {
            throw ApiException.unprocessable("TSK_CHECK_DIGIT_MISMATCH", "Check digit does not match location " + locationId);
        }
        String strategy = null;
        if (!locationId.equals(t.targetLocation())) {
            List<Stock> contents = projections.lpnContents(siteId, t.lpnId(), t.fromLocation());
            int reservedThere = reservations(siteId, t.id()).getOrDefault(locationId, 0);
            Optional<PutawayEngine.Rejection> rejection = engine.validate(siteId, contents, locationId, reservedThere);
            if (rejection.isPresent()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, rejection.get().code(), rejection.get().reason());
            }
            strategy = "OVERRIDE";
        }
        UUID operation = inventory.moveLpn(siteId, "TSK-" + taskId, t.lpnId(), t.fromLocation(), locationId);
        jdbc.sql("""
                        update task set status = 'COMPLETED', confirmed_location = :loc, target_location = :loc,
                            inventory_operation_id = :op, strategy = coalesce(:strategy, strategy), completed_at = :now,
                            updated_at = :now
                        where id = :id""")
                .param("loc", locationId).param("op", operation).param("strategy", strategy)
                .param("now", Timestamp.from(clock.instant())).param("id", taskId).update();
        event(taskId, "COMPLETED", (strategy != null ? "override of " + t.targetLocation() + " → " : "at ") + locationId);
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
                               or upper(lpn_id) like :q or upper(from_location) like :q
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
                               expected_lines::text, scans
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
                        rs.getString(31) == null ? null : json.readTree(rs.getString(31)), rs.getInt(32)))
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
                base.scans());
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
