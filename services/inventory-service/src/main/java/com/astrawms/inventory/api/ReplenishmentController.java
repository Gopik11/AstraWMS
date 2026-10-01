package com.astrawms.inventory.api;

import com.astrawms.inventory.api.InventoryDtos.OperationResult;
import com.astrawms.inventory.service.InventoryCommandService;
import com.astrawms.inventory.service.Replenishments;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Replenishment (§7): min/max rules per forward location and item, open replenishments, RF confirmation. */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/inventory")
public class ReplenishmentController {

    private final Replenishments replenishments;
    private final InventoryCommandService commands;

    public ReplenishmentController(Replenishments replenishments, InventoryCommandService commands) {
        this.replenishments = replenishments;
        this.commands = commands;
    }

    public record RuleRequest(@NotNull BigDecimal minQty, @NotNull BigDecimal maxQty, Boolean active) {
    }

    @GetMapping("/replenishment-rules")
    public List<Map<String, Object>> rules(@PathVariable String siteId) {
        return replenishments.rules(siteId);
    }

    @PreAuthorize("hasAnyRole('SOLUTION_ADMIN','INV_MANAGER')")
    @PutMapping("/replenishment-rules/{locationId}/{ownerId}/{itemNo}")
    public Replenishments.Rule putRule(@PathVariable String siteId, @PathVariable String locationId,
                                       @PathVariable String ownerId, @PathVariable String itemNo,
                                       @RequestBody RuleRequest body) {
        return replenishments.putRule(siteId, locationId, ownerId, itemNo, body.minQty(), body.maxQty(),
                body.active() == null || body.active());
    }

    @GetMapping("/replenishments")
    public List<Map<String, Object>> list(@PathVariable String siteId, @RequestParam(required = false) String status) {
        return replenishments.list(siteId, status);
    }

    /** Top-off run: evaluates every rule of the site now. */
    @PreAuthorize("hasAnyRole('SUPERVISOR','INV_MANAGER','SOLUTION_ADMIN')")
    @PostMapping("/replenishments/evaluate")
    public Map<String, Integer> evaluate(@PathVariable String siteId) {
        return Map.of("created", replenishments.evaluateAll(siteId));
    }

    @PreAuthorize("hasAnyRole('RECEIVER','PICKER','SUPERVISOR','WMS_SERVICE')")
    @PostMapping("/replenishments/{replenishmentId}/confirm")
    public ResponseEntity<OperationResult> confirm(@PathVariable String siteId, @PathVariable UUID replenishmentId,
                                                   @RequestHeader("Idempotency-Key") String key) {
        OperationResult r = commands.confirmReplenishment(siteId, replenishmentId, key);
        return ResponseEntity.status(r.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(r);
    }
}
