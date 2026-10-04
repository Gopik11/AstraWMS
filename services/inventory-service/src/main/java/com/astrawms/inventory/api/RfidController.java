package com.astrawms.inventory.api;

import com.astrawms.inventory.service.Rfid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RFID (ADR-0027) for handheld readers (the Android RF app) and fixed readers (portals, AUTOMATION clients).
 * Resolve and reconcile are queries sent as POST because a read carries up to {@value Rfid#MAX_READS} EPCs; they
 * change nothing. Commissioning is idempotent per EPC and binding, so the Idempotency-Key is only checked for presence.
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/inventory/rfid")
public class RfidController {

    private static final String READERS =
            "hasAnyRole('RECEIVER','PICKER','INV_ANALYST','INV_MANAGER','SUPERVISOR','QA_MANAGER','AUTOMATION')";

    private final Rfid rfid;

    public RfidController(Rfid rfid) {
        this.rfid = rfid;
    }

    @PreAuthorize(READERS)
    @PostMapping("/resolve")
    public List<Rfid.Resolved> resolve(@PathVariable String siteId, @RequestBody Rfid.ResolveRequest body) {
        return rfid.resolve(siteId, body.reads());
    }

    @PreAuthorize(READERS)
    @PostMapping("/locations/{locationId}/reconcile")
    public Rfid.Reconciliation reconcile(@PathVariable String siteId, @PathVariable String locationId,
                                         @RequestBody Rfid.ReconcileRequest body) {
        return rfid.reconcile(siteId, locationId.trim().toUpperCase(), body.reads());
    }

    @PreAuthorize(READERS)
    @PostMapping("/sightings")
    public Map<String, Object> sightings(@PathVariable String siteId, @RequestBody Rfid.SightingRequest body) {
        return rfid.sightings(siteId, body);
    }

    @PreAuthorize("hasAnyRole('RECEIVER','INV_ANALYST','INV_MANAGER','SUPERVISOR')")
    @PostMapping("/tags")
    public ResponseEntity<Rfid.TagView> commission(@PathVariable String siteId,
                                                   @RequestHeader("Idempotency-Key") String key,
                                                   @RequestBody Rfid.CommissionRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rfid.commission(siteId, body));
    }

    @PreAuthorize("hasAnyRole('INV_ANALYST','INV_MANAGER','SUPERVISOR')")
    @PostMapping("/tags/{epc}/retire")
    public Rfid.TagView retire(@PathVariable String siteId, @PathVariable String epc,
                               @RequestHeader("Idempotency-Key") String key) {
        return rfid.retire(siteId, epc);
    }

    @GetMapping("/tags/{epc}")
    public Rfid.TagView tag(@PathVariable String siteId, @PathVariable String epc) {
        return rfid.get(siteId, epc);
    }

    @GetMapping("/tags")
    public List<Rfid.TagView> tags(@PathVariable String siteId, @RequestParam(required = false) String lpnId,
                                   @RequestParam(required = false) String itemNo,
                                   @RequestParam(required = false) String serialNo,
                                   @RequestParam(required = false) String locationId) {
        return rfid.tags(siteId, lpnId, itemNo, serialNo, locationId);
    }
}
