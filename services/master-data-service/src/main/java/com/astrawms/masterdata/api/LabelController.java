package com.astrawms.masterdata.api;

import com.astrawms.masterdata.service.LabelService;
import java.util.List;
import java.util.Map;
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

/** Barcode labels for locations, items and LPNs, and the site's label printers (ADR-0022). */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/labels")
public class LabelController {

    private final LabelService labels;

    public LabelController(LabelService labels) {
        this.labels = labels;
    }

    public record LocationLabelRequest(List<String> locationIds, String zoneId, String from, String to, String printer) {
    }

    public record LpnLabelRequest(Integer count, String printer) {
    }

    @PreAuthorize("hasAnyRole('SOLUTION_ADMIN','SUPERVISOR','INV_MANAGER','INV_ANALYST')")
    @PostMapping("/locations")
    public LabelService.Labels locations(@PathVariable String siteId, @RequestBody LocationLabelRequest body) {
        return labels.locations(siteId, body.locationIds(), body.zoneId(), body.from(), body.to(), body.printer());
    }

    @PreAuthorize("hasAnyRole('SOLUTION_ADMIN','SUPERVISOR','INV_MANAGER','INV_ANALYST','RECEIVER')")
    @PostMapping("/items")
    public LabelService.Labels items(@PathVariable String siteId, @RequestBody LabelService.ItemLabelRequest body) {
        return labels.items(body, siteId);
    }

    @PreAuthorize("hasAnyRole('SOLUTION_ADMIN','SUPERVISOR','INV_MANAGER','RECEIVER')")
    @PostMapping("/lpns")
    public LabelService.Labels lpns(@PathVariable String siteId, @RequestBody LpnLabelRequest body) {
        return labels.lpns(siteId, body.count() == null ? 1 : body.count(), body.printer());
    }

    // ------------------------------------------------------------------ label lifecycle (ADR-0024)

    /** Labels printed (or previewed) at the site, newest first; {@code q} matches the barcode or the label text. */
    @GetMapping("/printed")
    public List<Map<String, Object>> printed(@PathVariable String siteId, @RequestParam(required = false) String type,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String q) {
        return labels.printed(siteId, type, status, q);
    }

    /** Verification scan of a label after it is applied. */
    @PreAuthorize("hasAnyRole('SOLUTION_ADMIN','SUPERVISOR','INV_MANAGER','INV_ANALYST','RECEIVER','PICKER')")
    @PostMapping("/printed/verify")
    public Map<String, Object> verify(@PathVariable String siteId, @RequestBody Map<String, String> body) {
        return labels.verify(siteId, body.get("scan"));
    }

    @PreAuthorize("hasAnyRole('SOLUTION_ADMIN','SUPERVISOR','INV_MANAGER','INV_ANALYST','RECEIVER')")
    @PostMapping("/printed/{id}/reprint")
    public Map<String, Object> reprint(@PathVariable String siteId, @PathVariable java.util.UUID id,
                                       @RequestBody(required = false) Map<String, String> body) {
        return labels.reprint(siteId, id, body == null ? null : body.get("printer"));
    }

    @PreAuthorize("hasAnyRole('SOLUTION_ADMIN','SUPERVISOR','INV_MANAGER')")
    @PostMapping("/printed/{id}/void")
    public Map<String, Object> voidLabel(@PathVariable String siteId, @PathVariable java.util.UUID id,
                                         @RequestBody Map<String, String> body) {
        return labels.voidLabel(siteId, id, body.get("reason"));
    }

    @GetMapping("/printers")
    public List<Map<String, Object>> printers(@PathVariable String siteId) {
        return labels.printers(siteId);
    }

    @PreAuthorize("hasRole('SOLUTION_ADMIN')")
    @PutMapping("/printers/{name}")
    public List<Map<String, Object>> putPrinter(@PathVariable String siteId, @PathVariable String name,
                                                @RequestBody LabelService.PrinterRequest body) {
        return labels.putPrinter(siteId, name, body);
    }

    @PreAuthorize("hasRole('SOLUTION_ADMIN')")
    @DeleteMapping("/printers/{name}")
    public ResponseEntity<Void> deletePrinter(@PathVariable String siteId, @PathVariable String name) {
        labels.deletePrinter(siteId, name);
        return ResponseEntity.noContent().build();
    }
}
