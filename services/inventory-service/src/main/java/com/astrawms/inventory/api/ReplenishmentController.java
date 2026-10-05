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
    private final com.astrawms.inventory.service.Slotting slotting;

    public ReplenishmentController(Replenishments replenishments, InventoryCommandService commands,
                                   com.astrawms.inventory.service.Slotting slotting) {
        this.replenishments = replenishments;
        this.commands = commands;
        this.slotting = slotting;
    }

    // ------------------------------------------------------------------ slotting (ADR-0021)

    public record SlottingRequest(String reserveZone, BigDecimal unitsPerPallet, String velocityClass) {
    }

    public record ReslotRequest(String toLocation, BigDecimal minQty, BigDecimal maxQty) {
    }

    /** Item–location master with 30-day velocity and golden-zone suggestions. */
    @GetMapping("/slotting")
    public List<Map<String, Object>> slotting(@PathVariable String siteId) {
        return slotting.analysis(siteId);
    }

    @PreAuthorize("hasAnyRole('SOLUTION_ADMIN','INV_MANAGER')")
    @PutMapping("/slotting/{ownerId}/{itemNo}")
    public Map<String, Object> putSlotting(@PathVariable String siteId, @PathVariable String ownerId,
                                           @PathVariable String itemNo, @RequestBody SlottingRequest body) {
        return slotting.put(siteId, ownerId, itemNo, body.reserveZone(), body.unitsPerPallet(), body.velocityClass());
    }

    /** Moves the item's pick face; free stock left at the old face becomes RF MOVE tasks. */
    @PreAuthorize("hasAnyRole('SOLUTION_ADMIN','INV_MANAGER')")
    @PostMapping("/slotting/{ownerId}/{itemNo}/reslot")
    public Map<String, Object> reslot(@PathVariable String siteId, @PathVariable String ownerId,
                                      @PathVariable String itemNo, @RequestBody ReslotRequest body) {
        return slotting.reslot(siteId, ownerId, itemNo, body.toLocation(), body.minQty(), body.maxQty());
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

    /** An item's pick faces (free, capacity, open replenishments) and the reserve it is replenished from (ADR-0028). */
    @GetMapping("/faces/{ownerId}/{itemNo}")
    public Map<String, Object> faces(@PathVariable String siteId, @PathVariable String ownerId, @PathVariable String itemNo) {
        return replenishments.faces(siteId, ownerId, itemNo);
    }

    /** Replenish an item's faces now: an open replenishment is kept (never a second task). */
    @PreAuthorize("hasAnyRole('SUPERVISOR','INV_MANAGER')")
    @PostMapping("/faces/{ownerId}/{itemNo}/replenish")
    public List<Map<String, Object>> replenishFaces(@PathVariable String siteId, @PathVariable String ownerId,
                                                    @PathVariable String itemNo) {
        return replenishments.replenishFaces(siteId, ownerId, itemNo);
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
