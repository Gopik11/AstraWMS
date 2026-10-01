package com.astrawms.outbound.api;

import com.astrawms.outbound.packing.LoadService;
import com.astrawms.outbound.packing.PackingService;
import com.astrawms.outbound.service.OrderScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Pack station (§5.1/5.2) and loading (§5.3). */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/outbound")
public class PackingController {

    private final PackingService packing;
    private final LoadService loads;
    private final OrderScope scope;

    public PackingController(PackingService packing, LoadService loads, OrderScope scope) {
        this.packing = packing;
        this.loads = loads;
        this.scope = scope;
    }

    public record CartonRequest(String cartonType) {
    }

    public record PackRequest(@NotBlank String erpLineRef, @NotNull @Positive BigDecimal qty) {
    }

    public record CloseCartonRequest(@Positive BigDecimal weightKg) {
    }

    public record LoadRequest(String carrierScac, String door, String trailerNo) {
    }

    public record LoadOrderRequest(String erpDocNo, String sscc) {
    }

    public record CloseLoadRequest(String sealNo) {
    }

    @GetMapping("/orders/{erpDocNo}/packing")
    public Map<String, Object> packView(@PathVariable String siteId, @PathVariable String erpDocNo) {
        scope.require(siteId, erpDocNo);
        return packing.packView(siteId, erpDocNo);
    }

    @PreAuthorize("hasAnyRole('PICKER','SUPERVISOR')")
    @PostMapping("/orders/{erpDocNo}/cartons")
    public ResponseEntity<Map<String, Object>> openCarton(@PathVariable String siteId, @PathVariable String erpDocNo,
                                                          @RequestBody(required = false) CartonRequest body) {
        scope.require(siteId, erpDocNo);
        return ResponseEntity.status(HttpStatus.CREATED).body(packing.openCarton(siteId, erpDocNo, body == null ? null : body.cartonType()));
    }

    @GetMapping("/cartons/{sscc}")
    public Map<String, Object> carton(@PathVariable String siteId, @PathVariable String sscc) {
        return packing.carton(siteId, sscc);
    }

    @PreAuthorize("hasAnyRole('PICKER','SUPERVISOR')")
    @PostMapping("/cartons/{sscc}/items")
    public Map<String, Object> pack(@PathVariable String siteId, @PathVariable String sscc, @Valid @RequestBody PackRequest body) {
        return packing.pack(siteId, sscc, body.erpLineRef(), body.qty());
    }

    @PreAuthorize("hasAnyRole('PICKER','SUPERVISOR')")
    @PostMapping("/cartons/{sscc}/close")
    public Map<String, Object> closeCarton(@PathVariable String siteId, @PathVariable String sscc,
                                           @Valid @RequestBody(required = false) CloseCartonRequest body) {
        return packing.close(siteId, sscc, body == null ? null : body.weightKg());
    }

    @GetMapping("/loads")
    public List<Map<String, Object>> loads(@PathVariable String siteId, @RequestParam(required = false) String status) {
        return loads.list(siteId, status);
    }

    @GetMapping("/loads/{loadNo}")
    public Map<String, Object> load(@PathVariable String siteId, @PathVariable String loadNo) {
        return loads.detail(siteId, loadNo);
    }

    @PreAuthorize("hasRole('SUPERVISOR')")
    @PostMapping("/loads")
    public ResponseEntity<Map<String, Object>> createLoad(@PathVariable String siteId, @RequestBody(required = false) LoadRequest body) {
        LoadRequest r = body == null ? new LoadRequest(null, null, null) : body;
        return ResponseEntity.status(HttpStatus.CREATED).body(loads.create(siteId, r.carrierScac(), r.door(), r.trailerNo()));
    }

    @PreAuthorize("hasAnyRole('PICKER','SUPERVISOR')")
    @PostMapping("/loads/{loadNo}/orders")
    public Map<String, Object> loadOrder(@PathVariable String siteId, @PathVariable String loadNo, @RequestBody LoadOrderRequest body) {
        if (body.erpDocNo() != null && !body.erpDocNo().isBlank()) {
            scope.require(siteId, body.erpDocNo());
        }
        return loads.addOrder(siteId, loadNo, body.erpDocNo(), body.sscc());
    }

    @PreAuthorize("hasRole('SUPERVISOR')")
    @DeleteMapping("/loads/{loadNo}/orders/{erpDocNo}")
    public Map<String, Object> unloadOrder(@PathVariable String siteId, @PathVariable String loadNo, @PathVariable String erpDocNo) {
        return loads.removeOrder(siteId, loadNo, erpDocNo);
    }

    @PreAuthorize("hasRole('SUPERVISOR')")
    @PostMapping("/loads/{loadNo}/close")
    public Map<String, Object> closeLoad(@PathVariable String siteId, @PathVariable String loadNo,
                                         @RequestBody(required = false) CloseLoadRequest body) {
        return loads.close(siteId, loadNo, body == null ? null : body.sealNo());
    }
}
