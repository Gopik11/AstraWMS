package com.astrawms.outbound.api;

import com.astrawms.outbound.service.OutboundService;
import com.astrawms.outbound.service.ReleasePolicy;
import com.astrawms.outbound.service.WaveService;
import com.astrawms.outbound.service.WaveService.WaveCriteria;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Outbound release control (§C.4): the site's release mode and wave planning; carrier cutoffs, wave hold, release by
 * cutoff, owner rules and per-order priority / ship complete (ADR-0021).
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/outbound")
public class WaveController {

    private final WaveService waves;
    private final OutboundService outbound;
    private final ReleasePolicy policy;

    public WaveController(WaveService waves, OutboundService outbound, ReleasePolicy policy) {
        this.waves = waves;
        this.outbound = outbound;
        this.policy = policy;
    }

    /** Any field may be omitted to keep its current value. */
    public record SiteConfigRequest(String releaseMode, Boolean packRequired, String timezone, Boolean shipComplete) {
    }

    public record CutoffRequest(String cutoffTime) {
    }

    public record HoldRequest(String reason) {
    }

    public record ReleaseByCutoffRequest(String carrierScac, Integer withinMinutes) {
    }

    public record OwnerPolicyRequest(Boolean shipComplete, Boolean packList, String labelTemplate) {
    }

    public record OrderPolicyRequest(Integer priority, Boolean shipComplete) {
    }

    @GetMapping("/config")
    public Map<String, Object> config(@PathVariable String siteId) {
        return outbound.siteConfig(siteId);
    }

    @PreAuthorize("hasRole('SOLUTION_ADMIN')")
    @PutMapping("/config")
    public Map<String, Object> setConfig(@PathVariable String siteId, @RequestBody SiteConfigRequest body) {
        return outbound.setSiteConfig(siteId, body.releaseMode(), body.packRequired(), body.timezone(), body.shipComplete());
    }

    @GetMapping("/carrier-cutoffs")
    public List<Map<String, Object>> cutoffs(@PathVariable String siteId) {
        return policy.cutoffs(siteId);
    }

    @PreAuthorize("hasAnyRole('SOLUTION_ADMIN', 'SUPERVISOR')")
    @PutMapping("/carrier-cutoffs/{carrierScac}")
    public List<Map<String, Object>> putCutoff(@PathVariable String siteId, @PathVariable String carrierScac,
                                               @RequestBody CutoffRequest body) {
        return policy.putCutoff(siteId, carrierScac, body.cutoffTime());
    }

    @PreAuthorize("hasAnyRole('SOLUTION_ADMIN', 'SUPERVISOR')")
    @DeleteMapping("/carrier-cutoffs/{carrierScac}")
    public List<Map<String, Object>> deleteCutoff(@PathVariable String siteId, @PathVariable String carrierScac) {
        return policy.deleteCutoff(siteId, carrierScac);
    }

    @GetMapping("/owner-policies")
    public List<Map<String, Object>> ownerPolicies(@PathVariable String siteId) {
        return policy.ownerPolicies();
    }

    /** Owner (3PL client) rules apply at every site of the tenant. */
    @PreAuthorize("hasRole('SOLUTION_ADMIN')")
    @PutMapping("/owner-policies/{ownerId}")
    public Map<String, Object> putOwnerPolicy(@PathVariable String siteId, @PathVariable String ownerId,
                                              @RequestBody OwnerPolicyRequest body) {
        return policy.putOwnerPolicy(ownerId, body.shipComplete(), body.packList(), body.labelTemplate());
    }

    @PreAuthorize("hasRole('SUPERVISOR')")
    @PutMapping("/orders/{erpDocNo}/policy")
    public Map<String, Object> orderPolicy(@PathVariable String siteId, @PathVariable String erpDocNo,
                                           @RequestBody OrderPolicyRequest body) {
        return outbound.setOrderPolicy(siteId, erpDocNo, body.priority(), body.shipComplete());
    }

    /** Preview only (ADV-030): which pooled orders a wave with these criteria would contain. */
    @PreAuthorize("hasRole('SUPERVISOR')")
    @PostMapping("/waves/plan")
    public WaveService.WavePlan plan(@PathVariable String siteId, @Valid @RequestBody(required = false) WaveCriteria body) {
        return waves.plan(siteId, body);
    }

    @PreAuthorize("hasRole('SUPERVISOR')")
    @PostMapping("/waves")
    public ResponseEntity<Map<String, Object>> create(@PathVariable String siteId,
                                                      @Valid @RequestBody(required = false) WaveCriteria body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(waves.create(siteId, body));
    }

    @PreAuthorize("hasRole('SUPERVISOR')")
    @PostMapping("/waves/{waveNo}/release")
    public Map<String, Object> release(@PathVariable String siteId, @PathVariable String waveNo) {
        return waves.release(siteId, waveNo);
    }

    @PreAuthorize("hasRole('SUPERVISOR')")
    @PostMapping("/waves/{waveNo}/hold")
    public Map<String, Object> hold(@PathVariable String siteId, @PathVariable String waveNo,
                                    @RequestBody(required = false) HoldRequest body) {
        return waves.hold(siteId, waveNo, true, body == null ? null : body.reason());
    }

    @PreAuthorize("hasRole('SUPERVISOR')")
    @PostMapping("/waves/{waveNo}/unhold")
    public Map<String, Object> unhold(@PathVariable String siteId, @PathVariable String waveNo) {
        return waves.hold(siteId, waveNo, false, null);
    }

    /** Plans and releases at once every pooled order whose carrier cutoff is within the next N minutes (default 120). */
    @PreAuthorize("hasRole('SUPERVISOR')")
    @PostMapping("/waves/release-by-cutoff")
    public ResponseEntity<Map<String, Object>> releaseByCutoff(@PathVariable String siteId,
                                                               @RequestBody(required = false) ReleaseByCutoffRequest body) {
        int minutes = body == null || body.withinMinutes() == null ? 120 : Math.max(1, body.withinMinutes());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(waves.releaseByCutoff(siteId, body == null ? null : body.carrierScac(), minutes));
    }

    @GetMapping("/waves")
    public List<Map<String, Object>> list(@PathVariable String siteId, @RequestParam(required = false) String status) {
        return waves.list(siteId, status);
    }

    @GetMapping("/waves/{waveNo}")
    public Map<String, Object> detail(@PathVariable String siteId, @PathVariable String waveNo) {
        return waves.detail(siteId, waveNo);
    }
}
