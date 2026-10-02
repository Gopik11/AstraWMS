package com.astrawms.inventory.api;

import com.astrawms.inventory.service.Billing;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 3PL billing (ADR-0021): rates, capture from the ledger, value-added services, totals and events. */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/inventory/billing")
@PreAuthorize("hasAnyRole('SOLUTION_ADMIN','INV_MANAGER','SUPERVISOR')")
public class BillingController {

    private final Billing billing;

    public BillingController(Billing billing) {
        this.billing = billing;
    }

    @GetMapping("/rates")
    public List<Map<String, Object>> rates(@PathVariable String siteId) {
        return billing.rates(siteId);
    }

    @PreAuthorize("hasRole('SOLUTION_ADMIN')")
    @PutMapping("/rates")
    public List<Map<String, Object>> putRate(@PathVariable String siteId, @RequestBody Billing.RateRequest body) {
        return billing.putRate(siteId, body);
    }

    @PostMapping("/capture")
    public Map<String, Object> capture(@PathVariable String siteId) {
        return billing.capture(siteId);
    }

    @PostMapping("/vas")
    public ResponseEntity<Map<String, Object>> vas(@PathVariable String siteId, @RequestBody Billing.VasRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(billing.vas(siteId, body));
    }

    @GetMapping("/summary")
    public List<Map<String, Object>> summary(@PathVariable String siteId, @RequestParam Instant from, @RequestParam Instant to,
                                             @RequestParam(required = false) String ownerId) {
        return billing.summary(siteId, from, to, ownerId);
    }

    @GetMapping("/events")
    public List<Map<String, Object>> events(@PathVariable String siteId, @RequestParam(required = false) Instant from,
                                            @RequestParam(required = false) Instant to,
                                            @RequestParam(required = false) String ownerId,
                                            @RequestParam(required = false) String type) {
        return billing.events(siteId, from, to, ownerId, type);
    }
}
