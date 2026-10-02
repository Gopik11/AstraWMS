package com.astrawms.inbound.api;

import com.astrawms.inbound.yard.YardService;
import com.astrawms.inbound.yard.YardService.Booking;
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

/** Yard and dock appointments (ADR-0021): book a door, gate check-in, to door, check-out; the yard now. */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/yard")
public class YardController {

    private final YardService yard;

    public YardController(YardService yard) {
        this.yard = yard;
    }

    public record TrailerRequest(String trailerNo) {
    }

    public record DoorRequest(String door) {
    }

    @GetMapping("/appointments")
    public List<Map<String, Object>> list(@PathVariable String siteId, @RequestParam(required = false) Instant from,
                                          @RequestParam(required = false) Instant to,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String docNo) {
        return yard.list(siteId, from, to, status, docNo);
    }

    @GetMapping("/appointments/{apptNo}")
    public Map<String, Object> detail(@PathVariable String siteId, @PathVariable String apptNo) {
        return yard.detail(siteId, apptNo);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(@PathVariable String siteId) {
        return yard.summary(siteId);
    }

    @PreAuthorize("hasAnyRole('SUPERVISOR','ERP_INTEGRATION')")
    @PostMapping("/appointments")
    public ResponseEntity<Map<String, Object>> book(@PathVariable String siteId, @RequestBody Booking body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(yard.book(siteId, body));
    }

    @PreAuthorize("hasAnyRole('SUPERVISOR','ERP_INTEGRATION')")
    @PutMapping("/appointments/{apptNo}")
    public Map<String, Object> update(@PathVariable String siteId, @PathVariable String apptNo, @RequestBody Booking body) {
        return yard.update(siteId, apptNo, body);
    }

    @PreAuthorize("hasAnyRole('RECEIVER','SUPERVISOR')")
    @PostMapping("/appointments/{apptNo}/check-in")
    public Map<String, Object> checkIn(@PathVariable String siteId, @PathVariable String apptNo,
                                       @RequestBody(required = false) TrailerRequest body) {
        return yard.checkIn(siteId, apptNo, body == null ? null : body.trailerNo());
    }

    @PreAuthorize("hasAnyRole('RECEIVER','SUPERVISOR')")
    @PostMapping("/appointments/{apptNo}/to-door")
    public Map<String, Object> toDoor(@PathVariable String siteId, @PathVariable String apptNo,
                                      @RequestBody(required = false) DoorRequest body) {
        return yard.toDoor(siteId, apptNo, body == null ? null : body.door());
    }

    @PreAuthorize("hasAnyRole('RECEIVER','SUPERVISOR')")
    @PostMapping("/appointments/{apptNo}/check-out")
    public Map<String, Object> checkOut(@PathVariable String siteId, @PathVariable String apptNo) {
        return yard.checkOut(siteId, apptNo);
    }

    @PreAuthorize("hasRole('SUPERVISOR')")
    @PostMapping("/appointments/{apptNo}/cancel")
    public Map<String, Object> cancel(@PathVariable String siteId, @PathVariable String apptNo) {
        return yard.close(siteId, apptNo, "CANCELLED");
    }

    @PreAuthorize("hasRole('SUPERVISOR')")
    @PostMapping("/appointments/{apptNo}/no-show")
    public Map<String, Object> noShow(@PathVariable String siteId, @PathVariable String apptNo) {
        return yard.close(siteId, apptNo, "NO_SHOW");
    }
}
