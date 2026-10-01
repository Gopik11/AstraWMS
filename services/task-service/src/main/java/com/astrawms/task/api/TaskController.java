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

    public TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    @GetMapping
    public List<TaskView> list(@PathVariable String siteId, @RequestParam(required = false) String status) {
        return tasks.list(siteId, status);
    }

    @GetMapping("/{taskId}")
    public TaskView get(@PathVariable String siteId, @PathVariable UUID taskId) {
        return tasks.get(siteId, taskId);
    }

    /** 200 with the operator's task, or 204 when there is no work. */
    @PreAuthorize("hasAnyRole('RECEIVER','PICKER','SUPERVISOR')")
    @PostMapping("/next")
    public ResponseEntity<TaskView> next(@PathVariable String siteId) {
        return tasks.next(siteId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PreAuthorize("hasAnyRole('RECEIVER','PICKER','SUPERVISOR')")
    @PostMapping("/{taskId}/confirm")
    public TaskView confirm(@PathVariable String siteId, @PathVariable UUID taskId, @Valid @RequestBody ConfirmRequest body) {
        return tasks.confirm(siteId, taskId, body.lpnId(), body.locationId(), body.checkDigit());
    }

    @PreAuthorize("hasAnyRole('PICKER','SUPERVISOR')")
    @PostMapping("/{taskId}/pick")
    public TaskView pick(@PathVariable String siteId, @PathVariable UUID taskId,
                         @Valid @RequestBody TaskDtos.PickConfirmRequest body) {
        return tasks.confirmPick(siteId, taskId, body.checkDigit(), body.qty(), body.serials());
    }

    @PreAuthorize("hasAnyRole('RECEIVER','PICKER','SUPERVISOR')")
    @PostMapping("/{taskId}/exception")
    public TaskView exception(@PathVariable String siteId, @PathVariable UUID taskId,
                              @Valid @RequestBody ExceptionRequest body) {
        return tasks.reportException(siteId, taskId, body.reason(), body.detail());
    }

    @PreAuthorize("hasRole('SUPERVISOR')")
    @PostMapping("/{taskId}/replan")
    public TaskView replan(@PathVariable String siteId, @PathVariable UUID taskId) {
        return tasks.replan(siteId, taskId);
    }
}
