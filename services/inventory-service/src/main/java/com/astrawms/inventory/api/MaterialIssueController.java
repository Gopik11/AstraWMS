package com.astrawms.inventory.api;

import com.astrawms.inventory.service.MaterialIssues;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Controlled material issue to cost centres, WBS elements and orders (ADR-0022), and the cost objects. */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/inventory")
public class MaterialIssueController {

    private final MaterialIssues issues;

    public MaterialIssueController(MaterialIssues issues) {
        this.issues = issues;
    }

    public record DecisionRequest(String note) {
    }

    @GetMapping("/cost-objects")
    public List<Map<String, Object>> costObjects(@PathVariable String siteId, @RequestParam(required = false) String type,
                                                 @RequestParam(required = false) String q) {
        return issues.costObjects(type, q);
    }

    /** Cost objects are tenant-wide (SAP controlling area); maintained here or replicated from the ERP. */
    @PreAuthorize("hasAnyRole('SOLUTION_ADMIN','INV_MANAGER','ERP_INTEGRATION')")
    @PutMapping("/cost-objects/{type}/{code}")
    public Map<String, Object> putCostObject(@PathVariable String siteId, @PathVariable String type,
                                             @PathVariable String code, @RequestBody MaterialIssues.CostObjectRequest body) {
        return issues.putCostObject(type, code, body);
    }

    @GetMapping("/material-issues")
    public List<Map<String, Object>> list(@PathVariable String siteId, @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String q) {
        return issues.list(siteId, status, q);
    }

    @GetMapping("/material-issues/{issueNo}")
    public Map<String, Object> detail(@PathVariable String siteId, @PathVariable String issueNo) {
        return issues.detail(siteId, issueNo);
    }

    @PreAuthorize("hasAnyRole('RECEIVER','PICKER','INV_ANALYST','INV_MANAGER','SUPERVISOR')")
    @PostMapping("/material-issues")
    public ResponseEntity<Map<String, Object>> create(@PathVariable String siteId,
                                                      @RequestBody MaterialIssues.CreateRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(issues.create(siteId, body));
    }

    @PreAuthorize("hasAnyRole('INV_MANAGER','SUPERVISOR')")
    @PostMapping("/material-issues/{issueNo}/approve")
    public Map<String, Object> approve(@PathVariable String siteId, @PathVariable String issueNo,
                                       @RequestBody(required = false) DecisionRequest body) {
        return issues.decide(siteId, issueNo, true, body == null ? null : body.note());
    }

    @PreAuthorize("hasAnyRole('INV_MANAGER','SUPERVISOR')")
    @PostMapping("/material-issues/{issueNo}/reject")
    public Map<String, Object> reject(@PathVariable String siteId, @PathVariable String issueNo,
                                      @RequestBody(required = false) DecisionRequest body) {
        return issues.decide(siteId, issueNo, false, body == null ? null : body.note());
    }

    /** Cancel before anything is issued; close a partly issued request. */
    @PreAuthorize("hasAnyRole('INV_MANAGER','SUPERVISOR')")
    @PostMapping("/material-issues/{issueNo}/close")
    public Map<String, Object> close(@PathVariable String siteId, @PathVariable String issueNo) {
        return issues.end(siteId, issueNo);
    }

    @PreAuthorize("hasAnyRole('RECEIVER','PICKER','INV_MANAGER','SUPERVISOR')")
    @PostMapping("/material-issues/{issueNo}/lines/{lineNo}/issue")
    public Map<String, Object> issue(@PathVariable String siteId, @PathVariable String issueNo, @PathVariable int lineNo,
                                     @RequestHeader("Idempotency-Key") String key, @RequestBody MaterialIssues.Scan body) {
        return issues.issue(siteId, issueNo, lineNo, key, body);
    }

    @PreAuthorize("hasAnyRole('RECEIVER','PICKER','INV_MANAGER','SUPERVISOR')")
    @PostMapping("/material-issues/{issueNo}/lines/{lineNo}/return")
    public Map<String, Object> returnLine(@PathVariable String siteId, @PathVariable String issueNo,
                                          @PathVariable int lineNo, @RequestHeader("Idempotency-Key") String key,
                                          @RequestBody MaterialIssues.Scan body) {
        return issues.returnLine(siteId, issueNo, lineNo, key, body);
    }
}
