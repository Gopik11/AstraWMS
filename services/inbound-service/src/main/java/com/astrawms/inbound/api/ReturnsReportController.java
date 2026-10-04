package com.astrawms.inbound.api;

import com.astrawms.common.security.AccessScope;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Returns quality (ADR-0025): units returned by item and reason with grade and disposition, for the return rate
 * (the UI divides by the units shipped from the inventory ledger); and the repair chain of units sent to refurbish,
 * kept as a status on the unit.
 */
@RestController
public class ReturnsReportController {

    static final List<String> REPAIR = List.of("AWAITING_REPAIR", "IN_REPAIR", "REPAIRED", "NOT_REPAIRABLE");

    private final JdbcClient jdbc;
    private final Clock clock;

    public ReturnsReportController(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/api/v1/sites/{siteId}/returns/report")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> report(@PathVariable String siteId, @RequestParam(defaultValue = "90") int days) {
        AccessScope scope = AccessScope.current();
        return jdbc.sql("""
                        select u.owner_id, u.item_no,
                               coalesce(nullif(u.return_reason_actual, ''), nullif(l.return_reason, ''), 'UNSPECIFIED') as reason,
                               sum(u.qty) as units,
                               count(*) as receipts,
                               sum(u.qty) filter (where u.condition_grade in ('A', 'B')) as resaleable,
                               sum(u.qty) filter (where u.disposition = 'RESTOCK') as restocked,
                               sum(u.qty) filter (where u.disposition = 'REFURBISH') as refurbish,
                               sum(u.qty) filter (where u.disposition in ('SCRAP', 'LIQUIDATE')) as written_off,
                               sum(u.qty) filter (where u.wrong_item) as wrong_item
                        from return_unit u
                        join return_order r on r.id = u.return_id
                        left join return_line l on l.return_id = u.return_id and l.erp_line_ref = u.erp_line_ref
                        where r.site_id = :site and u.received_at >= :since
                          and (:ownersAll or u.owner_id in (:owners))
                        group by u.owner_id, u.item_no, 3 order by units desc, u.item_no""")
                .param("site", siteId)
                .param("since", Timestamp.from(clock.instant().minus(Duration.ofDays(Math.max(1, Math.min(days, 730))))))
                .param("ownersAll", scope.ownersAll()).param("owners", scope.ownerList())
                .query().listOfRows();
    }

    /** Units sent to refurbish and where they are in the repair chain. */
    @GetMapping("/api/v1/sites/{siteId}/returns/repairs")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> repairs(@PathVariable String siteId, @RequestParam(required = false) String status) {
        return jdbc.sql("""
                        select u.id, r.rma_no, u.owner_id, u.item_no, u.qty, u.uom, u.serials, u.condition_grade,
                               u.return_reason_actual, u.repair_status, u.repair_updated_at, u.location_id, u.received_at
                        from return_unit u join return_order r on r.id = u.return_id
                        where r.site_id = :site and u.disposition = 'REFURBISH'
                          and (cast(:status as text) is null or u.repair_status = :status)
                        order by u.received_at desc""")
                .param("site", siteId).param("status", status == null || status.isBlank() ? null : status.toUpperCase())
                .query().listOfRows();
    }

    public record RepairRequest(String status) {
    }

    @PreAuthorize("hasAnyRole('SUPERVISOR','QA_MANAGER','RECEIVER')")
    @PostMapping("/api/v1/sites/{siteId}/returns/units/{unitId}/repair")
    @Transactional
    public Map<String, Object> repair(@PathVariable String siteId, @PathVariable UUID unitId, @RequestBody RepairRequest body) {
        String s = body.status() == null ? null : body.status().trim().toUpperCase();
        if (s == null || !REPAIR.contains(s)) {
            throw ApiException.badRequest("INB_REPAIR_STATUS", "status is one of " + REPAIR);
        }
        int n = jdbc.sql("""
                        update return_unit u set repair_status = :s, repair_updated_at = :now
                        from return_order r where r.id = u.return_id and r.site_id = :site and u.id = :id
                          and u.disposition = 'REFURBISH'""")
                .param("s", s).param("now", Timestamp.from(clock.instant())).param("site", siteId).param("id", unitId).update();
        if (n == 0) {
            throw ApiException.notFound("INB_REPAIR_UNKNOWN", "No unit " + unitId + " sent to refurbish at " + siteId);
        }
        TenantContext.require();
        return Map.of("id", unitId, "repairStatus", s);
    }
}
