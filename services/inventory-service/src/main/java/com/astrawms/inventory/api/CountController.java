package com.astrawms.inventory.api;

import com.astrawms.inventory.service.CountService;
import com.astrawms.inventory.service.CountService.CountLine;
import com.astrawms.inventory.service.CountService.CountView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Cycle counts (§6.3): create ad hoc, submit count results (RF, via the task service), approve or reject variances. */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/inventory/counts")
public class CountController {

    private final CountService counts;

    public CountController(CountService counts) {
        this.counts = counts;
    }

    public record CreateRequest(@NotEmpty List<String> locationIds, String note) {
    }

    public record ResultRequest(List<CountLine> lines) {
    }

    public record RejectRequest(String note) {
    }

    @PreAuthorize("hasAnyRole('INV_ANALYST','INV_MANAGER','SUPERVISOR')")
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@PathVariable String siteId, @Valid @RequestBody CreateRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("countIds", counts.create(siteId, body.locationIds(), body.note())));
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable String siteId, @RequestParam(required = false) String status) {
        return counts.list(siteId, status);
    }

    /** System quantities and other counters' results are shown to inventory control only: counting stays blind. */
    @GetMapping("/{countId}")
    public CountView detail(@PathVariable String siteId, @PathVariable UUID countId) {
        CountView v = counts.view(siteId, countId);
        boolean control = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> CONTROL.contains(a.getAuthority()));
        return control ? v : new CountView(v.id(), v.siteId(), v.locationId(), v.trigger(), v.status(), v.countsDone(),
                v.requestedBy(), v.note(), null, v.decidedBy(), List.of(), List.of());
    }

    private static final java.util.Set<String> CONTROL = java.util.Set.of("ROLE_INV_ANALYST", "ROLE_INV_MANAGER",
            "ROLE_SUPERVISOR", "ROLE_QA_MANAGER", "ROLE_SOLUTION_ADMIN");

    /** Blind count result; idempotent per Idempotency-Key (the task service sends TSK-&lt;taskId&gt;). */
    @PreAuthorize("hasAnyRole('RECEIVER','PICKER','INV_ANALYST','SUPERVISOR','WMS_SERVICE')")
    @PostMapping("/{countId}/results")
    public CountService.SubmitResult submit(@PathVariable String siteId, @PathVariable UUID countId,
                            @RequestHeader("Idempotency-Key") String key, @RequestBody ResultRequest body) {
        return counts.submit(siteId, countId, key, body.lines());
    }

    @PreAuthorize("hasAnyRole('INV_MANAGER','SUPERVISOR')")
    @PostMapping("/{countId}/approve")
    public CountView approve(@PathVariable String siteId, @PathVariable UUID countId) {
        return counts.approve(siteId, countId);
    }

    @PreAuthorize("hasAnyRole('INV_MANAGER','SUPERVISOR')")
    @PostMapping("/{countId}/reject")
    public CountView reject(@PathVariable String siteId, @PathVariable UUID countId,
                            @RequestBody(required = false) RejectRequest body) {
        return counts.reject(siteId, countId, body == null ? null : body.note());
    }
}
