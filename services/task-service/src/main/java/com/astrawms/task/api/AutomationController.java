package com.astrawms.task.api;

import com.astrawms.task.api.TaskDtos.TaskView;
import com.astrawms.task.service.Automation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Automation adapter API (ADR-0021): device controllers (role AUTOMATION) claim, confirm and raise exceptions on the
 * tasks of automated zones. Supervisors may call it too (simulating a device, or recovering one).
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/tasks/automation")
public class AutomationController {

    private final Automation automation;

    public AutomationController(Automation automation) {
        this.automation = automation;
    }

    public record ZoneRequest(String deviceType, Boolean enabled) {
    }

    public record ClaimRequest(String deviceId, String zoneId, List<String> taskTypes) {
    }

    public record ConfirmRequest(String deviceId, BigDecimal qty, String shortReason, String shortAction) {
    }

    public record ExceptionRequest(String deviceId, String reason, String detail) {
    }

    @GetMapping("/zones")
    public List<Map<String, Object>> zones(@PathVariable String siteId) {
        return automation.zones(siteId);
    }

    @PreAuthorize("hasAnyRole('SOLUTION_ADMIN','SUPERVISOR')")
    @PutMapping("/zones/{zoneId}")
    public List<Map<String, Object>> putZone(@PathVariable String siteId, @PathVariable String zoneId,
                                             @RequestBody ZoneRequest body) {
        return automation.putZone(siteId, zoneId, body.deviceType(), body.enabled());
    }

    /** 200 with the device's task, or 204 when the automated zones have no work. */
    @PreAuthorize("hasAnyRole('AUTOMATION','SUPERVISOR')")
    @PostMapping("/claim")
    public ResponseEntity<Automation.DeviceTask> claim(@PathVariable String siteId, @RequestBody ClaimRequest body) {
        return automation.claim(siteId, body.deviceId(), body.zoneId(), body.taskTypes()).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PreAuthorize("hasAnyRole('AUTOMATION','SUPERVISOR')")
    @PostMapping("/tasks/{taskId}/confirm")
    public TaskView confirm(@PathVariable String siteId, @PathVariable UUID taskId, @RequestBody ConfirmRequest body) {
        return automation.confirm(siteId, taskId, body.deviceId(), body.qty(), body.shortReason(), body.shortAction());
    }

    @PreAuthorize("hasAnyRole('AUTOMATION','SUPERVISOR')")
    @PostMapping("/tasks/{taskId}/exception")
    public TaskView exception(@PathVariable String siteId, @PathVariable UUID taskId, @RequestBody ExceptionRequest body) {
        return automation.exception(siteId, taskId, body.deviceId(), body.reason(), body.detail());
    }
}
