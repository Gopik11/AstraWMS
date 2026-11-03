package com.astrawms.task.api;

import com.astrawms.task.labels.LabelChecks;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Label-check policy of a site and the scans accepted without a label check (ADR-0029). */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/tasks/label-checks")
public class LabelCheckController {

    private final LabelChecks checks;

    public LabelCheckController(LabelChecks checks) {
        this.checks = checks;
    }

    public record PolicyRequest(String mode) {
    }

    public record ReviewRequest(String note) {
    }

    @GetMapping("/policy")
    public Map<String, Object> policy(@PathVariable String siteId) {
        return Map.of("siteId", siteId, "mode", checks.mode(siteId));
    }

    @PreAuthorize("hasAnyRole('SUPERVISOR','SOLUTION_ADMIN')")
    @PutMapping("/policy")
    public Map<String, Object> setPolicy(@PathVariable String siteId, @RequestBody PolicyRequest body) {
        return checks.setMode(siteId, body.mode());
    }

    @PreAuthorize("hasAnyRole('SUPERVISOR','SOLUTION_ADMIN','INV_MANAGER')")
    @GetMapping("/bypasses")
    public List<Map<String, Object>> bypasses(@PathVariable String siteId,
                                              @RequestParam(defaultValue = "true") boolean open) {
        return checks.bypasses(siteId, open);
    }

    @PreAuthorize("hasAnyRole('SUPERVISOR','INV_MANAGER')")
    @PostMapping("/bypasses/{id}/review")
    public Map<String, Object> review(@PathVariable String siteId, @PathVariable UUID id,
                                      @RequestBody(required = false) ReviewRequest body) {
        return checks.review(siteId, id, body == null ? null : body.note());
    }
}
