package com.astrawms.outbound.api;

import com.astrawms.outbound.service.OrderScope;
import com.astrawms.outbound.service.OutboundService;
import com.astrawms.outbound.service.OutboundService.ShipRequest;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Outbound API v1: order status, shipping (trailer close / carrier handover) and ERP repost. */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/outbound/orders")
public class OutboundController {

    private final OutboundService outbound;
    private final OrderScope scope;

    public OutboundController(OutboundService outbound, OrderScope scope) {
        this.outbound = outbound;
        this.scope = scope;
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable String siteId, @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String q) {
        return scope.filter(siteId, outbound.list(siteId, status, q));
    }

    @GetMapping("/{erpDocNo}")
    public Map<String, Object> detail(@PathVariable String siteId, @PathVariable String erpDocNo) {
        scope.require(siteId, erpDocNo);
        return outbound.detail(siteId, erpDocNo);
    }

    @PreAuthorize("hasRole('SUPERVISOR')")
    @PostMapping("/{erpDocNo}/ship")
    public Map<String, Object> ship(@PathVariable String siteId, @PathVariable String erpDocNo,
                                    @RequestBody(required = false) ShipRequest body) {
        scope.require(siteId, erpDocNo);
        return outbound.ship(siteId, erpDocNo, body);
    }

    /** "Reallocate shorts" (ADR-0019): allocates the order's short lines from stock available now. */
    @PreAuthorize("hasRole('SUPERVISOR')")
    @PostMapping("/{erpDocNo}/reallocate")
    public Map<String, Object> reallocate(@PathVariable String siteId, @PathVariable String erpDocNo) {
        scope.require(siteId, erpDocNo);
        return outbound.reallocateShorts(siteId, erpDocNo);
    }

    /** "Close shorts" (ADR-0020): the remaining short quantity ships short; BACKORDER shorts stop waiting. */
    @PreAuthorize("hasRole('SUPERVISOR')")
    @PostMapping("/{erpDocNo}/close-shorts")
    public Map<String, Object> closeShorts(@PathVariable String siteId, @PathVariable String erpDocNo) {
        scope.require(siteId, erpDocNo);
        return outbound.closeShorts(siteId, erpDocNo);
    }

    @PreAuthorize("hasRole('SUPERVISOR')")
    @PostMapping("/{erpDocNo}/repost")
    public Map<String, Object> repost(@PathVariable String siteId, @PathVariable String erpDocNo) {
        scope.require(siteId, erpDocNo);
        return outbound.repost(siteId, erpDocNo);
    }
}
