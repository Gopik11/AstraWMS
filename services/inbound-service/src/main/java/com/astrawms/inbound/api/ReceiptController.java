package com.astrawms.inbound.api;

import com.astrawms.inbound.api.InboundDtos.CloseRequest;
import com.astrawms.inbound.api.InboundDtos.ExpectationDetail;
import com.astrawms.inbound.api.InboundDtos.ExpectationSummary;
import com.astrawms.inbound.api.InboundDtos.ReceiveLineRequest;
import com.astrawms.inbound.api.InboundDtos.ReceiveResult;
import com.astrawms.inbound.api.InboundDtos.ReceiveSsccRequest;
import com.astrawms.inbound.receiving.ReceiptScope;
import com.astrawms.inbound.receiving.ReceivingService;
import jakarta.validation.Valid;
import java.util.List;
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

/** Inbound API v1: receiving against ERP expectations (scope §1). */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/receipts")
public class ReceiptController {

    private final ReceivingService receiving;
    private final ReceiptScope scope;

    public ReceiptController(ReceivingService receiving, ReceiptScope scope) {
        this.receiving = receiving;
        this.scope = scope;
    }

    @GetMapping
    public List<ExpectationSummary> list(@PathVariable String siteId, @RequestParam(required = false) String status) {
        return scope.filter(siteId, receiving.list(siteId, status), ExpectationSummary::erpDocNo);
    }

    @GetMapping("/{erpDocNo}")
    public ExpectationDetail detail(@PathVariable String siteId, @PathVariable String erpDocNo) {
        scope.require(siteId, erpDocNo);
        return receiving.detail(siteId, erpDocNo);
    }

    @PreAuthorize("hasAnyRole('RECEIVER','SUPERVISOR')")
    @PostMapping("/{erpDocNo}/lines/{lineRef}/receive")
    public ResponseEntity<ReceiveResult> receiveLine(@PathVariable String siteId, @PathVariable String erpDocNo,
                                                     @PathVariable String lineRef,
                                                     @RequestHeader("Idempotency-Key") String key,
                                                     @Valid @RequestBody ReceiveLineRequest body) {
        scope.require(siteId, erpDocNo);
        return respond(receiving.receiveLine(siteId, erpDocNo, lineRef, key, body));
    }

    @PreAuthorize("hasAnyRole('RECEIVER','SUPERVISOR')")
    @PostMapping("/{erpDocNo}/sscc/{sscc}/receive")
    public ResponseEntity<ReceiveResult> receiveSscc(@PathVariable String siteId, @PathVariable String erpDocNo,
                                                     @PathVariable String sscc,
                                                     @RequestHeader("Idempotency-Key") String key,
                                                     @Valid @RequestBody ReceiveSsccRequest body) {
        scope.require(siteId, erpDocNo);
        return respond(receiving.receiveSscc(siteId, erpDocNo, sscc, key, body));
    }

    @PreAuthorize("hasAnyRole('RECEIVER','SUPERVISOR')")
    @PostMapping("/{erpDocNo}/close")
    public ExpectationSummary close(@PathVariable String siteId, @PathVariable String erpDocNo,
                                    @RequestBody(required = false) CloseRequest body) {
        scope.require(siteId, erpDocNo);
        return receiving.close(siteId, erpDocNo, body);
    }

    @PreAuthorize("hasRole('SUPERVISOR')")
    @PostMapping("/{erpDocNo}/repost")
    public ExpectationSummary repost(@PathVariable String siteId, @PathVariable String erpDocNo) {
        scope.require(siteId, erpDocNo);
        return receiving.repost(siteId, erpDocNo);
    }

    private static ResponseEntity<ReceiveResult> respond(ReceiveResult result) {
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }
}
