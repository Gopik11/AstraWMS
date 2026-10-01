package com.astrawms.outbound.api;

import com.astrawms.outbound.service.OutboundService;
import com.astrawms.outbound.service.WaveService;
import com.astrawms.outbound.service.WaveService.WaveCriteria;
import jakarta.validation.Valid;
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

/** Outbound release control (§C.4): the site's release mode and wave planning. */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/outbound")
public class WaveController {

    private final WaveService waves;
    private final OutboundService outbound;

    public WaveController(WaveService waves, OutboundService outbound) {
        this.waves = waves;
        this.outbound = outbound;
    }

    /** Either field may be omitted to keep its current value. */
    public record SiteConfigRequest(String releaseMode, Boolean packRequired) {
    }

    @GetMapping("/config")
    public Map<String, Object> config(@PathVariable String siteId) {
        return outbound.siteConfig(siteId);
    }

    @PreAuthorize("hasRole('SOLUTION_ADMIN')")
    @PutMapping("/config")
    public Map<String, Object> setConfig(@PathVariable String siteId, @RequestBody SiteConfigRequest body) {
        return outbound.setSiteConfig(siteId, body.releaseMode(), body.packRequired());
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

    @GetMapping("/waves")
    public List<Map<String, Object>> list(@PathVariable String siteId, @RequestParam(required = false) String status) {
        return waves.list(siteId, status);
    }

    @GetMapping("/waves/{waveNo}")
    public Map<String, Object> detail(@PathVariable String siteId, @PathVariable String waveNo) {
        return waves.detail(siteId, waveNo);
    }
}
