package com.astrawms.task.api;

import com.astrawms.task.api.TaskDtos.ConfirmRequest;
import com.astrawms.task.api.TaskDtos.ExceptionRequest;
import com.astrawms.task.api.TaskDtos.TaskView;
import com.astrawms.task.service.TaskService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Task API v1: RF execution (next / confirm / exception) and supervisor views. The user comes from X-User-Id. */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/tasks")
public class TaskController {

    private final TaskService tasks;
    private final com.astrawms.task.service.Labor labor;

    public TaskController(TaskService tasks, com.astrawms.task.service.Labor labor) {
        this.tasks = tasks;
        this.labor = labor;
    }

    // ------------------------------------------------------------------ labor (ADR-0021)

    /** The supervisor's labor board: operators against standard, current task age, backlog in standard hours. */
    @PreAuthorize("hasAnyRole('SUPERVISOR','SOLUTION_ADMIN')")
    @GetMapping("/labor")
    public java.util.Map<String, Object> labor(@PathVariable String siteId, @RequestParam(defaultValue = "8") int hours) {
        return labor.board(siteId, Math.max(1, Math.min(hours, 72)));
    }

    @GetMapping("/standards")
    public List<com.astrawms.task.service.Labor.Standard> standards(@PathVariable String siteId) {
        return labor.standards(siteId);
    }

    public record StandardRequest(Integer baseSeconds, java.math.BigDecimal perUnitSeconds, String requiredSkill) {
    }

    @PreAuthorize("hasAnyRole('SUPERVISOR','SOLUTION_ADMIN')")
    @org.springframework.web.bind.annotation.PutMapping("/standards/{taskType}")
    public List<com.astrawms.task.service.Labor.Standard> putStandard(@PathVariable String siteId,
                                                                      @PathVariable String taskType,
                                                                      @RequestBody StandardRequest body) {
        return labor.putStandard(siteId, taskType, body.baseSeconds(), body.perUnitSeconds(), body.requiredSkill());
    }

    @GetMapping("/operators")
    public List<java.util.Map<String, Object>> operators(@PathVariable String siteId) {
        return labor.operators();
    }

    public record OperatorRequest(List<String> equipment, List<String> skills) {
    }

    @PreAuthorize("hasAnyRole('SUPERVISOR','SOLUTION_ADMIN')")
    @org.springframework.web.bind.annotation.PutMapping("/operators/{userId}")
    public java.util.Map<String, Object> putOperator(@PathVariable String siteId, @PathVariable String userId,
                                                     @RequestBody OperatorRequest body) {
        return labor.putOperator(userId, body.equipment(), body.skills());
    }

    @GetMapping("/zone-equipment")
    public List<java.util.Map<String, Object>> zoneEquipment(@PathVariable String siteId) {
        return labor.zoneEquipment(siteId);
    }

    public record ZoneEquipmentRequest(String equipment) {
    }

    @PreAuthorize("hasAnyRole('SUPERVISOR','SOLUTION_ADMIN')")
    @org.springframework.web.bind.annotation.PutMapping("/zone-equipment/{zoneId}")
    public List<java.util.Map<String, Object>> putZoneEquipment(@PathVariable String siteId, @PathVariable String zoneId,
                                                                @RequestBody ZoneEquipmentRequest body) {
        return labor.putZoneEquipment(siteId, zoneId, body.equipment());
    }

    @GetMapping
    public List<TaskView> list(@PathVariable String siteId, @RequestParam(required = false) String status,
                               @RequestParam(required = false) String q, @RequestParam(required = false) String type) {
        return tasks.list(siteId, status, q, type);
    }

    @GetMapping("/{taskId}")
    public TaskView get(@PathVariable String siteId, @PathVariable UUID taskId) {
        return tasks.get(siteId, taskId);
    }

    /** 200 with the operator's task, or 204 when there is no work. */
    @PreAuthorize("hasAnyRole('RECEIVER','PICKER','INV_ANALYST','SUPERVISOR')")
    @PostMapping("/next")
    public ResponseEntity<TaskView> next(@PathVariable String siteId) {
        return tasks.next(siteId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PreAuthorize("hasAnyRole('RECEIVER','PICKER','SUPERVISOR')")
    @PostMapping("/{taskId}/confirm")
    public TaskView confirm(@PathVariable String siteId, @PathVariable UUID taskId, @Valid @RequestBody ConfirmRequest body) {
        return tasks.confirm(siteId, taskId, body.lpnId(), body.locationId(), body.checkDigit(), body.overrideReason());
    }

    @PreAuthorize("hasAnyRole('RECEIVER','SUPERVISOR')")
    @PostMapping("/{taskId}/receive")
    public TaskService.ReceiveScanResult receive(@PathVariable String siteId, @PathVariable UUID taskId,
                                                 @Valid @RequestBody TaskDtos.ReceiveRequest b) {
        return tasks.confirmReceive(siteId, taskId, new TaskService.ReceiveScan(b.scanId(), b.docNo(), b.itemNo(),
                b.ownerId(), b.qty(), b.uom(), b.lotNo(), b.expiryDate(), b.serials(), b.lpnId(), b.locationId(),
                b.checkDigit(), b.conditionGrade(), b.disposition(), b.returnReason(), b.overrideReason()));
    }

    @PreAuthorize("hasAnyRole('RECEIVER','SUPERVISOR')")
    @PostMapping("/{taskId}/receive/close")
    public TaskService.ReceiveScanResult closeReceive(@PathVariable String siteId, @PathVariable UUID taskId,
                                                      @RequestBody(required = false) TaskDtos.ReceiveCloseRequest b) {
        return tasks.closeReceive(siteId, taskId, b == null ? null : b.shortReasons());
    }

    /** Offline work (ADR-0023): the operator's tasks plus up to {@code count} more, assigned to them for the device. */
    @PreAuthorize("hasAnyRole('RECEIVER','PICKER','INV_ANALYST','SUPERVISOR')")
    @PostMapping("/claim-batch")
    public List<TaskView> claimBatch(@PathVariable String siteId, @RequestParam(defaultValue = "10") int count) {
        return tasks.claimBatch(siteId, count);
    }

    @PreAuthorize("hasAnyRole('RECEIVER','PICKER','INV_ANALYST','SUPERVISOR')")
    @PostMapping("/{taskId}/release")
    public TaskView release(@PathVariable String siteId, @PathVariable UUID taskId) {
        return tasks.release(siteId, taskId);
    }

    /** Supervisor unassign (ADR-0024): back to the queue whoever holds it; the reason goes on the task history. */
    @PreAuthorize("hasRole('SUPERVISOR')")
    @PostMapping("/{taskId}/unassign")
    public TaskView unassign(@PathVariable String siteId, @PathVariable UUID taskId,
                             @RequestBody(required = false) java.util.Map<String, String> body) {
        return tasks.unassign(siteId, taskId, body == null ? null : body.get("reason"));
    }

    /** A confirmation sent from an offline device was refused: the server wins and the task becomes an exception. */
    @PreAuthorize("hasAnyRole('RECEIVER','PICKER','INV_ANALYST','SUPERVISOR')")
    @PostMapping("/{taskId}/sync-conflict")
    public TaskView syncConflict(@PathVariable String siteId, @PathVariable UUID taskId,
                                 @RequestBody(required = false) java.util.Map<String, String> body) {
        return tasks.syncConflict(siteId, taskId, body == null ? null : body.get("detail"));
    }

    /** The "stale assigned" tile's action: unassigns tasks assigned longer than {@code minutes} ago. */
    @PreAuthorize("hasRole('SUPERVISOR')")
    @PostMapping("/unassign-stale")
    public List<TaskView> unassignStale(@PathVariable String siteId, @RequestParam(required = false) String type,
                                        @RequestParam(defaultValue = "30") int minutes) {
        return tasks.unassignStale(siteId, type, Math.max(1, minutes));
    }

    @PreAuthorize("hasAnyRole('RECEIVER','PICKER','INV_ANALYST','SUPERVISOR')")
    @PostMapping("/{taskId}/move")
    public TaskView move(@PathVariable String siteId, @PathVariable UUID taskId,
                         @Valid @RequestBody TaskDtos.ReplenConfirmRequest body) {
        return tasks.confirmMove(siteId, taskId, body.checkDigit());
    }

    @PreAuthorize("hasAnyRole('PICKER','SUPERVISOR')")
    @PostMapping("/{taskId}/pick")
    public TaskView pick(@PathVariable String siteId, @PathVariable UUID taskId,
                         @Valid @RequestBody TaskDtos.PickConfirmRequest body) {
        return tasks.confirmPick(siteId, taskId, body.checkDigit(), body.item(), body.qty(), body.serials(),
                body.shortReason(), body.shortAction());
    }

    @PreAuthorize("hasAnyRole('RECEIVER','PICKER','SUPERVISOR')")
    @PostMapping("/{taskId}/replenish")
    public TaskView replenish(@PathVariable String siteId, @PathVariable UUID taskId,
                              @Valid @RequestBody TaskDtos.ReplenConfirmRequest body) {
        return tasks.confirmReplenishment(siteId, taskId, body.checkDigit());
    }

    @PreAuthorize("hasAnyRole('RECEIVER','PICKER','INV_ANALYST','SUPERVISOR')")
    @PostMapping("/{taskId}/count")
    public TaskView count(@PathVariable String siteId, @PathVariable UUID taskId,
                          @Valid @RequestBody TaskDtos.CountConfirmRequest body) {
        return tasks.confirmCount(siteId, taskId, body.checkDigit(), body.lines() == null ? List.of() : body.lines());
    }

    @PreAuthorize("hasAnyRole('RECEIVER','PICKER','SUPERVISOR')")
    @PostMapping("/{taskId}/return")
    public TaskView returnToStock(@PathVariable String siteId, @PathVariable UUID taskId,
                                  @Valid @RequestBody TaskDtos.ReturnConfirmRequest body) {
        return tasks.confirmReturn(siteId, taskId, body.checkDigit());
    }

    @PreAuthorize("hasAnyRole('RECEIVER','PICKER','SUPERVISOR')")
    @PostMapping("/{taskId}/exception")
    public TaskView exception(@PathVariable String siteId, @PathVariable UUID taskId,
                              @Valid @RequestBody ExceptionRequest body) {
        return tasks.reportException(siteId, taskId, body.reason(), body.detail());
    }

    /** Dock sweep (ADR-0020): putaways for everything still at inbound staging. */
    @PreAuthorize("hasRole('SUPERVISOR')")
    @PostMapping("/sweep-dock")
    public TaskService.SweepResult sweepDock(@PathVariable String siteId) {
        return tasks.sweepDock(siteId);
    }

    @PreAuthorize("hasRole('SUPERVISOR')")
    @PostMapping("/{taskId}/replan")
    public TaskView replan(@PathVariable String siteId, @PathVariable UUID taskId) {
        return tasks.replan(siteId, taskId);
    }
}
