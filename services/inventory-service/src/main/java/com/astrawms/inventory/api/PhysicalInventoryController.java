package com.astrawms.inventory.api;

import com.astrawms.inventory.service.PhysicalInventories;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Full physical inventory of a site or zones (ADR-0022). */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/inventory/physical-inventories")
public class PhysicalInventoryController {

    private final PhysicalInventories inventories;

    public PhysicalInventoryController(PhysicalInventories inventories) {
        this.inventories = inventories;
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable String siteId) {
        return inventories.list(siteId);
    }

    @GetMapping("/{piNo}")
    public Map<String, Object> detail(@PathVariable String siteId, @PathVariable String piNo) {
        return inventories.detail(siteId, piNo);
    }

    @PreAuthorize("hasAnyRole('INV_MANAGER','SUPERVISOR')")
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@PathVariable String siteId,
                                                      @RequestBody(required = false) PhysicalInventories.CreateRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventories.create(siteId,
                body == null ? new PhysicalInventories.CreateRequest(null, null, null) : body));
    }

    @PreAuthorize("hasAnyRole('INV_MANAGER','SUPERVISOR')")
    @PostMapping("/{piNo}/start")
    public Map<String, Object> start(@PathVariable String siteId, @PathVariable String piNo) {
        return inventories.start(siteId, piNo);
    }

    @PreAuthorize("hasAnyRole('INV_MANAGER','SUPERVISOR')")
    @PostMapping("/{piNo}/post")
    public Map<String, Object> post(@PathVariable String siteId, @PathVariable String piNo) {
        return inventories.post(siteId, piNo);
    }

    @PreAuthorize("hasAnyRole('INV_MANAGER','SUPERVISOR')")
    @PostMapping("/{piNo}/cancel")
    public Map<String, Object> cancel(@PathVariable String siteId, @PathVariable String piNo) {
        return inventories.cancel(siteId, piNo);
    }
}
