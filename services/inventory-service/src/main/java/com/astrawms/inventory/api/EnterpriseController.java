package com.astrawms.inventory.api;

import com.astrawms.inventory.service.CyclePlan;
import com.astrawms.inventory.service.EnterpriseInventory;
import com.astrawms.inventory.service.StoreReplenishment;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Enterprise inventory across sites (ADR-0025): item balance with in-transit, ownership, recall, value; predictive
 * store replenishment; the risk-based cycle count plan. The user's site and owner scope applies to every read.
 */
@RestController
public class EnterpriseController {

    private final EnterpriseInventory enterprise;
    private final StoreReplenishment replenishment;
    private final CyclePlan cycle;

    public EnterpriseController(EnterpriseInventory enterprise, StoreReplenishment replenishment, CyclePlan cycle) {
        this.enterprise = enterprise;
        this.replenishment = replenishment;
        this.cycle = cycle;
    }

    // ------------------------------------------------------------------ item balance, ownership, recall, value

    @GetMapping("/api/v1/network/items")
    public List<Map<String, Object>> items(@RequestParam(required = false) String q) {
        return enterprise.searchItems(q);
    }

    @GetMapping("/api/v1/network/items/{ownerId}/{itemNo}")
    public Map<String, Object> item(@PathVariable String ownerId, @PathVariable String itemNo) {
        return enterprise.itemBalance(ownerId, itemNo);
    }

    @GetMapping("/api/v1/network/owners")
    public List<Map<String, Object>> owners() {
        return enterprise.owners();
    }

    public record OwnerRequest(String name, String ownershipType) {
    }

    @PreAuthorize("hasAnyRole('SOLUTION_ADMIN','INV_MANAGER')")
    @PutMapping("/api/v1/network/owners/{ownerId}")
    public Map<String, Object> putOwner(@PathVariable String ownerId, @RequestBody OwnerRequest body) {
        return enterprise.putOwner(ownerId, body.name(), body.ownershipType());
    }

    @GetMapping("/api/v1/network/recall")
    public Map<String, Object> recall(@RequestParam String itemNo, @RequestParam(required = false) String lotNo,
                                      @RequestParam(required = false) String serialNo) {
        return enterprise.recall(itemNo, lotNo, serialNo);
    }

    @GetMapping("/api/v1/network/value")
    public List<Map<String, Object>> value() {
        return enterprise.value();
    }

    /** Units shipped per item from a site (customer and transfer goods issues, not material issues), for return rates. */
    @GetMapping("/api/v1/sites/{siteId}/inventory/shipped")
    public List<Map<String, Object>> shipped(@PathVariable String siteId, @RequestParam(defaultValue = "90") int days) {
        return enterprise.shipped(siteId, Math.max(1, Math.min(days, 730)));
    }

    // ------------------------------------------------------------------ store replenishment

    /** Recommendations for every store of the user's scope, or one ({@code siteId}). */
    @GetMapping("/api/v1/network/replenishment")
    public List<Map<String, Object>> recommendations(@RequestParam(required = false) String siteId) {
        return replenishment.recommendations(siteId == null || siteId.isBlank() ? null : siteId.trim().toUpperCase());
    }

    @GetMapping("/api/v1/sites/{siteId}/inventory/store-policies")
    public List<Map<String, Object>> storePolicies(@PathVariable String siteId) {
        return replenishment.policies(siteId);
    }

    @PreAuthorize("hasAnyRole('SOLUTION_ADMIN','INV_MANAGER','SUPERVISOR')")
    @PutMapping("/api/v1/sites/{siteId}/inventory/store-policies")
    public List<Map<String, Object>> putStorePolicy(@PathVariable String siteId,
                                                    @RequestBody StoreReplenishment.PolicyRequest body) {
        return replenishment.putPolicy(siteId, body);
    }

    /** A store's transit time from its source, in days (ADR-0025): the default for its item policies. */
    @GetMapping("/api/v1/sites/{siteId}/inventory/store-setting")
    public Map<String, Object> storeSetting(@PathVariable String siteId) {
        return replenishment.storeSetting(siteId);
    }

    public record StoreSettingRequest(Integer transitDays, Integer coverDays) {
    }

    @PreAuthorize("hasAnyRole('SOLUTION_ADMIN','INV_MANAGER','SUPERVISOR')")
    @PutMapping("/api/v1/sites/{siteId}/inventory/store-setting")
    public Map<String, Object> putStoreSetting(@PathVariable String siteId, @RequestBody StoreSettingRequest body) {
        return replenishment.putStoreSetting(siteId, body.transitDays(), body.coverDays());
    }

    @PreAuthorize("hasAnyRole('INV_MANAGER','SUPERVISOR')")
    @PostMapping("/api/v1/sites/{siteId}/inventory/store-replenishment/accept")
    public Map<String, Object> accept(@PathVariable String siteId, @RequestBody StoreReplenishment.AcceptRequest body) {
        return replenishment.accept(siteId, body);
    }

    // ------------------------------------------------------------------ cycle count plan

    @GetMapping("/api/v1/sites/{siteId}/inventory/counts/plan")
    public Map<String, Object> plan(@PathVariable String siteId) {
        return Map.of("frequency", cycle.frequency(siteId), "locations", cycle.plan(siteId));
    }

    @PreAuthorize("hasAnyRole('INV_MANAGER','SOLUTION_ADMIN')")
    @PutMapping("/api/v1/sites/{siteId}/inventory/counts/plan/frequency")
    public CyclePlan.Frequency putFrequency(@PathVariable String siteId, @RequestBody CyclePlan.Frequency body) {
        return cycle.putFrequency(siteId, body);
    }

    @PreAuthorize("hasAnyRole('INV_ANALYST','INV_MANAGER','SUPERVISOR')")
    @PostMapping("/api/v1/sites/{siteId}/inventory/counts/plan/open")
    public Map<String, Object> openDue(@PathVariable String siteId, @RequestParam(defaultValue = "20") int limit) {
        return cycle.openDue(siteId, Math.max(1, Math.min(limit, 200)), null);
    }
}
