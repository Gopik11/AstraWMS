package com.astrawms.outbound.service;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import jakarta.validation.constraints.Positive;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wave planning (§C.4, ADV-030): in WAVE mode, orders wait in the pool (status POOLED). A supervisor previews a wave
 * from selection criteria and size limits ("plan", no side effects), creates it, and releases it: every order of the
 * wave is then allocated and its picks are requested, exactly as a waveless order would be on receipt.
 */
@Service
public class WaveService {

    private final JdbcClient jdbc;
    private final OutboundService outbound;
    private final JsonMapper json;
    private final Clock clock;

    public WaveService(JdbcClient jdbc, OutboundService outbound, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.outbound = outbound;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Selection criteria and size limits of a wave. All are optional; pool orders are taken by planned goods issue
     * (earliest first) until a limit would be exceeded.
     */
    public record WaveCriteria(String carrierScac, String orderType, Instant goodsIssueBefore,
                               @Positive Integer maxOrders, @Positive Integer maxLines) {
    }

    public record PlannedOrder(String erpDocNo, String orderType, String carrierScac, Instant plannedGoodsIssueUtc,
                               int lines) {
    }

    public record WavePlan(List<PlannedOrder> orders, int orderCount, int lineCount) {
    }

    /** Preview: the orders a wave with these criteria would contain now. Changes nothing. */
    @Transactional(readOnly = true)
    public WavePlan plan(String siteId, WaveCriteria c) {
        return select(siteId, c, false);
    }

    @Transactional
    public Map<String, Object> create(String siteId, WaveCriteria c) {
        WavePlan plan = select(siteId, c, true);
        if (plan.orders().isEmpty()) {
            throw ApiException.unprocessable("OUT_WAVE_EMPTY", "No pooled orders match the wave criteria");
        }
        UUID id = UUID.randomUUID();
        long seq = jdbc.sql("select count(*) + 1 from outbound_wave where site_id = :site")
                .param("site", siteId).query(Long.class).single();
        String waveNo = "W%06d".formatted(seq);
        jdbc.sql("""
                        insert into outbound_wave (id, tenant_id, site_id, wave_no, status, criteria, created_by, created_at)
                        values (:id, :t, :site, :no, 'PLANNED', cast(:criteria as jsonb), :user, :now)""")
                .param("id", id).param("t", TenantContext.tenantId()).param("site", siteId).param("no", waveNo)
                .param("criteria", json.writeValueAsString(c)).param("user", TenantContext.require().userId())
                .param("now", Timestamp.from(clock.instant())).update();
        jdbc.sql("update outbound_order set wave_id = :w where site_id = :site and erp_doc_no in (:docs)")
                .param("w", id).param("site", siteId)
                .param("docs", plan.orders().stream().map(PlannedOrder::erpDocNo).toList()).update();
        return detail(siteId, waveNo);
    }

    /**
     * Releases a planned wave: allocates each order that is still pooled (changes and cancellations received since
     * planning are honoured) and requests its picks. Releasing a released wave returns it unchanged.
     */
    @Transactional
    public Map<String, Object> release(String siteId, String waveNo) {
        record Wave(UUID id, String status) {
        }
        Wave w = jdbc.sql("select id, status from outbound_wave where site_id = :site and wave_no = :no for update")
                .param("site", siteId).param("no", waveNo)
                .query((rs, n) -> new Wave(rs.getObject(1, UUID.class), rs.getString(2))).optional()
                .orElseThrow(() -> unknown(waveNo));
        if ("RELEASED".equals(w.status())) {
            return detail(siteId, waveNo);
        }
        List<UUID> orders = jdbc.sql("""
                        select id from outbound_order where wave_id = :w and status = 'POOLED'
                        order by planned_gi_utc nulls last, created_at""")
                .param("w", w.id()).query(UUID.class).list();
        for (UUID orderId : orders) {
            OutboundService.Order o = outbound.lockOrder(orderId);
            if ("POOLED".equals(o.status())) {
                outbound.allocateAndRelease(o);
            }
        }
        jdbc.sql("""
                        update outbound_wave set status = 'RELEASED', released_by = :user, released_at = :now
                        where id = :id""")
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant()))
                .param("id", w.id()).update();
        return detail(siteId, waveNo);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String siteId, String status) {
        return jdbc.sql("""
                        select w.wave_no, w.status, w.created_by, w.created_at, w.released_by, w.released_at,
                               (select count(*) from outbound_order o where o.wave_id = w.id) as orders
                        from outbound_wave w
                        where w.site_id = :site and (cast(:status as text) is null or w.status = :status)
                        order by w.created_at desc limit 200""")
                .param("site", siteId).param("status", status).query().listOfRows();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String siteId, String waveNo) {
        Map<String, Object> header = jdbc.sql("""
                        select id, wave_no, status, criteria::text as criteria, created_by, created_at, released_by,
                               released_at
                        from outbound_wave where site_id = :site and wave_no = :no""")
                .param("site", siteId).param("no", waveNo).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> unknown(waveNo));
        Map<String, Object> result = new HashMap<>(header);
        result.put("criteria", json.readTree((String) header.get("criteria")));
        result.put("orders", jdbc.sql("""
                        select erp_doc_no, status, carrier_scac, planned_gi_utc from outbound_order
                        where wave_id = :w order by planned_gi_utc nulls last, erp_doc_no""")
                .param("w", header.get("id")).query().listOfRows());
        return result;
    }

    private WavePlan select(String siteId, WaveCriteria c, boolean lock) {
        WaveCriteria criteria = c == null ? new WaveCriteria(null, null, null, null, null) : c;
        List<PlannedOrder> pool = jdbc.sql("""
                        select o.erp_doc_no, o.order_type, o.carrier_scac, o.planned_gi_utc,
                               (select count(*) from outbound_line l where l.order_id = o.id) as lines
                        from outbound_order o
                        where o.site_id = :site and o.status = 'POOLED' and o.wave_id is null
                          and (cast(:scac as text) is null or o.carrier_scac = :scac)
                          and (cast(:type as text) is null or o.order_type = :type)
                          and (cast(:before as timestamptz) is null or o.planned_gi_utc <= :before)
                        order by o.planned_gi_utc nulls last, o.created_at\s""" + (lock ? "for update of o skip locked" : ""))
                .param("site", siteId).param("scac", criteria.carrierScac()).param("type", criteria.orderType())
                .param("before", criteria.goodsIssueBefore() == null ? null : Timestamp.from(criteria.goodsIssueBefore()))
                .query((rs, n) -> new PlannedOrder(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant(), rs.getInt(5)))
                .list();
        List<PlannedOrder> selected = new java.util.ArrayList<>();
        int lines = 0;
        for (PlannedOrder o : pool) {
            if (criteria.maxOrders() != null && selected.size() >= criteria.maxOrders()) {
                break;
            }
            if (criteria.maxLines() != null && lines + o.lines() > criteria.maxLines()) {
                continue;   // a smaller order further down may still fit
            }
            selected.add(o);
            lines += o.lines();
        }
        return new WavePlan(selected, selected.size(), lines);
    }

    private static ApiException unknown(String waveNo) {
        return ApiException.notFound("OUT_WAVE_UNKNOWN", "No wave " + waveNo);
    }
}
