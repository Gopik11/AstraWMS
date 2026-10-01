package com.astrawms.inbound.api;

import com.astrawms.inbound.returns.ReturnsService;
import com.astrawms.inbound.returns.ReturnsService.ReceiveUnit;
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

/** Customer returns (§8): RMAs, blind returns, receive and grade units, close (IF-RET-002), repost. */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/returns")
public class ReturnsController {

    private final ReturnsService returns;

    public ReturnsController(ReturnsService returns) {
        this.returns = returns;
    }

    public record BlindRequest(String customerName) {
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable String siteId, @RequestParam(required = false) String status) {
        return returns.list(siteId, status);
    }

    @GetMapping("/{rmaNo}")
    public Map<String, Object> detail(@PathVariable String siteId, @PathVariable String rmaNo) {
        return returns.detail(siteId, rmaNo);
    }

    /** A return without RMA (RET-EX-01 / unexpected returns): identified later by the ERP from the confirmation. */
    @PreAuthorize("hasAnyRole('RECEIVER','SUPERVISOR')")
    @PostMapping
    public ResponseEntity<Map<String, Object>> blind(@PathVariable String siteId, @RequestBody(required = false) BlindRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(returns.createBlind(siteId, body == null ? null : body.customerName()));
    }

    @PreAuthorize("hasAnyRole('RECEIVER','SUPERVISOR')")
    @PostMapping("/{rmaNo}/units")
    public ResponseEntity<Map<String, Object>> receive(@PathVariable String siteId, @PathVariable String rmaNo,
                                                       @RequestHeader("Idempotency-Key") String key,
                                                       @RequestBody ReceiveUnit body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(returns.receive(siteId, rmaNo, key, body));
    }

    @PreAuthorize("hasAnyRole('RECEIVER','SUPERVISOR')")
    @PostMapping("/{rmaNo}/close")
    public Map<String, Object> close(@PathVariable String siteId, @PathVariable String rmaNo) {
        return returns.close(siteId, rmaNo);
    }

    @PreAuthorize("hasRole('SUPERVISOR')")
    @PostMapping("/{rmaNo}/repost")
    public Map<String, Object> repost(@PathVariable String siteId, @PathVariable String rmaNo) {
        return returns.repost(siteId, rmaNo);
    }
}
