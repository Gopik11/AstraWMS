package com.astrawms.outbound.api;

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

    public OutboundController(OutboundService outbound) {
        this.outbound = outbound;
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable String siteId, @RequestParam(required = false) String status) {
        return outbound.list(siteId, status);
    }

    @GetMapping("/{erpDocNo}")
    public Map<String, Object> detail(@PathVariable String siteId, @PathVariable String erpDocNo) {
        return outbound.detail(siteId, erpDocNo);
    }

    @PreAuthorize("hasRole('SUPERVISOR')")
    @PostMapping("/{erpDocNo}/ship")
    public Map<String, Object> ship(@PathVariable String siteId, @PathVariable String erpDocNo,
                                    @RequestBody(required = false) ShipRequest body) {
        return outbound.ship(siteId, erpDocNo, body);
    }

    @PreAuthorize("hasRole('SUPERVISOR')")
    @PostMapping("/{erpDocNo}/repost")
    public Map<String, Object> repost(@PathVariable String siteId, @PathVariable String erpDocNo) {
        return outbound.repost(siteId, erpDocNo);
    }
}
